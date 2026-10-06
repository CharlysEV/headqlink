package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * «Arranque del servidor de Android Auto» en modo manual (sin accesibilidad). HeadQLink no pulsa nada: mira si el servidor
 * de head unit de AA contesta en 127.0.0.1:5277 (conexión con tiempo corto, siempre fuera del hilo principal) y decide
 * con {@link AaServerPolicy}:
 * - encendido: se sigue como siempre (el Self-Mode conecta solo, también con el móvil bloqueado);
 * - apagado cuando hace falta (Conectar, Bluetooth o cable del coche, coche anunciado, sesión que necesita AA): aviso de
 *   prioridad alta «Arranca el servidor de Android Auto» (al tocarlo, los ajustes de AA), la fila «Auto» lo dice y se
 *   vuelve a mirar cada 2 s; en cuanto contesta, fuera el aviso, «AA server: listo (arrancado a mano)» en el log y, si
 *   una sesión esperaba a Android Auto, se relanza el Self-Mode;
 * - mientras el enlace espera al coche, se vuelve a mirar cada minuto (sin tocar AA si nuestra head unit está conectada);
 * - al cerrar el enlace no se puede parar: si sigue encendido, un aviso (una vez por cierre) dice cómo pararlo.
 *
 * Todo el estado se toca en un solo hilo («aa-server-manual»); la interfaz solo lee {@link #isWaiting()}.
 */
final class AaServerManual {
    static final String HOST = "127.0.0.1";
    static final int PORT = 5277;
    /** Conexión a localhost: si escucha, contesta en el acto; si no, la rechaza en el acto. */
    private static final int PROBE_TIMEOUT_MS = 400;
    /** Tras parar Android Auto al cerrar, lo que se espera antes de mirar si el servidor sigue encendido. */
    private static final long END_PROBE_DELAY_MS = 1_500;
    /** Tras arrancarlo el usuario, lo que se espera antes de relanzar el Self-Mode (el intento fallido tiene que acabar). */
    private static final long RELAUNCH_DELAY_MS = 1_500;

    private static final String CHANNEL_ASK = "aa_server_manual";
    private static final String CHANNEL_INFO = "aa_server_info";
    private static final int NOTIF_ASK = 7;
    private static final int NOTIF_STILL_ON = 8;

    private static final Object LOCK = new Object();
    private static ScheduledExecutorService exec;

    /** Solo en el hilo propio. */
    private static ScheduledFuture<?> next;
    private static AaServerPolicy.Server last = AaServerPolicy.Server.UNKNOWN;
    /** Por qué se espera al usuario (para las comprobaciones siguientes). */
    private static volatile AaServerPolicy.Need waitingFor = AaServerPolicy.Need.WATCH;

    /** Aviso «Arranca el servidor…» puesto y mirando cada 2 s. */
    private static volatile boolean waiting;
    /** Una sesión esperaba a Android Auto: al encenderse el servidor se relanza el Self-Mode. */
    private static volatile boolean relaunch;

    private AaServerManual() {
    }

    static boolean enabled(Context ctx) {
        return new Config(ctx).aaServerManual();
    }

    /** Se está esperando a que el usuario arranque el servidor (para la fila «Auto» de la pantalla principal). */
    static boolean isWaiting() {
        return waiting;
    }

    /**
     * ¿Contesta el servidor de head unit de AA? Bloquea como mucho {@link #PROBE_TIMEOUT_MS}: nunca en el hilo principal.
     * Con nuestra head unit conectada a AA no se conecta (AA atiende una sola conexión): está encendido.
     */
    static AaServerPolicy.Server probe(Context ctx) {
        if (aaConnected(ctx)) return AaServerPolicy.Server.UP;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(HOST, PORT), PROBE_TIMEOUT_MS);
            AaServerStarter.noteDevModeOn(ctx);
            return AaServerPolicy.Server.UP;
        } catch (IOException | RuntimeException e) {
            return AaServerPolicy.Server.DOWN;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * El servidor hace falta: se mira ya (en el hilo propio) y, si está apagado y el enlace en marcha, se avisa al
     * usuario y se sigue mirando. relaunchSelfMode: una sesión espera a AA (relanzar el Self-Mode al encenderse).
     */
    static void need(Context ctx, AaServerPolicy.Need need, boolean relaunchSelfMode) {
        if (relaunchSelfMode) relaunch = true;
        Context app = ctx.getApplicationContext();
        submit(() -> {
            cancelNext();
            check(app, need);
        });
    }

    /**
     * Self-Mode (hilo de E/S): 127.0.0.1:5277 no contestó. true si ahora sí (el usuario lo acaba de arrancar); si no,
     * con el enlace en marcha, el aviso y la espera (con relanzamiento al encenderse) y false. Nunca pulsa nada.
     */
    static boolean awaitForSession(Context ctx) {
        if (probe(ctx) == AaServerPolicy.Server.UP) {
            L.life("AA server: encendido (127.0.0.1:" + PORT + " contesta; arranque manual)");
            return true;
        }
        if (!LinkState.running) {
            L.w("AA server: apagado y el enlace no está en marcha (arranque manual): arráncalo en Android Auto › ⋮");
            return false;
        }
        need(ctx, AaServerPolicy.Need.SESSION, true);
        return false;
    }

    /** Pantalla principal o comprobación de vuelta (quizá de los ajustes de AA): mirar ya si se estaba esperando. */
    static void checkSoon(Context ctx) {
        if (waiting) need(ctx, waitingForNow(), false);
    }

    private static AaServerPolicy.Need waitingForNow() {
        return waiting ? waitingFor : AaServerPolicy.Need.WATCH;
    }

    /** Se cambió el ajuste: con el manual y el enlace en marcha se mira ya; con el automático se deja de esperar. */
    static void onModeChanged(Context ctx, boolean manual, String where) {
        L.life("arranque del servidor de Android Auto: " + (manual ? "manual (sin accesibilidad)" : "automático (accesibilidad)")
                + " (" + where + ")");
        Context app = ctx.getApplicationContext();
        if (manual) {
            if (LinkState.running) need(app, AaServerPolicy.Need.WATCH, false);
        } else {
            app.getSystemService(NotificationManager.class).cancel(NOTIF_STILL_ON);
            submit(() -> stopInternal(app, "arranque automático elegido"));
        }
    }

    /** El enlace se cierra con el servidor que no se puede parar: deja de mirar y, si sigue encendido, lo dice. */
    static void onLinkClosed(Context ctx, String why) {
        Context app = ctx.getApplicationContext();
        submit(() -> {
            stopInternal(app, "el enlace se cierra");
            schedule(() -> tellIfStillOn(app, why), END_PROBE_DELAY_MS);
        });
    }

    /** Deja de esperar y de vigilar (sin avisos). */
    static void stop(Context ctx, String why) {
        Context app = ctx.getApplicationContext();
        submit(() -> stopInternal(app, why));
    }

    /** Otro cierre que deja el servidor encendido (guardián en modo manual): mirar y avisar. */
    static void tellIfStillOnLater(Context ctx, String why) {
        Context app = ctx.getApplicationContext();
        schedule(() -> tellIfStillOn(app, why), END_PROBE_DELAY_MS);
    }

    // ---------------------------------------------------------------- hilo propio

    private static void check(Context app, AaServerPolicy.Need need) {
        Config cfg = new Config(app);
        boolean manual = cfg.aaServerManual();
        boolean aaMode = Config.isAa(cfg.mode()) || AaPark.parked;
        if (!manual || !aaMode) {
            stopInternal(app, manual ? "modo sin Android Auto" : "arranque automático");
            return;
        }
        boolean connected = aaConnected(app);
        AaServerPolicy.Server server = probe(app);
        boolean locked = isLocked(app);
        AaServerPolicy.State st = new AaServerPolicy.State().manual(true).server(server).connected(connected).locked(locked);
        AaServerPolicy.Action a = AaServerPolicy.onNeed(need, st);
        AaServerPolicy.Server before = last;
        last = server;
        if (a == AaServerPolicy.Action.ASK_USER) {
            if (!LinkState.running) {
                L.i("AA server: apagado (" + AaServerPolicy.needName(need) + "; arranque manual) con el enlace parado: no aviso");
                relaunch = false;
                return;
            }
            if (!waiting) startAsking(app, need, locked);
            else if (need != AaServerPolicy.Need.WATCH) waitingFor = need;
        } else if (waiting) {
            onUp(app);
        } else if (before != AaServerPolicy.Server.UP || need != AaServerPolicy.Need.WATCH) {
            L.life("AA server: encendido (" + (connected ? "Android Auto conectado" : "127.0.0.1:" + PORT + " contesta") + "; "
                    + AaServerPolicy.needName(need) + "; arranque manual)");
        }
        scheduleNext(app, cfg);
    }

    private static void scheduleNext(Context app, Config cfg) {
        long ms = AaServerPolicy.nextCheckMs(LinkState.running, cfg.aaServerManual(), Config.isAa(cfg.mode()) || AaPark.parked,
                waiting);
        if (ms < 0) {
            stopInternal(app, "el enlace no está en marcha");
            return;
        }
        next = schedule(() -> {
            next = null;
            check(app, waitingForNow());
        }, ms);
    }

    private static void startAsking(Context app, AaServerPolicy.Need need, boolean locked) {
        waiting = true;
        waitingFor = need == AaServerPolicy.Need.WATCH ? AaServerPolicy.Need.CAR_SEEN : need;
        L.lifeWarn("AA server: apagado (" + AaServerPolicy.needName(need) + (locked ? ", móvil bloqueado" : "")
                + "): aviso «" + Str.get(R.string.hql_aa_server_ask_title) + "» y miro cada "
                + AaServerPolicy.POLL_MS / 1000 + " s si 127.0.0.1:" + PORT + " contesta (arranque manual, sin accesibilidad)");
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
        LinkState.setSource(LinkState.Level.BUSY, Str.get(R.string.hql_aa_server_wait));
    }

    /** El servidor contesta tras el aviso: fuera el aviso y, si una sesión esperaba a AA, Self-Mode otra vez. */
    private static void onUp(Context app) {
        waiting = false;
        app.getSystemService(NotificationManager.class).cancel(NOTIF_ASK);
        L.life("AA server: listo (arrancado a mano)");
        if (Str.get(R.string.hql_aa_server_wait).equals(LinkState.source)) {
            LinkState.setSource(LinkState.Level.OK, Str.get(R.string.hql_aa_server_ready));
        }
        if (!relaunch) return;
        relaunch = false;
        // El intento del Self-Mode que no encontró el servidor tiene que haber terminado (no se lanzan dos a la vez).
        schedule(() -> {
            boolean videoWaiting = com.andrerinas.openheadunit.decoder.video.VideoTap.getSink() != null;
            if (!LinkState.running || aaConnected(app) || !videoWaiting) return;
            L.life("relanzo Android Auto (Self-Mode) para el coche que espera (servidor arrancado a mano)");
            try {
                app.startForegroundService(new Intent(app, com.andrerinas.openheadunit.aap.AapService.class)
                        .setAction(com.andrerinas.openheadunit.aap.AapService.ACTION_START_SELF_MODE));
            } catch (RuntimeException e) {
                L.e("no se pudo relanzar Android Auto", e);
            }
        }, RELAUNCH_DELAY_MS);
    }

    private static void stopInternal(Context app, String why) {
        cancelNext();
        relaunch = false;
        last = AaServerPolicy.Server.UNKNOWN;
        if (!waiting) return;
        waiting = false;
        app.getSystemService(NotificationManager.class).cancel(NOTIF_ASK);
        L.life("AA server: dejo de esperar a que lo arranques (" + why + ")");
        if (Str.get(R.string.hql_aa_server_wait).equals(LinkState.source)) LinkState.setSource(LinkState.Level.IDLE, "");
    }

    /** Tras cerrar el enlace: si el servidor sigue encendido, un aviso con cómo pararlo (nunca se pulsa nada). */
    private static void tellIfStillOn(Context app, String why) {
        if (LinkState.running) return; // se ha vuelto a conectar mientras tanto
        AaServerPolicy.Server server = probe(app);
        AaServerPolicy.End end = AaServerPolicy.onEnd(true, new AaServerPolicy.State().manual(true).server(server));
        if (end != AaServerPolicy.End.LEAVE_ON_NOTICE) {
            L.life("AA server: apagado al cerrar (" + why + "): nada que avisar");
            return;
        }
        L.lifeWarn("AA server: sigue encendido tras " + why + " (arranque manual: HeadQLink no lo para); aviso «"
                + Str.get(R.string.hql_server_on_title) + "» con cómo pararlo (Android Auto › ⋮ › Parar servidor)");
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_INFO, Str.get(R.string.hql_aa_server_info_channel),
                NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent pi = PendingIntent.getActivity(app, NOTIF_STILL_ON, AaServerStarter.aaSettingsIntent(app),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = Str.get(R.string.hql_aa_server_still_on_text);
        nm.notify(NOTIF_STILL_ON, new Notification.Builder(app, CHANNEL_INFO)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setColor(app.getColor(R.color.hql_warn))
                .setContentTitle(Str.get(R.string.hql_server_on_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    // ---------------------------------------------------------------- utilidades

    private static boolean aaConnected(Context ctx) {
        try {
            return com.andrerinas.openheadunit.App.Companion.provide(ctx).getCommManager().isConnected();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isLocked(Context ctx) {
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    private static void cancelNext() {
        ScheduledFuture<?> f = next;
        next = null;
        if (f != null) f.cancel(false);
    }

    private static ScheduledExecutorService exec() {
        synchronized (LOCK) {
            if (exec == null) {
                ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1, r -> {
                    Thread t = new Thread(r, "aa-server-manual");
                    t.setDaemon(true);
                    return t;
                });
                exec = e;
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
