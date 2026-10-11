package com.headqlink.link;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

/** ExoPlayer gestiona las interrupciones nativas; la web conserva el foco durante una suspensión temporal. */
final class MediaFocus implements AudioManager.OnAudioFocusChangeListener {
    private static MediaFocus active;
    private final AudioManager audio;
    private final Runnable pause;
    private final Runnable suspend;
    private final Runnable resume;
    private ExoPlayer nativePlayer;
    private Player.Listener nativeListener;
    private AudioFocusRequest request;
    private boolean held;
    private boolean closed;
    private boolean interrupted;

    MediaFocus(Context context, Runnable pause) {
        this(context, pause, pause, null);
    }

    MediaFocus(Context context, Runnable pause, Runnable suspend, Runnable resume) {
        audio = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        this.pause = pause;
        this.suspend = suspend;
        this.resume = resume;
    }

    boolean acquire() {
        if (closed || interrupted) return false;
        if (held) return true;
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            if (request == null) request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(this, new Handler(Looper.getMainLooper())).build();
            result = audio.requestAudioFocus(request);
        } else {
            result = audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false;
        held = true;
        select();
        return true;
    }

    private void select() {
        MediaFocus previous = active;
        active = this;
        // Android puede tratar varios reproductores de nuestro proceso como un mismo propietario.
        if (previous != null && previous != this) previous.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
    }

    void release() {
        boolean wasHeld = held;
        held = false;
        interrupted = false;
        if (active == this) active = null;
        if (!wasHeld || nativePlayer != null) return;
        if (Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(request);
        else audio.abandonAudioFocus(this);
    }

    void close() {
        closed = true;
        if (nativePlayer != null) {
            nativePlayer.removeListener(nativeListener);
            nativePlayer.pause();
        }
        release();
    }

    boolean isInterrupted() {
        return interrupted;
    }

    @Override
    public void onAudioFocusChange(int change) {
        if (!held || closed) return;
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pause.run();
                release();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (!interrupted) {
                    interrupted = true;
                    suspend.run();
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (interrupted && active == this) {
                    interrupted = false;
                    if (resume != null) resume.run();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // En Android 8+ el sistema baja/restaura el volumen: willPauseWhenDucked=false.
                break;
        }
    }

    static MediaFocus bind(Context context, ExoPlayer player) {
        MediaFocus focus = new MediaFocus(context, player::pause);
        focus.nativePlayer = player;
        // ExoPlayer mantiene playWhenReady durante una pérdida transitoria y suspende la
        // reproducción. Una pausa explícita cambia esa intención y cancela la recuperación.
        player.setAudioAttributes(player.getAudioAttributes(), true);
        focus.nativeListener = new Player.Listener() {
            private void update() {
                if (focus.closed) return;
                if (player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED
                        && player.getPlaybackState() != Player.STATE_IDLE) {
                    focus.held = true;
                    focus.select();
                } else focus.release();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean ready, int reason) { update(); }

            @Override
            public void onPlaybackStateChanged(int state) { update(); }
        };
        player.addListener(focus.nativeListener);
        return focus;
    }
}
