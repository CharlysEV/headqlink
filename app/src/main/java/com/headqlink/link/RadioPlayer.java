package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Radio por internet: un único reproductor que sigue sonando al salir de la pantalla Radio (como la
 * música de AA) y se para al cerrar la interfaz propia. Pide el foco de audio, así que pausa la
 * música de AA y se pausa si AA (o una llamada) lo toma. Solo hilo principal.
 */
final class RadioPlayer {
    static final class Station {
        String name;
        String url;
        String logo;
        String group;
    }

    private static ExoPlayer player;
    private static final List<Station> list = new ArrayList<>();
    private static int index = -1;
    /** Título que emite la emisora (ICY StreamTitle), o "" si no manda. */
    static String nowTitle = "";
    static String error = "";
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private RadioPlayer() {
    }

    static void addListener(Runnable r) {
        listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private static void changed() {
        for (Runnable r : listeners) r.run();
    }

    static Station current() {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    static boolean isPlaying() {
        return player != null && player.getPlayWhenReady();
    }

    static boolean isBuffering() {
        return player != null && player.getPlaybackState() == Player.STATE_BUFFERING;
    }

    static void play(Context ctx, List<Station> stations, int i) {
        list.clear();
        list.addAll(stations);
        index = i;
        start(ctx.getApplicationContext());
    }

    private static void start(Context app) {
        Station s = current();
        if (s == null) return;
        if (player == null) {
            player = new ExoPlayer.Builder(app).build();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
            // Con la pantalla del móvil apagada la radio sigue: CPU y WiFi despiertos mientras suena.
            player.setWakeMode(C.WAKE_MODE_NETWORK);
            player.addListener(new Player.Listener() {
                @Override
                public void onMediaMetadataChanged(MediaMetadata m) {
                    nowTitle = m.title != null ? m.title.toString() : "";
                    changed();
                }

                @Override
                public void onPlayerError(PlaybackException e) {
                    error = Str.get(R.string.hql_cannot_play, e.getErrorCodeName());
                    Station st = current();
                    L.w("radio: " + (st != null ? st.name : "?") + ": " + e);
                    changed();
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    changed();
                }

                @Override
                public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                    changed();
                }
            });
        }
        nowTitle = "";
        error = "";
        player.setMediaItem(new MediaItem.Builder().setUri(s.url)
                .setMediaMetadata(new MediaMetadata.Builder().setStation(s.name).build()).build());
        player.prepare();
        player.play();
        L.i("radio: " + s.name + " · " + s.url);
        changed();
    }

    /** Solo la vista previa: una emisora "sonando" sin reproducir nada. */
    static void previewOnly(Station s) {
        list.clear();
        list.add(s);
        index = 0;
        changed();
    }

    static void toggle() {
        if (player == null) return;
        if (player.getPlayWhenReady()) {
            player.pause();
        } else {
            // Radio en directo: al seguir, se vuelve al directo y no a donde se pausó.
            player.seekToDefaultPosition();
            player.prepare();
            player.play();
        }
        changed();
    }

    static void step(Context ctx, int delta) {
        if (list.isEmpty()) return;
        index = (index + delta + list.size()) % list.size();
        start(ctx.getApplicationContext());
    }

    static void stop() {
        if (player != null) {
            player.release();
            player = null;
        }
        index = -1;
        nowTitle = "";
        error = "";
        changed();
    }
}
