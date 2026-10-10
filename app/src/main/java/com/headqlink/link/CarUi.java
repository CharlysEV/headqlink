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

        /**
         * Zona de AA en la pantalla del coche: desde x, w de ancho (hasta el borde derecho, o la mitad con la pantalla
         * partida).
         */
        default void onAaRegion(int x, int w) {
            onPanelWidth(x);
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
    /** Dibuja la interfaz en el vídeo con la pantalla del móvil apagada (Android deja de componer la pantalla virtual). */
    private PhoneOffRenderer phoneOff;
    private Presentation pres;
    private FrameLayout content;
    private View panel;
    private int panelColor;
    /** El gris del panel elegido en Ajustes (el de noche; de día el panel es claro: CarTheme.panelColor). */
    private int panelGray;
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
    // Aviso del plan de carga (RoutePlanner): tarjeta ámbar abajo en el panel, por encima de Android Auto.
    private LinearLayout alertCard;
    private TextView alertTitle;
    private AlertIcon alertIcon;
    private TextView alertText;
    private View alertGo;
    private boolean alertPending;
    /** El panel se despliega para enseñar el aviso y se vuelve a ocultar pasado esto (si se oculta solo). */
    private static final long ALERT_SHOW_MS = 20_000;
    /** Sin tocarlo, el aviso se quita pasado esto (sigue en la pestaña Ruta). */
    private static final long ALERT_KEEP_MS = 10 * 60_000L;
    private final Runnable alertExpire = this::clearAlert;
    private final RoutePlanner.ChargeAlertListener chargeAlert = (title, text, kind) -> main.post(() -> showAlert(title, text, kind));
    private final Runnable hideTask = () -> setHidden(true);
    /** AA ya ha dado imagen; antes, en su zona se ve la animación de carga. */
    private boolean aaReady;
    private SplashView splash;
    private volatile boolean aaShown = true;
    /**
     * Pantalla partida (lo pide el usuario con el botón de la barra en Web, Vídeos o TV): AA a la izquierda, junto a la
     * barra, y nuestra pantalla a la derecha (splitAaWidth). Dura la sesión con el coche: al volver a una de esas
     * pantallas, sigue partida; en la sesión siguiente empieza sin partir.
     */
    private static volatile boolean split;
    private LinearLayout splitButton;
    /** Aviso de la pantalla partida abierto (o null). */
    private View splitWarning;
    /** Aviso aceptado en esta sesión: se pide una vez por sesión con el coche. */
    private boolean splitAccepted;
    /** Panel a la derecha (Ajustes): AA y nuestras pantallas a su izquierda. */
    private boolean panelRight;
    /** Datos en la barra de iconos (hora, temperatura exterior y batería del coche), solo con el panel reducido. */
    private LinearLayout railInfo;
    private TextView railTime;
    private TextView railTemp;
    private TextView railSoc;
    private final Runnable railTick = this::renderRailInfo;
    private CarScreen screen;
    private String screenName = "aa";
    /**
     * Día o noche, como Android Auto: AapService avisa cada vez que le manda a AA el modo (NightModeManager). Al cambiar,
     * la interfaz se rehace entera con la otra paleta (CarTheme) y vuelve a la misma pantalla.
     */
    private final BroadcastReceiver nightReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            boolean isNight = i.getBooleanExtra("isNight", true);
            main.post(() -> applyNight(isNight));
        }
    };
    private boolean nightRegistered;

    private void applyNight(boolean isNight) {
        if (!CarTheme.apply(isNight) || pres == null) return;
        L.i("CarUi: modo " + (isNight ? "noche" : "día") + " (como Android Auto)");
        rebuild();
    }

    /** Rehace la interfaz entera con los colores de ahora (CarTheme) y vuelve a la misma pantalla. */
    private void rebuild() {
        panelColor = CarTheme.panelColor(panelGray);
        listener.onPanelColor(panelColor);
        String name = screenName == null ? "aa" : screenName;
        boolean wasHidden = hidden;
        if (screen != null) screen.destroy();
        screen = null;
        if (splash != null) splash.stop();
        splash = null;
        // El aviso abierto se va con la interfaz vieja.
        splitWarning = null;
        pres.setContentView(buildRoot(pres.getContext()));
        open(name);
        if (wasHidden && "aa".equals(name)) setHidden(true);
        if (alertPending) renderAlert();
    }

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
        CarTheme.setFixedPanel(cfg.panelFixedColor());
        CarTheme.setPanelAlpha(cfg.panelAlpha());
        this.panelRight = cfg.panelRight();
        this.panelGray = cfg.panelColor();
        this.panelColor = CarTheme.panelColor(panelGray);
        this.autoHide = cfg.panelAutoHide();
        // Sesión nueva: sin partir, hasta aceptar otra vez el aviso.
        split = false;
    }

    /** Color del panel en vivo (ajuste del móvil); no hace nada si el modo ampliado no está en marcha. */
    static void applyPanelColor(int color) {
        CarUi ui = current;
        if (ui != null) ui.main.post(() -> {
            ui.panelGray = color;
            ui.panelColor = CarTheme.panelColor(color);
            if (ui.panel != null) ui.panel.setBackgroundColor(ui.panelColor);
            if (ui.content != null) ui.content.setBackgroundColor(ui.panelColor);
            ui.listener.onPanelColor(ui.panelColor);
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
    /** Color fijo de la barra (0 = automático) elegido en Ajustes: se aplica al momento rehaciendo la interfaz. */
    static void applyPanelFixed(int color) {
        CarTheme.setFixedPanel(color);
        CarUi ui = current;
        if (ui != null) ui.main.post(() -> {
            L.i("CarUi: color de la barra " + (color == 0 ? "automático (día y noche)" : String.format("fijo #%06X", color & 0xFFFFFF)));
            if (ui.pres != null) ui.rebuild();
        });
    }

    /** Transparencia del panel (0-100) elegida en Ajustes: al momento. */
    static void applyPanelAlpha(int pct) {
        CarTheme.setPanelAlpha(pct);
        CarUi ui = current;
        if (ui != null) ui.main.post(() -> {
            L.i("CarUi: transparencia del panel " + pct + " %");
            if (ui.pres != null) ui.rebuild();
        });
    }

    /** Panel a la derecha o a la izquierda (Ajustes): se rehace la interfaz y AA se recoloca. */
    static void applyPanelRight(boolean right) {
        CarUi ui = current;
        if (ui != null) ui.main.post(() -> {
            ui.panelRight = right;
            L.i("CarUi: panel a la " + (right ? "derecha" : "izquierda"));
            if (ui.pres != null) ui.rebuild();
        });
    }

    /** Botones del panel (cuáles y en qué orden) cambiados en Ajustes: se rehace el panel. */
    static void applyPanelButtons() {
        CarUi ui = current;
        if (ui != null) ui.main.post(() -> {
            L.i("CarUi: botones del panel " + new Config(ui.ctx).panelButtons());
            if (ui.pres != null) ui.rebuild();
        });
    }

    static void applyAutoHide(boolean on) {
        CarUi ui = current;
        if (ui != null) ui.main.post(() -> {
            ui.autoHide = on;
            if (!on) ui.main.removeCallbacks(ui.hideTask);
        });
    }

    int contentWidth() {
        int pw = layoutPanelW();
        return width - pw - (splitActive() ? splitAaWidth(width, height, pw, dpi) : 0);
    }

    /** Pantallas que se pueden poner junto a AA: la sección Coche (instrumentos: vale en marcha) y las de ocio. */
    static boolean splitCapable(String name) {
        return "car".equals(name) || "web".equals(name) || "videos".equals(name) || "tv".equals(name);
    }

    /** El aviso (mejor parado, bajo tu responsabilidad) es para el ocio; la sección Coche es información de conducción. */
    static boolean splitNeedsWarning(String name) {
        return splitCapable(name) && !"car".equals(name);
    }

    /**
     * Ancho mínimo de AA con la pantalla partida. Más estrecho, AA pasa a su diseño de tarjetas (mapa arriba, tiempo y
     * música debajo) y Google Maps se queda en modo reducido: no se mueve ni deja buscar, porque cada toque solo le pide a
     * AA más sitio (requestIncreaseContentArea) y AA no se lo da. Medido en el C10 (1920x882, 200 dpi): falla a 912 px y
     * va bien a 950. Con margen: 800 dp y 1,15 veces el alto.
     */
    static final int SPLIT_MIN_AA_DP = 800;
    static final float SPLIT_MIN_AA_ASPECT = 1.15f;
    /** Lo mínimo que se deja a nuestra pantalla al lado. */
    static final int SPLIT_MIN_SCREEN_W = 560;

    /** Ancho de AA con la pantalla partida: la mitad de lo que deja la barra, sin bajar del mínimo de Maps (puro). */
    static int splitAaWidth(int width, int height, int railW, int dpi) {
        int free = width - railW;
        int min = Math.max(Math.round(SPLIT_MIN_AA_DP * dpi / 160f), Math.round(height * SPLIT_MIN_AA_ASPECT));
        return Math.min(Math.max(free / 2, min), Math.max(free / 2, free - SPLIT_MIN_SCREEN_W));
    }

    /** ¿Se ve ahora la pantalla partida? (pedida y con una pantalla que la admite abierta) */
    private boolean splitActive() {
        return split && screen != null && splitCapable(screenName);
    }

    /** ¿Se ve AA? Sola o junto a nuestra pantalla (partida). */
    boolean aaVisible() {
        return aaShown || splitActive();
    }

    /** Ancho de la barra o del panel tal como se colocan ahora (con la pantalla partida, la barra de iconos). */
    private int layoutPanelW() {
        return splitActive() ? COMPACT_W : currentPanelW();
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
        boolean sp = splitActive();
        int pw = layoutPanelW();
        boolean rail = sp || rail();
        panel.getLayoutParams().width = pw;
        panel.requestLayout();
        for (LinearLayout b : navButtons) {
            b.getChildAt(1).setVisibility(rail ? View.GONE : View.VISIBLE);
            b.setGravity(rail ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
            b.setPadding(rail ? 0 : 18, 0, rail ? 0 : 18, 0);
        }
        battery.setVisibility(rail ? View.GONE : View.VISIBLE);
        railInfo.setVisibility(rail ? View.VISIBLE : View.GONE);
        if (rail) renderRailInfo();
        expand.setVisibility(hidden ? View.VISIBLE : View.GONE);
        renderAlert();
        panel.setPadding(rail ? 12 : 20, 20, rail ? 12 : 20, 20);
        renderRadioMini();
        int aaW = sp ? splitAaWidth(width, height, pw, dpi) : 0;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) content.getLayoutParams();
        // Con el panel a la derecha, AA va pegado al borde izquierdo y nuestra pantalla entre AA y el panel.
        int aaX = panelRight ? 0 : pw;
        lp.leftMargin = aaX + aaW;
        lp.width = width - pw - aaW;
        content.setLayoutParams(lp);
        renderSplitButton(rail);
        // Solo importa a AA cuando se ve (el modo compacto es de nuestras pantallas).
        if (sp) {
            listener.onAaRegion(aaX, aaW);
        } else if (!compact) {
            listener.onAaRegion(aaX, width - pw);
        }
    }

    /** Botón «Partir pantalla» de la barra: solo en las pantallas que lo admiten; marcado con la pantalla partida. */
    private void renderSplitButton(boolean rail) {
        LinearLayout b = splitButton;
        if (b == null) return;
        b.setVisibility(splitCapable(screenName) && screen != null ? View.VISIBLE : View.GONE);
        b.getChildAt(1).setVisibility(rail ? View.GONE : View.VISIBLE);
        b.setGravity(rail ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        b.setPadding(rail ? 0 : 18, 0, rail ? 0 : 18, 0);
        ((TextView) b.getChildAt(1)).setText(Str.get(split ? R.string.hql_split_off : R.string.hql_split_on));
        int fg = split ? CarStyle.ON_ACCENT : CarTheme.navText();
        b.setBackground(split ? CarStyle.accent(36) : null);
        ((ImageView) b.getChildAt(0)).setImageTintList(ColorStateList.valueOf(fg));
        ((TextView) b.getChildAt(1)).setTextColor(fg);
    }

    /** El botón de la barra: AA junto a nuestra pantalla, o nuestra pantalla sola otra vez. */
    private void toggleSplit() {
        if (!split && !splitAccepted && splitNeedsWarning(screenName)) {
            showSplitWarning();
            return;
        }
        split = !split;
        L.i("CarUi: pantalla partida " + (split ? "sí" : "no") + " (" + screenName + ")");
        applyLayout();
        listener.onAaVisible(aaVisible());
    }

    /**
     * Aviso de la pantalla partida, la primera vez de cada sesión: mejor con el coche parado, y bajo la
     * responsabilidad de quien la usa. Hay que aceptarlo; «Cancelar» deja la pantalla como estaba.
     */
    private void showSplitWarning() {
        if (splitWarning != null || root == null) return;
        Context c = root.getContext();
        FrameLayout scrim = new FrameLayout(c);
        scrim.setBackgroundColor(0xB3000000);
        // Se queda con todos los toques mientras está abierto.
        scrim.setClickable(true);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(36, 32, 36, 28);
        card.setBackground(CarStyle.round(CarTheme.alertBg(false), 28));
        card.setClickable(true);
        TextView title = CarStyle.text(c, Str.get(R.string.hql_split_warn_title), 28, CarKit.AMBER);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);
        TextView text = CarStyle.text(c, Str.get(R.string.hql_split_warn_text), 23, CarStyle.TEXT);
        text.setPadding(0, 18, 0, 26);
        card.addView(text);
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        TextView cancel = CarKit.pill(c, Str.get(R.string.hql_cancel), false);
        cancel.setOnClickListener(v -> {
            L.i("CarUi: aviso de la pantalla partida: cancelado");
            dismissSplitWarning();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = 14;
        btns.addView(cancel, clp);
        TextView accept = CarKit.pill(c, Str.get(R.string.hql_split_accept), true);
        accept.setOnClickListener(v -> {
            L.i("CarUi: aviso de la pantalla partida: aceptado");
            splitAccepted = true;
            dismissSplitWarning();
            toggleSplit();
        });
        btns.addView(accept);
        card.addView(btns);
        scrim.addView(card, new FrameLayout.LayoutParams(Math.min(780, width - 80), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        splitWarning = scrim;
        L.i("CarUi: aviso de la pantalla partida");
    }

    private void dismissSplitWarning() {
        View w = splitWarning;
        splitWarning = null;
        if (w != null && root != null) root.removeView(w);
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

    /** Sin el dibujo propio con la pantalla del móvil apagada (vista previa). */
    void start(Surface surface) {
        start(surface, null, null);
    }

    /**
     * surface: entrada normal de la capa (la pantalla virtual compone ahí). manual y useManual: entrada y conmutador de la
     * capa dibujada por HeadQLink con la pantalla del móvil apagada (PhoneOffRenderer); null sin ese modo.
     */
    void start(Surface surface, Surface manual, java.util.function.Consumer<Boolean> useManual) {
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
            if (manual != null && useManual != null) {
                Presentation shown = pres;
                phoneOff = new PhoneOffRenderer(ctx, main, manual, useManual, width, height,
                        () -> shown.getWindow() != null ? shown.getWindow().getDecorView() : null);
                phoneOff.start();
            }
            ctx.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            listenNight();
            RadioPlayer.addListener(radioChanged);
            current = this;
            // Sección Coche: viajes, ruta y datos de la vía, mientras dure el modo ampliado (y los datos reales del coche
            // de la cuenta de Leapmotor, si está configurada: CarCloud).
            CarCloud.acquire(ctx, "ui");
            // Coche virtual (sin coche): sin guardar viajes.
            if (!RoutePlanner.testMode()) TripLog.start(ctx);
            RoutePlanner.start(ctx);
            RoutePlanner.chargeAlerts = chargeAlert;
            RoadInfo.start(ctx);
            open("aa");
            if (autoHide) setHidden(true);
        });
    }

    /** Escucha el día y la noche de AA y pide el estado de ahora (si AA ya está conectado, llega enseguida). */
    private void listenNight() {
        if (nightRegistered) return;
        androidx.core.content.ContextCompat.registerReceiver(ctx, nightReceiver,
                new IntentFilter(com.andrerinas.openheadunit.aap.AapService.ACTION_NIGHT_MODE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        nightRegistered = true;
        ctx.sendBroadcast(new Intent(com.andrerinas.openheadunit.aap.AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE)
                .setPackage(ctx.getPackageName()));
    }

    void stop() {
        main.post(() -> {
            if (current == this) current = null;
            main.removeCallbacks(hideTask);
            main.removeCallbacks(alertExpire);
            main.removeCallbacks(railTick);
            if (RoutePlanner.chargeAlerts == chargeAlert) RoutePlanner.chargeAlerts = null;
            SspSession.setDrivingUi(true);
            try {
                ctx.unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            if (nightRegistered) {
                try {
                    ctx.unregisterReceiver(nightReceiver);
                } catch (IllegalArgumentException ignored) {
                }
                nightRegistered = false;
            }
            if (screen != null) screen.destroy();
            screen = null;
            RadioPlayer.removeListener(radioChanged);
            RadioPlayer.stop();
            TripLog.stop();
            CarCloud.release("ui");
            RoutePlanner.stop();
            RoadInfo.stop();
            if (phoneOff != null) phoneOff.stop();
            phoneOff = null;
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
            boolean atEdge = panelRight ? x > width - COMPACT_W : x < COMPACT_W;
            if (action == 1) swipeX = hidden && atEdge ? x : -1;
            if (action == 3 && swipeX >= 0 && (panelRight ? swipeX - x : x - swipeX) > 70) {
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
        CarCloud.acquire(ctx, "ui");
        TripLog.start(ctx);
        RoutePlanner.start(ctx);
        RoutePlanner.chargeAlerts = chargeAlert;
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
        int side = panelRight ? Gravity.RIGHT : Gravity.LEFT;
        root.addView(panel, new FrameLayout.LayoutParams(panelW, height, side));
        content = new FrameLayout(c);
        content.setBackgroundColor(panelColor);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(contentWidth(), height);
        lp.leftMargin = panelRight ? 0 : panelW;
        root.addView(content, lp);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(panelW - 24, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | side);
        alp.leftMargin = 12;
        alp.rightMargin = 12;
        alp.bottomMargin = 12;
        root.addView(buildAlert(c), alp);
        return root;
    }

    /** Tarjeta del aviso del plan de carga: qué ha cambiado, «Ir» (Google Maps con las paradas nuevas) y «Ruta». */
    private View buildAlert(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(18, 16, 18, 16);
        card.setBackground(CarStyle.round(CarTheme.alertBg(false), 24));
        card.setClickable(true);
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        alertIcon = new AlertIcon(c);
        top.addView(alertIcon, new LinearLayout.LayoutParams(40, 40));
        alertTitle = CarStyle.text(c, "", 22, CarKit.AMBER);
        alertTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        alertTitle.setPadding(12, 0, 0, 0);
        alertTitle.setMaxLines(2);
        top.addView(alertTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);
        alertText = CarStyle.text(c, "", 20, CarStyle.TEXT);
        alertText.setMaxLines(6);
        alertText.setPadding(0, 10, 0, 12);
        card.addView(alertText);
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView go = CarKit.pill(c, Str.get(R.string.hql_go), true);
        go.setOnClickListener(v -> {
            RoutePlanner r = RoutePlanner.get();
            RoutePlanner.Plan p = r == null ? null : r.plan();
            ChargePlanner.Result cp = r == null ? null : r.lastChargePlan();
            clearAlert();
            if (p != null && cp != null) RoutePlanner.navigateWithStops(ctx, p, cp);
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.rightMargin = 12;
        btns.addView(go, glp);
        alertGo = go;
        TextView route = CarKit.pill(c, Str.get(R.string.hql_tab_route), false);
        route.setOnClickListener(v -> {
            clearAlert();
            CarHubScreen.selectTab(0);
            open("car");
        });
        btns.addView(route);
        card.addView(btns);
        card.setVisibility(View.GONE);
        alertCard = card;
        return card;
    }

    /**
     * El plan de carga ha cambiado o la carga es lenta (ámbar), o ya puedes seguir (verde): se despliega el panel con el
     * aviso un rato (y el botón Coche queda en ámbar).
     */
    private void showAlert(String title, String text, int kind) {
        if (alertCard == null) return;
        boolean ok = kind == RoutePlanner.ALERT_READY;
        alertCard.setBackground(CarStyle.round(CarTheme.alertBg(ok), 24));
        alertTitle.setTextColor(ok ? CarKit.GREEN : CarKit.AMBER);
        alertIcon.setColor(ok ? CarKit.GREEN : CarKit.AMBER);
        alertTitle.setText(title);
        alertText.setText(text);
        RoutePlanner r = RoutePlanner.get();
        ChargePlanner.Result cp = r == null ? null : r.lastChargePlan();
        alertGo.setVisibility(cp != null && cp.next() != null ? View.VISIBLE : View.GONE);
        alertPending = true;
        main.removeCallbacks(alertExpire);
        main.postDelayed(alertExpire, ALERT_KEEP_MS);
        if (hidden) setHidden(false);
        renderAlert();
        markNav();
        main.removeCallbacks(hideTask);
        if (autoHide && aaShown) main.postDelayed(hideTask, ALERT_SHOW_MS);
    }

    private void clearAlert() {
        alertPending = false;
        main.removeCallbacks(alertExpire);
        renderAlert();
        markNav();
    }

    /** La tarjeta solo con el panel completo (en la barra de iconos no cabe: queda el botón Coche en ámbar). */
    private void renderAlert() {
        if (alertCard != null) alertCard.setVisibility(alertPending && !rail() ? View.VISIBLE : View.GONE);
    }

    /** Rayo en un círculo (icono del aviso): ámbar, o verde si ya puedes seguir. */
    private static final class AlertIcon extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int color = CarKit.AMBER;

        AlertIcon(Context c) {
            super(c);
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(CarKit.alpha(color, 0.25f));
            cv.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p);
            CarIcons.bolt(cv, getWidth() / 2f, getHeight() / 2f, r * 1.2f, color, p);
        }
    }

    private View buildPanel(Context c) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(panelColor);
        p.setPadding(20, 20, 20, 20);
        navButtons.clear();
        p.addView(navButton(c, Str.get(R.string.hql_mode_aa), "aa", R.drawable.hql_ic_aa));
        // Los botones que se eligieron en Ajustes, en su orden («Auto» arriba y «Ajustes» abajo van fijos).
        for (String id : new Config(ctx).panelButtons()) {
            p.addView(navButton(c, navLabel(id), id, navIcon(id)));
            if ("radio".equals(id)) p.addView(buildRadioMini(c));
        }
        p.addView(buildSplitButton(c));

        View spacer = new View(c);
        p.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        p.addView(navButton(c, Str.get(R.string.hql_settings), "settings", R.drawable.hql_ic_settings));
        // Desplegar el panel minimizado.
        ImageView ex = new ImageView(c);
        ex.setImageResource(R.drawable.hql_ic_expand);
        // La flecha apunta hacia el contenido: con el panel a la derecha, hacia la izquierda.
        ex.setScaleX(panelRight ? -1f : 1f);
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
        battery = CarStyle.text(c, "", 22, CarTheme.navTextDim());
        battery.setPadding(8, 0, 0, 6);
        p.addView(battery);
        p.addView(buildRailInfo(c));
        return p;
    }

    /** Texto de un botón del panel por su id (Config.PANEL_BUTTONS_ALL). */
    static String navLabel(String id) {
        switch (id) {
            case "car": return Str.get(R.string.hql_car);
            case "photos": return Str.get(R.string.hql_photos);
            case "videos": return Str.get(R.string.hql_videos);
            case "web": return "Web";
            case "tv": return "TV";
            case "radio": return Str.get(R.string.hql_radio);
            case "games": return Str.get(R.string.hql_games);
            default: return id;
        }
    }

    static int navIcon(String id) {
        switch (id) {
            case "car": return R.drawable.hql_ic_gauges;
            case "photos": return R.drawable.hql_ic_photos;
            case "videos": return R.drawable.hql_ic_videos;
            case "web": return R.drawable.hql_ic_web;
            case "tv": return R.drawable.hql_ic_tv;
            case "radio": return R.drawable.hql_ic_radio;
            case "games": return R.drawable.hql_ic_games;
            default: return R.drawable.hql_ic_settings;
        }
    }

    /**
     * Datos en la barra de iconos (cuando el panel está reducido): hora, temperatura exterior (del tiempo, si los sensores
     * la tienen) y batería del coche (de la cuenta de Leapmotor, si hay dato). Se refresca cada 30 s.
     */
    private View buildRailInfo(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(0, 4, 0, 4);
        railTime = CarStyle.text(c, "", 20, CarTheme.navText());
        railTime.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        railTime.setGravity(Gravity.CENTER);
        railTemp = CarStyle.text(c, "", 18, CarTheme.navTextDim());
        railTemp.setGravity(Gravity.CENTER);
        railSoc = CarStyle.text(c, "", 18, CarTheme.navTextDim());
        railSoc.setGravity(Gravity.CENTER);
        l.addView(railTime);
        l.addView(railTemp);
        l.addView(railSoc);
        l.setVisibility(View.GONE);
        railInfo = l;
        return l;
    }

    private void renderRailInfo() {
        main.removeCallbacks(railTick);
        if (railInfo == null || railInfo.getVisibility() != View.VISIBLE) return;
        railTime.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date()));
        double t = CarSensors.outsideTempC();
        railTemp.setText(Double.isNaN(t) ? "" : Math.round(t) + "\u00b0");
        railTemp.setVisibility(Double.isNaN(t) ? View.GONE : View.VISIBLE);
        CarCloud.Snapshot cs = CarCloud.snapshot();
        double soc = cs.hasData() ? cs.status.socBest() : Double.NaN;
        railSoc.setText(Double.isNaN(soc) ? "" : Math.round(soc) + " %");
        railSoc.setVisibility(Double.isNaN(soc) ? View.GONE : View.VISIBLE);
        main.postDelayed(railTick, 30_000);
    }

    /** Elemento del panel al estilo de AA: icono + texto; el activo, en una píldora de color. */
    /** «Partir pantalla» (Web, Vídeos y TV): como un botón de la barra, pero no abre una pantalla. */
    private View buildSplitButton(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(18, 0, 18, 0);
        ImageView iv = new ImageView(c);
        iv.setImageResource(R.drawable.hql_ic_split);
        b.addView(iv, new LinearLayout.LayoutParams(40, 40));
        TextView t = CarStyle.text(c, Str.get(R.string.hql_split_on), 25, CarStyle.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(20, 0, 0, 0);
        t.setSingleLine(true);
        b.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72);
        lp.topMargin = 12;
        lp.bottomMargin = 6;
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> toggleSplit());
        b.setVisibility(View.GONE);
        splitButton = b;
        return b;
    }

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
            int fg = on ? CarStyle.ON_ACCENT : alertPending && "car".equals(b.getTag()) ? CarKit.AMBER : CarTheme.navText();
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
        // La pantalla partida depende de la pantalla abierta: se recoloca todo y AA se ve si va sola o al lado.
        applyLayout();
        listener.onAaVisible(aaVisible());
        scheduleHide();
        L.i("CarUi: pantalla " + name + (splitActive() ? " (partida, con AA)" : ""));
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
