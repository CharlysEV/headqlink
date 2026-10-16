package com.headqlink.link;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Ajustes del móvil (menú ⚙ › Ajustes): una lista de Material 3 por secciones (Conexión, Imagen, Pantalla del coche,
 * Pantalla del móvil, Viaje y, plegado, Avanzado), cada fila con lo que está puesto debajo. Cada cambio se guarda en el
 * acto. Lo que solo vale al reconectar (perfil, fluidez, tamaños, H.264, números de Avanzado, motor, latencia, freno),
 * con el enlace en marcha, saca abajo «Algunos cambios se aplican al reconectar · Reconectar ahora», que hace lo que hacía
 * «Guardar» en el antiguo diálogo «Ajustes de imagen» (LinkService.ACTION_APPLY, y AA vuelve a negociar si cambian el
 * perfil, los fps o los tamaños). Sin pulsarlo, se aplica en la próxima conexión.
 */
public class SettingsActivity extends Activity implements LinkState.Listener {
    private static final int BG = 0xFF161C24;
    private static final int TEXT = 0xFFEAF2F7;
    private static final int TEXT_DIM = 0xFFA3B3C2;
    private static final int ACCENT = 0xFF00E5C7;
    private static final String STATE_PENDING = "pending";
    private static final String STATE_BEFORE_ID = "before_id";
    private static final String STATE_BEFORE_FPS = "before_fps";
    private static final String STATE_AA_SIZE = "before_aa_size";
    private static final String STATE_UI_SCALE = "before_ui_scale";
    private static final String STATE_ADVANCED = "advanced_open";

    private Config cfg;
    private float density;
    /** Donde van las filas que se añaden: la lista o la caja de Avanzado. */
    private LinearLayout target;
    /** Cada fila sabe repintarse (valor de debajo, interruptor, si se ve). */
    private final List<Runnable> refreshers = new ArrayList<>();
    /** Repintando los interruptores: sus cambios no son del usuario. */
    private boolean syncing;
    private View bar;
    private LinearLayout advBox;
    private ImageView advChevron;
    /** Hay cambios que solo valen al reconectar, hechos con el enlace en marcha y sin aplicar todavía. */
    private boolean pending;
    private boolean wasRunning;
    /** Cómo estaba al empezar (o al último «Reconectar ahora»): para saber si Android Auto tiene que volver a negociar. */
    private String beforeId;
    private int beforeFps;
    private int aaSizeBefore;
    private int uiScaleBefore;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        cfg = new Config(this);
        density = getResources().getDisplayMetrics().density;
        snapshot();
        if (b != null && b.getBoolean(STATE_PENDING)) {
            pending = true;
            beforeId = b.getString(STATE_BEFORE_ID, beforeId);
            beforeFps = b.getInt(STATE_BEFORE_FPS, beforeFps);
            aaSizeBefore = b.getInt(STATE_AA_SIZE, aaSizeBefore);
            uiScaleBefore = b.getInt(STATE_UI_SCALE, uiScaleBefore);
        }
        wasRunning = LinkState.running;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(BG);
        com.google.android.material.appbar.MaterialToolbar bar0 = new com.google.android.material.appbar.MaterialToolbar(this);
        bar0.setTitle(Str.get(R.string.hql_settings));
        bar0.setTitleTextColor(TEXT);
        bar0.setNavigationIcon(R.drawable.ic_arrow_back);
        bar0.setNavigationIconTint(TEXT);
        bar0.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description);
        bar0.setNavigationOnClickListener(v -> finish());
        root.addView(bar0, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(24));
        target = list;
        buildConnection();
        buildImage();
        buildCarScreen();
        buildPhoneScreen();
        buildTrip();
        buildAdvanced(list, b != null && b.getBoolean(STATE_ADVANCED));
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        bar = reconnectBar();
        root.addView(bar);
        setContentView(root);
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LinkState.addListener(this);
        // De vuelta de «Modo y conexión» (u otra pantalla): el modo pudo cambiar, y con él qué filas se ven.
        onLinkStateChanged();
        refreshAll();
    }

    @Override
    protected void onPause() {
        LinkState.removeListener(this);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(STATE_PENDING, pending);
        out.putString(STATE_BEFORE_ID, beforeId);
        out.putInt(STATE_BEFORE_FPS, beforeFps);
        out.putInt(STATE_AA_SIZE, aaSizeBefore);
        out.putInt(STATE_UI_SCALE, uiScaleBefore);
        out.putBoolean(STATE_ADVANCED, advBox != null && advBox.getVisibility() == View.VISIBLE);
    }

    @Override
    public void onLinkStateChanged() {
        boolean running = LinkState.running;
        if (running && !wasRunning) {
            // Una sesión nueva ya arranca con lo guardado: no queda nada por aplicar.
            pending = false;
            snapshot();
        }
        wasRunning = running;
        updateBar();
    }

    // ------------------------------------------------------------------ secciones

    private void buildConnection() {
        section(R.drawable.hql_ln_wifi, Str.get(R.string.hql_settings_connection));
        View mode = row(Str.get(R.string.hql_aj_mode), () -> Ui.modeTitle(cfg.mode()) + " · " + Ui.linkTitle(cfg.linkMode()));
        mode.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_STEP, 1)));
        switchRow(Str.get(R.string.hql_bt_auto_switch),
                () -> cfg.btAutoConnect() ? Str.get(R.string.hql_aj_bt_auto_sum, cfg.btAutoName()) : Str.get(R.string.hql_aj_off),
                cfg::btAutoConnect, on -> AutoConnect.setBt(this, cfg, on, this::refreshAll));
        switchRow(Str.get(R.string.hql_usb_auto_switch),
                () -> Str.get(cfg.usbAutoConnect() ? R.string.hql_aj_usb_auto_sum : R.string.hql_aj_usb_auto_off_sum),
                cfg::usbAutoConnect, on -> AutoConnect.setUsb(this, cfg, on));
        row(Str.get(R.string.hql_car_wait_title), () -> carWaitLabel(cfg.carWaitMin())).setOnClickListener(v -> pickCarWait());
        View server = row(Str.get(R.string.hql_aa_server_start_title),
                () -> Str.get(cfg.aaServerManual() ? R.string.hql_aa_server_start_manual : R.string.hql_aa_server_start_auto));
        server.setOnClickListener(v -> pickAaServer());
        visibleWhen(server, () -> Config.isAa(cfg.mode()));
    }

    private void buildImage() {
        section(R.drawable.hql_ln_screen, Str.get(R.string.hql_image));
        row(Str.get(R.string.hql_picture_profile), this::profileSummary).setOnClickListener(v -> pickProfile());
        row(Str.get(R.string.hql_fluidity), () -> Str.get(cfg.fluidity() == VideoProfile.FLUID_60 ? R.string.hql_fluidity_60 : R.string.hql_fluidity_30))
                .setOnClickListener(v -> pickFluidity());
        row(Str.get(R.string.hql_thermal_mode), () -> thermalLabel(cfg.thermalMode())).setOnClickListener(v -> pickThermal());
    }

    private void buildCarScreen() {
        section(R.drawable.hql_ln_car, Str.get(R.string.hql_aj_sec_car_screen));
        row(Str.get(R.string.hql_aa_size), () -> aaSizeLabel(cfg.aaSizePercent())).setOnClickListener(v -> pickAaSize());
        View scale = row(Str.get(R.string.hql_aj_ui_scale), () -> uiScaleLabel(cfg.uiScalePercent()));
        scale.setOnClickListener(v -> pickUiScale());
        visibleWhen(scale, this::extended);
        switchRow(Str.get(R.string.hql_autohide_panel), this::extOnlyNote, cfg::panelAutoHide, on -> {
            cfg.putBool(Config.PANEL_AUTOHIDE, on);
            L.i("panel lateral: ocultarse solo " + (on ? "sí" : "no"));
            CarUi.applyAutoHide(on);
        });
        switchRow(Str.get(R.string.hql_panel_right), this::extOnlyNote, cfg::panelRight, on -> {
            cfg.putBool(Config.PANEL_RIGHT, on);
            CarUi.applyPanelRight(on);
        });
        switchRow(Str.get(R.string.hql_psx_enhanced), () -> Str.get(R.string.hql_psx_enhanced_sum), cfg::psxEnhanced, on -> {
            cfg.putBool(Config.PSX_ENHANCED, on);
            L.i("PlayStation: imagen " + (on ? "mejorada" : "original") + " (se aplica al abrir el próximo juego)");
        });
        note(Str.get(R.string.hql_aj_panel_help));
    }

    private void buildPhoneScreen() {
        section(R.drawable.hql_ln_phone, Str.get(R.string.hql_aj_sec_phone_screen));
        switchRow(Str.get(R.string.hql_aj_keep_screen), () -> Str.get(R.string.hql_aj_keep_screen_sum), cfg::keepScreenOn, on -> {
            // Se aplica ya si Android Auto está en marcha (modo coche con o sin ALLOW_SLEEP).
            cfg.putBool(Config.KEEP_SCREEN_ON, on);
            L.i("pantalla: «Mantener la pantalla del móvil encendida» " + (on ? "sí" : "no"));
            com.andrerinas.openheadunit.aap.AapService.refreshCarModeFlags();
        });
        switchRow(Str.get(R.string.hql_aj_car_mode), () -> Str.get(R.string.hql_aj_car_mode_sum),
                () -> cfg.getBool(PhoneScreen.ANDROID_CAR_MODE), on -> {
                    // Se aplica en la próxima sesión de Android Auto (el modo coche se activa al crearse su servicio).
                    cfg.putBool(PhoneScreen.ANDROID_CAR_MODE, on);
                    L.i("pantalla: «Modo coche de Android al proyectar» " + (on ? "sí" : "no") + " (próxima sesión)");
                });
    }

    private void buildTrip() {
        section(R.drawable.hql_ic_efficiency, Str.get(R.string.hql_settings_sec_trip));
        switchRow(Str.get(R.string.hql_radar_voice), null, cfg::radarVoice, on -> {
            cfg.putBool(Config.RADAR_VOICE, on);
            L.i("avisos de radar por voz: " + (on ? "sí" : "no"));
        });
        switchRow(Str.get(R.string.hql_float_card), () -> Str.get(R.string.hql_float_card_help), cfg::floatCard, on -> {
            cfg.putBool(Config.FLOAT_CARD, on);
            L.i("tarjeta flotante: " + (on ? "sí" : "no"));
            CarUi.applyFloatCard();
        });
        switchRow(Str.get(R.string.hql_maps_planner), () -> Str.get(R.string.hql_maps_planner_help), cfg::mapsPlanner, on -> {
            cfg.putBool(Config.MAPS_PLANNER, on);
            L.i("planificador desde Google Maps: " + (on ? "sí" : "no"));
        });
        switchRow(Str.get(R.string.hql_trip_notice), () -> Str.get(R.string.hql_trip_notice_help), cfg::tripNotice, on -> {
            cfg.putBool("trip_notice", on);
            L.i("resumen del viaje al desconectar: " + (on ? "sí" : "no"));
        });
        row(Str.get(R.string.hql_aj_price_home), () -> SettingsRows.priceText(cfg.electricityPrice(), Locale.getDefault()))
                .setOnClickListener(v -> editPrice(Str.get(R.string.hql_aj_price_home), Str.get(R.string.hql_settings_price_help),
                        SettingsRows.PRICE_HOME_MAX, cfg::electricityPrice, p -> {
                            cfg.setElectricityPrice(p);
                            L.i(String.format(Locale.US, "precio de la electricidad en casa: %.2f €/kWh", cfg.electricityPrice()));
                        }));
        row(Str.get(R.string.hql_settings_price_fast), () -> SettingsRows.priceText(cfg.fastChargePrice(), Locale.getDefault()))
                .setOnClickListener(v -> editPrice(Str.get(R.string.hql_settings_price_fast), Str.get(R.string.hql_settings_price_fast_help),
                        SettingsRows.PRICE_FAST_MAX, cfg::fastChargePrice, p -> {
                            cfg.setFastChargePrice(p);
                            L.i(String.format(Locale.US, "precio de la carga rápida: %.2f €/kWh", cfg.fastChargePrice()));
                        }));
    }

    /** Avanzado: plegado (salvo que se dejara abierto antes de girar la pantalla), con el aviso de que es para pruebas. */
    private void buildAdvanced(LinearLayout list, boolean open) {
        list.addView(divider());
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(dp(56));
        head.setPadding(dp(16), dp(8), dp(16), dp(8));
        head.setBackgroundResource(selectable());
        head.addView(leading(R.drawable.hql_ln_gear));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        t.setTextColor(ACCENT);
        t.setText(Str.get(R.string.hql_advanced));
        texts.addView(t);
        TextView warn = new TextView(this);
        warn.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        warn.setTextColor(getColor(R.color.hql_warn));
        warn.setText(Str.get(R.string.hql_aj_adv_warning));
        texts.addView(warn);
        head.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        advChevron = new ImageView(this);
        advChevron.setImageResource(R.drawable.ic_chevron_right);
        advChevron.setImageTintList(android.content.res.ColorStateList.valueOf(TEXT_DIM));
        head.addView(advChevron, new LinearLayout.LayoutParams(dp(24), dp(24)));
        list.addView(head);
        advBox = new LinearLayout(this);
        advBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(advBox);
        head.setOnClickListener(v -> {
            boolean show = advBox.getVisibility() != View.VISIBLE;
            advBox.setVisibility(show ? View.VISIBLE : View.GONE);
            advChevron.animate().rotation(show ? 90 : 0).setDuration(180).start();
        });
        advBox.setVisibility(open ? View.VISIBLE : View.GONE);
        advChevron.setRotation(open ? 90 : 0);

        target = advBox;
        switchRow(Str.get(R.string.hql_aj_low_latency), () -> Str.get(R.string.hql_aj_low_latency_sum), cfg::lowLatency, on -> {
            // Ajuste manual: desde ahora manda sobre el perfil (elegir otro perfil lo vuelve a dejar en el del perfil).
            cfg.putBool(Config.LOW_LATENCY, on);
            L.i("optimizaciones de latencia: " + (on ? "sí" : "no") + reconnectNote());
            needsReconnect();
        });
        numberRow(Str.get(R.string.hql_aj_fps), Config.FPS, SettingsRows.FPS_MAX);
        numberRow(Str.get(R.string.hql_aj_kbps), Config.KBPS, SettingsRows.KBPS_MAX);
        numberRow(Str.get(R.string.hql_aj_width), Config.WIDTH, SettingsRows.SIZE_MAX);
        numberRow(Str.get(R.string.hql_aj_height), Config.HEIGHT, SettingsRows.SIZE_MAX);
        row(Str.get(R.string.hql_h264_profile), () -> SettingsRows.H264_NAMES[SettingsRows.indexOf(SettingsRows.H264, cfg.profile(), 0)])
                .setOnClickListener(v -> pickH264());
        switchRow(Str.get(R.string.hql_aj_brake), () -> Str.get(R.string.hql_brake) + "\n" + Str.get(R.string.hql_brake_help),
                cfg::aaBrake, on -> {
                    cfg.putBool(Config.AA_BRAKE, on);
                    L.i("freno de Android Auto: " + (on ? "sí" : "no") + reconnectNote());
                    needsReconnect();
                });
        numberRow(Str.get(R.string.hql_aj_window), Config.AA_WINDOW, SettingsRows.WINDOW_MAX);
        row(Str.get(R.string.hql_engine_title), () -> engineLabel(cfg.linkEngine())).setOnClickListener(v -> pickEngine());
        switchRow(Str.get(R.string.hql_aj_diag_trace), () -> Str.get(R.string.hql_aj_diag_trace_sum), cfg::diagTrace, on -> {
            // La lee el servicio al arrancar (y LogcatCapture, PerfTrace…): vale al volver a conectar.
            cfg.putBool(Config.DIAG_TRACE, on);
            L.i("traza detallada: " + (on ? "sí" : "no") + " (se aplica al volver a conectar)");
            if (LinkState.running) {
                android.widget.Toast.makeText(this, Str.get(R.string.hql_applies_on_reconnect), android.widget.Toast.LENGTH_LONG).show();
            }
        });
        target = list;
    }

    // ------------------------------------------------------------------ selectores

    private void pickProfile() {
        // «Automático» sigue al recomendado; el recomendado lleva la marca aunque se elija otro.
        String rec = VideoProfile.recommended(this);
        String chosen = cfg.videoProfileChoice();
        String[] ids = SettingsRows.profileIds();
        CharSequence[] items = new CharSequence[ids.length];
        for (int i = 0; i < ids.length; i++) {
            String title = ids[i].isEmpty() ? Str.get(R.string.hql_profile_auto_named, VideoProfile.title(rec)) : VideoProfile.title(ids[i]);
            String tag = rec.equals(ids[i]) ? "  · " + Str.get(R.string.hql_recommended) : "";
            String detail = ids[i].isEmpty() ? Str.get(R.string.hql_profile_auto_detail) : VideoProfile.detail(ids[i]);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(title + tag + "\n" + detail);
            if (!tag.isEmpty()) {
                sb.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.hql_ok)), title.length(), title.length() + tag.length(), 0);
            }
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.82f), title.length() + tag.length() + 1, sb.length(), 0);
            sb.setSpan(new android.text.style.ForegroundColorSpan(TEXT_DIM), title.length() + tag.length() + 1, sb.length(), 0);
            items[i] = sb;
        }
        choose(Str.get(R.string.hql_picture_profile), items, SettingsRows.indexOf(ids, chosen, -1),
                Str.get(R.string.hql_this_phone, VideoProfile.reason()), i -> {
                    // Elegir perfil quita los ajustes manuales viejos (fps, tamaño, recodificar, latencia).
                    cfg.setVideoProfile(ids[i]);
                    L.i("perfil de imagen: " + (ids[i].isEmpty() ? "automático (" + rec + ")" : ids[i]) + reconnectNote());
                    needsReconnect();
                });
    }

    private void pickFluidity() {
        // Fluidez: 30 fps (lo que pide el coche, menos calor) o 60 fps; cuenta con Coche y con Automático si es Coche.
        int[] values = {VideoProfile.FLUID_30, VideoProfile.FLUID_60};
        CharSequence[] items = {Str.get(R.string.hql_fluidity_30), Str.get(R.string.hql_fluidity_60)};
        int before = cfg.fluidity();
        choose(Str.get(R.string.hql_fluidity), items, SettingsRows.indexOf(values, before), Str.get(R.string.hql_fluidity_help), i -> {
            cfg.setFluidity(values[i]);
            L.i("fluidez: " + values[i] + " fps (perfil " + cfg.videoProfile().id + "; "
                    + (LinkState.running ? "se aplica al reconectar" : "se aplica en la próxima sesión") + ")");
            needsReconnect();
        });
    }

    private void pickThermal() {
        // Protección térmica: Normal, Suave o Apagada (ThermalPolicy); se aplica al vídeo vivo en el acto.
        CharSequence[] items = new CharSequence[SettingsRows.THERMAL.length];
        for (int i = 0; i < items.length; i++) items[i] = thermalLabel(SettingsRows.THERMAL[i]);
        choose(Str.get(R.string.hql_thermal_mode), items, SettingsRows.indexOf(SettingsRows.THERMAL, cfg.thermalMode(), 0),
                Str.get(R.string.hql_thermal_help), i -> {
                    String mode = SettingsRows.THERMAL[i];
                    cfg.setThermalMode(mode);
                    L.i("protección térmica: " + ThermalPolicy.modeName(mode) + " (se aplica en el acto)");
                    ThermalGuard.onModeChanged();
                });
    }

    private void pickAaSize() {
        // La densidad que se le anuncia a Android Auto (AaSize); en pantallas pequeñas ya va más pequeño solo.
        CharSequence[] items = new CharSequence[AaSize.CHOICES.length];
        for (int i = 0; i < items.length; i++) items[i] = aaSizeLabel(AaSize.CHOICES[i]);
        choose(Str.get(R.string.hql_aa_size), items, SettingsRows.indexOf(AaSize.CHOICES, cfg.aaSizePercent()),
                Str.get(R.string.hql_aa_size_note), i -> {
                    cfg.setAaSizePercent(AaSize.CHOICES[i]);
                    L.i("tamaño de Android Auto: " + AaSize.CHOICES[i] + " %" + reconnectNote());
                    needsReconnect();
                });
    }

    private void pickUiScale() {
        // Solo se lee y se escribe el ajuste: cómo se escala en el coche lo decide UiScale al empezar la sesión.
        int[] choices = SettingsRows.UI_SCALE_CHOICES;
        CharSequence[] items = new CharSequence[choices.length];
        for (int i = 0; i < items.length; i++) items[i] = uiScaleLabel(choices[i]);
        choose(Str.get(R.string.hql_aj_ui_scale), items, SettingsRows.indexOf(choices, cfg.uiScalePercent()),
                Str.get(R.string.hql_aj_ui_scale_help), i -> {
                    cfg.setUiScalePercent(choices[i]);
                    L.i("tamaño de la interfaz del coche: " + (choices[i] == 0 ? "automático" : choices[i] + " %") + reconnectNote());
                    needsReconnect();
                });
    }

    private void pickCarWait() {
        // «Esperar al coche»: una opción por minuto de Config.CAR_WAIT_CHOICES; la de por defecto, marcada.
        int[] choices = Config.CAR_WAIT_CHOICES;
        CharSequence[] items = new CharSequence[choices.length];
        for (int i = 0; i < items.length; i++) items[i] = carWaitLabel(choices[i]);
        choose(Str.get(R.string.hql_car_wait_title), items, SettingsRows.indexOf(choices, cfg.carWaitMin()),
                Str.get(R.string.hql_car_wait_help), i -> {
                    // Se lee al perder al coche: vale ya para la próxima espera, sin reconectar.
                    cfg.setCarWaitMin(choices[i]);
                    L.life("«Esperar al coche»: " + cfg.carWaitMin() + " min (vale para la próxima espera)");
                });
    }

    private void pickAaServer() {
        CharSequence[] items = {Str.get(R.string.hql_aa_server_start_auto), Str.get(R.string.hql_aa_server_start_manual)};
        choose(Str.get(R.string.hql_aa_server_start_title), items, cfg.aaServerManual() ? 1 : 0,
                Str.get(R.string.hql_aa_server_start_help), i -> {
                    // Se lee en cada decisión: vale para lo siguiente que necesite el servidor, sin reconectar.
                    boolean manual = i == 1;
                    cfg.setAaServerManual(manual);
                    AaServerManual.onModeChanged(this, manual, "Ajustes");
                });
    }

    private void pickH264() {
        choose(Str.get(R.string.hql_h264_profile), SettingsRows.H264_NAMES, SettingsRows.indexOf(SettingsRows.H264, cfg.profile(), 0),
                null, i -> {
                    // Con Máximo (High de serie) se guarda aparte, para no cambiar el de los demás perfiles.
                    cfg.setProfileFor(cfg.videoProfile(), SettingsRows.H264[i]);
                    L.i("perfil H.264: " + SettingsRows.H264[i] + reconnectNote());
                    needsReconnect();
                });
    }

    private void pickEngine() {
        String[] engines = {Config.ENGINE_QDAUTO, Config.ENGINE_ORIGINAL};
        CharSequence[] items = {engineLabel(Config.ENGINE_QDAUTO), engineLabel(Config.ENGINE_ORIGINAL)};
        choose(Str.get(R.string.hql_engine_title), items, SettingsRows.indexOf(engines, cfg.linkEngine(), 0),
                Str.get(R.string.hql_applies_on_reconnect), i -> {
                    cfg.setLinkEngine(engines[i]);
                    L.i("motor de protocolo: " + engines[i] + " (se aplica al volver a conectar)");
                    if (LinkState.running) {
                        android.widget.Toast.makeText(this, Str.get(R.string.hql_applies_on_reconnect), android.widget.Toast.LENGTH_LONG).show();
                    }
                    needsReconnect();
                });
    }

    // ------------------------------------------------------------------ textos de las filas

    private String profileSummary() {
        String rec = VideoProfile.recommended(this);
        String chosen = cfg.videoProfileChoice();
        String name = chosen.isEmpty() ? Str.get(R.string.hql_profile_auto_named, VideoProfile.title(rec)) : VideoProfile.title(chosen);
        return rec.equals(chosen) ? name + " · " + Str.get(R.string.hql_recommended) : name;
    }

    private static String thermalLabel(String mode) {
        if (ThermalPolicy.MODE_SOFT.equals(mode)) return Str.get(R.string.hql_thermal_soft);
        if (ThermalPolicy.MODE_OFF.equals(mode)) return Str.get(R.string.hql_thermal_off);
        return Str.get(R.string.hql_thermal_normal);
    }

    private static String aaSizeLabel(int pct) {
        int[] labels = {R.string.hql_aa_size_bigger, R.string.hql_aa_size_normal, R.string.hql_aa_size_smaller,
                R.string.hql_aa_size_smallest};
        int i = SettingsRows.indexOf(AaSize.CHOICES, pct);
        return i >= 0 && i < labels.length ? Str.get(labels[i]) : Str.get(R.string.hql_aj_percent, pct);
    }

    private static String uiScaleLabel(int pct) {
        return pct <= 0 ? Str.get(R.string.hql_aj_ui_scale_auto, SettingsRows.uiScaleAutoT03()) : Str.get(R.string.hql_aj_percent, pct);
    }

    private static String carWaitLabel(int min) {
        return min == Config.DEFAULT_CAR_WAIT_MIN ? Str.get(R.string.hql_car_wait_option_default, min) : Str.get(R.string.hql_car_wait_option, min);
    }

    private static String engineLabel(String engine) {
        return Str.get(Config.ENGINE_QDAUTO.equals(engine) ? R.string.hql_engine_qdauto : R.string.hql_engine_original);
    }

    private boolean extended() {
        return Config.MODE_AA_EXT.equals(cfg.mode());
    }

    /** Debajo de lo del panel lateral, fuera de Auto extendido: que solo cuenta allí. */
    private String extOnlyNote() {
        return extended() ? null : Str.get(R.string.hql_aj_ext_only);
    }

    private static String reconnectNote() {
        return LinkState.running ? " (se aplica al reconectar)" : " (se aplica en la próxima conexión)";
    }

    // ------------------------------------------------------------------ reconectar

    private void snapshot() {
        VideoProfile p = cfg.videoProfile();
        beforeId = p.id;
        beforeFps = p.fps;
        aaSizeBefore = cfg.aaSizePercent();
        uiScaleBefore = cfg.uiScalePercent();
    }

    /** Un cambio que solo vale al reconectar: con el enlace en marcha, la barra para aplicarlo ya. */
    private void needsReconnect() {
        if (LinkState.running) pending = true;
        updateBar();
    }

    private void updateBar() {
        if (bar != null) bar.setVisibility(pending && LinkState.running ? View.VISIBLE : View.GONE);
    }

    /**
     * «Reconectar ahora»: lo que hacía «Guardar» en el antiguo diálogo de imagen. Sesión nueva con los ajustes; con otro
     * perfil, otros fps o un tamaño distinto, también Android Auto (resolución, fps y densidad se negocian al conectar).
     */
    private void applyNow() {
        VideoProfile after = cfg.videoProfile();
        boolean renegotiate = SettingsRows.renegotiate(beforeId, beforeFps, after.id, after.fps,
                aaSizeBefore != cfg.aaSizePercent(), extended() && uiScaleBefore != cfg.uiScalePercent());
        pending = false;
        snapshot();
        updateBar();
        if (!LinkState.running) return;
        L.i("ajustes: reconectar ahora para aplicar lo cambiado" + (renegotiate ? " (Android Auto vuelve a negociar)" : ""));
        startForegroundService(LinkService.fromApp(new Intent(this, LinkService.class).setAction(LinkService.ACTION_APPLY)
                .putExtra(LinkService.EXTRA_AA_RENEGOTIATE, renegotiate)));
    }

    private View reconnectBar() {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), dp(6), dp(8), dp(6));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getColor(R.color.hql_surface_top));
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setElevation(dp(6));
        TextView t = new TextView(this);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setTextColor(TEXT);
        t.setText(Str.get(R.string.hql_aj_reconnect_msg));
        b.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        com.google.android.material.button.MaterialButton go = M3.button(this, Str.get(R.string.hql_aj_reconnect_now), false);
        go.setTextColor(ACCENT);
        go.setOnClickListener(v -> applyNow());
        b.addView(go);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(4), dp(12), dp(12));
        b.setLayoutParams(lp);
        b.setVisibility(View.GONE);
        return b;
    }

    // ------------------------------------------------------------------ diálogos

    /**
     * Selector de una opción: la lista con la elegida marcada y, debajo, su explicación. Al tocar otra, se guarda y se
     * cierra.
     */
    private void choose(String title, CharSequence[] items, int checked, CharSequence help, IntConsumer onPick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        RadioGroup group = new RadioGroup(this);
        int[] ids = new int[items.length];
        for (int i = 0; i < items.length; i++) {
            android.widget.RadioButton rb = new com.google.android.material.radiobutton.MaterialRadioButton(this);
            ids[i] = View.generateViewId();
            rb.setId(ids[i]);
            rb.setText(items[i]);
            rb.setPadding(rb.getPaddingLeft(), dp(6), 0, dp(6));
            group.addView(rb);
            if (i == checked) rb.setChecked(true);
        }
        box.addView(group);
        if (help != null) {
            TextView h = new TextView(this);
            h.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            h.setTextColor(TEXT_DIM);
            h.setText(help);
            h.setPadding(0, dp(8), 0, dp(8));
            box.addView(h);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(sv)
                .setNegativeButton(Str.get(R.string.hql_cancel), null)
                .show();
        group.setOnCheckedChangeListener((g, id) -> {
            dlg.dismiss();
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == id && i != checked) {
                    onPick.accept(i);
                    refreshAll();
                }
            }
        });
    }

    /** Un número de Avanzado (vacío = automático): fuera de rango, el aviso en el campo y no se cierra. */
    private void numberRow(String title, String key, int max) {
        row(title, () -> key.equals(Config.AA_WINDOW) && cfg.getInt(key) <= 0
                ? Str.get(R.string.hql_aj_window_auto) : SettingsRows.numberSummary(cfg.getInt(key), Str.get(R.string.hql_profile_auto)))
                .setOnClickListener(v -> {
                    TextInputEditText e = M3.field(this);
                    e.setInputType(InputType.TYPE_CLASS_NUMBER);
                    e.setSingleLine(true);
                    e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
                    int cur = cfg.getInt(key);
                    if (cur > 0) e.setText(String.valueOf(cur));
                    TextInputLayout field = M3.outlined(e);
                    field.setHelperText(Str.get(R.string.hql_aj_range, max));
                    editDialog(title, null, e, field, () -> {
                        int val = HomeActivity.parseSetting(e.getText() == null ? "" : e.getText().toString(), max);
                        if (val < 0) {
                            L.w("ajustes avanzados: «" + e.getText() + "» no vale para " + key + " (de 0 a " + max + "): se queda " + cfg.getInt(key));
                            field.setError(Str.get(R.string.hql_aj_range_invalid, max));
                            return false;
                        }
                        if (val != cfg.getInt(key)) {
                            cfg.putInt(key, val);
                            L.i("ajustes avanzados: " + key + " = " + (val > 0 ? String.valueOf(val) : "automático") + reconnectNote());
                            needsReconnect();
                        }
                        return true;
                    });
                });
    }

    /** Un precio (€/kWh) escrito a mano: con coma o punto, redondeado al céntimo y entre 0 y max. */
    private void editPrice(String title, String help, double max, java.util.function.DoubleSupplier get,
                           java.util.function.DoubleConsumer set) {
        TextInputEditText e = M3.field(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setSingleLine(true);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10)});
        e.setText(SettingsRows.priceField(get.getAsDouble(), Locale.getDefault()));
        e.setSelectAllOnFocus(true);
        TextInputLayout field = M3.outlined(e);
        field.setSuffixText("€/kWh");
        String maxText = SettingsRows.priceField(max, Locale.getDefault());
        editDialog(title, help, e, field, () -> {
            double p = SettingsRows.parsePrice(e.getText() == null ? "" : e.getText().toString(), max);
            if (p < 0) {
                field.setError(Str.get(R.string.hql_aj_price_invalid, maxText));
                return false;
            }
            set.accept(p);
            return true;
        });
    }

    /** Diálogo con un campo: «Guardar» comprueba (save devuelve false si no vale y el diálogo sigue abierto). */
    private void editDialog(String title, String help, TextInputEditText e, TextInputLayout field, BooleanSupplier save) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        if (help != null) {
            TextView h = new TextView(this);
            h.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            h.setTextColor(TEXT_DIM);
            h.setText(help);
            box.addView(h);
        }
        box.addView(field);
        androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(Str.get(R.string.hql_save), null)
                .setNegativeButton(Str.get(R.string.hql_cancel), null)
                .show();
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                field.setError(null);
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean ok;
            try {
                ok = save.getAsBoolean();
            } catch (RuntimeException ex) {
                // Nada de lo que se escriba debe cerrar la app: se queda lo que había.
                L.w("ajustes: valor no válido en «" + title + "»: " + ex);
                ok = false;
            }
            if (!ok) return;
            dlg.dismiss();
            refreshAll();
        });
        e.requestFocus();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    // ------------------------------------------------------------------ piezas de la lista

    private void refreshAll() {
        for (Runnable r : refreshers) r.run();
        updateBar();
    }

    /** Cabecera de sección: icono y título en el acento, con un separador encima (salvo la primera). */
    private void section(int icon, String title) {
        if (target.getChildCount() > 0) target.addView(divider());
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(dp(48));
        head.setPadding(dp(16), dp(12), dp(16), dp(4));
        head.addView(leading(icon));
        TextView t = new TextView(this);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        t.setTextColor(ACCENT);
        t.setText(title);
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (android.os.Build.VERSION.SDK_INT >= 28) head.setAccessibilityHeading(true);
        target.addView(head);
    }

    /** Fila de la lista (sangrada bajo la cabecera): título y, debajo, lo que está puesto (summary puede ser null). */
    private LinearLayout row(String title, Supplier<String> summary) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(56));
        r.setPadding(dp(56), dp(10), dp(16), dp(10));
        r.setBackgroundResource(selectable());
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        t.setTextColor(TEXT);
        t.setText(title);
        texts.addView(t);
        TextView s = new TextView(this);
        s.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        s.setTextColor(TEXT_DIM);
        s.setVisibility(View.GONE);
        texts.addView(s);
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (summary != null) {
            refreshers.add(() -> {
                String v = summary.get();
                s.setText(v);
                s.setVisibility(v == null || v.isEmpty() ? View.GONE : View.VISIBLE);
            });
        }
        target.addView(r);
        return r;
    }

    /** Fila con interruptor: se toca la fila entera o el interruptor; el cambio se guarda en el acto. */
    private void switchRow(String title, Supplier<String> summary, BooleanSupplier get, Consumer<Boolean> set) {
        LinearLayout r = row(title, summary);
        MaterialSwitch sw = new MaterialSwitch(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        r.addView(sw, lp);
        sw.setContentDescription(title);
        sw.setOnCheckedChangeListener((b, on) -> {
            if (syncing) return;
            set.accept(on);
            refreshAll();
        });
        r.setOnClickListener(v -> sw.toggle());
        refreshers.add(() -> {
            boolean on = get.getAsBoolean();
            if (sw.isChecked() == on) return;
            syncing = true;
            sw.setChecked(on);
            syncing = false;
        });
    }

    /** Una línea de ayuda suelta, sangrada como las filas. */
    private void note(String text) {
        TextView t = new TextView(this);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setTextColor(getColor(R.color.hql_text_faint));
        t.setText(text);
        t.setPadding(dp(56), dp(4), dp(16), dp(12));
        target.addView(t);
    }

    private void visibleWhen(View v, BooleanSupplier cond) {
        refreshers.add(() -> v.setVisibility(cond.getAsBoolean() ? View.VISIBLE : View.GONE));
    }

    private View divider() {
        com.google.android.material.divider.MaterialDivider dv = new com.google.android.material.divider.MaterialDivider(this);
        dv.setDividerColor(0xFF242C36);
        return dv;
    }

    private ImageView leading(int icon) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24));
        lp.rightMargin = dp(16);
        iv.setLayoutParams(lp);
        return iv;
    }

    private int selectable() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
