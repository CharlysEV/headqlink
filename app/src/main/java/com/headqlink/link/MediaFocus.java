package com.headqlink.link;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.Player;

/** Foco exclusivo para el ocio: perderlo pausa y libera, sin reanudar contra la decisión del usuario. */
final class MediaFocus implements AudioManager.OnAudioFocusChangeListener {
    private static MediaFocus active;
    private final AudioManager audio;
    private final Runnable pause;
    private AudioFocusRequest request;
    private boolean held;
    private boolean closed;

    MediaFocus(Context context, Runnable pause) {
        audio = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        this.pause = pause;
    }

    boolean acquire() {
        if (closed) return false;
        if (held) return true;
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            if (request == null) request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(this, new Handler(Looper.getMainLooper())).build();
            result = audio.requestAudioFocus(request);
        } else {
            result = audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false;
        MediaFocus previous = active;
        held = true;
        active = this;
        // Android puede tratar varios reproductores de nuestro proceso como un mismo propietario.
        if (previous != null && previous != this) previous.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
        return true;
    }

    void release() {
        if (!held) return;
        held = false;
        if (active == this) active = null;
        if (Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(request);
        else audio.abandonAudioFocus(this);
    }

    void close() {
        closed = true;
        release();
    }

    @Override
    public void onAudioFocusChange(int change) {
        if (!held || change >= 0) return;
        pause.run();
        release();
    }

    static MediaFocus bind(Context context, Player player) {
        MediaFocus focus = new MediaFocus(context, player::pause);
        player.addListener(new Player.Listener() {
            private void update() {
                if (player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED
                        && player.getPlaybackState() != Player.STATE_IDLE) {
                    if (!focus.acquire()) player.pause();
                } else focus.release();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean ready, int reason) { update(); }

            @Override
            public void onPlaybackStateChanged(int state) { update(); }
        });
        return focus;
    }
}
