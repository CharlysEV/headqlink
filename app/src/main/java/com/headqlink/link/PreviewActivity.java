package com.headqlink.link;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.andrerinas.openheadunit.R;
import com.andrerinas.openheadunit.utils.ToastUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Vista previa del modo extendido, sin coche: el panel del coche (1920x882 a 200 ppp, el mismo que se proyecta) con el
 * trayecto de demostración en marcha, encajado en la pantalla del móvil en horizontal y con los toques llevados al
 * panel. Se abre desde Diagnóstico.
 *
 * Capturas con adb (PNG en Android/data/com.headqlink.app/files/preview/):
 *   adb shell am start -n com.headqlink.app/com.headqlink.link.PreviewActivity --es render all [--es suffix _despues]
 * render: all, coche (las cinco pestañas de Coche) o nombres separados por comas (PreviewShots).
 *
 * La actividad está exportada solo para quien tenga android.permission.DUMP (adb, no otras apps). Con la sesión del
 * coche en marcha no hace nada: el modo demostración no debe mezclarse con los datos reales.
 */
public class PreviewActivity extends Activity {
    static final String EXTRA_RENDER = "render";
    static final String EXTRA_SUFFIX = "suffix";
    private static final int W = 1920;
    private static final int H = 882;
    private static final int DPI = 200;
    /** Instante del trayecto con el que arranca la vista previa en vivo (saliendo a la autovía). */
    private static final double LIVE_START_SEC = 104;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextView status;
    private CarUi ui;
    private Surface surface;
    private ImageReader reader;
    private Image latest;
    private boolean rendering;
    private boolean interactive;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF06090D);
        status = new TextView(this);
        status.setTextColor(0xFFA3B3C2);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        status.setPadding(pad, pad, pad, pad);
        root.addView(status, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        immersive();
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (rendering) return;
        if (intent.getStringExtra(EXTRA_RENDER) != null) {
            stopAll();
            main.post(() -> handle(intent));
        }
    }

    private void handle(Intent i) {
        if (LinkState.running) {
            status.setText(Str.get(R.string.hql_preview_busy));
            return;
        }
        String render = i.getStringExtra(EXTRA_RENDER);
        if (render != null) {
            String suffix = i.getStringExtra(EXTRA_SUFFIX);
            startRender(render, suffix == null ? "" : suffix);
        } else {
            startInteractive();
        }
    }

    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    // ------------------------------------------------------------------ vista previa interactiva

    private void startInteractive() {
        if (!DemoMode.enable(true, LIVE_START_SEC)) {
            status.setText(Str.get(R.string.hql_preview_busy));
            return;
        }
        interactive = true;
        status.setVisibility(View.GONE);
        TextureView tv = new TextureView(this);
        root.addView(tv, 0, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fit(tv, r - l, bt - t));
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                st.setDefaultBufferSize(W, H);
                surface = new Surface(st);
                ui = new CarUi(getApplicationContext(), W, H, AaPassthroughSource.PANEL_W, DPI, visible -> {
                });
                ui.start(surface);
                main.postDelayed(() -> {
                    if (ui != null) ui.showForPreview("car-0");
                }, 300);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                st.setDefaultBufferSize(W, H);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                stopAll();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        tv.setOnTouchListener((v, e) -> {
            if (ui == null || v.getWidth() == 0) return true;
            int a;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    a = 1;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    a = 2;
                    break;
                case MotionEvent.ACTION_MOVE:
                    a = 3;
                    break;
                default:
                    return true;
            }
            ui.touch(a, e.getX() * W / v.getWidth(), e.getY() * H / v.getHeight());
            return true;
        });
        ToastUtils.showToast(this, Str.get(R.string.hql_preview_demo_toast), Toast.LENGTH_LONG, true);
    }

    /** El panel del coche, lo más grande posible con su proporción, centrado. */
    private static void fit(View v, int w, int h) {
        if (w <= 0 || h <= 0) return;
        float scale = Math.min(w / (float) W, h / (float) H);
        int vw = Math.round(W * scale);
        int vh = Math.round(H * scale);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (lp.width == vw && lp.height == vh) return;
        lp.width = vw;
        lp.height = vh;
        v.post(() -> v.setLayoutParams(lp));
    }

    // ------------------------------------------------------------------ capturas PNG

    private void startRender(String which, String suffix) {
        List<PreviewShots.Shot> shots = PreviewShots.select(which);
        if (shots.isEmpty() || !DemoMode.enable(false, 0)) {
            status.setText(Str.get(R.string.hql_preview_busy));
            return;
        }
        rendering = true;
        status.setText(Str.get(R.string.hql_preview_rendering, 0, shots.size()));
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
            if (latest != null) latest.close();
            latest = img;
        }, main);
        ui = new CarUi(getApplicationContext(), W, H, AaPassthroughSource.PANEL_W, DPI, visible -> {
        });
        ui.start(reader.getSurface());
        L.i("vista previa: " + shots.size() + " capturas" + (suffix.isEmpty() ? "" : " (" + suffix + ")"));
        main.postDelayed(() -> shoot(shots, 0, suffix), 1200);
    }

    private void shoot(List<PreviewShots.Shot> shots, int i, String suffix) {
        if (ui == null) return;
        if (i >= shots.size()) {
            File dir = getExternalFilesDir("preview");
            status.setText(Str.get(R.string.hql_preview_done, shots.size(), dir != null ? dir.getAbsolutePath() : ""));
            L.i("vista previa: capturas terminadas");
            rendering = false;
            stopAll();
            return;
        }
        PreviewShots.Shot s = shots.get(i);
        DemoMode.applyState(s.state);
        DemoMode.seek(s.seekSec);
        ui.showForPreview(s.screen);
        status.setText(Str.get(R.string.hql_preview_rendering, i + 1, shots.size()));
        main.postDelayed(() -> {
            save(s.name + suffix);
            shoot(shots, i + 1, suffix);
        }, s.waitMs);
    }

    private void save(String name) {
        Image img = latest;
        if (img == null) {
            L.w("vista previa: sin imagen para " + name);
            return;
        }
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int rowPx = p.getRowStride() / p.getPixelStride();
        Bitmap full = Bitmap.createBitmap(rowPx, H, Bitmap.Config.ARGB_8888);
        buf.rewind();
        full.copyPixelsFromBuffer(buf);
        Bitmap crop = Bitmap.createBitmap(full, 0, 0, W, H);
        File dir = getExternalFilesDir("preview");
        if (dir == null) return;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, name + ".png");
        try (FileOutputStream o = new FileOutputStream(f)) {
            crop.compress(Bitmap.CompressFormat.PNG, 100, o);
            L.i("vista previa: " + f.getName());
        } catch (Exception e) {
            L.e("vista previa: no se pudo guardar " + name, e);
        }
        full.recycle();
        crop.recycle();
    }

    // ------------------------------------------------------------------ fin

    /** Para la interfaz y, después (en la misma cola), el modo demostración: no queda nada de él. */
    private void stopAll() {
        CarUi u = ui;
        ui = null;
        if (u != null) u.stop();
        Surface s = surface;
        surface = null;
        ImageReader r = reader;
        reader = null;
        main.post(() -> {
            DemoMode.disable();
            if (s != null) s.release();
            if (latest != null) {
                latest.close();
                latest = null;
            }
            if (r != null) r.close();
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // La vista previa en vivo no sigue en segundo plano; las capturas sí terminan (no dependen de la ventana).
        if (interactive && !isChangingConfigurations()) {
            stopAll();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (!rendering) stopAll();
        super.onDestroy();
    }
}
