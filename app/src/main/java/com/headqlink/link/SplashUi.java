package com.headqlink.link;

import android.app.Presentation;
import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

/**
 * Animación de carga en el modo AA sin panel ("último frame"): se pinta en la capa de GlFrameRelay
 * (en modo setSplashOnly) hasta el primer frame de AA, y entonces se cierra.
 */
final class SplashUi {
    private final Context ctx;
    private final int width;
    private final int height;
    private final int dpi;
    private final Handler main = new Handler(Looper.getMainLooper());
    private VirtualDisplay vd;
    private Presentation pres;

    SplashUi(Context ctx, int width, int height, int dpi) {
        this.ctx = ctx.getApplicationContext();
        this.width = width;
        this.height = height;
        this.dpi = dpi;
    }

    void start(Surface surface) {
        main.post(() -> {
            vd = CarUi.createDisplay(ctx, "HeadQLink-splash", width, height, dpi, surface, 30);
            pres = new Presentation(ctx, vd.getDisplay(), android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {
                @Override
                protected void onCreate(Bundle b) {
                    super.onCreate(b);
                    setContentView(new SplashView(getContext(), new Config(getContext()).panelColor()));
                }
            };
            try {
                pres.show();
            } catch (RuntimeException e) {
                L.e("splash: no se pudo mostrar", e);
            }
        });
    }

    void stop() {
        main.post(() -> {
            if (pres != null) pres.dismiss();
            if (vd != null) vd.release();
            pres = null;
            vd = null;
        });
    }
}
