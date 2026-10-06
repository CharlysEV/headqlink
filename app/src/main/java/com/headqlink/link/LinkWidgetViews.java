package com.headqlink.link;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.andrerinas.openheadunit.R;

import java.util.HashMap;
import java.util.Map;

/**
 * Las vistas del widget (RemoteViews) para una foto del estado ({@link LinkGlance}): textos en el idioma de la app (el
 * launcher pintaría los del XML con el idioma del sistema), colores «Eléctrico» y qué hace cada toque:
 * <ul>
 *   <li>botón grande: QuickToggleActivity (Conectar con la comprobación, o Desconectar), un PendingIntent de actividad:
 *       así Android 12+ deja arrancar el servicio en primer plano;</li>
 *   <li>marca: abre la app sin conectar sola;</li>
 *   <li>conexión y modo: LinkWidget (difusión propia, no exportado).</li>
 * </ul>
 * Todos los PendingIntent son inmutables (FLAG_IMMUTABLE) y explícitos. Lo usan el widget, el botón de los ajustes
 * rápidos (los textos) y la vista previa (PreviewActivity y la prueba que dibuja en el PC).
 */
final class LinkWidgetViews {
    private static final int RC_POWER = 100;
    private static final int RC_OPEN = 101;
    private static final int RC_LINK = 110;
    private static final int RC_CYCLE = 113;
    private static final int RC_MODE = 120;
    private static final String[] LINKS = {Config.LINK_HOTSPOT, Config.LINK_P2P, Config.LINK_USB};
    private static final int[] LINK_IDS = {R.id.hql_w_link_hotspot, R.id.hql_w_link_p2p, R.id.hql_w_link_usb};

    private LinkWidgetViews() {
    }

    /** Las vistas de un tamaño. */
    static RemoteViews build(Context c, LinkGlance g, LinkGlance.Size size) {
        boolean compact = size.compact();
        boolean tall = size == LinkGlance.Size.WIDE || size == LinkGlance.Size.COMPACT;
        RemoteViews rv = new RemoteViews(c.getPackageName(), compact ? R.layout.hql_widget_compact : R.layout.hql_widget_wide);
        String title = title(c, g);
        String detail = detail(c, g);
        String action = actionText(c, g);

        rv.setImageViewResource(R.id.hql_w_power_icon, powerIcon(g.look));
        rv.setOnClickPendingIntent(R.id.hql_w_power, togglePending(c));
        rv.setContentDescription(R.id.hql_w_power, action + ". " + title + (detail.isEmpty() ? "" : ". " + detail));
        rv.setTextViewText(R.id.hql_w_title, title);
        rv.setTextColor(R.id.hql_w_title, ContextCompat.getColor(c, lookColor(g.look)));
        rv.setTextViewText(R.id.hql_w_detail, detail);
        // Bajo: el título en una línea y, en el 2x2, sin detalle (si no, no cabe el botón).
        rv.setInt(R.id.hql_w_title, "setMaxLines", tall ? 2 : 1);
        rv.setInt(R.id.hql_w_detail, "setMaxLines", tall ? 2 : 1);
        rv.setViewVisibility(R.id.hql_w_detail,
                detail.isEmpty() || size == LinkGlance.Size.COMPACT_SHORT ? View.GONE : View.VISIBLE);

        if (compact) {
            // 2x2: el icono de la conexión elegida; al tocarlo, la siguiente.
            rv.setImageViewResource(R.id.hql_w_link_cycle, linkIcon(g.linkMode));
            rv.setContentDescription(R.id.hql_w_link_cycle, c.getString(R.string.hql_w_cd_link, linkName(c, g.linkMode)));
            rv.setOnClickPendingIntent(R.id.hql_w_link_cycle, broadcast(c, RC_CYCLE, LinkWidget.ACTION_CYCLE, null));
            return rv;
        }

        rv.setTextViewText(R.id.hql_w_action, action);
        rv.setTextColor(R.id.hql_w_action, ContextCompat.getColor(c, actionColor(g)));
        rv.setViewVisibility(R.id.hql_w_action, tall ? View.VISIBLE : View.GONE);

        // Cabecera (4x2 con altura): la marca abre la app; el modo, Auto o Auto extendido.
        rv.setViewVisibility(R.id.hql_w_header, tall ? View.VISIBLE : View.GONE);
        rv.setOnClickPendingIntent(R.id.hql_w_brand, openPending(c));
        rv.setContentDescription(R.id.hql_w_brand, c.getString(R.string.hql_w_cd_open));
        option(c, rv, R.id.hql_w_mode_aa, c.getString(R.string.hql_mode_aa), c.getString(R.string.hql_mode_aa),
                Config.MODE_AA.equals(g.mode), broadcast(c, RC_MODE, LinkWidget.ACTION_MODE, Config.MODE_AA));
        option(c, rv, R.id.hql_w_mode_ext, c.getString(R.string.hql_w_mode_ext), c.getString(R.string.hql_mode_aa_ext),
                Config.MODE_AA_EXT.equals(g.mode), broadcast(c, RC_MODE + 1, LinkWidget.ACTION_MODE, Config.MODE_AA_EXT));

        // Selector de conexión: la elegida, marcada.
        for (int i = 0; i < LINKS.length; i++) {
            String name = linkName(c, LINKS[i]);
            option(c, rv, LINK_IDS[i], name, name, LINKS[i].equals(g.linkMode),
                    broadcast(c, RC_LINK + i, LinkWidget.ACTION_LINK, LINKS[i]));
        }
        return rv;
    }

    /**
     * Android 12+: las cuatro versiones juntas; el launcher elige la más grande que cabe (2x2 desde 110×110 dp y con el
     * detalle desde 110×{@link LinkGlance#TALL_MIN_DP}; 4x2 desde {@link LinkGlance#WIDE_MIN_DP}×110 y con la cabecera
     * desde {@link LinkGlance#WIDE_MIN_DP}×{@link LinkGlance#TALL_MIN_DP}). Los mismos umbrales que LinkGlance.sizeFor.
     */
    @RequiresApi(31)
    static RemoteViews responsive(Context c, LinkGlance g) {
        Map<SizeF, RemoteViews> m = new HashMap<>();
        m.put(new SizeF(110f, 110f), build(c, g, LinkGlance.Size.COMPACT_SHORT));
        m.put(new SizeF(110f, LinkGlance.TALL_MIN_DP), build(c, g, LinkGlance.Size.COMPACT));
        m.put(new SizeF(LinkGlance.WIDE_MIN_DP, 110f), build(c, g, LinkGlance.Size.WIDE_SHORT));
        m.put(new SizeF(LinkGlance.WIDE_MIN_DP, LinkGlance.TALL_MIN_DP), build(c, g, LinkGlance.Size.WIDE));
        return new RemoteViews(m);
    }

    /** Antes de Android 12: la versión que cabe en el tamaño de este widget (en vertical: ancho mínimo, alto máximo). */
    static RemoteViews forOptions(Context c, LinkGlance g, Bundle options) {
        int w = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) : 0;
        int h = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) : 0;
        LinkGlance.Size size = w <= 0 ? LinkGlance.Size.WIDE : LinkGlance.sizeFor(w, h > 0 ? h : LinkGlance.TALL_MIN_DP);
        return build(c, g, size);
    }

    // ---------------------------------------------------------------- textos

    /** El título: el estado. */
    static String title(Context c, LinkGlance g) {
        switch (g.status) {
            case OFF:
                return c.getString(R.string.hql_w_off);
            case PREPARING:
                return c.getString(R.string.hql_preparing);
            case SEARCHING:
                return c.getString(R.string.hql_w_searching);
            case FOUND:
                return c.getString(R.string.hql_w_found);
            case CONNECTED:
            case LIVE:
                return c.getString(R.string.hql_car_connected);
            case WAITING_RETURN:
                return c.getString(R.string.hql_w_waiting_return);
            case AA_SERVER:
                return c.getString(R.string.hql_w_aa_server);
            case PORT_BUSY:
                return c.getString(R.string.hql_w_close_qdlink);
            case NETWORK_PROBLEM:
                return c.getString(R.string.hql_w_check_link);
            default:
                return c.getString(Config.MODE_APP.equals(g.mode) ? R.string.hql_w_check_app : R.string.hql_w_check_aa);
        }
    }

    /** Lo que va debajo del título ("" si nada). */
    static String detail(Context c, LinkGlance g) {
        switch (g.detail) {
            case TAP_TO_CONNECT:
                return c.getString(R.string.hql_w_tap_to_connect);
            case STARTING_AA:
                return c.getString(R.string.hql_starting_auto);
            case NETWORK:
            case SOURCE:
                return g.text;
            case VIDEO:
                return g.videoText();
            case WAITING_VIDEO:
                return c.getString(R.string.hql_w_waiting_video);
            case AA_PAUSED:
                return c.getString(R.string.hql_w_aa_paused);
            case PORT_BUSY:
                return c.getString(R.string.hql_w_port_busy);
            default:
                return "";
        }
    }

    /** «Coche conectado · 30 fps · 4,8 Mbit/s»: el estado en una línea (subtítulo de los ajustes rápidos, registro). */
    static String oneLine(Context c, LinkGlance g) {
        String d = detail(c, g);
        return d.isEmpty() || g.detail == LinkGlance.Detail.TAP_TO_CONNECT ? title(c, g) : title(c, g) + " · " + d;
    }

    /** Lo que hace el botón grande. */
    static String actionText(Context c, LinkGlance g) {
        if (g.status == LinkGlance.Status.PREPARING) return c.getString(R.string.hql_preparing);
        return c.getString(g.action == LinkGlance.Action.DISCONNECT ? R.string.hql_disconnect : R.string.hql_connect);
    }

    /** Nombre corto de la conexión: «Zona Wi-Fi», «Wi-Fi Direct», «Cable USB». */
    static String linkName(Context c, String linkMode) {
        if (Config.LINK_USB.equals(linkMode)) return c.getString(R.string.hql_w_link_usb);
        return c.getString(Config.LINK_HOTSPOT.equals(linkMode) ? R.string.hql_w_link_hotspot : R.string.hql_w_link_p2p);
    }

    // ---------------------------------------------------------------- piezas

    static int powerIcon(LinkGlance.Look look) {
        switch (look) {
            case LIVE:
                return R.drawable.hql_w_power_live;
            case BUSY:
                return R.drawable.hql_w_power_busy;
            case ERROR:
                return R.drawable.hql_w_power_error;
            default:
                return R.drawable.hql_w_power_off;
        }
    }

    static int linkIcon(String linkMode) {
        if (Config.LINK_USB.equals(linkMode)) return R.drawable.hql_w_link_usb;
        return Config.LINK_HOTSPOT.equals(linkMode) ? R.drawable.hql_w_link_hotspot : R.drawable.hql_w_link_p2p;
    }

    private static int lookColor(LinkGlance.Look look) {
        switch (look) {
            case LIVE:
                return R.color.hql_ok;
            case BUSY:
                return R.color.hql_warn;
            case ERROR:
                return R.color.hql_error;
            default:
                return R.color.hql_text;
        }
    }

    /** CONECTAR en cian (la invitación); Preparando… en ámbar; DESCONECTAR, discreto. */
    private static int actionColor(LinkGlance g) {
        if (g.status == LinkGlance.Status.PREPARING) return R.color.hql_warn;
        return g.action == LinkGlance.Action.CONNECT ? R.color.hql_accent : R.color.hql_text_dim;
    }

    /**
     * Una opción del selector (conexión o modo): la elegida, con fondo de acento; tocarla manda pi. name: el nombre
     * completo para la accesibilidad («Auto extendido» aunque la etiqueta diga «Extendido»).
     */
    private static void option(Context c, RemoteViews rv, int id, String label, String name, boolean selected,
                               PendingIntent pi) {
        rv.setTextViewText(id, label);
        rv.setTextColor(id, ContextCompat.getColor(c, selected ? R.color.hql_accent : R.color.hql_text_dim));
        rv.setInt(id, "setBackgroundResource", selected ? R.drawable.hql_w_seg_on : R.drawable.hql_w_seg_off);
        rv.setContentDescription(id, selected ? c.getString(R.string.hql_w_cd_selected, name) : name);
        rv.setOnClickPendingIntent(id, pi);
    }

    // ---------------------------------------------------------------- toques

    /** Botón grande (y botón de los ajustes rápidos): el puente invisible que hace lo del botón de la app. */
    static PendingIntent togglePending(Context c, String from, int requestCode) {
        Intent i = new Intent(c, QuickToggleActivity.class)
                .setAction(QuickToggleActivity.ACTION_TOGGLE)
                .putExtra(QuickToggleActivity.EXTRA_FROM, from)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return PendingIntent.getActivity(c, requestCode, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent togglePending(Context c) {
        return togglePending(c, "widget", RC_POWER);
    }

    /** La app, sin conectar sola (para eso está el botón grande). */
    static PendingIntent openPending(Context c) {
        Intent i = new Intent(c, HomeActivity.class)
                .putExtra(HomeActivity.EXTRA_NO_AUTOCONNECT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, RC_OPEN, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent broadcast(Context c, int requestCode, String action, String value) {
        Intent i = new Intent(c, LinkWidget.class).setAction(action);
        if (value != null) i.putExtra(LinkWidget.EXTRA_VALUE, value);
        return PendingIntent.getBroadcast(c, requestCode, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
