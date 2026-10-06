package com.headqlink.link;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import com.andrerinas.openheadunit.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Capturas del widget sin coche: cada estado a 4x2 y a 2x2, con las mismas vistas que el widget de verdad
 * (LinkWidgetViews → RemoteViews.apply), sobre un fondo de pantalla de inicio. PNG «widget_&lt;estado&gt;_&lt;tamaño&gt;».
 * Las usan PreviewActivity (en el móvil, con adb: --es render widget) y la prueba que dibuja en el PC
 * (WidgetRenderTest, -Ppreview).
 */
final class WidgetShots {
    static final class Shot {
        final String name;
        final String state;
        final LinkGlance.Size size;
        final int widthDp;
        final int heightDp;

        Shot(String state, LinkGlance.Size size, String sizeName, int widthDp, int heightDp) {
            this.name = "widget_" + state + "_" + sizeName;
            this.state = state;
            this.size = size;
            this.widthDp = widthDp;
            this.heightDp = heightDp;
        }
    }

    /** Estados: los cuatro principales y, para ver los colores ámbar y rojo, la espera y un problema. */
    static final String[] STATES = {"apagado", "buscando", "conectado_wifi", "conectado_usb", "esperando", "problema"};
    /** Un 4x2 y un 2x2 de un móvil de unos 6,5" con la rejilla de 4 columnas (dp). */
    static final int WIDE_W = 340;
    static final int WIDE_H = 180;
    static final int COMPACT_W = 165;
    static final int COMPACT_H = 180;
    /** Margen con el «fondo de pantalla» alrededor del widget (dp). */
    private static final int MARGIN = 16;

    private WidgetShots() {
    }

    static List<Shot> all() {
        List<Shot> out = new ArrayList<>();
        for (String s : STATES) out.add(new Shot(s, LinkGlance.Size.WIDE, "4x2", WIDE_W, WIDE_H));
        for (String s : STATES) out.add(new Shot(s, LinkGlance.Size.COMPACT, "2x2", COMPACT_W, COMPACT_H));
        return out;
    }

    /** ¿Pide capturas del widget? («widget», o nombres que empiezan por «widget_»). */
    static boolean wanted(String which) {
        return which != null && which.trim().startsWith("widget");
    }

    /** «widget» (o «widgets»): todas; si no, los nombres separados por comas. */
    static List<Shot> select(String which) {
        String w = which == null ? "" : which.trim();
        if (w.equals("widget") || w.equals("widgets")) return all();
        List<Shot> out = new ArrayList<>();
        for (Shot s : all()) {
            for (String part : w.split(",")) {
                if (part.trim().equals(s.name)) out.add(s);
            }
        }
        return out;
    }

    /** La foto de un estado de demostración, con los textos de la red del idioma de la app. */
    static LinkGlance glance(Context c, String state) {
        LinkGlance.Input in = new LinkGlance.Input();
        Locale loc = androidx.core.os.ConfigurationCompat.getLocales(c.getResources().getConfiguration()).get(0);
        if (loc == null) loc = Locale.getDefault();
        in.locale = loc.toLanguageTag();
        in.mode = Config.MODE_AA;
        in.linkMode = Config.LINK_HOTSPOT;
        // Como lo publica el vídeo en LinkState (formato del idioma del móvil).
        String video = String.format(loc, "%.0f fps · %.1f Mbps", 30f, 4.8f);
        String hotspotOn = c.getString(R.string.hql_hotspot_on, "swlan0 10.42.0.1");
        switch (state) {
            case "buscando":
                running(in, Config.LINK_HOTSPOT);
                in.car = LinkState.Car.SEARCHING;
                in.networkLevel = LinkState.Level.OK;
                in.network = hotspotOn;
                break;
            case "conectado_wifi":
                running(in, Config.LINK_HOTSPOT);
                in.car = LinkState.Car.CONNECTED;
                in.video = video;
                in.networkLevel = LinkState.Level.OK;
                in.network = hotspotOn;
                break;
            case "conectado_usb":
                in.linkMode = Config.LINK_USB;
                running(in, Config.LINK_USB);
                in.car = LinkState.Car.CONNECTED;
                in.video = video;
                in.networkLevel = LinkState.Level.OK;
                in.network = c.getString(R.string.hql_usb_net_connected, "Neusoft QDriveLink");
                break;
            case "esperando":
                running(in, Config.LINK_HOTSPOT);
                in.car = LinkState.Car.RECONNECTING;
                in.aaParked = true;
                in.networkLevel = LinkState.Level.OK;
                in.network = hotspotOn;
                break;
            case "problema":
                running(in, Config.LINK_HOTSPOT);
                in.car = LinkState.Car.SEARCHING;
                in.udpBusy = true;
                in.networkLevel = LinkState.Level.ERROR;
                in.network = c.getString(R.string.hql_udp_busy);
                break;
            default:
                // apagado
                break;
        }
        return LinkGlance.of(in);
    }

    private static void running(LinkGlance.Input in, String link) {
        in.running = true;
        in.activeLinkMode = link;
    }

    /** Dibuja una captura a la densidad de ctx: el widget aplicado como lo haría el launcher, con su margen. */
    static Bitmap render(Context ctx, Shot s) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int w = Math.round(s.widthDp * d);
        int h = Math.round(s.heightDp * d);
        int m = Math.round(MARGIN * d);
        RemoteViews rv = LinkWidgetViews.build(ctx, glance(ctx, s.state), s.size);
        FrameLayout parent = new FrameLayout(ctx);
        View v = rv.apply(ctx, parent);
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w + 2 * m, h + 2 * m, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint wallpaper = new Paint();
        wallpaper.setShader(new LinearGradient(0, 0, 0, b.getHeight(), 0xFF2A3644, 0xFF0E1319, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, b.getWidth(), b.getHeight(), wallpaper);
        c.translate(m, m);
        v.draw(c);
        return b;
    }

    /**
     * La imagen de la vista previa (res/drawable-*nodpi/hql_widget_preview.png, que enseña también hql_widget_preview.xml):
     * el widget de verdad, apagado, a 4x2 y a la densidad de ctx, sin fondo de pantalla ni margen (fuera de sus esquinas,
     * transparente). La pinta WidgetRenderTest con -Ppreview.
     */
    static Bitmap renderPreview(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int w = Math.round(WIDE_W * d);
        int h = Math.round(WIDE_H * d);
        RemoteViews rv = LinkWidgetViews.build(ctx, glance(ctx, "apagado"), LinkGlance.Size.WIDE);
        View v = rv.apply(ctx, new FrameLayout(ctx));
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        return b;
    }

    static void save(Bitmap b, File f) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }
}
