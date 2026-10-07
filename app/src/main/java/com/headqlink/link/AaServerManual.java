package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import com.andrerinas.openheadunit.App;
import com.andrerinas.openheadunit.aap.AapService;
import com.andrerinas.openheadunit.aap.protocol.proto.Control;
import com.andrerinas.openheadunit.connection.CommManager;
import com.andrerinas.openheadunit.decoder.video.VideoTap;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * «Arranque del servidor de Android Auto» en modo manual (sin accesibilidad). HeadQLink no pulsa nada y **nunca abre una
 * conexión a 127.0.0.1:5277 para mirar**: el servidor de head unit de desarrollador de AA vuelve a atender después de una
 * sesión cerrada con orden (ByeBye), pero una conexión cortada a medias, como una sonda que abre y cierra sin hablar, lo
 * bloquea hasta pararlo y volver a iniciarlo (prueba real del 2026-10-06, docs §15, ver {@link AaServeAttempts}). La
 * única conexión es la del Self-Mode cuando una sesión con el coche necesita Android Auto, y {@link AaServeAttempts}
 * decide con ella:
 * - servida (AA contesta: sus primeros bytes del handshake): se sigue como siempre, también con el móvil bloqueado;
 * - no servida (rechazada, cerrada, o 6 s con el TCP abierto y sin respuesta): se cierra ese intento, aviso de prioridad
 *   alta «Arranca (o vuelve a arrancar) el servidor de Android Auto» (al tocarlo, los ajustes de AA), la fila «Auto» y
 *   el widget dicen «Esperando al servidor de Android Auto» y se reintenta cada 5 s mientras la sesión siga; el primer
 *   intento servido quita el aviso.
 * Android Auto sigue el ciclo de vida del automático (vídeo vivo, pausa, cierre al vencer «Esperar al coche»); HeadQLink
 * nunca para el servidor (no puede) y cierra siempre su conexión con orden ({@link AaClose}): el servidor sigue encendido
 * y el próximo viaje lo vuelve a usar sin reiniciarlo, también con el móvil bloqueado.
 *
 * Todo el estado de los intentos se toca en un solo hilo («aa-server-manual»); la interfaz solo lee {@link #isWaiting()}.
 */
public final class AaServerManual {
    private static final String CHANNEL_ASK = "aa_server_manual";
    private static final int NOTIF_ASK = 7;
    /** El aviso «Android Auto cerrado» y su canal de la versión anterior (daba por gastado el servidor; no lo está). */
    private static final String LEGACY_CHANNEL_INFO = "aa_server_info";
    private static final int LEGACY_NOTIF_RESTART = 8;

    private static final Object LOCK = new Object();
    private static ScheduledExecutorService exec;

    /** Marcaciones del Self-Mode a 127.0.0.1:5277: TCP aceptado o rechazado (las cuenta onDevServerDial). */
    private static final AtomicLong DIALS = new AtomicLong();
    private static final AtomicLong REFUSALS = new AtomicLong();

    /** Solo en el hilo propio. */
    private static AaServeAttempts attempts;
    private static ScheduledFuture<?> tick;

    /** Aviso «Arranca (o vuelve a arrancar)…» puesto: los intentos fallan. */
    private static volatile boolean waiting;

    private AaServerManual() {
    }

    static boolean enabled(Context ctx) {
        return new Config(ctx).aaServerManual();
    }

    /**
     * El arranque manual manda aquí: elegido, modo con Android Auto y AA 17.4 o más (su servidor de head unit, sin
     * force_legacy_launch). Con un AA más viejo el Self-Mode no usa 127.0.0.1:5277 y se lanza como siempre.
     */
    static boolean applies(Context ctx) {
        Config cfg = new Config(ctx);
        if (!cfg.aaServerManual() || !Config.isAa(cfg.mode()) || cfg.getBool("force_legacy_launch")) return false;
        try {
            String v = ctx.getPackageManager().getPackageInfo(AaServerStarter.AA_PKG, 0).versionName;
            return Requirements.usesHeadUnitServer(v);
        } catch (Exception e) {
            return false;
        }
    }

    /** Los intentos fallan y está puesto el aviso (la fila «Auto» de la pantalla principal y el widget). */
    static boolean isWaiting() {
        return waiting;
    }

    /** Para la «Comprobación»: lo que se sabe del servidor sin conectarse a él. */
    static Requirements.AaServer serverState(Context ctx) {
        if (waiting) return Requirements.AaServer.WAITING;
        return aaConnected(ctx) ? Requirements.AaServer.IN_USE : Requirements.AaServer.UNKNOWN;
    }

    /**
     * Una sesión con el coche necesita Android Auto (AaPassthroughSource: arranca o vuelve el vídeo de AA). Sin AA
     * conectado, lanza el Self-Mode y mira si AA lo atiende (si no, aviso y reintentos); con AA conectado, vigila que siga
     * (si se cae con la sesión en marcha, intento nuevo). Nunca sondea el puerto.
     */
    static void sessionNeedsAa(Context ctx, String why) {
        Context app = ctx.getApplicationContext();
        submit(() -> apply(app, policy().need(seen(app), why)));
    }

    /**
     * Self-Mode (SelfLauncherV17_4, hilo de E/S): resultado de su conexión a 127.0.0.1:5277. TCP aceptado no es
     * «servido» (eso lo dice la respuesta de AA); rechazado es servidor apagado. Se cuenta siempre, se use o no el manual.
     */
    public static void onDevServerDial(Context ctx, boolean connected) {
        (connected ? DIALS : REFUSALS).incrementAndGet();
        Context app = ctx.getApplicationContext();
        if (!enabled(app)) return;
        submit(() -> apply(app, policy().tick(seen(app))));
    }

    /** Pantalla principal de vuelta (quizá de los ajustes de AA) o móvil desbloqueado: reintento ya si se esperaba. */
    static void checkSoon(Context ctx) {
        if (!waiting) return;
        Context app = ctx.getApplicationContext();
        submit(() -> apply(app, policy().soon()));
    }

    /** Se cambió el ajuste: con el automático se deja de intentar (y fuera el aviso del manual). */
    static void onModeChanged(Context ctx, boolean manual, String where) {
        L.life("arranque del servidor de Android Auto: " + (manual ? "manual (sin accesibilidad)" : "automático (accesibilidad)")
                + " (" + where + ")");
        if (manual) return;
        stop(ctx.getApplicationContext(), "arranque automático elegido");
    }

    /**
     * El enlace arranca: fuera el aviso «Android Auto cerrado» de la versión anterior y su canal, si quedaron (decían que
     * había que reiniciar el servidor, y no hace falta: si no atiende, lo dirá el intento).
     */
    static void onLinkStarting(Context ctx) {
        NotificationManager nm = ctx.getApplicationContext().getSystemService(NotificationManager.class);
        nm.cancel(LEGACY_NOTIF_RESTART);
        nm.deleteNotificationChannel(LEGACY_CHANNEL_INFO);
    }

    /**
     * El enlace se cierra (Desconectar, fin del viaje): se deja de intentar. Android Auto (aaConnected: lo estaba) se
     * cierra con orden (ByeBye, {@link AaClose}) y su servidor sigue encendido: el próximo viaje lo vuelve a usar sin
     * reiniciarlo. Sin aviso: solo el log.
     */
    static void onLinkClosed(Context ctx, String why, boolean aaConnected) {
        Context app = ctx.getApplicationContext();
        submit(() -> {
            apply(app, policy().reset("el enlace se cierra (" + why + ")"));
            if (aaConnected) {
                L.life("AA server (arranque manual): Android Auto cerrado (" + why + ") con un cierre limpio (ByeBye): su"
                        + " servidor sigue encendido y atenderá la próxima conexión sin reiniciarlo; HeadQLink no lo para");
            }
        });
    }

    /** Se deja de intentar (sin avisos nuevos). */
    static void stop(Context ctx, String why) {
        Context app = ctx.getApplicationContext();
        submit(() -> apply(app, policy().reset(why)));
    }

    // ---------------------------------------------------------------- hilo propio

    private static AaServeAttempts policy() {
        if (attempts == null) attempts = new AaServeAttempts(SystemClock::elapsedRealtime);
        return attempts;
    }

    /** Lo que se ve ahora (sin tocar la red). */
    private static AaServeAttempts.Seen seen(Context app) {
        AaServeAttempts.Seen s = new AaServeAttempts.Seen();
        // El vídeo de AA de una sesión con el coche espera (su grifo puesto) y sigue el manual (la versión de AA ya la
        // miró quien pidió el intento: aquí, cada 250 ms, solo los ajustes).
        Config cfg = new Config(app);
        s.sessionWantsAa = LinkState.running && VideoTap.getSink() != null && cfg.aaServerManual() && Config.isAa(cfg.mode());
        try {
            CommManager cm = comm(app);
            Object st = cm.getConnectionState().getValue();
            s.tcpUp = cm.isConnected();
            s.connecting = st instanceof CommManager.ConnectionState.Connecting;
            s.handshakeDone = st instanceof CommManager.ConnectionState.HandshakeComplete
                    || st instanceof CommManager.ConnectionState.TransportStarted;
            s.answers = cm.getPeerAnswers();
        } catch (RuntimeException e) {
            L.w("AA server (arranque manual): no se pudo leer el estado de Android Auto: " + e);
        }
        s.dials = DIALS.get();
        s.refusals = REFUSALS.get();
        s.relaunchHoldMs = AaFlapWatch.relaunchHoldMs();
        return s;
    }

    private static void apply(Context app, AaServeAttempts.Step step) {
        // Antes de los efectos: la fila «Auto», el widget y la comprobación lo leen al repintar por el cambio de LinkState.
        waiting = attempts != null && attempts.waiting();
        if (step.reason != null) {
            String line = "AA server (arranque manual): " + step.reason;
            if (step.kind == AaServeAttempts.Kind.MISSED) L.lifeWarn(line);
            else L.life(line);
        }
        switch (step.kind) {
            case LAUNCH:
                launchSelfMode(app);
                break;
            case SERVED:
                onServed(app, step);
                break;
            case MISSED:
                onMissed(app, step);
                break;
            case STOP:
                if (step.dismiss) dismissAsk(app, LinkState.Level.IDLE, "");
                break;
            default:
                break;
        }
        scheduleTick(app);
    }

    private static void launchSelfMode(Context app) {
        // Vigila las sesiones con AA que se cortan solas a los pocos segundos (Open Headunit #985, AA 17.8).
        AaFlapWatch.ensure(app);
        L.i("AA: lanzando Self-Mode");
        AaPassthroughSource.AA_LAUNCHES.incrementAndGet();
        AaClose.noteLaunch();
        try {
            app.startForegroundService(new Intent(app, AapService.class).setAction(AapService.ACTION_START_SELF_MODE));
        } catch (RuntimeException e) {
            L.e("AA server (arranque manual): no se pudo lanzar Android Auto (Self-Mode)", e);
        }
    }

    private static void onServed(Context app, AaServeAttempts.Step step) {
        // Solo desde su modo desarrollador se arranca el servidor: consta activo.
        AaServerStarter.noteDevModeOn(app);
        if (step.dismiss) dismissAsk(app, LinkState.Level.BUSY, Str.get(R.string.hql_starting_auto));
    }

    private static void onMissed(Context app, AaServeAttempts.Step step) {
        boolean closed = true;
        if (step.tearDown) closed = tearDown(app, step.answersAtDecision);
        // El lanzador del Self-Mode queda limpio (y su plazo de 10 s no hace nada) antes del reintento.
        if (closed) stopSelfMode(app);
        if (step.notice) postAsk(app);
        if (step.retry) {
            LinkState.setSource(LinkState.Level.BUSY, Str.get(R.string.hql_aa_server_wait));
        } else if (step.dismiss) {
            dismissAsk(app, LinkState.Level.IDLE, "");
        }
    }

    /**
     * Cierra el intento con el TCP abierto y sin respuesta en 6 s (el servidor ya estaba bloqueado: no hay sesión que
     * cerrar con orden, y el ByeBye, cifrado, no se puede mandar antes del handshake). Si AA ha contestado justo ahora
     * (el contador de respuestas ya no es el de la decisión), no se cierra: el siguiente vistazo lo da por servido.
     */
    private static boolean tearDown(Context app, long answersAtDecision) {
        try {
            CommManager cm = comm(app);
            if (cm.getPeerAnswers() != answersAtDecision) {
                L.life("AA server (arranque manual): Android Auto contestó justo al ir a cerrar el intento: no lo cierro");
                return false;
            }
            cm.disconnect(false, false, Control.ByeByeReason.USER_SELECTION, false);
        } catch (RuntimeException e) {
            L.e("AA server (arranque manual): no se pudo cerrar el intento", e);
        }
        return true;
    }

    private static void stopSelfMode(Context app) {
        try {
            app.startService(new Intent(app, AapService.class).setAction(AapService.ACTION_STOP_SELF_MODE));
        } catch (RuntimeException e) {
            L.w("AA server (arranque manual): no se pudo parar el Self-Mode: " + e);
        }
    }

    private static void scheduleTick(Context app) {
        ScheduledFuture<?> f = tick;
        tick = null;
        if (f != null) f.cancel(false);
        long delay = attempts != null ? attempts.tickDelayMs() : -1;
        if (delay < 0) return;
        tick = schedule(() -> {
            tick = null;
            apply(app, policy().tick(seen(app)));
        }, delay);
    }

    private static void postAsk(Context app) {
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ASK, Str.get(R.string.hql_aa_server_ask_channel),
                NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(app, NOTIF_ASK, AaServerStarter.aaSettingsIntent(app),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = Str.get(R.string.hql_aa_server_ask_text);
        nm.notify(NOTIF_ASK, new Notification.Builder(app, CHANNEL_ASK)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setColor(app.getColor(R.color.hql_accent))
                .setContentTitle(Str.get(R.string.hql_aa_server_ask_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build());
    }

    /** Fuera el aviso; la fila «Auto», si aún decía «Esperando al servidor…», pasa a level/text. */
    private static void dismissAsk(Context app, LinkState.Level level, String text) {
        app.getSystemService(NotificationManager.class).cancel(NOTIF_ASK);
        if (Str.get(R.string.hql_aa_server_wait).equals(LinkState.source)) LinkState.setSource(level, text);
    }

    // ---------------------------------------------------------------- utilidades

    private static CommManager comm(Context ctx) {
        return App.Companion.provide(ctx).getCommManager();
    }

    private static boolean aaConnected(Context ctx) {
        try {
            return comm(ctx).isConnected();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ScheduledExecutorService exec() {
        synchronized (LOCK) {
            if (exec == null) {
                exec = new ScheduledThreadPoolExecutor(1, r -> {
                    Thread t = new Thread(r, "aa-server-manual");
                    t.setDaemon(true);
                    return t;
                });
            }
            return exec;
        }
    }

    private static void submit(Runnable r) {
        schedule(r, 0);
    }

    private static ScheduledFuture<?> schedule(Runnable r, long delayMs) {
        try {
            return exec().schedule(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    L.e("AA server (arranque manual)", e);
                }
            }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
