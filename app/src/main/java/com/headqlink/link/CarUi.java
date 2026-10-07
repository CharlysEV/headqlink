package com.headqlink.link;

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
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Interfaz propia en la pantalla del coche (modo "Android Auto ampliado"): un panel fijo a la
 * izquierda, del lado del conductor (botones y batería del móvil) y, a su derecha, Android Auto o
 * una pantalla nuestra (fotos, vídeos, web, TV, juegos). Se dibuja en un VirtualDisplay
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

        /** Ancho del panel junto a AA (completo, o la pestaña si se ha ocultado). */
        default void onPanelWidth(int width) {
        }

        /** Color del panel (fondo de las esquinas redondeadas de AA). */
        default void onPanelColor(int color) {
        }
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
    private final List<LinearLayout> navButtons = new ArrayList<>();
    /** Ancho del panel reducido (solo iconos) mientras algo ocupa la pantalla grande. */
    private static final int COMPACT_W = 96;
    private boolean compact;
    /**
     * Panel minimizado (con AA en pantalla): queda la barra de iconos. No una pestaña en el borde: el
     * coche puede quedarse los toques de su borde izquierdo para su propio gesto. Se despliega con «»», tocando el icono de AA o deslizando hacia la derecha.
     */
    private static final long AUTO_HIDE_MS = 8000;
    private boolean autoHide;
    private boolean hidden;
    private View expand;
    private View radioMini;
    private ImageView radioLogo;
    private FrameLayout root;
    private float swipeX = -1;
    private final Runnable radioChanged = () -> main.post(this::renderRadioMini);
    private final Runnable hideTask = () -> setHidden(true);
    /** AA ya ha dado imagen; antes, en su zona se ve la animación de carga. */
    private boolean aaReady;
    private SplashView splash;
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
        Config cfg = new Config(ctx);
        this.panelColor = cfg.panelColor();
        this.autoHide = cfg.panelAutoHide();
    }

    /** Color del panel en vivo (ajuste del móvil); no hace nada si el modo ampliado no está en marcha. */
    static void applyPanelColor(int color) {
        CarUi ui = current;
        if (ui != null) ui.main.post(() -> {
            ui.panelColor = color;
            if (ui.panel != null) ui.panel.setBackgroundColor(color);
            if (ui.content != null) ui.content.setBackgroundColor(color);
            ui.listener.onPanelColor(color);
            ui.markNav();
        });
    }

    /** Primer frame de AA: fuera la animación de carga (AA ya la tapa; así deja de dibujarse). */
    void onAaReady() {
        main.post(() -> {
            aaReady = true;
            if (splash != null) {
                splash.stop();
                if (splash.getParent() == content) content.removeView(splash);
                splash = null;
            }
        });
    }

    /** Cambia a la pestaña de AA con el panel ya minimizado. */
    static void switchToAa() {
        CarUi ui = current;
        if (ui != null) ui.main.post(() -> {
            ui.open("aa");
            ui.setHidden(true);
        });
    }

    /** Ocultar el panel solo: en vivo desde Ajustes (coche o móvil). */
    static void applyAutoHide(boolean on) {
        CarUi ui = current;
        if (ui != null) ui.main.post(() -> {
            ui.autoHide = on;
            if (!on) ui.main.removeCallbacks(ui.hideTask);
        });
    }

    int contentWidth() {
        return width - currentPanelW();
    }

    /** Barra de iconos: con una pantalla propia a lo grande (compact) o con el panel minimizado (hidden). */
    private boolean rail() {
        return hidden || compact;
    }

    private int currentPanelW() {
        return rail() ? COMPACT_W : panelW;
    }

    /** Recoloca panel y contenido y pone el panel como barra de iconos o completo. */
    private void applyLayout() {
        int pw = currentPanelW();
        boolean rail = rail();
        panel.getLayoutParams().width = pw;
        panel.requestLayout();
        for (LinearLayout b : navButtons) {
            b.getChildAt(1).setVisibility(rail ? View.GONE : View.VISIBLE);
            b.setGravity(rail ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
            b.setPadding(rail ? 0 : 18, 0, rail ? 0 : 18, 0);
        }
        battery.setVisibility(rail ? View.GONE : View.VISIBLE);
        expand.setVisibility(hidden ? View.VISIBLE : View.GONE);
        panel.setPadding(rail ? 12 : 20, 20, rail ? 12 : 20, 20);
        renderRadioMini();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) content.getLayoutParams();
        lp.leftMargin = pw;
        lp.width = width - pw;
        content.setLayoutParams(lp);
        // Solo importa a AA cuando se ve (el modo compacto es de nuestras pantallas).
        if (!compact) listener.onPanelWidth(pw);
    }

    /**
     * Minimiza o despliega el panel con AA en pantalla: AA se recoloca sobre la marcha (márgenes
     * nuevos) para ocupar el hueco.
     */
    private void setHidden(boolean on) {
        if (panel == null || on == hidden || (on && !aaShown)) return;
        hidden = on;
        applyLayout();
        L.i("CarUi: panel " + (hidden ? "minimizado" : "desplegado"));
    }

    /** Programa el ocultado tras un rato sin tocar el panel (solo con AA en pantalla). */
    private void scheduleHide() {
        main.removeCallbacks(hideTask);
        if (autoHide && aaShown && !hidden) main.postDelayed(hideTask, AUTO_HIDE_MS);
    }

    /** Inicio de un gesto en el coche; toUi: va a nuestra interfaz (si no, a AA). */
    void noteTouch(boolean toUi) {
        if (toUi) main.post(this::scheduleHide);
    }

    /**
     * Pantalla grande (vídeo, TV): el panel se queda en una barra de iconos y el contenido ocupa el
     * resto. Solo con una pantalla propia: con AA visible el panel conserva su ancho (AA tiene sus
     * márgenes fijados para él).
     */
    private void setCompact(boolean on) {
        if (on == compact || panel == null) return;
        compact = on && !aaShown;
        applyLayout();
        L.i("CarUi: pantalla grande " + (compact ? "sí" : "no"));
    }

    /** ¿Ocupa nuestra interfaz la zona de contenido (y por tanto recibe sus toques)? */
    boolean ownScreenActive() {
        return !aaShown;
    }

    /** fps del vídeo al coche: la pantalla virtual se pide a ese ritmo (llamar antes de start). */
    private float refreshHz;

    void setRefreshRate(float hz) {
        refreshHz = hz;
    }

    /**
     * Pantalla virtual para nuestra interfaz. Android 14+: a la frecuencia del vídeo, para que las
     * animaciones (juegos, desplazamientos) no se dibujen al ritmo del móvil (hasta 120 Hz) y se
     * descarten la mitad de forma irregular.
     */
    static VirtualDisplay createDisplay(Context ctx, String name, int w, int h, int dpi, Surface surface, float hz) {
        DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        if (hz > 0 && android.os.Build.VERSION.SDK_INT >= 34) {
            try {
                android.hardware.display.VirtualDisplayConfig cfg = new android.hardware.display.VirtualDisplayConfig.Builder(name, w, h, dpi)
                        .setSurface(surface).setFlags(flags).setRequestedRefreshRate(hz).build();
                VirtualDisplay vd = dm.createVirtualDisplay(cfg, null, null);
                L.i("pantalla virtual " + name + " a " + hz + " Hz (pedido)");
                return vd;
            } catch (RuntimeException e) {
                L.w("pantalla virtual sin frecuencia propia: " + e.getMessage());
            }
        }
        return dm.createVirtualDisplay(name, w, h, dpi, surface, flags);
    }

    void start(Surface surface) {
        main.post(() -> {
            vd = createDisplay(ctx, "HeadQLink-ui", width, height, dpi, surface, refreshHz);
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
            RadioPlayer.addListener(radioChanged);
            current = this;
            // Sección Coche: viajes, ruta y datos de la vía, mientras dure el modo ampliado (y los datos reales del coche
            // de la cuenta de Leapmotor, si está configurada: CarCloud).
            CarCloud.start(ctx);
            TripLog.start(ctx);
            RoutePlanner.start(ctx);
            RoadInfo.start(ctx);
            open("aa");
            if (autoHide) setHidden(true);
        });
    }

    void stop() {
        main.post(() -> {
            if (current == this) current = null;
            main.removeCallbacks(hideTask);
            SspSession.setDrivingUi(true);
            try {
                ctx.unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            if (screen != null) screen.destroy();
            screen = null;
            RadioPlayer.removeListener(radioChanged);
            RadioPlayer.stop();
            TripLog.stop();
            CarCloud.stop();
            RoutePlanner.stop();
            RoadInfo.stop();
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
            // Panel minimizado: deslizar hacia la derecha desde la barra lo despliega.
            if (action == 1) swipeX = hidden && x < COMPACT_W ? x : -1;
            if (action == 3 && swipeX >= 0 && x - swipeX > 70) {
                swipeX = -1;
                setHidden(false);
                scheduleHide();
            }
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

    /**
     * Solo para las capturas en el PC (Robolectric): la interfaz en una vista normal, sin pantalla virtual ni
     * Presentation. Se cierra con stop().
     */
    View attachOffscreen(Context c) {
        View r = buildRoot(c);
        current = this;
        CarCloud.start(ctx);
        TripLog.start(ctx);
        RoutePlanner.start(ctx);
        RoadInfo.start(ctx);
        open("aa");
        return r;
    }

    /**
     * Solo para la vista previa (PreviewActivity) y las capturas: abre una pantalla por nombre (aa, car, car-N con la
     * pestaña N de Coche, photos, videos, web, tv, radio, games, settings, aa-rail, radio-mini, game-N).
     */
    void showForPreview(String name) {
        main.post(() -> {
            // "car-N": sección Coche en la pestaña N.
            if (name.equals("aa-rail")) {
                setHidden(true);
            } else if (name.equals("radio-mini")) {
                RadioPlayer.Station st = new RadioPlayer.Station();
                st.name = "Radio";
                st.url = "";
                st.logo = "";
                RadioPlayer.previewOnly(st);
                open("radio");
            } else if (name.startsWith("game-")) {
                GamesScreen.previewGame = Integer.parseInt(name.substring(5));
                open("games");
            } else if (name.startsWith("car-")) {
                // "car-N" o "car-N-detalle" (Viajes con un viaje abierto) o "car-N-buscar" (Ruta con el buscador).
                String[] parts = name.split("-", 3);
                CarHubScreen.selectTab(Integer.parseInt(parts[1]));
                if (parts.length > 2 && parts[2].equals("detalle")) TripsTab.previewDetail = true;
                if (parts.length > 2 && parts[2].equals("buscar")) RouteTab.previewQuery = "electrolinera";
                open("car");
            } else {
                open(name);
            }
        });
    }

    // ------------------------------------------------------------------ vistas

    private View buildRoot(Context c) {
        root = new FrameLayout(c);
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
        p.addView(navButton(c, Str.get(R.string.hql_mode_aa), "aa", R.drawable.hql_ic_aa));
        p.addView(navButton(c, Str.get(R.string.hql_car), "car", R.drawable.hql_ic_gauges));
        p.addView(navButton(c, Str.get(R.string.hql_photos), "photos", R.drawable.hql_ic_photos));
        p.addView(navButton(c, Str.get(R.string.hql_videos), "videos", R.drawable.hql_ic_videos));
        p.addView(navButton(c, "Web", "web", R.drawable.hql_ic_web));
        p.addView(navButton(c, "TV", "tv", R.drawable.hql_ic_tv));
        p.addView(navButton(c, Str.get(R.string.hql_radio), "radio", R.drawable.hql_ic_radio));
        p.addView(buildRadioMini(c));
        p.addView(navButton(c, Str.get(R.string.hql_games), "games", R.drawable.hql_ic_games));

        View spacer = new View(c);
        p.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        p.addView(navButton(c, Str.get(R.string.hql_settings), "settings", R.drawable.hql_ic_settings));
        // Desplegar el panel minimizado.
        ImageView ex = new ImageView(c);
        ex.setImageResource(R.drawable.hql_ic_expand);
        ex.setImageTintList(ColorStateList.valueOf(CarStyle.TEXT));
        ex.setBackground(CarStyle.round(CarStyle.PILL_BG, 36));
        ex.setPadding(16, 16, 16, 16);
        ex.setVisibility(View.GONE);
        ex.setOnClickListener(v -> {
            setHidden(false);
            scheduleHide();
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72);
        elp.topMargin = 6;
        p.addView(ex, elp);
        expand = ex;
        battery = CarStyle.text(c, "", 22, CarStyle.TEXT_DIM);
        battery.setPadding(8, 0, 0, 6);
        p.addView(battery);
        return p;
    }

    /** Elemento del panel al estilo de AA: icono + texto; el activo, en una píldora de color. */
    private LinearLayout navButton(Context c, String label, String target, int icon) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(18, 0, 18, 0);
        b.setTag(target);
        ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        b.addView(iv, new LinearLayout.LayoutParams(40, 40));
        TextView t = CarStyle.text(c, label, 25, CarStyle.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(20, 0, 0, 0);
        t.setSingleLine(true);
        b.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72);
        lp.bottomMargin = 6;
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            // Con el panel minimizado, el icono de AA (ya abierto) lo despliega.
            if (hidden && "aa".equals(target) && "aa".equals(screenName)) {
                setHidden(false);
                scheduleHide();
                return;
            }
            open(target);
        });
        navButtons.add(b);
        return b;
    }

    /**
     * Mini reproductor bajo «Radio» mientras suena una emisora: su logo (abre la pantalla Radio),
     * cambiar de emisora y parar. Con la barra de iconos no cabe y se oculta.
     */
    private View buildRadioMini(Context c) {
        LinearLayout m = new LinearLayout(c);
        m.setOrientation(LinearLayout.HORIZONTAL);
        m.setGravity(Gravity.CENTER_VERTICAL);
        m.setPadding(14, 8, 8, 8);
        m.setBackground(CarStyle.round(CarStyle.lighter(panelColor, 14), 36));
        radioLogo = new ImageView(c);
        radioLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        radioLogo.setOnClickListener(v -> open("radio"));
        m.addView(radioLogo, new LinearLayout.LayoutParams(0, 56, 1f));
        m.addView(miniButton(c, R.drawable.hql_ic_next, () -> RadioPlayer.step(ctx, 1)));
        m.addView(miniButton(c, R.drawable.hql_ic_stop, RadioPlayer::stop));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72);
        lp.bottomMargin = 6;
        m.setLayoutParams(lp);
        m.setVisibility(View.GONE);
        radioMini = m;
        return m;
    }

    private static View miniButton(Context c, int icon, Runnable r) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(CarStyle.TEXT));
        b.setBackground(CarStyle.round(CarStyle.PILL_BG, 28));
        b.setPadding(14, 14, 14, 14);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(56, 56);
        lp.leftMargin = 8;
        b.setLayoutParams(lp);
        return b;
    }

    private void renderRadioMini() {
        if (radioMini == null) return;
        RadioPlayer.Station s = RadioPlayer.current();
        boolean show = s != null && !rail();
        radioMini.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        if (!s.equals(radioMini.getTag())) {
            radioMini.setTag(s);
            if (s.logo != null && !s.logo.isEmpty()) {
                com.bumptech.glide.Glide.with(ctx).load(s.logo).error(R.drawable.hql_ic_radio).into(radioLogo);
            } else {
                radioLogo.setImageResource(R.drawable.hql_ic_radio);
            }
        }
    }

    private void markNav() {
        for (LinearLayout b : navButtons) {
            boolean on = screenName.equals(b.getTag());
            int fg = on ? CarStyle.ON_ACCENT : 0xFFDADCE0;
            b.setBackground(on ? CarStyle.accent(36) : null);
            if (radioMini != null) radioMini.setBackground(CarStyle.round(CarStyle.lighter(panelColor, 14), 36));
            ((ImageView) b.getChildAt(0)).setImageTintList(ColorStateList.valueOf(fg));
            ((TextView) b.getChildAt(1)).setTextColor(fg);
        }
    }

    private void open(String name) {
        // WhitelistAppOn: Android Auto y la sección Coche son interfaces de conducción; el resto, ocio.
        // La radio también (solo audio, como la música de AA).
        SspSession.setDrivingUi("aa".equals(name) || "car".equals(name) || "radio".equals(name));
        setCompact(false);
        if (!"aa".equals(name)) setHidden(false);
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
                v = CarStyle.message(content.getContext(), Str.get(R.string.hql_screen_open_failed));
            }
            content.addView(v, CarStyle.match());
        } else if (!aaReady) {
            // Zona de AA antes de su primer frame: animación de carga (GlFrameRelay aún no dibuja AA).
            splash = new SplashView(content.getContext(), panelColor);
            content.addView(splash, CarStyle.match());
        }
        aaShown = factory == null;
        markNav();
        listener.onAaVisible(aaShown);
        scheduleHide();
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
            case "games":
                return GamesScreen::new;
            case "radio":
                return RadioScreen::new;
            case "settings":
                return SettingsScreen::new;
            case "car":
                return CarHubScreen::new;
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

        @Override
        public void setFullscreen(boolean on) {
            setCompact(on);
        }
    };

    private void showBattery(Intent i) {
        if (battery == null || i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        if (level >= 0) {
            battery.setText(Str.get(R.string.hql_phone_battery, level * 100 / scale) + (charging ? " · " + Str.get(R.string.hql_charging) : ""));
        }
    }
}
