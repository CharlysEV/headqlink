package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.Collections;
import java.util.List;

/**
 * Reproductor de vídeo del coche, con controles grandes: dos toques en un lado ±10 s, velocidad 1×/1,5×/2×, subtítulos
 * (.srt junto al vídeo, en la carpeta de la app), barra de avance gruesa para el dedo, «¿Seguir desde 12:34?» (marca de
 * 48 h, MediaResume) y, al acabar, el siguiente de la misma lista.
 */
final class VideoPlayer {
    static final long SEEK_MS = 10_000;
    static final long HIDE_MS = 4000;
    static final long SAVE_MS = 5000;
    /** Sin respuesta a «¿Seguir…?» en tanto: sigue. */
    static final long RESUME_ASK_MS = 10_000;
    static final float[] SPEEDS = {1f, 1.5f, 2f};

    private final CarScreen.Host host;
    private final List<MediaLibrary.Item> items;
    private final Runnable onBack;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private MediaFocus focus;
    private int index;
    private int speed;
    private boolean subsOn = true;
    private View controls;
    private TextView time;
    private TextView playBtn;
    private SeekTrack track;

    VideoPlayer(CarScreen.Host host, List<MediaLibrary.Item> items, Runnable onBack) {
        this.host = host;
        this.items = items;
        this.onBack = onBack;
    }

    boolean playing() {
        return player != null;
    }

    private final Runnable hide = () -> {
        if (controls != null) controls.setVisibility(View.GONE);
    };

    private final Runnable tick = new Runnable() {
        private long lastSave;

        @Override
        public void run() {
            if (player == null) return;
            long pos = player.getCurrentPosition();
            long dur = player.getDuration();
            if (time != null) time.setText(MediaResume.clock(pos) + (dur > 0 ? " / " + MediaResume.clock(dur) : ""));
            if (track != null && dur > 0) track.set(pos / (float) dur);
            if (playBtn != null) playBtn.setText(player.isPlaying() ? "❚❚" : "▶");
            long now = System.currentTimeMillis();
            if (now - lastSave >= SAVE_MS && player.isPlaying()) {
                lastSave = now;
                save();
            }
            main.postDelayed(this, 500);
        }
    };

    private void save() {
        if (player == null || index < 0 || index >= items.size()) return;
        long dur = player.getDuration();
        if (dur <= 0) return;
        MediaResume.put(host.context(), items.get(index).uri.toString(), player.getCurrentPosition(), dur);
    }

    void open(int i) {
        release();
        index = i;
        Context c = host.context();
        MediaLibrary.Item it = items.get(i);
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(Color.BLACK);
        player = new ExoPlayer.Builder(c.getApplicationContext()).build();
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false);
        focus = MediaFocus.bind(c, player);
        PlayerView pv = CarStyle.player(c);
        pv.setUseController(false);
        pv.setPlayer(player);
        f.addView(pv, CarStyle.match());

        // Toques sobre el vídeo: uno enseña u oculta los controles; dos en un lado, ±10 s.
        View touch = new View(c);
        GestureDetector g = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (controls.getVisibility() == View.VISIBLE) hide.run();
                else showControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float x = e.getX() / Math.max(1, touch.getWidth());
                if (x < 0.4f) seekBy(-SEEK_MS);
                else if (x > 0.6f) seekBy(SEEK_MS);
                else togglePlay();
                return true;
            }
        });
        touch.setOnTouchListener((v, ev) -> g.onTouchEvent(ev));
        f.addView(touch, CarStyle.match());

        Uri subs = MediaLibrary.subtitles(c, it);
        controls = buildControls(c, it, subs != null);
        // Ocultos hasta que empiece (con «¿Seguir…?» delante no estorban).
        controls.setVisibility(View.GONE);
        f.addView(controls, CarStyle.match());

        MediaItem.Builder mb = new MediaItem.Builder().setUri(it.uri);
        if (subs != null) {
            mb.setSubtitleConfigurations(Collections.singletonList(new MediaItem.SubtitleConfiguration.Builder(subs)
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()));
            L.i("vídeos: con subtítulos");
        }
        player.setMediaItem(mb.build());
        player.setPlaybackParameters(new PlaybackParameters(SPEEDS[speed]));
        applySubs();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) ended();
            }
        });
        player.prepare();
        host.setContent(f);
        host.setFullscreen(true);
        main.post(tick);

        MediaResume.Mark m = MediaResume.get(c, it.uri.toString());
        if (m != null && m.resumable()) {
            askResume(f, m.posMs);
        } else {
            player.play();
            showControls();
        }
    }

    /** «¿Seguir desde 12:34?»: Seguir / Desde el principio; sin respuesta en RESUME_ASK_MS, sigue. */
    private void askResume(FrameLayout f, long posMs) {
        Context c = host.context();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(36, 28, 36, 28);
        card.setBackground(CarStyle.round(0xF0202124, 32));
        TextView q = CarStyle.text(c, Str.get(R.string.hql_vid_resume_q, MediaResume.clock(posMs)), 30, Color.WHITE);
        card.addView(q);
        LinearLayout row = new LinearLayout(c);
        row.setPadding(0, 24, 0, 0);
        TextView yes = CarStyle.pill(c, Str.get(R.string.hql_vid_resume_yes));
        yes.setBackground(CarStyle.accent(28));
        yes.setTextColor(CarStyle.ON_ACCENT);
        TextView no = CarStyle.pill(c, Str.get(R.string.hql_vid_resume_start));
        row.addView(yes);
        row.addView(no);
        card.addView(row);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        f.addView(card, lp);
        Runnable[] choose = new Runnable[2];
        Runnable auto = () -> choose[0].run();
        choose[0] = () -> {
            main.removeCallbacks(auto);
            f.removeView(card);
            if (player == null) return;
            player.seekTo(posMs);
            player.play();
            showControls();
        };
        choose[1] = () -> {
            main.removeCallbacks(auto);
            f.removeView(card);
            if (player == null) return;
            player.seekTo(0);
            player.play();
            showControls();
        };
        yes.setOnClickListener(v -> choose[0].run());
        no.setOnClickListener(v -> choose[1].run());
        main.postDelayed(auto, RESUME_ASK_MS);
    }

    private View buildControls(Context c, MediaLibrary.Item it, boolean hasSubs) {
        FrameLayout box = new FrameLayout(c);
        box.setBackgroundColor(0x66000000);
        box.setClickable(false);
        // Arriba: volver, título, velocidad y subtítulos.
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(16, 16, 16, 0);
        TextView back = CarStyle.pill(c, "‹  " + Str.get(R.string.hql_back));
        back.setOnClickListener(v -> {
            release();
            host.setFullscreen(false);
            onBack.run();
        });
        top.addView(back);
        TextView title = CarStyle.text(c, it.name, 24, Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(16, 0, 16, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sp = CarStyle.pill(c, speedLabel());
        sp.setOnClickListener(v -> {
            speed = (speed + 1) % SPEEDS.length;
            if (player != null) player.setPlaybackParameters(new PlaybackParameters(SPEEDS[speed]));
            sp.setText(speedLabel());
            showControls();
        });
        top.addView(sp);
        if (hasSubs) {
            TextView cc = CarStyle.pill(c, "CC");
            cc.setAlpha(subsOn ? 1f : 0.5f);
            cc.setOnClickListener(v -> {
                subsOn = !subsOn;
                cc.setAlpha(subsOn ? 1f : 0.5f);
                applySubs();
                showControls();
            });
            top.addView(cc);
        }
        box.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
        // Centro: −10 s, reproducir/pausa, +10 s y siguiente.
        LinearLayout mid = new LinearLayout(c);
        mid.setGravity(Gravity.CENTER);
        mid.addView(big(c, "⟲ 10", () -> seekBy(-SEEK_MS)));
        playBtn = big(c, "❚❚", this::togglePlay);
        mid.addView(playBtn);
        mid.addView(big(c, "10 ⟳", () -> seekBy(SEEK_MS)));
        if (index + 1 < items.size()) mid.addView(big(c, "▶▶|", () -> open(index + 1)));
        box.addView(mid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        // Abajo: tiempo y barra gruesa.
        LinearLayout bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(32, 0, 32, 24);
        time = CarStyle.text(c, "", 24, Color.WHITE);
        bottom.addView(time);
        track = new SeekTrack(c, frac -> {
            if (player != null && player.getDuration() > 0) player.seekTo((long) (frac * player.getDuration()));
            showControls();
        });
        bottom.addView(track, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 64));
        box.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        return box;
    }

    private TextView big(Context c, String s, Runnable r) {
        TextView b = CarStyle.text(c, s, 40, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(36, 20, 36, 20);
        b.setBackground(CarStyle.round(0x88202124, 60));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(24, 0, 24, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            r.run();
            showControls();
        });
        return b;
    }

    private String speedLabel() {
        float s = SPEEDS[speed];
        return (s == (int) s ? String.valueOf((int) s) : String.valueOf(s).replace('.', ',')) + "×";
    }

    private void showControls() {
        if (controls == null) return;
        controls.setVisibility(View.VISIBLE);
        main.removeCallbacks(hide);
        main.postDelayed(hide, HIDE_MS);
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
    }

    private void seekBy(long d) {
        if (player == null) return;
        long dur = player.getDuration();
        long to = Math.max(0, player.getCurrentPosition() + d);
        if (dur > 0) to = Math.min(dur, to);
        player.seekTo(to);
        L.i("vídeos: " + (d > 0 ? "+" : "−") + Math.abs(d / 1000) + " s");
    }

    private void applySubs() {
        if (player == null) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn).build());
    }

    /** Al acabar: visto y, si hay, el siguiente de la lista. */
    private void ended() {
        if (player == null) return;
        long dur = player.getDuration();
        if (dur > 0) MediaResume.put(host.context(), items.get(index).uri.toString(), dur, dur);
        if (index + 1 < items.size()) {
            L.i("vídeos: acabado; pongo el siguiente");
            int nextIndex = index + 1;
            main.postDelayed(() -> {
                if (player != null) open(nextIndex);
            }, 1500);
        }
    }

    void release() {
        main.removeCallbacks(tick);
        main.removeCallbacks(hide);
        if (player != null) {
            save();
            if (focus != null) focus.close();
            focus = null;
            player.release();
            player = null;
        }
    }

    /** Barra de avance gruesa: lo visto en el color de acento; tocar o arrastrar salta ahí. */
    private static final class SeekTrack extends View {
        interface OnSeek {
            void seek(float frac);
        }

        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final OnSeek onSeek;
        private float frac;

        SeekTrack(Context c, OnSeek s) {
            super(c);
            onSeek = s;
            bg.setColor(0x66FFFFFF);
            fg.setColor(CarStyle.ACCENT);
        }

        void set(float f) {
            frac = f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = 16;
            float y = (getHeight() - h) / 2f;
            c.drawRoundRect(new RectF(0, y, getWidth(), y + h), h / 2, h / 2, bg);
            c.drawRoundRect(new RectF(0, y, getWidth() * frac, y + h), h / 2, h / 2, fg);
            c.drawCircle(getWidth() * frac, getHeight() / 2f, 22, fg);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                set(Math.max(0f, Math.min(1f, ev.getX() / Math.max(1, getWidth()))));
                return true;
            }
            if (a == MotionEvent.ACTION_UP) {
                onSeek.seek(Math.max(0f, Math.min(1f, ev.getX() / Math.max(1, getWidth()))));
                return true;
            }
            return a == MotionEvent.ACTION_CANCEL;
        }
    }
}
