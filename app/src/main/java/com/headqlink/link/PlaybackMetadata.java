package com.headqlink.link;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

/** Título y logo de TV/radio en los controles del sistema y del coche por Bluetooth (AVRCP). */
final class PlaybackMetadata implements AutoCloseable {
    private final Player player;
    private final MediaSessionCompat session;
    private final RequestManager images;
    private final Player.Listener listener;
    private CustomTarget<Bitmap> image;
    private Bitmap artwork;
    private String channel = "";
    private String detail = "";
    private String logo = "";
    private long generation;
    private boolean closed;

    PlaybackMetadata(Context context, Player player, String kind, Runnable play, Runnable stop,
                     Runnable previous, Runnable next) {
        this.player = player;
        images = Glide.with(context.getApplicationContext());
        session = new MediaSessionCompat(context.getApplicationContext(), "HeadQLink-" + kind);
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { if (!closed) play.run(); }
            @Override public void onPause() { if (!closed) player.pause(); }
            @Override public void onStop() { if (!closed) stop.run(); }
            @Override public void onSkipToPrevious() { if (!closed && previous != null) previous.run(); }
            @Override public void onSkipToNext() { if (!closed && next != null) next.run(); }
        }, new Handler(Looper.getMainLooper()));
        listener = new Player.Listener() {
            @Override public void onEvents(Player p, Player.Events events) { updateState(previous, next); }
        };
        player.addListener(listener);
        updateState(previous, next);
    }

    void setChannel(String name, String subtitle, String artUrl) {
        if (closed) return;
        channel = name == null ? "" : name;
        detail = subtitle == null ? "" : subtitle;
        String url = artUrl == null ? "" : artUrl;
        boolean newLogo = !url.equals(logo);
        logo = url;
        if (newLogo) {
            ++generation;
            // Retirar el arte antes de liberar el bitmap de Glide.
            artwork = null;
            publish();
            if (image != null) images.clear(image);
            image = null;
        } else publish();
        if (!newLogo || logo.isEmpty()) return;
        long requested = generation;
        image = new CustomTarget<Bitmap>(256, 256) {
            @Override public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                if (closed || requested != generation) return;
                // MediaSession conserva el bitmap aunque Glide libere su recurso.
                artwork = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                publish();
            }
            @Override public void onLoadCleared(@Nullable Drawable placeholder) { }
        };
        images.asBitmap().load(logo).into(image);
    }

    private void publish() {
        MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, channel)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, detail)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, channel)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, channel)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, detail);
        if (!logo.isEmpty()) {
            meta.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, logo)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, logo)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, logo);
        }
        if (artwork != null) {
            meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artwork);
        }
        session.setMetadata(meta.build());
    }

    private void updateState(Runnable previous, Runnable next) {
        if (closed) return;
        int playback = player.getPlaybackState();
        boolean playing = player.getPlayWhenReady() && player.getPlaybackSuppressionReason() == Player.PLAYBACK_SUPPRESSION_REASON_NONE;
        int state = playback == Player.STATE_IDLE || playback == Player.STATE_ENDED ? PlaybackStateCompat.STATE_STOPPED
                : !playing ? PlaybackStateCompat.STATE_PAUSED
                : playback == Player.STATE_BUFFERING ? PlaybackStateCompat.STATE_BUFFERING : PlaybackStateCompat.STATE_PLAYING;
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP;
        if (previous != null) actions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        if (next != null) actions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        session.setPlaybackState(new PlaybackStateCompat.Builder().setActions(actions)
                .setState(state, Math.max(0, player.getCurrentPosition()), playing ? 1f : 0f).build());
        // Una fuente pausada no debe competir con Spotify o con la sesión de Android Auto.
        session.setActive(playing && playback != Player.STATE_IDLE && playback != Player.STATE_ENDED);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ++generation;
        player.removeListener(listener);
        session.setActive(false);
        session.setMetadata(null);
        session.release();
        if (image != null) images.clear(image);
        image = null;
        artwork = null;
    }
}
