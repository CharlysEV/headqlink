package com.c10link.link;

import android.app.Presentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Interfaz propia en la pantalla del coche (modo "Android Auto ampliado"): un panel fijo a la
 * izquierda, del lado del conductor (botones y batería del móvil) y, a su derecha, Android Auto o
 * una pantalla nuestra (fotos, vídeos, web, TV, PDF, juegos). Se dibuja en un VirtualDisplay
 * propio que GlFrameRelay mezcla con la imagen de AA; los toques del coche se entregan
 * directamente a estas vistas. El color del panel se ajusta desde el móvil para fundirse con las
 * barras del coche.
 *
 * Pendiente (fases finales): bloquear el vídeo con el coche en marcha.
 */
final class CarUi {
    /** Qué se ve en la zona de contenido. */
    interface Listener {
        void onAaVisible(boolean visible);
    }

    /** Instancia en marcha, para ajustar el color del panel en vivo desde el móvil. */
    private static volatile CarUi current;

    private final Context ctx;
    private final int width;
    private final int height;
    private final int panelW;
    private final int dpi;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private VirtualDisplay vd;
    private Presentation pres;
    private FrameLayout content;
    private View panel;
    private int panelColor;
    private TextView battery;
    private final List<TextView> navButtons = new ArrayList<>();
    private volatile boolean aaShown = true;
    private CarScreen screen;
    private String screenName = "aa";
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            showBattery(i);
        }
    };

    // Gesto en curso (los toques del coche llegan como acciones sueltas).
    private long downTime;

    CarUi(Context ctx, int width, int height, int panelW, int dpi, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.width = width;
        this.height = height;
        this.panelW = panelW;
        this.dpi = dpi;
        this.listener = listener;
        this.panelColor = new Config(ctx).panelColor();
    }

    /** Color del panel en vivo (ajuste del móvil); no hace nada si el modo ampliado no está en marcha. */
    static void applyPanelColor(int color) {
        CarUi ui = current;
        if (ui != null) ui.main.post(() -> {
            ui.panelColor = color;
            if (ui.panel != null) ui.panel.setBackgroundColor(color);
            if (ui.content != null) ui.content.setBackgroundColor(color);
            ui.markNav();
        });
    }

    int contentWidth() {
        return width - panelW;
    }

    /** ¿Ocupa nuestra interfaz la zona de contenido (y por tanto recibe sus toques)? */
    boolean ownScreenActive() {
        return !aaShown;
    }

    void start(Surface surface) {
        main.post(() -> {
            DisplayManager dm = ctx.getSystemService(DisplayManager.class);
            vd = dm.createVirtualDisplay("C10Link-ui", width, height, dpi, surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            Display d = vd.getDisplay();
            pres = new Presentation(ctx, d, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {
                @Override
                protected void onCreate(Bundle b) {
                    super.onCreate(b);
                    setContentView(buildRoot(getContext()));
                }
            };
            try {
                pres.show();
                L.i("CarUi: interfaz propia en display " + d.getDisplayId() + " " + width + "x" + height + " panel " + panelW);
            } catch (RuntimeException e) {
                L.e("CarUi: no se pudo mostrar la interfaz propia", e);
                return;
            }
            ctx.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            current = this;
            open("aa");
        });
    }

    void stop() {
        main.post(() -> {
            if (current == this) current = null;
            try {
                ctx.unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            if (screen != null) screen.destroy();
            screen = null;
            if (pres != null) pres.dismiss();
            if (vd != null) vd.release();
            pres = null;
            vd = null;
        });
    }

    /** Toque del coche en píxeles de la pantalla (1 down, 2 up, 3 move). */
    void touch(int action, float x, float y) {
        main.post(() -> {
            if (pres == null) return;
            long now = SystemClock.uptimeMillis();
            int a;
            switch (action) {
                case 1:
                    downTime = now;
                    a = MotionEvent.ACTION_DOWN;
                    break;
                case 2:
                    a = MotionEvent.ACTION_UP;
                    break;
                default:
                    a = MotionEvent.ACTION_MOVE;
            }
            MotionEvent ev = MotionEvent.obtain(downTime, now, a, x, y, 0);
            ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            pres.dispatchTouchEvent(ev);
            ev.recycle();
        });
    }

    /** Solo para UiPreview: abre una pantalla por nombre (aa, photos, videos, web, tv, pdf, games, gauges, efficiency). */
    void showForPreview(String name) {
        main.post(() -> open(name));
    }

    // ------------------------------------------------------------------ vistas

    private View buildRoot(Context c) {
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(CarStyle.BG);
        panel = buildPanel(c);
        root.addView(panel, new FrameLayout.LayoutParams(panelW, height));
        content = new FrameLayout(c);
        content.setBackgroundColor(panelColor);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(contentWidth(), height);
        lp.leftMargin = panelW;
        root.addView(content, lp);
        return root;
    }

    private View buildPanel(Context c) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(panelColor);
        p.setPadding(20, 20, 20, 20);
        navButtons.clear();
        p.addView(navButton(c, "Android Auto", "aa"));
        p.addView(navButton(c, "Fotos", "photos"));
        p.addView(navButton(c, "Vídeos", "videos"));
        p.addView(navButton(c, "Web", "web"));
        p.addView(navButton(c, "TV", "tv"));
        p.addView(navButton(c, "PDF", "pdf"));
        p.addView(navButton(c, "Juegos", "games"));
        p.addView(navButton(c, "Instrumentos", "gauges"));
        p.addView(navButton(c, "Eficiencia", "efficiency"));
        View spacer = new View(c);
        p.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        battery = CarStyle.text(c, "", 22, CarStyle.TEXT_DIM);
        battery.setPadding(8, 0, 0, 6);
        p.addView(battery);
        return p;
    }

    private TextView navButton(Context c, String label, String target) {
        TextView b = CarStyle.text(c, label, 26, CarStyle.TEXT);
        b.setTag(target);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(20, 0, 20, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72);
        lp.bottomMargin = 8;
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> open(target));
        navButtons.add(b);
        return b;
    }

    private void markNav() {
        for (TextView b : navButtons) {
            boolean on = screenName.equals(b.getTag());
            b.setBackground(CarStyle.round(on ? CarStyle.ACCENT_BG : CarStyle.lighter(panelColor, 18), 14));
            b.setTextColor(on ? CarStyle.ACCENT : CarStyle.TEXT);
        }
    }

    private void open(String name) {
        if (screen != null) screen.destroy();
        screen = null;
        screenName = name;
        content.removeAllViews();
        Supplier<CarScreen> factory = factory(name);
        if (factory != null) {
            screen = factory.get();
            View v;
            try {
                v = screen.create(host);
            } catch (RuntimeException e) {
                L.e("CarUi: no se pudo abrir " + name, e);
                v = CarStyle.message(content.getContext(), "No se pudo abrir esta pantalla.");
            }
            content.addView(v, CarStyle.match());
        }
        aaShown = factory == null;
        markNav();
        listener.onAaVisible(aaShown);
        L.i("CarUi: pantalla " + name);
    }

    private static Supplier<CarScreen> factory(String name) {
        switch (name) {
            case "photos":
                return () -> new GalleryScreen(false);
            case "videos":
                return () -> new GalleryScreen(true);
            case "web":
                return WebScreen::new;
            case "tv":
                return IptvScreen::new;
            case "pdf":
                return PdfScreen::new;
            case "games":
                return GamesScreen::new;
            case "gauges":
                return InstrumentsScreen::new;
            case "efficiency":
                return EfficiencyScreen::new;
            default:
                return null;
        }
    }

    private final CarScreen.Host host = new CarScreen.Host() {
        @Override
        public Context context() {
            return content.getContext();
        }

        @Override
        public void setContent(View v) {
            content.removeAllViews();
            content.addView(v, CarStyle.match());
        }

        @Override
        public int width() {
            return contentWidth();
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public void post(Runnable r) {
            main.post(r);
        }
    };

    private void showBattery(Intent i) {
        if (battery == null || i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        if (level >= 0) {
            battery.setText(String.format(Locale.getDefault(), "Móvil %d %%%s", level * 100 / scale, charging ? " · cargando" : ""));
        }
    }
}
