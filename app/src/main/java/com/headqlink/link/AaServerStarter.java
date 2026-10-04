package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
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

/**
 * Arranca el "servidor de unidad principal" de Android Auto pulsando su menú de desarrollador con
 * el servicio de accesibilidad (TouchService). Desde AA 17.4 es la única forma de que AA acepte
 * una head unit local (127.0.0.1:5277) sin hardware externo.
 *
 * Requisitos: modo desarrollador de AA activado una vez, TouchService activo y móvil desbloqueado
 * (los ajustes de AA no se abren sobre la pantalla de bloqueo).
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
    /** Con el menú ⋮ abierto, cuánto esperamos a ver la opción del servidor antes de concluir que falta. */
    private static final long MENU_WAIT_MS = 2000;
    private static String coverText(boolean starting) {
        return "HeadQLink\n\n" + Str.get(starting ? R.string.hql_cover_starting : R.string.hql_cover_stopping);
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

    /** El servidor de AA no está en marcha y no hemos podido arrancarlo: se muestra en la pantalla principal. */
    public static void reportCannotStart(Context ctx) {
        String why = cannotRunReason(ctx);
        String msg = why != null ? Str.get(R.string.hql_aa_no_start_why, why)
                : devModeState(ctx) == 0 ? Str.get(R.string.hql_aa_no_start_why, Str.get(R.string.hql_aa_devmode_missing))
                : Str.get(R.string.hql_aa_server_failed);
        L.w("AA: " + msg);
        LinkState.setSource(LinkState.Level.ERROR, msg);
    }

    /** Motivo por el que no se puede automatizar ahora, o null si se puede. */
    public static String cannotRunReason(Context ctx) {
        if (TouchService.instance == null) return Str.get(R.string.hql_reason_accessibility);
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) return Str.get(R.string.hql_reason_unlock);
        return null;
    }

    /**
     * Bloquea hasta que el servidor se ha pedido arrancar (true) o falla/expira (false).
     * No llamar desde el hilo principal.
     */
    public static boolean startAndWait(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PENDING_STOP, false).apply();
        return runAndWait(ctx, true);
    }

    /**
     * Apaga el servidor ahora si el móvil está desbloqueado; si no, lo deja pendiente y TouchService
     * lo apaga en cuanto el usuario desbloquee (así el puerto 5277 no queda abierto).
     */
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
        if (pi == null) return false;
        try {
            pi.send();
            stopIntent = null;
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PENDING_STOP, false).apply();
            L.i("AA server: apagado con el botón de su notificación (sin abrir ajustes)");
            return true;
        } catch (android.app.PendingIntent.CanceledException e) {
            stopIntent = null;
            L.w("AA server: el botón de su notificación ya no vale");
            return false;
        }
    }

    public static void requestStop(Context ctx) {
        if (stopViaNotification(ctx)) return;
        if (cannotRunReason(ctx) == null) {
            new Thread(() -> runAndWait(ctx, false), "aa-server-stop").start();
        } else {
            L.i("AA server: se apagará al desbloquear el móvil");
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PENDING_STOP, true).apply();
        }
    }

    /**
     * Apaga el servidor solo si ahora se puede sin que se note (móvil desbloqueado, tras la capa); si
     * no, lo deja encendido y sin apagado pendiente (no saldría la capa al desbloquear).
     */
    public static void stopIfUnlocked(Context ctx) {
        cancelPendingStop(ctx);
        if (stopViaNotification(ctx)) return;
        String why = cannotRunReason(ctx);
        if (why == null) {
            new Thread(() -> runAndWait(ctx, false), "aa-server-stop").start();
        } else {
            L.i("AA server: queda encendido (" + why + ")");
        }
    }

    /** Olvida un apagado pendiente (no se abrirán los ajustes de AA al desbloquear). */
    public static void cancelPendingStop(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PENDING_STOP, false).apply();
    }

    /** Apaga y vuelve a encender el servidor (recupera un servidor que acepta TCP pero no responde). */
    public static boolean restartAndWait(Context ctx) {
        L.i("AA server: reinicio");
        // Una sola visita a los ajustes: parar (sin cerrar), esperar y volver a iniciar.
        // La capa se mantiene durante los dos pasos.
        TouchService ts = TouchService.instance;
        if (ts != null) ts.acquireCover(coverText(true));
        try {
            runAndWait(ctx, false, false);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            return startAndWait(ctx);
        } finally {
            if (ts != null) ts.releaseCover(1200);
        }
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
        AaServerStarter s = new AaServerStarter(true, true);
        s.checkOnly = true;
        s.main.post(() -> s.begin(ctx.getApplicationContext()));
        try {
            s.done.await(TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return devModeState(ctx);
    }

    /** Último resultado conocido del modo desarrollador de AA (ver [DEV_MODE]). */
    public static int devModeState(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(DEV_MODE, -1);
    }

    /** Abre los ajustes de Android Auto para que el usuario active el modo desarrollador. */
    public static void openAaSettings(Context ctx) {
        ctx.startActivity(new Intent().setClassName(AA_PKG, AA_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Llamado por TouchService al desbloquear. */
    static void runPendingStop(Context ctx) {
        if (!ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PENDING_STOP, false)) return;
        if (stopViaNotification(ctx)) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PENDING_STOP, false).apply();
        L.i("AA server: apagado pendiente tras desbloqueo");
        new Thread(() -> runAndWait(ctx, false), "aa-server-stop").start();
    }

    private static boolean runAndWait(Context ctx, boolean wantRunning) {
        return runAndWait(ctx, wantRunning, true);
    }

    private static boolean runAndWait(Context ctx, boolean wantRunning, boolean closeAfter) {
        String why = cannotRunReason(ctx);
        if (why != null) {
            L.w("AA server: no se puede automatizar: " + why);
            return false;
        }
        AaServerStarter s = new AaServerStarter(wantRunning, closeAfter);
        s.main.post(() -> s.begin(ctx.getApplicationContext()));
        try {
            s.done.await(TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return s.ok.get();
    }

    private void begin(Context ctx) {
        appCtx = ctx;
        TouchService cover = TouchService.instance;
        if (cover != null) cover.acquireCover(coverText(wantRunning));
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
