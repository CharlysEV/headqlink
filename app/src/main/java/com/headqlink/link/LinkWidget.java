package com.headqlink.link;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * El widget «HeadQLink» de la pantalla de inicio: el botón grande (Conectar / Desconectar, por QuickToggleActivity), el
 * estado, la conexión (zona Wi-Fi, Wi-Fi Direct o cable USB) y el modo (Auto / Auto extendido). Lo repinta
 * WidgetUpdater cuando cambia algo; Android, al añadirlo, al redimensionarlo y tras actualizar la app.
 *
 * No está exportado: Android (AppWidgetService, en el sistema) le entrega sus avisos igualmente, y los toques del
 * selector y del modo llegan con PendingIntent propios. Así ninguna otra app puede cambiar la conexión.
 */
public final class LinkWidget extends AppWidgetProvider {
    /** Elegir la conexión (EXTRA_VALUE: Config.LINK_*). */
    static final String ACTION_LINK = "com.headqlink.link.widget.LINK";
    /** Pasar a la conexión siguiente (icono de la versión 2x2). */
    static final String ACTION_CYCLE = "com.headqlink.link.widget.CYCLE";
    /** Elegir el modo (EXTRA_VALUE: Config.MODE_AA o MODE_AA_EXT). */
    static final String ACTION_MODE = "com.headqlink.link.widget.MODE";
    static final String EXTRA_VALUE = "value";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (ACTION_LINK.equals(a) || ACTION_CYCLE.equals(a) || ACTION_MODE.equals(a)) {
            L.init(c);
            Config cfg = new Config(c);
            if (ACTION_MODE.equals(a)) {
                setMode(c, cfg, i.getStringExtra(EXTRA_VALUE));
            } else {
                setLink(c, cfg, ACTION_CYCLE.equals(a) ? LinkGlance.nextLink(cfg.linkMode()) : i.getStringExtra(EXTRA_VALUE));
            }
            return;
        }
        super.onReceive(c, i);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        WidgetUpdater.onWidgetsChanged();
        WidgetUpdater.render(c, m, ids, WidgetUpdater.glance(c));
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        // Redimensionado: antes de Android 12 la versión depende del tamaño; después la elige el launcher.
        WidgetUpdater.render(c, m, new int[]{id}, WidgetUpdater.glance(c));
    }

    @Override
    public void onEnabled(Context c) {
        WidgetUpdater.onWidgetsChanged();
        L.init(c);
        L.i("widget: añadido a la pantalla de inicio");
    }

    @Override
    public void onDeleted(Context c, int[] ids) {
        WidgetUpdater.onWidgetsChanged();
    }

    @Override
    public void onDisabled(Context c) {
        WidgetUpdater.onWidgetsChanged();
        L.init(c);
        L.i("widget: quitado de la pantalla de inicio");
    }

    /**
     * Otra conexión: se guarda (vale para el próximo «Conectar») y, con el enlace en marcha, se aplica ya
     * (LinkService.ACTION_SET_LINK: cambia de conexión sin reiniciar Android Auto). El servicio está en primer plano, así
     * que startService vale desde aquí.
     */
    static void setLink(Context c, Config cfg, String value) {
        if (value == null) return;
        String target = Config.resolveLinkMode(value, true);
        String before = cfg.linkMode();
        if (!target.equals(before)) cfg.setLinkMode(target);
        boolean apply = LinkState.running && (!target.equals(before) || !target.equals(LinkState.activeLinkMode));
        L.i("widget: conexión " + Ui.linkTitle(before) + " → " + Ui.linkTitle(target)
                + (apply ? " (enlace en marcha: se aplica ya)" : LinkState.running ? "" : " (para el próximo Conectar)"));
        if (apply) {
            try {
                c.startService(new Intent(c, LinkService.class).setAction(LinkService.ACTION_SET_LINK)
                        .putExtra(LinkService.EXTRA_FROM, "widget"));
            } catch (RuntimeException e) {
                L.w("widget: no se pudo aplicar la conexión con el enlace en marcha: " + e);
            }
        }
        WidgetUpdater.poke();
    }

    /**
     * Otro modo (Auto / Auto extendido): se guarda y, con el enlace en marcha, se aplica como al guardar los ajustes de
     * imagen con otro perfil (ACTION_APPLY: sesión nueva y Android Auto reconectado con la geometría del modo).
     */
    static void setMode(Context c, Config cfg, String mode) {
        if (!Config.isAa(mode) || mode.equals(cfg.mode())) return;
        String before = cfg.mode();
        cfg.setMode(mode);
        L.i("widget: modo " + before + " → " + mode + (LinkState.running ? " (enlace en marcha: reconecto el vídeo y Android Auto)" : ""));
        if (LinkState.running) {
            try {
                c.startService(new Intent(c, LinkService.class).setAction(LinkService.ACTION_APPLY)
                        .putExtra(LinkService.EXTRA_AA_RENEGOTIATE, true));
            } catch (RuntimeException e) {
                L.w("widget: no se pudo aplicar el modo con el enlace en marcha: " + e);
            }
        }
        WidgetUpdater.poke();
    }
}
