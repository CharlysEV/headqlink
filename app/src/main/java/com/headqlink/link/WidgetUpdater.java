package com.headqlink.link;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.andrerinas.openheadunit.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Mantiene al día el widget con el estado del enlace, por avisos y sin sondeos ni candados: escucha {@link LinkState}
 * (arranque y parada, coche encontrado o perdido, sesión, «Preparando…», las cifras del vídeo cada 5 s) y los ajustes
 * de conexión y modo, se cambien donde se cambien. Agrupa los avisos seguidos (250 ms), no repinta si no cambia nada
 * de lo que se ve y, si solo cambian las cifras del vídeo, como mucho cada ~5 s ({@link LinkGlance#pushDelay}). Sin
 * widgets en la pantalla de inicio no hace nada.
 *
 * Se arranca desde App (tras el primer desbloqueo: lee los ajustes). El botón de los ajustes rápidos se pone al día por
 * su cuenta mientras se ve (LinkTileService).
 */
public final class WidgetUpdater implements LinkState.Listener, SharedPreferences.OnSharedPreferenceChangeListener {
    private static final long COALESCE_MS = 250;
    /** Lo último que se pintó (sin las cifras del vídeo): si el proceso murió en marcha, al volver se corrige. */
    private static final String PREFS = "hql_widget";
    private static final String KEY_STATE = "state";

    private static volatile WidgetUpdater instance;
    /** Los widgets puestos (null = hay que preguntar). Lo borran los avisos del widget (añadido, quitado…). */
    private static volatile int[] cachedIds;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable flush = this::flush;
    /** Hilo principal: cuándo toca el próximo repintado (uptime) o -1. */
    private long dueAt = -1;
    private LinkGlance last;
    private long lastMs;

    private WidgetUpdater(Context app) {
        this.app = app;
    }

    /** Desde App, con los ajustes ya legibles (tras el primer desbloqueo). Hilo principal. */
    public static void init(Context ctx) {
        if (instance != null) return;
        WidgetUpdater u = new WidgetUpdater(ctx.getApplicationContext());
        instance = u;
        LinkState.addListener(u);
        new Config(u.app).listen(u, true);
        // El proceso acaba de arrancar: si el widget se quedó con otro estado (el proceso murió en marcha), se corrige.
        u.main.postDelayed(u::syncAfterStart, 2_000);
    }

    /** Algo pudo cambiar fuera de LinkState (el idioma de la app, al volver a la pantalla principal). */
    static void poke() {
        WidgetUpdater u = instance;
        if (u != null) u.main.post(() -> u.schedule(COALESCE_MS));
    }

    /** El widget avisa de que se añadió, se quitó o se redimensionó: la lista de widgets se vuelve a preguntar. */
    static void onWidgetsChanged() {
        cachedIds = null;
    }

    @Override
    public void onLinkStateChanged() {
        schedule(COALESCE_MS);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (Config.MODE.equals(key) || Config.LINK_MODE.equals(key)) schedule(COALESCE_MS);
    }

    /** Hilo principal: repintar dentro de delayMs (si ya hay uno antes, ese vale). */
    private void schedule(long delayMs) {
        if (!hasWidgets()) return;
        long at = SystemClock.uptimeMillis() + Math.max(0, delayMs);
        if (dueAt >= 0 && dueAt <= at) return;
        main.removeCallbacks(flush);
        main.postAtTime(flush, at);
        dueAt = at;
    }

    private void flush() {
        dueAt = -1;
        if (!hasWidgets()) return;
        LinkGlance g = glance(app);
        long wait = LinkGlance.pushDelay(last, lastMs, g, SystemClock.uptimeMillis());
        if (wait < 0) return;
        if (wait > 0) {
            schedule(wait);
            return;
        }
        push(g);
    }

    private void push(LinkGlance g) {
        LinkGlance before = last;
        last = g;
        lastMs = SystemClock.uptimeMillis();
        render(app, AppWidgetManager.getInstance(app), ids(app), g);
        if (before == null || !before.sameButVideo(g)) {
            // Solo con los cambios de estado (no cada 5 s con las cifras del vídeo).
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATE, g.stateKey()).apply();
        }
    }

    private void syncAfterStart() {
        if (!hasWidgets()) return;
        LinkGlance g = glance(app);
        String painted = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, "");
        if (g.stateKey().equals(painted)) {
            last = g;
            lastMs = SystemClock.uptimeMillis();
            return;
        }
        L.i("widget: al arrancar el proceso estaba en otro estado; lo pongo al día (" + g.status + ")");
        push(g);
    }

    private boolean hasWidgets() {
        return ids(app).length > 0;
    }

    // ---------------------------------------------------------------- compartido con el widget y los ajustes rápidos

    /** Los widgets «HeadQLink» de la pantalla de inicio (vacío si no hay o si este móvil no tiene widgets). */
    static int[] ids(Context c) {
        int[] ids = cachedIds;
        if (ids != null) return ids;
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            ids = m == null ? new int[0] : m.getAppWidgetIds(new ComponentName(c, LinkWidget.class));
        } catch (RuntimeException e) {
            ids = new int[0];
        }
        if (ids == null) ids = new int[0];
        cachedIds = ids;
        return ids;
    }

    /** La foto del estado ahora: LinkState y los ajustes. */
    static LinkGlance glance(Context c) {
        Config cfg = new Config(c);
        LinkGlance.Input in = new LinkGlance.Input();
        in.running = LinkState.running;
        in.preparing = LinkState.preparing;
        in.car = LinkState.car;
        in.video = LinkState.video;
        in.networkLevel = LinkState.networkLevel;
        in.network = LinkState.network;
        in.sourceLevel = LinkState.sourceLevel;
        in.source = LinkState.source;
        in.udpBusy = LinkState.udpBusy;
        in.mode = cfg.mode();
        in.aaServerWaiting = Config.isAa(in.mode) && AaServerManual.isWaiting();
        in.aaParked = AaPark.parked;
        in.linkMode = cfg.linkMode();
        in.activeLinkMode = LinkState.activeLinkMode;
        in.usbOverride = LinkState.usbOverride;
        in.locale = locale(c);
        return LinkGlance.of(in);
    }

    /** Pinta estos widgets con la foto g (Android 12+: las tres versiones; antes, la que cabe en cada uno). */
    static void render(Context c, AppWidgetManager m, int[] ids, LinkGlance g) {
        if (m == null || ids == null || ids.length == 0) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                m.updateAppWidget(ids, LinkWidgetViews.responsive(c, g));
            } else {
                for (int id : ids) m.updateAppWidget(id, LinkWidgetViews.forOptions(c, g, m.getAppWidgetOptions(id)));
            }
        } catch (RuntimeException e) {
            L.w("widget: no se pudo actualizar: " + e);
        }
    }

    private static String locale(Context c) {
        try {
            java.util.Locale l = androidx.core.os.ConfigurationCompat.getLocales(c.getResources().getConfiguration()).get(0);
            return l != null ? l.toLanguageTag() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Menú del engranaje › «Añadir widget a la pantalla de inicio»: el launcher pregunta dónde ponerlo
     * (requestPinAppWidget). Si no lo admite, se explica cómo hacerlo a mano.
     */
    static void requestPin(Activity a) {
        boolean asked = false;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                AppWidgetManager m = AppWidgetManager.getInstance(a);
                if (m != null && m.isRequestPinAppWidgetSupported()) {
                    asked = m.requestPinAppWidget(new ComponentName(a, LinkWidget.class), null, null);
                }
            } catch (RuntimeException e) {
                L.w("widget: no se pudo pedir al launcher que lo añada: " + e);
            }
        }
        L.i("widget: «Añadir a la pantalla de inicio» " + (asked ? "pedido al launcher" : "no admitido por el launcher: explico cómo"));
        if (asked) return;
        new MaterialAlertDialogBuilder(a)
                .setTitle(Str.get(R.string.hql_w_pin_manual_title))
                .setMessage(Str.get(R.string.hql_w_pin_manual))
                .setPositiveButton(Str.get(R.string.hql_close), null)
                .show();
    }
}
