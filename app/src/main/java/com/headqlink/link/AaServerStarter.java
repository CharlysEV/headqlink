package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Arranca el "servidor de unidad principal" de Android Auto pulsando su menú de desarrollador con
 * el servicio de accesibilidad (TouchService). Desde AA 17.4 es la única forma de que AA acepte
 * una head unit local (127.0.0.1:5277) sin hardware externo.
 *
 * Requisitos: modo desarrollador de AA activado una vez, TouchService activo y móvil desbloqueado
 * (los ajustes de AA no se abren sobre la pantalla de bloqueo).
 *
 * Una automatización cada vez (arrancar y parar a la vez se pisaban en los mismos ajustes); un apagado en cola se anula
 * si después se pide el servidor (arrancarlo, o el coche que vuelve). Con el móvil bloqueado: el apagado queda pendiente
 * hasta desbloquear y el arranque, con el enlace en marcha, se avisa («Desbloquea el móvil para iniciar Android Auto») y se
 * hace solo al desbloquear. Si un apagado no se confirma, una notificación lo dice y permite reintentarlo.
 *
 * Con el «Arranque del servidor de Android Auto» en manual ({@link #manual}) no se automatiza nada: ni arrancar, ni
 * parar, ni la capa, ni el botón de su notificación. Cada entrada lo comprueba; los intentos los lleva
 * {@link AaServerManual} con la conexión real del Self-Mode (nunca se sondea el puerto: una conexión que abre y cierra
 * sin hablar bloquea el servidor hasta pararlo y volver a iniciarlo).
 */
public final class AaServerStarter {
    static final String AA_PKG = "com.google.android.projection.gearhead";
    private static final String AA_SETTINGS = "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity";
    private static final long POLL_MS = 300;
    private static final long TIMEOUT_MS = 10_000;

    private static final String PREFS = "cfg";
    private static final String PENDING_STOP = "aa_server_stop_pending";
    /** Último resultado conocido: 1 = modo desarrollador de AA activo, 0 = falta, -1 = desconocido. */
    static final String DEV_MODE = "aa_dev_mode";
    /** Último estado conocido del servidor (1 encendido, 0 apagado, -1 no se sabe) y cuándo (System.currentTimeMillis). */
    private static final String SERVER_STATE = "aa_server_state";
    private static final String SERVER_STATE_AT = "aa_server_state_at";
    /** Un arranque confirmado hace menos de esto vale: no se repite la automatización (arranque anticipado + Self-Mode). */
    private static final long FRESH_ON_MS = 20_000;
    /** Con el menú ⋮ abierto, cuánto esperamos a ver la opción del servidor antes de concluir que falta. */
    private static final long MENU_WAIT_MS = 2000;

    private static final String CHANNEL_UNLOCK = "aa_unlock";
    /** El de los avisos de seguridad de AaGuardService. */
    private static final String CHANNEL_ALERT = "aa_guard_alert";
    private static final int NOTIF_UNLOCK = 5;
    private static final int NOTIF_SERVER_ON = 6;

    /** Una sola automatización de los ajustes de AA a la vez (reentrante: el reinicio son dos seguidas). */
    private static final ReentrantLock RUN_LOCK = new ReentrantLock();
    /** Sube al pedir el servidor (arranque) o anular el apagado: un apagado en cola que la ve cambiada no se hace. */
    private static final AtomicLong stopEpoch = new AtomicLong();
    /** Arrancar el servidor al desbloquear (aviso «Desbloquea el móvil para iniciar Android Auto» puesto). */
    private static volatile boolean startOnUnlock;
    /** Un arranque falló por el bloqueo con el enlace en marcha: tras arrancarlo al desbloquear, se relanza el Self-Mode. */
    private static volatile boolean relaunchAfterUnlock;

    private static String coverText(boolean starting) {
        return "HeadQLink\n\n" + Str.get(starting ? R.string.hql_cover_starting : R.string.hql_cover_stopping);
    }

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private final boolean wantRunning;
    /** Solo comprobar si el modo desarrollador está activo (no pulsa nada en el menú). */
    private boolean checkOnly;
    /** false: dejar los ajustes de AA abiertos (para encadenar otra acción, p. ej. en un reinicio). */
    private final boolean closeAfter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicBoolean ok = new AtomicBoolean();
    private long deadline;
    private boolean menuOpened;
    private long menuOpenedAt;
    private Context appCtx;

    private AaServerStarter(boolean wantRunning, boolean closeAfter) {
        this.wantRunning = wantRunning;
        this.closeAfter = closeAfter;
    }

    /**
     * «Arranque del servidor de Android Auto» en manual (sin accesibilidad): nada de automatizar sus ajustes (ni arrancar,
     * ni parar, ni la capa, ni el botón de su notificación). Lo lleva {@link AaServerManual}.
     */
    static boolean manual(Context ctx) {
        return new Config(ctx).aaServerManual();
    }

    /** AA atendió un intento (arranque manual): el modo desarrollador de AA está activo (solo desde él se arranca). */
    static void noteDevModeOn(Context ctx) {
        android.content.SharedPreferences sp = prefs(ctx);
        if (sp.getInt(DEV_MODE, -1) != 1) sp.edit().putInt(DEV_MODE, 1).apply();
    }

    /** El servidor de AA no está en marcha y no hemos podido arrancarlo: se muestra en la pantalla principal. */
    public static void reportCannotStart(Context ctx) {
        if (manual(ctx)) {
            // Arranque manual: el intento rechazado ya lo cuenta AaServerManual, que pone el aviso, la fila «Auto» y los
            // reintentos (sin sondear el puerto).
            L.i("AA: 127.0.0.1:5277 no acepta la conexión; arranque manual: lo llevan los intentos (aviso y reintento)");
            return;
        }
        String why = cannotRunReason(ctx);
        String msg = why != null ? Str.get(R.string.hql_aa_no_start_why, why)
                : devModeState(ctx) == 0 ? Str.get(R.string.hql_aa_no_start_why, Str.get(R.string.hql_aa_devmode_missing))
                : Str.get(R.string.hql_aa_server_failed);
        L.w("AA: " + msg);
        LinkState.setSource(LinkState.Level.ERROR, msg);
    }

    /** Motivo por el que no se puede automatizar ahora, o null si se puede. En el arranque manual, nunca se puede. */
    public static String cannotRunReason(Context ctx) {
        if (manual(ctx)) return Str.get(R.string.hql_reason_manual);
        if (TouchService.instance == null) return Str.get(R.string.hql_reason_accessibility);
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) return Str.get(R.string.hql_reason_unlock);
        return null;
    }

    private static boolean isLocked(Context ctx) {
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    /**
     * Bloquea hasta que el servidor se ha pedido arrancar (true) o falla/expira (false).
     * No llamar desde el hilo principal. Anula un apagado pendiente o en cola. Con el móvil bloqueado y el enlace en
     * marcha, deja el arranque para el desbloqueo (con su aviso) y devuelve false.
     */
    public static boolean startAndWait(Context ctx) {
        prefs(ctx).edit().putBoolean(PENDING_STOP, false).apply();
        stopEpoch.incrementAndGet();
        // Arranque manual: no se pulsa nada ni se mira el puerto (una sonda bloquearía el servidor). Lo llama el Self-Mode
        // tras una conexión rechazada, que AaServerManual ya contó: aviso y reintentos.
        if (manual(ctx)) return false;
        if (TouchService.instance != null && isLocked(ctx) && LinkState.running) {
            relaunchAfterUnlock = true;
            requestStartOnUnlock(ctx, "Android Auto necesita su servidor y el móvil está bloqueado");
            L.w("AA server: no se puede automatizar: " + Str.get(R.string.hql_reason_unlock));
            return false;
        }
        RUN_LOCK.lock();
        try {
            if (serverFreshOn(ctx)) {
                L.i("AA server: arrancado hace " + (System.currentTimeMillis() - prefs(ctx).getLong(SERVER_STATE_AT, 0)) / 1000
                        + " s; no repito la automatización");
                return true;
            }
            return runAndWait(ctx, true);
        } finally {
            RUN_LOCK.unlock();
        }
    }

    /** Último estado conocido del servidor: encendido (lo arrancamos y no lo hemos apagado después). */
    static boolean serverLikelyOn(Context ctx) {
        return prefs(ctx).getInt(SERVER_STATE, -1) == 1;
    }

    /** Hay un apagado del servidor pendiente (se hará al desbloquear). */
    static boolean stopPending(Context ctx) {
        return prefs(ctx).getBoolean(PENDING_STOP, false);
    }

    private static boolean serverFreshOn(Context ctx) {
        android.content.SharedPreferences sp = prefs(ctx);
        long age = System.currentTimeMillis() - sp.getLong(SERVER_STATE_AT, 0);
        return sp.getInt(SERVER_STATE, -1) == 1 && age >= 0 && age < FRESH_ON_MS;
    }

    /** Resultado de una automatización (o del botón de la notificación): el servidor queda encendido o apagado. */
    private static void noteServerState(Context ctx, boolean on) {
        prefs(ctx).edit().putInt(SERVER_STATE, on ? 1 : 0).putLong(SERVER_STATE_AT, System.currentTimeMillis()).apply();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        // Encendido a propósito o apagado: el aviso «sigue encendido» ya no vale.
        nm.cancel(NOTIF_SERVER_ON);
        if (on && startOnUnlock) cancelStartOnUnlock(ctx, "servidor arrancado");
    }

    /** Botón «Detener» de la notificación del servidor de AA (lo guarda TouchService al publicarse). */
    private static volatile android.app.PendingIntent stopIntent;

    /**
     * La notificación del servidor (canal gearhead_connection_status) lleva un único botón, que manda
     * la acción "shutdown" a su servicio. Se guarda ese PendingIntent.
     */
    static void noteAaNotification(android.app.Notification n) {
        if (n.actions == null || n.actions.length != 1 || n.actions[0].actionIntent == null) return;
        if (!"gearhead_connection_status".equals(n.getChannelId())) return;
        android.app.PendingIntent pi = n.actions[0].actionIntent;
        if (android.os.Build.VERSION.SDK_INT >= 31 && !pi.isService()) return;
        if (stopIntent == null) L.i("AA server: guardado el botón «Detener» de su notificación");
        stopIntent = pi;
    }

    /** ¿Se puede apagar el servidor sin abrir sus ajustes (con el móvil bloqueado incluso)? */
    static boolean canStopWithoutUi() {
        return stopIntent != null;
    }

    /** Apaga el servidor pulsando el botón de su notificación. false si no se tiene o ya no vale. */
    private static boolean stopViaNotification(Context ctx) {
        android.app.PendingIntent pi = stopIntent;
        if (pi == null || manual(ctx)) return false;
        try {
            pi.send();
            stopIntent = null;
            prefs(ctx).edit().putBoolean(PENDING_STOP, false).apply();
            noteServerState(ctx, false);
            L.life("AA server: apagado con el botón de su notificación (sin abrir ajustes)");
            return true;
        } catch (android.app.PendingIntent.CanceledException e) {
            stopIntent = null;
            L.w("AA server: el botón de su notificación ya no vale");
            return false;
        }
    }

    /**
     * Apaga el servidor ahora si el móvil está desbloqueado; si no, lo deja pendiente y TouchService
     * lo apaga en cuanto el usuario desbloquee (así el puerto 5277 no queda abierto).
     */
    public static void requestStop(Context ctx) {
        if (leaveOnInManual(ctx)) return;
        if (stopViaNotification(ctx)) return;
        String why = cannotRunReason(ctx);
        if (why == null) {
            stopInBackground(ctx, "cierre");
        } else {
            L.life("AA server: se apagará al desbloquear el móvil (" + why + ")");
            prefs(ctx).edit().putBoolean(PENDING_STOP, true).apply();
        }
    }

    /**
     * Apaga el servidor solo si ahora se puede sin que se note (móvil desbloqueado, tras la capa); si
     * no, lo deja encendido y sin apagado pendiente (no saldría la capa al desbloquear).
     */
    public static void stopIfUnlocked(Context ctx) {
        cancelPendingStop(ctx);
        if (leaveOnInManual(ctx)) return;
        if (stopViaNotification(ctx)) return;
        String why = cannotRunReason(ctx);
        if (why == null) {
            stopInBackground(ctx, "cierre");
        } else {
            L.life("AA server: queda encendido (" + why + ")");
        }
    }

    /** Arranque manual: el servidor no se para nunca desde HeadQLink (ni ahora ni al desbloquear). */
    private static boolean leaveOnInManual(Context ctx) {
        if (!manual(ctx)) return false;
        prefs(ctx).edit().putBoolean(PENDING_STOP, false).apply();
        L.life("AA server: arranque manual: no lo apago (no se pulsa nada; queda encendido)");
        return true;
    }

    /** Olvida un apagado pendiente (no se abrirán los ajustes de AA al desbloquear) y anula el que esté en cola. */
    public static void cancelPendingStop(Context ctx) {
        stopEpoch.incrementAndGet();
        if (!prefs(ctx).getBoolean(PENDING_STOP, false)) return;
        prefs(ctx).edit().putBoolean(PENDING_STOP, false).apply();
        L.life("AA server: apagado pendiente anulado");
    }

    /** Apagado en otro hilo; si antes de empezar se pide el servidor (stopEpoch), no se hace. */
    private static void stopInBackground(Context ctx, String why) {
        long epoch = stopEpoch.get();
        Context app = ctx.getApplicationContext();
        new Thread(() -> runStop(app, epoch, why), "aa-server-stop").start();
    }

    private static void runStop(Context ctx, long epoch, String why) {
        RUN_LOCK.lock();
        try {
            if (stopEpoch.get() != epoch) {
                L.life("AA server: apagado (" + why + ") anulado: se ha vuelto a pedir el servidor");
                return;
            }
            boolean ok = runAndWait(ctx, false);
            if (!ok && stopEpoch.get() == epoch && !LinkState.running && cannotRunReason(ctx) == null
                    && com.andrerinas.openheadunit.App.Companion.provide(ctx).getCommManager().isConnected()) {
                // Con nuestra head unit aún conectada (aparcada), el menú de AA puede no mostrar la opción del servidor:
                // se suelta Android Auto y se reintenta una vez.
                L.lifeWarn("AA server: no se pudo apagar con Android Auto conectado; lo suelto y lo reintento");
                AaPark.release("reintento del apagado");
                AaClose.stopAa(ctx, "reintento del apagado del servidor");
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
                ok = runAndWait(ctx, false);
            }
            if (!ok && stopEpoch.get() == epoch) {
                L.lifeWarn("AA server: el apagado (" + why + ") no se confirmó; puede seguir encendido: aviso al usuario");
                notifyServerStillOn(ctx);
            }
            // Servidor apagado sin enlace: nuestra head unit cae con él; fuera la pausa (si el guardián no estaba).
            if (ok && !LinkState.running && !AaGuardService.active) AaPark.release("servidor apagado");
        } finally {
            RUN_LOCK.unlock();
        }
    }

    /** Apaga y vuelve a encender el servidor (recupera un servidor que acepta TCP pero no responde). */
    public static boolean restartAndWait(Context ctx) {
        if (manual(ctx)) {
            L.w("AA server: reinicio pedido con el arranque manual: no se pulsa nada (páralo y arráncalo en Android Auto › ⋮)");
            return false;
        }
        L.i("AA server: reinicio");
        // Una sola visita a los ajustes: parar (sin cerrar), esperar y volver a iniciar.
        // La capa se mantiene durante los dos pasos.
        RUN_LOCK.lock();
        TouchService ts = TouchService.instance;
        if (ts != null) ts.acquireCover(coverText(true), false);
        try {
            runAndWait(ctx, false, false);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            prefs(ctx).edit().putBoolean(PENDING_STOP, false).apply();
            stopEpoch.incrementAndGet();
            return runAndWait(ctx, true);
        } finally {
            if (ts != null) ts.releaseCover(1200);
            RUN_LOCK.unlock();
        }
    }

    // ---------------------------------------------------------------- arrancar al desbloquear y avisos

    /**
     * Hay que arrancar el servidor y el móvil está bloqueado: aviso de prioridad alta «Desbloquea el móvil para iniciar
     * Android Auto» y arranque automático al desbloquear (TouchService → onUnlock), sin abrir la app.
     */
    static void requestStartOnUnlock(Context ctx, String why) {
        prefs(ctx).edit().putBoolean(PENDING_STOP, false).apply();
        stopEpoch.incrementAndGet();
        if (manual(ctx)) {
            // Sin accesibilidad no se arranca al desbloquear, y no se mira el puerto: lo dirá el intento con el coche.
            L.life("AA server: " + why + ": arranque manual: nada que arrancar al desbloquear (lo dirá el intento real)");
            return;
        }
        if (startOnUnlock) return;
        startOnUnlock = true;
        L.life("AA server: " + why + ": aviso «" + Str.get(R.string.hql_unlock_start_title)
                + "» y lo arranco al desbloquear");
        Context app = ctx.getApplicationContext();
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_UNLOCK, Str.get(R.string.hql_unlock_channel),
                NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(app, NOTIF_UNLOCK, new Intent(app, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(NOTIF_UNLOCK, new Notification.Builder(app, CHANNEL_UNLOCK)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setColor(app.getColor(R.color.hql_accent))
                .setContentTitle(Str.get(R.string.hql_unlock_start_title))
                .setContentText(Str.get(R.string.hql_unlock_start_text))
                .setStyle(new Notification.BigTextStyle().bigText(Str.get(R.string.hql_unlock_start_text)))
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    /** Ya no hace falta arrancar el servidor al desbloquear (arrancado, o el enlace se cierra). */
    static void cancelStartOnUnlock(Context ctx, String why) {
        relaunchAfterUnlock = false;
        if (!startOnUnlock) return;
        startOnUnlock = false;
        ctx.getSystemService(NotificationManager.class).cancel(NOTIF_UNLOCK);
        L.life("AA server: ya no lo arranco al desbloquear (" + why + ")");
    }

    /** TouchService, al desbloquear: el arranque pedido con el móvil bloqueado o, si no, el apagado pendiente. */
    static void onUnlock(Context ctx) {
        if (manual(ctx)) {
            // Arranque manual (quizá cambiado con algo pendiente del automático): nada que pulsar al desbloquear.
            if (startOnUnlock) cancelStartOnUnlock(ctx, "arranque manual");
            runPendingStop(ctx);
            AaServerManual.checkSoon(ctx);
            return;
        }
        if (!startOnUnlock) {
            runPendingStop(ctx);
            return;
        }
        startOnUnlock = false;
        boolean relaunch = relaunchAfterUnlock;
        relaunchAfterUnlock = false;
        Context app = ctx.getApplicationContext();
        app.getSystemService(NotificationManager.class).cancel(NOTIF_UNLOCK);
        if (!LinkState.running) {
            L.life("desbloqueado: el enlace ya no está en marcha; no arranco el servidor de Android Auto");
            return;
        }
        L.life("desbloqueado: arranco el servidor de Android Auto (pedido con el móvil bloqueado)");
        new Thread(() -> {
            boolean ok = startAndWait(app);
            L.life("servidor de Android Auto tras desbloquear: " + (ok ? "listo" : "no confirmado"));
            boolean connected = com.andrerinas.openheadunit.App.Companion.provide(app).getCommManager().isConnected();
            // Solo con un vídeo esperando a AA (su grifo puesto): sin superficie, AA mandaría imagen a un decodificador
            // sin ella. Sin vídeo, ya lo lanzará la sesión con el coche.
            boolean videoWaiting = com.andrerinas.openheadunit.decoder.video.VideoTap.getSink() != null;
            if (ok && relaunch && LinkState.running && !connected && videoWaiting) {
                // El Self-Mode de la sesión con el coche falló por el bloqueo: se relanza (Open Headunit no lanza dos a la vez).
                L.life("relanzo Android Auto (Self-Mode) para el coche que espera");
                AaClose.noteLaunch();
                try {
                    app.startForegroundService(new Intent(app, com.andrerinas.openheadunit.aap.AapService.class)
                            .setAction(com.andrerinas.openheadunit.aap.AapService.ACTION_START_SELF_MODE));
                } catch (RuntimeException e) {
                    L.e("no se pudo relanzar Android Auto", e);
                }
            }
        }, "aa-unlock-start").start();
    }

    /** La capa de la automatización llevaba demasiado (TouchService la ha quitado). */
    static void onCoverTimeout(Context ctx, boolean stopping, long ms) {
        L.lifeWarn("la capa «" + Str.get(stopping ? R.string.hql_cover_stopping : R.string.hql_cover_starting)
                + "» llevaba " + ms / 1000 + " s: la quito (la automatización de Android Auto no terminó)");
        if (stopping && prefs(ctx).getInt(SERVER_STATE, -1) != 0) notifyServerStillOn(ctx);
    }

    /** Aviso «El servidor de Android Auto sigue encendido · Tocar para apagarlo» (reintenta el apagado). */
    static void notifyServerStillOn(Context ctx) {
        Context app = ctx.getApplicationContext();
        L.lifeWarn("aviso: el servidor de Android Auto puede seguir encendido");
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT, Str.get(R.string.hql_guard_alert_channel),
                NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getForegroundService(app, NOTIF_SERVER_ON,
                new Intent(app, LinkService.class).setAction(LinkService.ACTION_AA_SERVER_OFF),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(NOTIF_SERVER_ON, new Notification.Builder(app, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setColor(app.getColor(R.color.hql_warn))
                .setContentTitle(Str.get(R.string.hql_server_on_title))
                .setContentText(Str.get(R.string.hql_server_on_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    static void cancelServerStillOn(Context ctx) {
        ctx.getSystemService(NotificationManager.class).cancel(NOTIF_SERVER_ON);
    }

    /**
     * Comprueba (sin arrancar ni parar nada) si el menú de AA tiene la opción del servidor, es decir,
     * si el modo desarrollador de AA está activo. Devuelve 1, 0 o -1 (no se pudo comprobar).
     */
    public static int checkDevModeAndWait(Context ctx) {
        String why = cannotRunReason(ctx);
        if (why != null) {
            L.w("AA server: no se puede comprobar: " + why);
            return -1;
        }
        RUN_LOCK.lock();
        try {
            AaServerStarter s = new AaServerStarter(true, true);
            s.checkOnly = true;
            s.main.post(() -> s.begin(ctx.getApplicationContext()));
            try {
                s.done.await(TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        } finally {
            RUN_LOCK.unlock();
        }
        return devModeState(ctx);
    }

    /** Último resultado conocido del modo desarrollador de AA (ver [DEV_MODE]). */
    public static int devModeState(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(DEV_MODE, -1);
    }

    /** Abre los ajustes de Android Auto (modo desarrollador; en el arranque manual, su menú ⋮ con el servidor). */
    public static void openAaSettings(Context ctx) {
        ctx.startActivity(aaSettingsIntent(ctx));
    }

    /**
     * La pantalla de ajustes de Android Auto (la misma que abre la automatización), para abrirla desde la app o desde una
     * notificación. Si este Android Auto no la tiene, su «Info. de la app».
     */
    static Intent aaSettingsIntent(Context ctx) {
        Intent i = new Intent().setClassName(AA_PKG, AA_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (ctx.getPackageManager().resolveActivity(i, 0) != null) return i;
        } catch (RuntimeException ignored) {
        }
        return new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + AA_PKG)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Al desbloquear (TouchService, AaGuardService): el apagado pendiente, si lo hay y nadie lo ha anulado. */
    static void runPendingStop(Context ctx) {
        if (!prefs(ctx).getBoolean(PENDING_STOP, false)) return;
        if (leaveOnInManual(ctx)) return;
        if (stopViaNotification(ctx)) return;
        prefs(ctx).edit().putBoolean(PENDING_STOP, false).apply();
        if (LinkState.running) {
            // El enlace volvió a arrancar (espera del coche): el servidor hace falta; lo apagará el enlace al terminar.
            L.life("desbloqueado: el enlace está en marcha; no apago el servidor de Android Auto");
            return;
        }
        L.life("AA server: apagado pendiente tras desbloqueo");
        stopInBackground(ctx, "pendiente tras desbloqueo");
    }

    private static boolean runAndWait(Context ctx, boolean wantRunning) {
        return runAndWait(ctx, wantRunning, true);
    }

    /** Una automatización (con RUN_LOCK: nunca dos a la vez). No llamar desde el hilo principal. */
    private static boolean runAndWait(Context ctx, boolean wantRunning, boolean closeAfter) {
        String why = cannotRunReason(ctx);
        if (why != null) {
            L.w("AA server: no se puede automatizar: " + why);
            return false;
        }
        RUN_LOCK.lock();
        try {
            AaServerStarter s = new AaServerStarter(wantRunning, closeAfter);
            s.main.post(() -> s.begin(ctx.getApplicationContext()));
            try {
                if (!s.done.await(TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS)) {
                    L.lifeWarn("AA server: la automatización (" + (wantRunning ? "arrancar" : "apagar") + ") no terminó en "
                            + (TIMEOUT_MS + 2000) / 1000 + " s");
                }
            } catch (InterruptedException ignored) {
            }
            return s.ok.get();
        } finally {
            RUN_LOCK.unlock();
        }
    }

    private void begin(Context ctx) {
        appCtx = ctx;
        TouchService cover = TouchService.instance;
        if (cover != null) cover.acquireCover(coverText(wantRunning), !wantRunning && !checkOnly);
        L.i("AA server: abriendo ajustes de Android Auto");
        Intent i = new Intent().setClassName(AA_PKG, AA_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (RuntimeException e) {
            L.e("AA server: no se pudieron abrir los ajustes de AA", e);
            finish(false);
            return;
        }
        deadline = System.currentTimeMillis() + TIMEOUT_MS;
        main.postDelayed(this::poll, POLL_MS);
    }

    private int menuRetries;

    private void poll() {
        TouchService ts = TouchService.instance;
        if (ts == null) {
            finish(false);
            return;
        }
        if (System.currentTimeMillis() > deadline) {
            L.w("AA server: tiempo agotado (¿modo desarrollador de AA activado?)");
            back(ts, menuOpened ? 2 : 1);
            finish(false);
            return;
        }
        AccessibilityNodeInfo root = ts.getRootInActiveWindow();
        if (root != null && root.getPackageName() != null && AA_PKG.contentEquals(root.getPackageName())) {
            AccessibilityNodeInfo item = find(root, AaServerStarter::isServerItem);
            if (item != null && checkOnly) {
                L.i("AA server: modo desarrollador de AA activo");
                setDevMode(1);
                back(ts, 2);
                finish(true);
                return;
            }
            if (item != null) {
                String text = String.valueOf(item.getText()).toLowerCase(Locale.ROOT);
                // El texto del menú es la acción disponible: "Parar/Detener/Stop" => está encendido.
                boolean running = text.contains("parar") || text.contains("detener") || text.contains("stop");
                if (running == wantRunning) {
                    L.i("AA server: ya estaba " + (running ? "iniciado" : "parado") + " (" + item.getText() + ")");
                    back(ts, closeAfter ? 2 : 1); // cerrar el menú (y los ajustes)
                } else {
                    L.i("AA server: pulsando '" + item.getText() + "'");
                    click(item); // el menú se cierra solo
                    if (closeAfter) back(ts, 1);
                }
                setDevMode(1);
                if (appCtx != null) noteServerState(appCtx, wantRunning);
                finish(true);
                return;
            }
            if (!menuOpened) {
                AccessibilityNodeInfo more = find(root, AaServerStarter::isOverflowButton);
                if (more != null) {
                    L.i("AA server: abriendo menú '" + more.getContentDescription() + "'");
                    click(more);
                    menuOpened = true;
                    menuOpenedAt = System.currentTimeMillis();
                }
            } else if (System.currentTimeMillis() - menuOpenedAt > MENU_WAIT_MS && menuRetries < 1) {
                // Menú sin la opción: puede que AA aún esté cerrando una sesión y lo haya pintado a
                // medias. Se cierra y se vuelve a abrir una vez antes de darlo por perdido.
                menuRetries++;
                L.i("AA server: el menú no muestra la opción del servidor; lo reabro");
                back(ts, 1);
                menuOpened = false;
            } else if (System.currentTimeMillis() - menuOpenedAt > MENU_WAIT_MS) {
                // Tampoco al reabrirlo: el modo desarrollador de AA no está activado.
                L.w("AA server: el menú de AA no tiene la opción del servidor; falta activar el modo desarrollador de AA");
                setDevMode(0);
                back(ts, 2);
                finish(false);
                return;
            }
        }
        main.postDelayed(this::poll, POLL_MS);
    }

    private static boolean isOverflowButton(AccessibilityNodeInfo n) {
        CharSequence d = n.getContentDescription();
        if (d == null) return false;
        String s = d.toString().toLowerCase(Locale.ROOT);
        return s.contains("más opciones") || s.contains("more options") || s.equals("opciones") || s.equals("options");
    }

    private static boolean isServerItem(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null) return false;
        String s = t.toString().toLowerCase(Locale.ROOT);
        return s.contains("unidad principal") || s.contains("head unit") || s.contains("headunit");
    }

    private interface Match {
        boolean test(AccessibilityNodeInfo n);
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, Match m) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (m.test(n)) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }

    /** Pulsa el nodo o el primer ancestro pulsable. */
    private static void click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        while (cur != null && !cur.isClickable()) cur = cur.getParent();
        (cur != null ? cur : n).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private void back(TouchService ts, int times) {
        for (int i = 0; i < times; i++) {
            main.postDelayed(() -> ts.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK), 400L * (i + 1));
        }
    }

    private void setDevMode(int v) {
        if (appCtx != null) appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(DEV_MODE, v).apply();
    }

    private void finish(boolean success) {
        TouchService ts = TouchService.instance;
        if (closeAfter && ts != null) {
            leaveAa(ts, success, 0);
            return;
        }
        complete(success);
    }

    /**
     * Antes de quitar la capa, comprueba que los ajustes de AA ya no están delante: el «atrás» que
     * sigue a pulsar una opción del menú a veces se lo come el cierre del propio menú, y los ajustes
     * quedaban a la vista. Hasta 3 «atrás» más y, si aún siguen, al escritorio.
     */
    private void leaveAa(TouchService ts, boolean success, int tries) {
        main.postDelayed(() -> {
            AccessibilityNodeInfo root = ts.getRootInActiveWindow();
            boolean stillAa = root != null && root.getPackageName() != null && AA_PKG.contentEquals(root.getPackageName());
            if (stillAa && tries < 3) {
                L.i("AA server: los ajustes de AA siguen delante; atrás");
                ts.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                leaveAa(ts, success, tries + 1);
                return;
            }
            if (stillAa) ts.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            complete(success);
        }, tries == 0 ? 900 : 500);
    }

    private void complete(boolean success) {
        TouchService cover = TouchService.instance;
        if (cover != null) cover.releaseCover(1200); // tapa también las animaciones de vuelta
        ok.set(success);
        done.countDown();
    }
}
