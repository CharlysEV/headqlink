package com.headqlink.link;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;

import com.andrerinas.openheadunit.R;

/**
 * Modo "app específica": crea un VirtualDisplay propio que pinta directamente en la Surface del encoder
 * y lanza en él la app elegida. Los toques del coche se inyectan con TouchService (accesibilidad).
 *
 * Sin verificar todavía en el coche: que Android permita lanzar apps de terceros en un display
 * no confiable y que el display siga dibujando con la pantalla apagada. Todo se registra en el log.
 */
final class AppSource implements VideoSource {
    private static final int FLAG_PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private static final int FLAG_OWN_CONTENT = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
    private static final int FLAG_PRESENTATION = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;

    private final Context ctx;
    private final String pkg;
    private final int dpi;
    private final Handler main = new Handler(Looper.getMainLooper());
    private VirtualDisplay vd;
    private int displayId = Display.INVALID_DISPLAY;

    AppSource(Context ctx, String pkg, int dpi) {
        this.ctx = ctx.getApplicationContext();
        this.pkg = pkg;
        this.dpi = dpi;
    }

    @Override
    public void start(Surface surface, int width, int height, int fps, String info) {
        DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        int[] attempts = {FLAG_PUBLIC | FLAG_OWN_CONTENT, FLAG_PRESENTATION | FLAG_OWN_CONTENT};
        for (int flags : attempts) {
            try {
                vd = dm.createVirtualDisplay("HeadQLink", width, height, dpi, surface, flags);
                displayId = vd.getDisplay().getDisplayId();
                L.i("VirtualDisplay " + displayId + " " + width + "x" + height + " dpi=" + dpi + " flags=0x"
                        + Integer.toHexString(flags) + " estado=" + vd.getDisplay().getState());
                break;
            } catch (SecurityException e) {
                L.w("VirtualDisplay flags=0x" + Integer.toHexString(flags) + " rechazado: " + e.getMessage());
            }
        }
        if (vd == null) {
            L.w("no se pudo crear ningún VirtualDisplay");
            return;
        }
        main.post(this::launch);
    }

    private void launch() {
        if (pkg == null || pkg.isEmpty()) {
            L.w("modo app sin app elegida");
            return;
        }
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            L.w("la app " + pkg + " no tiene actividad lanzable");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        ActivityOptions o = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
        try {
            ctx.startActivity(i, o.toBundle());
            L.i("lanzada " + pkg + " en display " + displayId);
            LinkState.setSource(LinkState.Level.OK, Str.get(R.string.hql_app_opened_in_car));
        } catch (SecurityException e) {
            L.e("Android no permite lanzar " + pkg + " en el display " + displayId, e);
            LinkState.setSource(LinkState.Level.ERROR, Str.get(R.string.hql_app_open_blocked));
        }
    }

    @Override
    public void touch(int action, float x, float y) {
        TouchService ts = TouchService.instance;
        if (ts == null) {
            L.w("toque ignorado: activa el servicio de accesibilidad de HeadQLink");
            return;
        }
        ts.inject(displayId, action, x, y);
    }

    @Override
    public void setStatus(String status) {
    }

    @Override
    public String takeJitterSummary() {
        return null;
    }

    @Override
    public void stop() {
        if (vd != null) {
            vd.release();
            vd = null;
        }
    }
}
