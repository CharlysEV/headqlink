package com.headqlink.link;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.List;

import dev.qdauto.core.session.KeyframeReason;

/**
 * Dueño del vídeo del motor QDAuto (qdauto §4.7): lo crea y lo posee LinkService, y vive entre sesiones con el coche.
 * Es el único que toca la VideoPipeline viva; crear, enganchar, desenganchar, pedir IDR y parar se serializan, en
 * orden de llegada, en el hilo hql-video (los callbacks de las sesiones solo encolan, y como llegan en orden del hilo de
 * eventos de cada sesión, «enganchar» va siempre antes que el IDR de STREAM_START). Guarda el modo noche del coche entre
 * sesiones. El táctil va directo (sin cola) a la fuente.
 */
final class VideoHub {
    private static final long TICK_MS = 500;

    private final Context ctx;
    private final Config cfg;
    private final HandlerThread thread = new HandlerThread("hql-video");
    private final Handler h;
    private final Object touchLock = new Object();

    /** Solo hql-video. */
    private VideoPipeline pipeline;
    /** Lectura sin candado (táctil, estadísticas, ¿hay vídeo?). */
    private volatile VideoPipeline live;
    private volatile Boolean carDark;
    /** Nivel térmico actual (ThermalGuard): vale también para la pipeline que se cree después. */
    private volatile int thermalLevel = ThermalPolicy.NORMAL;
    private volatile int thermalStatus = -1;
    private long detachedAtNs;
    private volatile String lastVerdict = "";
    private boolean quitting;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            VideoPipeline p = pipeline;
            if (p != null) p.tick();
            h.postDelayed(this, TICK_MS);
        }
    };

    VideoHub(Context ctx, Config cfg) {
        this.ctx = ctx.getApplicationContext();
        this.cfg = cfg;
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(LowLatency::boostCurrentThread);
        h.postDelayed(tick, TICK_MS);
    }

    /** La sesión pide vídeo (VIDEO_CTRL{1}): reutiliza la pipeline si sirve, o la crea. */
    void attachOrCreate(SessionPort port, VideoPlan.Car car) {
        h.post(() -> doAttach(port, car));
    }

    /** La sesión se cerró: la pipeline sigue viva, sin sesión (solo si port es la activa). */
    void detach(SessionPort port, String reason) {
        h.post(() -> {
            VideoPipeline p = pipeline;
            if (p != null && p.active() == port) {
                p.detach(port);
                detachedAtNs = System.nanoTime();
                QdTrace.i("HQL/Vídeo", "S" + port.getId() + " cerrada (" + reason + "): vídeo vivo a la espera del coche");
            }
        });
    }

    void requestKeyFrame(SessionPort port, KeyframeReason reason) {
        h.post(() -> {
            VideoPipeline p = pipeline;
            if (p != null && p.active() == port) p.requestKeyFrame(reason);
        });
    }

    /** El núcleo descartó un frame de port por pasar de su tope de tamaño (llega antes que su petición de IDR). */
    void onOversized(SessionPort port, int messageBytes, boolean key) {
        h.post(() -> {
            VideoPipeline p = pipeline;
            if (p != null && p.active() == port) p.onOversized(messageBytes, key);
        });
    }

    /** Táctil del coche: directo a la fuente (también antes de enganchar, como el fork). */
    void touch(int action, Proto.Finger[] fingers) {
        VideoPipeline p = live;
        if (p == null) return;
        synchronized (touchLock) {
            p.touch(action, fingers);
        }
    }

    /** Modo día/noche del coche: se guarda (vale para las sesiones siguientes) y se aplica si hay vídeo. */
    void setCarDark(boolean dark) {
        carDark = dark;
        h.post(() -> {
            VideoPipeline p = pipeline;
            if (p != null) p.setCarDark(dark);
        });
    }

    /** Nivel térmico nuevo (ThermalGuard): se aplica al vídeo vivo, sin reiniciar nada, y al que se cree después. */
    void setThermalLevel(int level, int status) {
        thermalLevel = level;
        thermalStatus = status;
        h.post(() -> {
            VideoPipeline p = pipeline;
            if (p != null) p.applyThermal(level, status);
        });
    }

    /** fps máximos del vídeo vivo (los de la sesión o el tope térmico), o 0 sin vídeo. */
    int fpsCap() {
        VideoPipeline p = live;
        return p != null ? p.fpsCap() : 0;
    }

    /** Para el vídeo (ajustes o fin del servicio). */
    void stop(String why) {
        h.post(() -> doStop(why));
    }

    /**
     * Para el vídeo si está enganchado a port o a ninguna sesión (qd_keep_video=false al cerrarse port): si en un
     * relevo la sesión nueva ya se ha enganchado, su vídeo sigue.
     */
    void stopFor(SessionPort port, String why) {
        h.post(() -> {
            VideoPipeline p = pipeline;
            if (p == null) return;
            SessionPort a = p.active();
            if (a == null || a == port) {
                doStop(why);
            } else {
                L.i("VIDEO: S" + port.getId() + " cerrada, pero el vídeo ya es de S" + a.getId() + "; sigue");
            }
        });
    }

    /** Hay vídeo vivo (con o sin sesión). */
    boolean hasVideo() {
        return live != null;
    }

    /** Lo que pasó en el último enganche: CREADO, REUTILIZADO o RECREADO (para el resumen de la sesión). */
    String lastVerdict() {
        return lastVerdict;
    }

    List<String> takeStats(SessionPort port) {
        VideoPipeline p = live;
        return p != null ? p.takeStats(port) : null;
    }

    void setStatus(SessionPort port, String line) {
        VideoPipeline p = live;
        if (p != null && p.active() == port) p.setStatus(line);
    }

    long firstIdrAfterAttachMs(SessionPort port) {
        VideoPipeline p = live;
        return p != null && p.active() == port ? p.firstIdrAfterAttachMs() : -1;
    }

    int aaCycles() {
        VideoPipeline p = live;
        return p != null ? p.aaCycles() : 0;
    }

    /** Para el vídeo y el hilo (onDestroy del servicio). */
    void quit() {
        h.post(() -> {
            doStop("servicio parado");
            quitting = true;
            h.removeCallbacks(tick);
            thread.quitSafely();
        });
    }

    // ---------------------------------------------------------------- hql-video

    private void doAttach(SessionPort port, VideoPlan.Car car) {
        if (quitting) return;
        if (port.getClosed()) {
            L.i("VIDEO: la sesión S" + port.getId() + " ya se cerró; no se engancha");
            return;
        }
        VideoPipeline p = pipeline;
        if (p != null && p.active() == port) {
            // VIDEO_CTRL{1} repetido con el vídeo en marcha: el núcleo ya reenvía SPS/PPS y pide el IDR.
            L.i("VIDEO_CTRL{1} repetido en S" + port.getId() + ": el vídeo sigue enganchado");
            return;
        }
        VideoPlan plan = VideoPlan.compute(cfg, car);
        String verdict;
        long sinceDetachMs = detachedAtNs > 0 ? (System.nanoTime() - detachedAtNs) / 1_000_000 : -1;
        if (p != null && plan.equals(p.plan) && p.healthy()) {
            verdict = "REUTILIZADO";
        } else {
            if (p != null) {
                String why = !plan.equals(p.plan) ? "plan distinto: " + plan.differenceFrom(p.plan) : p.unhealthyReason();
                L.i("VIDEO recreado (" + why + ")");
                doStop("recrear: " + why);
                verdict = "RECREADO";
            } else {
                verdict = "CREADO";
            }
            try {
                p = VideoPipeline.create(ctx, cfg, plan, carDark, h);
            } catch (Exception e) {
                L.e("no se pudo arrancar el vídeo (" + plan + ")", e);
                lastVerdict = "ERROR";
                return;
            }
            pipeline = p;
            live = p;
            // Con calor, la pipeline nueva empieza ya con el tope térmico.
            if (thermalLevel != ThermalPolicy.NORMAL) p.applyThermal(thermalLevel, thermalStatus);
        }
        lastVerdict = verdict;
        p.attach(port);
        String msg = "vídeo " + verdict + " para S" + port.getId() + ": " + plan
                + ("REUTILIZADO".equals(verdict) && sinceDetachMs >= 0 ? " · " + sinceDetachMs + " ms sin sesión" : "");
        L.i(msg);
        QdTrace.i("HQL/Vídeo", msg);
    }

    private void doStop(String why) {
        VideoPipeline p = pipeline;
        if (p == null) return;
        pipeline = null;
        live = null;
        detachedAtNs = 0;
        L.i("VIDEO parado (" + why + ")");
        QdTrace.i("HQL/Vídeo", "vídeo parado (" + why + ")");
        try {
            p.stop();
        } catch (RuntimeException e) {
            L.e("al parar el vídeo", e);
        }
    }
}
