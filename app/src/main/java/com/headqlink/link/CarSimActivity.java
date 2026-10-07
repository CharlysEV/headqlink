package com.headqlink.link;

import android.app.Activity;
import android.graphics.SurfaceTexture;
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
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.andrerinas.openheadunit.R;
import com.andrerinas.openheadunit.utils.ToastUtils;

/**
 * Coche virtual: la pantalla del coche entera en el móvil, sin coche. El mismo motor que con el coche
 * (AaPassthroughSource en modo ampliado: Android Auto de verdad por Self-Mode, nuestro panel y nuestras pantallas,
 * mezclados por GlFrameRelay), pero en lugar del encoder que va al coche pinta en la pantalla del móvil, y los toques
 * del móvil van como los del coche (multitáctil incluido). Con GPS y rutas reales (RoutePlanner en modo prueba: se
 * puede elegir la salida y el %), sin guardar viajes. Atrás lo cierra todo (y Android Auto).
 *
 * Si algo se pone delante (los ajustes de AA al arrancar su servidor), no se corta: deja de pintar hasta volver (la
 * SurfaceTexture de la vista sigue viva mientras la vista no se quite de la ventana).
 */
public class CarSimActivity extends Activity {
    private static final int W = 1920;
    private static final int H = 882;
    private static final int FPS = 30;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextView status;
    private TextureView view;
    private AaPassthroughSource source;
    private Surface surface;
    private volatile boolean visible;
    private boolean serverReady;
    private boolean closing;
    /** Si el coche de verdad se conecta, el virtual se aparta. */
    private final Runnable watchLink = new Runnable() {
        @Override
        public void run() {
            if (LinkState.running && source != null) {
                L.i("coche virtual: se conecta el coche de verdad; me cierro");
                close();
                return;
            }
            main.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF06090D);
        status = new TextView(this);
        status.setTextColor(0xFFA3B3C2);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        immersive();
        if (LinkState.running) {
            status.setText(Str.get(R.string.hql_preview_busy));
            return;
        }
        RoutePlanner.setTestMode(true);
        status.setText(Str.get(R.string.hql_sim_starting));
        L.i("coche virtual: arranco (Android Auto y la interfaz del coche en el móvil)");
        // Primero el servidor de AA (como el enlace con el coche: la automatización pasa por sus ajustes y vuelve);
        // después la pantalla.
        new Thread(() -> {
            boolean ok = AaServerStarter.manual(this) || AaServerStarter.startAndWait(this);
            L.i("coche virtual: servidor de Android Auto " + (ok ? "listo" : "no confirmado (se intenta igualmente)"));
            main.post(() -> {
                serverReady = true;
                if (!closing) startScreen();
            });
        }, "carsim-aa").start();
        main.postDelayed(watchLink, 2000);
    }

    /** La pantalla del coche: la vista del tamaño del coche, encajada, y el motor pintando en ella. */
    private void startScreen() {
        if (view != null) return;
        view = new TextureView(this);
        root.addView(view, 0, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fit(view, r - l, bt - t));
        fit(view, root.getWidth(), root.getHeight());
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                st.setDefaultBufferSize(W, H);
                if (source != null) return;
                surface = new Surface(st);
                startSource();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                st.setDefaultBufferSize(W, H);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                // Se queda viva: el motor sigue con ella (sin pintar mientras no se vea) hasta cerrar.
                return closing;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        view.setOnTouchListener(this::onTouch);
        status.setVisibility(View.GONE);
        ToastUtils.showToast(this, Str.get(R.string.hql_sim_toast), Toast.LENGTH_LONG, true);
    }

    private void startSource() {
        AaPassthroughSource s = new AaPassthroughSource(getApplicationContext(), new Config(this).aaDpi(), true, true);
        s.setCarSize(W, H);
        s.setVideoSize(W, H);
        s.setTargetFps(FPS);
        // Solo se pinta mientras se ve (detrás de otra app la vista no consume y el motor se quedaría esperando).
        s.setLinkGate(new GlFrameRelay.Gate() {
            @Override
            public boolean ready() {
                return visible;
            }

            @Override
            public void submitted() {
            }
        });
        source = s;
        s.start(surface, W, H, FPS, "coche virtual en el móvil");
    }

    // ------------------------------------------------------------------ toques

    /** Los dedos del móvil, como los del coche (1 down, 2 up, 3 move en píxeles de su pantalla). */
    private boolean onTouch(View v, MotionEvent e) {
        AaPassthroughSource s = source;
        if (s == null || v.getWidth() == 0) return true;
        int am = e.getActionMasked();
        int changed = e.getActionIndex();
        int n = Math.min(e.getPointerCount(), 3);
        Proto.Finger[] fingers = new Proto.Finger[n];
        for (int i = 0; i < n; i++) {
            Proto.Finger f = new Proto.Finger();
            f.id = e.getPointerId(i);
            f.x = Math.max(0, Math.min(W - 1, e.getX(i) * W / v.getWidth()));
            f.y = Math.max(0, Math.min(H - 1, e.getY(i) * H / v.getHeight()));
            f.action = 3;
            if (i == changed) {
                if (am == MotionEvent.ACTION_DOWN || am == MotionEvent.ACTION_POINTER_DOWN) f.action = 1;
                else if (am == MotionEvent.ACTION_UP || am == MotionEvent.ACTION_POINTER_UP || am == MotionEvent.ACTION_CANCEL) f.action = 2;
            }
            fingers[i] = f;
        }
        if (am == MotionEvent.ACTION_CANCEL) for (Proto.Finger f : fingers) f.action = 2;
        s.touchMulti(e.getAction(), fingers);
        return true;
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        immersive();
        if (source != null) source.redraw();
    }

    @Override
    protected void onStop() {
        visible = false;
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        close();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(watchLink);
        if (!closing) shutdown();
        super.onDestroy();
    }

    private void close() {
        if (closing) return;
        shutdown();
        finish();
    }

    /** Fuera todo: el motor, Android Auto (y su servidor, si se puede) y el modo prueba de la Ruta. */
    private void shutdown() {
        closing = true;
        main.removeCallbacks(watchLink);
        AaPassthroughSource s = source;
        source = null;
        if (s != null) {
            s.stop();
            AaClose.stopAa(this, "coche virtual cerrado");
            AaServerStarter.stopIfUnlocked(this);
        }
        if (surface != null) surface.release();
        surface = null;
        RoutePlanner.setTestMode(false);
        L.i("coche virtual: cerrado");
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

    /** La pantalla del coche, lo más grande posible con su proporción, centrada. */
    private static void fit(View v, int w, int h) {
        if (v == null || w <= 0 || h <= 0) return;
        float scale = Math.min(w / (float) W, h / (float) H);
        int vw = Math.round(W * scale);
        int vh = Math.round(H * scale);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (lp.width == vw && lp.height == vh) return;
        lp.width = vw;
        lp.height = vh;
        v.post(() -> v.setLayoutParams(lp));
    }
}
