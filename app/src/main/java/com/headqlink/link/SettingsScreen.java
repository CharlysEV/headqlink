package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Ajustes desde el coche, en dos columnas como los de Android Auto: a la izquierda las secciones (con lo que está puesto
 * debajo de cada una) y a la derecha lo de la elegida, en tarjetas.
 * - Imagen: perfil (con el recomendado marcado), fluidez y optimizaciones de latencia. Cambiar de perfil o de fluidez
 *   reconecta coche y Android Auto (unos segundos).
 * - Panel lateral: ocultarse solo, lado, color y transparencia, y qué botones salen y en qué orden.
 * - Viaje: avisos de radar por voz y precio de la electricidad (coste de los viajes en la sección Coche).
 * - Conexión: la de ahora (solo lectura).
 * Lo mismo que en el móvil, sin tener que cogerlo. Al cambiar el panel, la interfaz se rehace: la sección y el punto de
 * la lista se recuerdan para volver al mismo sitio.
 */
final class SettingsScreen implements CarScreen {
    private static final String SEC_IMAGE = "image";
    private static final String SEC_PANEL = "panel";
    private static final String SEC_TRIP = "trip";
    private static final String SEC_CONN = "conn";
    private static final String[] SECTIONS = {SEC_IMAGE, SEC_PANEL, SEC_TRIP, SEC_CONN};
    /** Sección elegida y desplazamiento de su lista: sobreviven a que la interfaz se rehaga (color, botones del panel). */
    private static String section = SEC_IMAGE;
    private static int scrollY;

    private Host host;
    private Config cfg;
    private String pending;
    /** Fluidez pendiente de aplicar (VideoProfile.FLUID_30 o FLUID_60). */
    private int pendingFluid;
    private TextView apply;
    private TextView detail;
    private final java.util.List<TextView> profilePills = new java.util.ArrayList<>();
    private final java.util.List<TextView> fluidPills = new java.util.ArrayList<>();
    /** «Tamaño de Android Auto» pendiente de aplicar (AaSize.CHOICES). */
    private int pendingAaSize;
    private final java.util.List<TextView> sizePills = new java.util.ArrayList<>();
    /** «Tamaño de la interfaz» pendiente de aplicar (Config.uiScalePercent: 0 automático, UI_SCALE_CHOICES). */
    private int pendingUiScale;
    private final java.util.List<TextView> uiScalePills = new java.util.ArrayList<>();
    /** «Tema» (0 automático, 1 claro, 2 oscuro): se aplica al tocar, sin reconectar. */
    private final java.util.List<TextView> themePills = new java.util.ArrayList<>();
    /** Lo que se puede elegir en «Tamaño de la interfaz»: automático y del 60 al 100 %. */
    static final int[] UI_SCALE_CHOICES = {0, 60, 70, 80, 90, 100};
    private LinearLayout nav;
    private ScrollView pane;

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        cfg = new Config(c);
        pending = cfg.videoProfileChoice();
        pendingFluid = cfg.fluidity();
        pendingAaSize = cfg.aaSizePercent();
        pendingUiScale = cfg.uiScalePercent();

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(CarStyle.BG);

        // Secciones.
        nav = new LinearLayout(c);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(26, 30, 18, 30);
        TextView head = CarStyle.text(c, Str.get(R.string.hql_settings), 38, CarStyle.TEXT);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.setPadding(18, 0, 0, 22);
        nav.addView(head);
        for (String s : SECTIONS) nav.addView(navRow(c, s));
        root.addView(nav, new LinearLayout.LayoutParams(Math.max(360, Math.min(440, h.width() / 4)), ViewGroup.LayoutParams.MATCH_PARENT));

        // Lo de la sección.
        pane = new ScrollView(c);
        pane.setFillViewport(true);
        pane.setVerticalScrollBarEnabled(false);
        pane.setOnScrollChangeListener((v, x, y, ox, oy) -> scrollY = y);
        root.addView(pane, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        showSection(section, true);
        return root;
    }

    // ------------------------------------------------------------------ secciones

    private View navRow(Context c, String s) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(20, 16, 16, 16);
        r.setTag(s);
        ImageView icon = new ImageView(c);
        icon.setImageResource(sectionIcon(s));
        r.addView(icon, new LinearLayout.LayoutParams(44, 44));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(20, 0, 0, 0);
        TextView t = CarStyle.text(c, sectionTitle(s), 26, CarStyle.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        texts.addView(t);
        TextView sub = CarStyle.text(c, sectionSummary(s), 19, CarStyle.TEXT_DIM);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(sub);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.setOnClickListener(v -> showSection(s, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 6;
        r.setLayoutParams(lp);
        return r;
    }

    private static int sectionIcon(String s) {
        switch (s) {
            case SEC_PANEL:
                return R.drawable.hql_ic_split;
            case SEC_TRIP:
                return R.drawable.hql_ic_gauges;
            case SEC_CONN:
                return R.drawable.hql_ic_aa;
            default:
                return R.drawable.hql_ic_photos;
        }
    }

    private static String sectionTitle(String s) {
        switch (s) {
            case SEC_PANEL:
                return Str.get(R.string.hql_settings_sec_panel);
            case SEC_TRIP:
                return Str.get(R.string.hql_settings_sec_trip);
            case SEC_CONN:
                return Str.get(R.string.hql_settings_connection);
            default:
                return Str.get(R.string.hql_image);
        }
    }

    /** Lo que está puesto, debajo del nombre de la sección. */
    private String sectionSummary(String s) {
        switch (s) {
            case SEC_PANEL:
                return Str.get(R.string.hql_settings_sec_panel_sub);
            case SEC_TRIP:
                return Money.per(cfg.electricityPrice(), "kWh")
                        + (cfg.radarVoice() ? " · " + Str.get(R.string.hql_radar_voice) : "");
            case SEC_CONN:
                return Ui.linkTitle(LinkState.linkModeFor(cfg));
            default: {
                String choice = cfg.videoProfileChoice();
                String eff = choice.isEmpty() ? VideoProfile.recommended(host.context()) : choice;
                String name = choice.isEmpty() ? Str.get(R.string.hql_profile_auto) + " (" + VideoProfile.title(eff) + ")" : VideoProfile.title(eff);
                return VideoProfile.of(eff).followsCar ? name + " · " + cfg.fluidity() + " fps" : name;
            }
        }
    }

    private void showSection(String s, boolean keepScroll) {
        if (!keepScroll) scrollY = 0;
        section = s;
        for (int i = 0; i < nav.getChildCount(); i++) {
            View r = nav.getChildAt(i);
            if (r.getTag() == null) continue;
            boolean sel = s.equals(r.getTag());
            r.setBackground(sel ? CarStyle.round(CarStyle.ACCENT_BG, 30) : null);
            ImageView icon = (ImageView) ((LinearLayout) r).getChildAt(0);
            icon.setColorFilter(sel ? CarStyle.ACCENT : CarStyle.TEXT_DIM);
            TextView t = (TextView) ((LinearLayout) ((LinearLayout) r).getChildAt(1)).getChildAt(0);
            t.setTextColor(sel ? CarStyle.ACCENT : CarStyle.TEXT);
        }
        Context c = host.context();
        profilePills.clear();
        fluidPills.clear();
        sizePills.clear();
        uiScalePills.clear();
        themePills.clear();
        apply = null;
        detail = null;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(16, 30, 40, 40);
        switch (s) {
            case SEC_PANEL:
                buildPanel(c, col);
                break;
            case SEC_TRIP:
                buildTrip(c, col);
                break;
            case SEC_CONN:
                buildConnection(c, col);
                break;
            default:
                buildImage(c, col);
                break;
        }
        pane.removeAllViews();
        pane.addView(col);
        int y = scrollY;
        if (y > 0) pane.post(() -> pane.scrollTo(0, y));
    }

    /** Vuelve a escribir lo que está puesto debajo de cada sección. */
    private void refreshSummaries() {
        for (int i = 0; i < nav.getChildCount(); i++) {
            View r = nav.getChildAt(i);
            if (r.getTag() == null) continue;
            TextView sub = (TextView) ((LinearLayout) ((LinearLayout) r).getChildAt(1)).getChildAt(1);
            sub.setText(sectionSummary((String) r.getTag()));
        }
    }

    // ------------------------------------------------------------------ Imagen

    private void buildImage(Context c, LinearLayout col) {
        String rec = VideoProfile.recommended(c);
        LinearLayout img = card(c, Str.get(R.string.hql_settings_profile));
        String[] ids = new String[VideoProfile.ALL.length + 1];
        ids[0] = "";
        System.arraycopy(VideoProfile.ALL, 0, ids, 1, VideoProfile.ALL.length);
        GridLayout grid = new GridLayout(c);
        grid.setColumnCount(4);
        grid.setPadding(0, 14, 0, 4);
        for (String id : ids) {
            String name = id.isEmpty() ? Str.get(R.string.hql_profile_auto) : VideoProfile.title(id);
            if (rec.equals(id)) name += "  ★";
            TextView p = CarStyle.pill(c, name);
            p.setTag(id);
            p.setPadding(20, 22, 20, 22);
            twoLinesIfScaled(p);
            p.setOnClickListener(v -> select(id));
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            glp.width = 0;
            glp.setMargins(0, 0, 14, 14);
            profilePills.add(p);
            grid.addView(p, glp);
        }
        img.addView(grid);
        detail = CarStyle.text(c, "", 22, CarStyle.TEXT_DIM);
        detail.setPadding(4, 4, 0, 0);
        img.addView(detail);
        col.addView(img, cardLp());

        // Fluidez: 30 fps (lo que pide el coche, menos calor) o 60; solo cuenta con Coche (y Automático si es Coche).
        LinearLayout fluid = card(c, Str.get(R.string.hql_fluidity));
        LinearLayout seg = new LinearLayout(c);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(0, 14, 0, 4);
        for (int f : new int[]{VideoProfile.FLUID_30, VideoProfile.FLUID_60}) {
            TextView p = CarStyle.pill(c, Str.get(f == VideoProfile.FLUID_60 ? R.string.hql_fluidity_60 : R.string.hql_fluidity_30));
            p.setTag(f);
            p.setPadding(20, 22, 20, 22);
            p.setOnClickListener(v -> {
                pendingFluid = f;
                refresh();
            });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            plp.rightMargin = 14;
            fluidPills.add(p);
            seg.addView(p, plp);
        }
        fluid.addView(seg);
        col.addView(fluid, cardLp());

        // Tamaño de Android Auto: la densidad que se le anuncia (AaSize); en pantallas pequeñas ya va más pequeño solo.
        LinearLayout size = card(c, Str.get(R.string.hql_aa_size));
        LinearLayout sseg = new LinearLayout(c);
        sseg.setOrientation(LinearLayout.HORIZONTAL);
        sseg.setPadding(0, 14, 0, 4);
        int[] labels = {R.string.hql_aa_size_bigger, R.string.hql_aa_size_normal, R.string.hql_aa_size_smaller,
                R.string.hql_aa_size_smallest};
        for (int i = 0; i < AaSize.CHOICES.length; i++) {
            int pct = AaSize.CHOICES[i];
            TextView p = CarStyle.pill(c, Str.get(labels[i]));
            p.setTag(pct);
            p.setPadding(16, 22, 16, 22);
            twoLinesIfScaled(p);
            p.setOnClickListener(v -> {
                pendingAaSize = pct;
                refresh();
            });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            plp.rightMargin = 14;
            sizePills.add(p);
            sseg.addView(p, plp);
        }
        size.addView(sseg);
        TextView sizeNote = CarStyle.text(c, Str.get(R.string.hql_aa_size_note), 20, CarStyle.TEXT_DIM);
        sizeNote.setPadding(4, 4, 0, 0);
        size.addView(sizeNote);
        col.addView(size, cardLp());

        // Tamaño de nuestra interfaz (UiScale): solo en una pantalla pequeña (T03) o si ya se eligió uno a mano.
        if (showUiScale(CarStyle.smallScreen(), cfg.uiScalePercent())) col.addView(uiScaleCard(c), cardLp());

        apply = CarStyle.pill(c, Str.get(R.string.hql_apply_reconnect));
        apply.setBackground(CarStyle.accent(32));
        apply.setTextColor(CarStyle.ON_ACCENT);
        apply.setPadding(40, 22, 40, 22);
        apply.setOnClickListener(v -> applyProfile());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.bottomMargin = 20;
        col.addView(apply, alp);

        LinearLayout more = card(c, null);
        more.addView(toggle(c, Str.get(R.string.hql_low_latency_reconnect), null, cfg.lowLatency(),
                on -> cfg.putBool(Config.LOW_LATENCY, on)));
        col.addView(more, cardLp());

        TextView note = CarStyle.text(c, Str.get(R.string.hql_settings_reconnect_note), 20, CarStyle.TEXT_DIM);
        note.setPadding(8, 0, 8, 10);
        col.addView(note);
        TextView info = CarStyle.text(c, Str.get(R.string.hql_settings_info, LinkState.video.isEmpty() ? "—" : LinkState.video, VideoProfile.reason()), 20, CarStyle.TEXT_DIM);
        info.setPadding(8, 0, 8, 0);
        col.addView(info);
        refresh();
    }

    /** Píldora de una línea; con la interfaz reducida (texto más grande), hasta dos para que no se corte. */
    private static void twoLinesIfScaled(TextView p) {
        if (CarStyle.scaled()) {
            p.setMaxLines(2);
            p.setEllipsize(android.text.TextUtils.TruncateAt.END);
        } else {
            p.setSingleLine(true);
        }
    }

    /** Puro: la fila «Tamaño de la interfaz» se ve en una pantalla pequeña o si hay un tamaño elegido a mano. */
    static boolean showUiScale(boolean smallScreen, int percent) {
        return smallScreen || percent != 0;
    }

    /** Puro: el texto de cada opción de «Tamaño de la interfaz» (0: «Automático»; si no, «70 %»). */
    static String uiScaleLabel(int percent, String auto) {
        return percent == 0 ? auto : percent + " %";
    }

    /**
     * «Tamaño de la interfaz»: a qué escala se dibuja nuestra interfaz en la pantalla del coche (Automático: la que cabe;
     * más %: todo más grande y cabe menos). Se guarda con «Aplicar y reconectar».
     */
    private View uiScaleCard(Context c) {
        LinearLayout card = card(c, Str.get(R.string.hql_carui_size));
        GridLayout grid = new GridLayout(c);
        // Seis opciones: en la pantalla pequeña no caben en una fila con el texto más grande, en dos de tres.
        grid.setColumnCount(CarStyle.scaled() ? 3 : UI_SCALE_CHOICES.length);
        grid.setPadding(0, 14, 0, 4);
        for (int pct : UI_SCALE_CHOICES) {
            TextView p = CarStyle.pill(c, uiScaleLabel(pct, Str.get(R.string.hql_profile_auto)));
            p.setTag(pct);
            p.setPadding(16, 22, 16, 22);
            p.setSingleLine(true);
            p.setOnClickListener(v -> {
                pendingUiScale = pct;
                refresh();
            });
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            glp.width = 0;
            glp.setMargins(0, 0, 14, 14);
            uiScalePills.add(p);
            grid.addView(p, glp);
        }
        card.addView(grid);
        TextView note = CarStyle.text(c, Str.get(R.string.hql_carui_size_note), 20, CarStyle.TEXT_DIM);
        note.setPadding(4, 4, 0, 0);
        card.addView(note);
        return card;
    }

    // ------------------------------------------------------------------ Panel lateral

    private void buildPanel(Context c, LinearLayout col) {
        col.addView(themeCard(c), cardLp());
        LinearLayout ui = card(c, Str.get(R.string.hql_settings_behaviour));
        ui.addView(toggle(c, Str.get(R.string.hql_autohide_panel), null, cfg.panelAutoHide(), on -> {
            cfg.putBool(Config.PANEL_AUTOHIDE, on);
            CarUi.applyAutoHide(on);
        }));
        ui.addView(toggle(c, Str.get(R.string.hql_panel_right), null, cfg.panelRight(), on -> {
            cfg.putBool(Config.PANEL_RIGHT, on);
            CarUi.applyPanelRight(on);
        }));
        col.addView(ui, cardLp());

        LinearLayout color = card(c, Str.get(R.string.hql_panel_color));
        color.addView(panelColorRow(c));
        col.addView(color, cardLp());

        LinearLayout buttons = card(c, Str.get(R.string.hql_panel_buttons));
        buttons.addView(panelButtonsRows(c));
        col.addView(buttons, cardLp());
    }

    /**
     * «Tema»: Automático (como la pantalla del coche o el sensor de luz), Siempre claro o Siempre oscuro, para Android Auto,
     * el panel y estas pantallas. Al cambiar el día o la noche, la interfaz se rehace sola (CarUi.applyNight).
     */
    private View themeCard(Context c) {
        LinearLayout card = card(c, Str.get(R.string.hql_theme));
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 14, 0, 4);
        for (int t = 0; t < 3; t++) {
            int theme = t;
            TextView p = CarStyle.pill(c, SettingsActivity.themeLabel(t));
            p.setTag(t);
            p.setPadding(16, 22, 16, 22);
            twoLinesIfScaled(p);
            p.setGravity(Gravity.CENTER);
            p.setOnClickListener(v -> {
                if (cfg.carTheme() == theme) return;
                cfg.putInt(Config.CAR_THEME, theme);
                L.i("tema de la pantalla del coche: " + (theme == 0 ? "automático" : theme == 1 ? "siempre claro" : "siempre oscuro") + " (desde el coche)");
                AaPassthroughSource.applyCarTheme(c);
                refresh();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (t < 2) lp.rightMargin = 14;
            themePills.add(p);
            row.addView(p, lp);
        }
        card.addView(row);
        TextView note = CarStyle.text(c, Str.get(R.string.hql_theme_help), 20, CarStyle.TEXT_DIM);
        note.setPadding(4, 4, 0, 0);
        card.addView(note);
        return card;
    }

    // ------------------------------------------------------------------ Viaje

    private void buildTrip(Context c, LinearLayout col) {
        LinearLayout alerts = card(c, null);
        alerts.addView(toggle(c, Str.get(R.string.hql_radar_voice), null, cfg.radarVoice(), on -> {
            cfg.putBool(Config.RADAR_VOICE, on);
            refreshSummaries();
        }));
        alerts.addView(divider(c));
        alerts.addView(toggle(c, Str.get(R.string.hql_float_card), Str.get(R.string.hql_float_card_help), cfg.floatCard(), on -> {
            cfg.putBool(Config.FLOAT_CARD, on);
            CarUi.applyFloatCard();
        }));
        alerts.addView(divider(c));
        alerts.addView(toggle(c, Str.get(R.string.hql_maps_planner), Str.get(R.string.hql_maps_planner_help), cfg.mapsPlanner(), on -> {
            cfg.putBool(Config.MAPS_PLANNER, on);
            L.i("planificador desde Google Maps: " + (on ? "sí" : "no"));
        }));
        // Solo en el coche virtual: día o noche a mano, para probar el cambio de tema con las tarjetas en pantalla.
        if (RoutePlanner.testMode()) {
            alerts.addView(divider(c));
            alerts.addView(toggle(c, Str.get(R.string.hql_sim_night), Str.get(R.string.hql_sim_night_help), CarTheme.night(),
                    on -> AaPassthroughSource.simNight(c, on)));
        }
        alerts.addView(divider(c));
        alerts.addView(toggle(c, Str.get(R.string.hql_trip_notice), Str.get(R.string.hql_trip_notice_help), cfg.tripNotice(),
                on -> cfg.putBool("trip_notice", on)));
        col.addView(alerts, cardLp());

        // Energía: €/kWh para el coste de los viajes y la pestaña Eficiencia.
        LinearLayout energy = card(c, Str.get(R.string.hql_settings_energy));
        priceRow(c, energy, Str.get(R.string.hql_settings_price_kwh), Str.get(R.string.hql_settings_price_help),
                cfg::electricityPrice, cfg::setElectricityPrice);
        energy.addView(divider(c));
        // La carga rápida, para lo que cuesta el plan de carga de la Ruta.
        priceRow(c, energy, Str.get(R.string.hql_settings_price_fast), Str.get(R.string.hql_settings_price_fast_help),
                cfg::fastChargePrice, cfg::setFastChargePrice);
        col.addView(energy, cardLp());
    }

    /** Un precio (€/kWh) con − y + de céntimo en céntimo, y su explicación debajo. */
    private void priceRow(Context c, LinearLayout card, String label, String help, java.util.function.DoubleSupplier get,
                          java.util.function.DoubleConsumer set) {
        LinearLayout priceRow = new LinearLayout(c);
        priceRow.setOrientation(LinearLayout.HORIZONTAL);
        priceRow.setGravity(Gravity.CENTER_VERTICAL);
        priceRow.setPadding(0, 16, 0, 4);
        TextView priceLabel = CarStyle.text(c, label, 24, CarStyle.TEXT);
        priceRow.addView(priceLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView minus = CarStyle.pill(c, "−");
        TextView value = CarStyle.text(c, "", 32, CarStyle.TEXT);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(210);
        TextView plus = CarStyle.pill(c, "+");
        minus.setMinWidth(90);
        plus.setMinWidth(90);
        minus.setPadding(26, 18, 26, 18);
        plus.setPadding(26, 18, 26, 18);
        priceRow.addView(minus);
        priceRow.addView(value);
        priceRow.addView(plus);
        Runnable showPrice = () -> {
            value.setText(Money.per(get.getAsDouble(), "kWh"));
            refreshSummaries();
        };
        showPrice.run();
        minus.setOnClickListener(v -> {
            set.accept(get.getAsDouble() - 0.01);
            showPrice.run();
        });
        plus.setOnClickListener(v -> {
            set.accept(get.getAsDouble() + 0.01);
            showPrice.run();
        });
        card.addView(priceRow);
        TextView priceHelp = CarStyle.text(c, help, 20, CarStyle.TEXT_DIM);
        priceHelp.setPadding(4, 8, 0, 0);
        card.addView(priceHelp);
    }

    // ------------------------------------------------------------------ Conexión

    /** Solo lectura: modo, motor, coche e interfaz local (qdauto §5.2). */
    private void buildConnection(Context c, LinearLayout col) {
        LinearLayout conn = card(c, null);
        // La del servicio en marcha, no la configurada (un cambio en marcha se aplica al volver a conectar).
        String engineName = Config.ENGINE_QDAUTO.equals(LinkState.engineFor(cfg)) ? Str.get(R.string.hql_engine_qdauto) : Str.get(R.string.hql_engine_original);
        TextView connLine = CarStyle.text(c, Str.get(R.string.hql_settings_link_line, Ui.linkTitle(LinkState.linkModeFor(cfg)), engineName), 24, CarStyle.TEXT);
        connLine.setPadding(4, 4, 0, 0);
        conn.addView(connLine);
        String det = LinkState.linkDetail;
        String net = LinkState.network;
        TextView connDetail = CarStyle.text(c, (det.isEmpty() ? "" : Str.get(R.string.hql_settings_car_line, det))
                + (!det.isEmpty() && !net.isEmpty() ? "\n" : "") + net, 21, CarStyle.TEXT_DIM);
        connDetail.setPadding(4, 10, 0, 0);
        if (det.isEmpty() && net.isEmpty()) connDetail.setVisibility(View.GONE);
        conn.addView(connDetail);
        col.addView(conn, cardLp());
        TextView info = CarStyle.text(c, Str.get(R.string.hql_settings_info, LinkState.video.isEmpty() ? "—" : LinkState.video, VideoProfile.reason()), 20, CarStyle.TEXT_DIM);
        info.setPadding(8, 0, 8, 0);
        col.addView(info);
    }

    /**
     * Color de la barra: «Automático» (oscura de noche y clara de día, como el coche) o uno de los 16 fijos
     * (CarTheme.PANEL_COLORS). Se aplica al momento: la interfaz se rehace y esta pantalla vuelve con el elegido marcado.
     */
    private View panelColorRow(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 16, 0, 4);
        int fixed = cfg.panelFixedColor();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView auto = CarStyle.pill(c, Str.get(R.string.hql_panel_color_auto));
        auto.setPadding(30, 16, 30, 16);
        auto.setBackground(CarStyle.round(fixed == 0 ? CarStyle.ACCENT_BG : CarStyle.PILL_BG, 28));
        auto.setOnClickListener(v -> setPanelColor(0));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.rightMargin = 18;
        row.addView(auto, alp);
        for (int pc : CarTheme.PANEL_COLORS) {
            TextView sw = new TextView(c);
            sw.setGravity(Gravity.CENTER);
            sw.setText(pc == CarTheme.PANEL_DAY_SWATCH ? "D" : pc == CarTheme.PANEL_NIGHT_SWATCH ? "N" : "");
            sw.setTextSize(20);
            sw.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            sw.setTextColor(CarTheme.isDark(pc) ? 0xFFFFFFFF : 0xFF22282E);
            sw.setContentDescription(pc == CarTheme.PANEL_DAY_SWATCH ? Str.get(R.string.hql_panel_day)
                    : pc == CarTheme.PANEL_NIGHT_SWATCH ? Str.get(R.string.hql_panel_night)
                    : String.format(java.util.Locale.ROOT, "#%06X", pc & 0xFFFFFF));
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(pc);
            boolean sel = pc == fixed;
            // El elegido, con un aro del acento; el resto, con un borde fino (para ver el negro y el blanco).
            d.setStroke(sel ? 7 : 2, sel ? CarStyle.ACCENT : CarKit.EDGE);
            sw.setBackground(d);
            sw.setOnClickListener(v -> setPanelColor(pc));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(CarStyle.touch(60), CarStyle.touch(60));
            slp.rightMargin = 12;
            row.addView(sw, slp);
        }
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        box.addView(scroll);
        TextView help = CarStyle.text(c, Str.get(R.string.hql_panel_color_help), 20, CarStyle.TEXT_DIM);
        help.setPadding(6, 10, 0, 0);
        box.addView(help);
        // Cualquier otro color: tono y claridad en dos barras; y la transparencia (fundido con el negro de la pantalla).
        float[] hsv = new float[]{200f, 0.72f, 0.55f};
        if (fixed != 0) android.graphics.Color.colorToHSV(fixed, hsv);
        float[] pick = {hsv[0], hsv[2]};
        int[] hues = new int[13];
        for (int i = 0; i < hues.length; i++) hues[i] = CarTheme.fromHue(i * 30f, 0.55f);
        box.addView(strip(c, Str.get(R.string.hql_panel_hue), hues, pick[0] / 360f, f -> {
            pick[0] = f * 360f;
            setPanelColor(CarTheme.fromHue(pick[0], pick[1]));
        }));
        int[] lights = {CarTheme.fromHue(pick[0], 0.12f), CarTheme.fromHue(pick[0], 0.55f), CarTheme.fromHue(pick[0], 1f)};
        box.addView(strip(c, Str.get(R.string.hql_panel_light), lights, pick[1], f -> {
            pick[1] = f;
            setPanelColor(CarTheme.fromHue(pick[0], pick[1]));
        }));
        int[] alphas = {0xFF9AA0A6, 0xFF000000};
        box.addView(strip(c, Str.get(R.string.hql_panel_alpha), alphas, cfg.panelAlpha() / 100f, f -> {
            int pct = Math.round(f * 100);
            cfg.setPanelAlpha(pct);
            CarUi.applyPanelAlpha(pct);
        }));
        TextView ahelp = CarStyle.text(c, Str.get(R.string.hql_panel_alpha_help), 20, CarStyle.TEXT_DIM);
        ahelp.setPadding(6, 6, 0, 0);
        box.addView(ahelp);
        return box;
    }

    /** Barra de degradado con un marcador; al tocar o arrastrar, da la posición (0-1). */
    private static View strip(Context c, String label, int[] colors, float pos, java.util.function.Consumer<Float> onPick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 16, 0, 0);
        TextView t = CarStyle.text(c, label, 22, CarStyle.TEXT);
        t.setMinWidth(230);
        row.addView(t);
        View bar = new View(c) {
            float p = pos;

            @Override
            protected void onDraw(android.graphics.Canvas cv) {
                android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                float h = getHeight();
                android.graphics.RectF r = new android.graphics.RectF(0, h / 2 - 14, getWidth(), h / 2 + 14);
                pt.setShader(new android.graphics.LinearGradient(0, 0, getWidth(), 0, colors, null,
                        android.graphics.Shader.TileMode.CLAMP));
                cv.drawRoundRect(r, 14, 14, pt);
                pt.setShader(null);
                float x = p * getWidth();
                pt.setColor(0xFFFFFFFF);
                cv.drawCircle(x, h / 2, 20, pt);
                pt.setColor(CarStyle.ACCENT);
                cv.drawCircle(x, h / 2, 15, pt);
            }

            float downX;
            float downY;
            boolean dragging;

            @Override
            public boolean onTouchEvent(android.view.MotionEvent e) {
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        dragging = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        // Arrastre horizontal: la barra; vertical: lo deja pasar al ScrollView (que cancela este toque).
                        if (!dragging && Math.abs(e.getX() - downX) > 24 && Math.abs(e.getX() - downX) > Math.abs(e.getY() - downY)) {
                            dragging = true;
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        if (dragging) {
                            p = Math.max(0f, Math.min(1f, e.getX() / getWidth()));
                            invalidate();
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        // Toque suelto o fin del arrastre: se aplica.
                        if (!dragging && Math.abs(e.getY() - downY) > 40) return true;
                        p = Math.max(0f, Math.min(1f, e.getX() / getWidth()));
                        invalidate();
                        onPick.accept(p);
                        return true;
                    default:
                        return super.onTouchEvent(e);
                }
            }
        };
        row.addView(bar, new LinearLayout.LayoutParams(0, CarStyle.touch(60), 1f));
        return row;
    }

    /**
     * Botones del panel: una fila por botón con su interruptor (se ve o no) y flechas para ordenarlos. «Auto» y
     * «Ajustes» no se tocan. Se aplica al momento.
     */
    private View panelButtonsRows(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 6, 0, 0);
        java.util.List<String> shown = cfg.panelButtons();
        // Los que se ven, en su orden, y después los ocultos en el orden de siempre.
        java.util.List<String> all = new java.util.ArrayList<>(shown);
        for (String id : Config.PANEL_BUTTONS_ALL) if (!all.contains(id)) all.add(id);
        for (int i = 0; i < all.size(); i++) {
            String id = all.get(i);
            boolean on = shown.contains(id);
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, 10, 0, 10);
            TextView t = CarStyle.text(c, CarUi.navLabel(id), 24, on ? CarStyle.TEXT : CarStyle.TEXT_DIM);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            int pos = shown.indexOf(id);
            TextView up = CarStyle.pill(c, "▲");
            up.setMinWidth(84);
            up.setAlpha(on && pos > 0 ? 1f : 0.25f);
            up.setOnClickListener(v -> moveButton(id, -1));
            r.addView(up);
            TextView down = CarStyle.pill(c, "▼");
            down.setMinWidth(84);
            down.setAlpha(on && pos < shown.size() - 1 ? 1f : 0.25f);
            down.setOnClickListener(v -> moveButton(id, 1));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.rightMargin = 26;
            r.addView(down, dlp);
            // El interruptor, en una caja del alto táctil mínimo (con la interfaz reducida, más alta que él).
            android.widget.FrameLayout sw = new android.widget.FrameLayout(c);
            sw.addView(switchView(c, on), new android.widget.FrameLayout.LayoutParams(96, 56, Gravity.CENTER));
            sw.setOnClickListener(v -> {
                java.util.List<String> ids = cfg.panelButtons();
                if (ids.contains(id)) ids.remove(id);
                else ids.add(id);
                cfg.setPanelButtons(ids);
                CarUi.applyPanelButtons();
            });
            r.addView(sw, new LinearLayout.LayoutParams(96, CarStyle.touch(56)));
            if (i > 0) box.addView(divider(c));
            box.addView(r);
        }
        return box;
    }

    private void moveButton(String id, int dir) {
        java.util.List<String> ids = cfg.panelButtons();
        int i = ids.indexOf(id);
        int j = i + dir;
        if (i < 0 || j < 0 || j >= ids.size()) return;
        ids.remove(i);
        ids.add(j, id);
        cfg.setPanelButtons(ids);
        CarUi.applyPanelButtons();
    }

    private void setPanelColor(int color) {
        if (color == cfg.panelFixedColor()) return;
        cfg.setPanelFixedColor(color);
        CarUi.applyPanelFixed(color);
    }

    private static LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 20;
        return lp;
    }

    /** Tarjeta con su título (o sin él). */
    private static LinearLayout card(Context c, String title) {
        LinearLayout card = CarStyle.card(c);
        card.setPadding(32, 26, 32, 22);
        if (title != null) {
            TextView t = CarStyle.text(c, title, 27, CarStyle.TEXT);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            card.addView(t);
        }
        return card;
    }

    private static View divider(Context c) {
        View d = new View(c);
        d.setBackgroundColor(CarKit.alpha(CarStyle.TEXT_DIM, 0.18f));
        d.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return d;
    }

    /** Interruptor como los de Android Auto: pista redondeada y botón; con el acento cuando está encendido. */
    private static View switchView(Context c, boolean on) {
        android.widget.FrameLayout sw = new android.widget.FrameLayout(c);
        sw.setBackground(CarStyle.round(on ? CarStyle.ACCENT : CarStyle.PILL_BG, 28));
        View thumb = new View(c);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(on ? CarStyle.ON_ACCENT : CarStyle.TEXT_DIM);
        thumb.setBackground(d);
        android.widget.FrameLayout.LayoutParams tlp = new android.widget.FrameLayout.LayoutParams(40, 40,
                Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START));
        tlp.setMargins(8, 0, 8, 0);
        sw.addView(thumb, tlp);
        sw.setContentDescription(Str.get(on ? R.string.hql_on_yes : R.string.hql_on_no));
        return sw;
    }

    /** Fila con texto (y una línea de ayuda) y un interruptor; se toca la fila entera. */
    private static View toggle(Context c, String label, String help, boolean initial, java.util.function.Consumer<Boolean> onChange) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, 18, 0, 18);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(CarStyle.text(c, label, 24, CarStyle.TEXT));
        if (help != null) {
            TextView h = CarStyle.text(c, help, 20, CarStyle.TEXT_DIM);
            h.setPadding(0, 4, 0, 0);
            texts.addView(h);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.rightMargin = 24;
        r.addView(texts, tlp);
        boolean[] on = {initial};
        android.widget.FrameLayout holder = new android.widget.FrameLayout(c);
        holder.addView(switchView(c, on[0]), new android.widget.FrameLayout.LayoutParams(96, 56));
        r.addView(holder, new LinearLayout.LayoutParams(96, 56));
        r.setOnClickListener(v -> {
            on[0] = !on[0];
            holder.removeAllViews();
            holder.addView(switchView(c, on[0]), new android.widget.FrameLayout.LayoutParams(96, 56));
            onChange.accept(on[0]);
        });
        return r;
    }

    private void select(String id) {
        pending = id;
        refresh();
    }

    private void refresh() {
        if (detail == null || apply == null) return;
        for (TextView p : profilePills) {
            boolean sel = pending.equals(p.getTag());
            p.setBackground(sel ? CarKit.outlined(CarStyle.ACCENT_BG, CarStyle.ACCENT, 28) : CarStyle.round(CarStyle.PILL_BG, 28));
            p.setTextColor(sel ? CarStyle.ACCENT : CarStyle.TEXT);
        }
        String eff = pending.isEmpty() ? VideoProfile.recommended(host.context()) : pending;
        detail.setText((pending.isEmpty() ? Str.get(R.string.hql_follows_recommended, VideoProfile.title(eff)) + " · " : "")
                + VideoProfile.detail(eff));
        // La fluidez solo cuenta en Coche: con otro perfil, las píldoras se atenúan.
        boolean fluidCounts = VideoProfile.of(eff).followsCar;
        for (TextView p : fluidPills) {
            boolean sel = pendingFluid == (int) p.getTag();
            p.setBackground(sel ? CarKit.outlined(CarStyle.ACCENT_BG, CarStyle.ACCENT, 28) : CarStyle.round(CarStyle.PILL_BG, 28));
            p.setTextColor(sel ? CarStyle.ACCENT : CarStyle.TEXT);
            p.setAlpha(fluidCounts ? 1f : 0.5f);
        }
        for (TextView p : sizePills) {
            boolean sel = pendingAaSize == (int) p.getTag();
            p.setBackground(sel ? CarKit.outlined(CarStyle.ACCENT_BG, CarStyle.ACCENT, 28) : CarStyle.round(CarStyle.PILL_BG, 28));
            p.setTextColor(sel ? CarStyle.ACCENT : CarStyle.TEXT);
        }
        for (TextView p : themePills) {
            boolean sel = cfg.carTheme() == (int) p.getTag();
            p.setBackground(sel ? CarKit.outlined(CarStyle.ACCENT_BG, CarStyle.ACCENT, 28) : CarStyle.round(CarStyle.PILL_BG, 28));
            p.setTextColor(sel ? CarStyle.ACCENT : CarStyle.TEXT);
        }
        for (TextView p : uiScalePills) {
            boolean sel = pendingUiScale == (int) p.getTag();
            p.setBackground(sel ? CarKit.outlined(CarStyle.ACCENT_BG, CarStyle.ACCENT, 28) : CarStyle.round(CarStyle.PILL_BG, 28));
            p.setTextColor(sel ? CarStyle.ACCENT : CarStyle.TEXT);
        }
        boolean changed = !pending.equals(cfg.videoProfileChoice()) || pendingFluid != cfg.fluidity()
                || pendingAaSize != cfg.aaSizePercent() || pendingUiScale != cfg.uiScalePercent();
        apply.setVisibility(changed ? View.VISIBLE : View.GONE);
    }

    private void applyProfile() {
        Context c = host.context();
        VideoProfile before = cfg.videoProfile();
        cfg.setVideoProfile(pending);
        if (pendingFluid != cfg.fluidity()) {
            cfg.setFluidity(pendingFluid);
            L.i("fluidez: " + pendingFluid + " fps (desde el coche; reconecta ahora)");
        }
        boolean sizeChanged = pendingAaSize != cfg.aaSizePercent();
        if (sizeChanged) {
            cfg.setAaSizePercent(pendingAaSize);
            L.i("tamaño de Android Auto: " + pendingAaSize + " % (desde el coche; reconecta ahora)");
        }
        // El tamaño de nuestra interfaz se toma al arrancar el vídeo (AaPassthroughSource.start): basta con reconectar; AA
        // recibe los márgenes nuevos del panel sin volver a negociar.
        if (pendingUiScale != cfg.uiScalePercent()) {
            cfg.setUiScalePercent(pendingUiScale);
            L.i("tamaño de la interfaz: " + (pendingUiScale == 0 ? "automático" : pendingUiScale + " %") + " (desde el coche; reconecta ahora)");
        }
        VideoProfile after = cfg.videoProfile();
        // Otro perfil, u otros fps (fluidez con Coche), u otro tamaño: AA vuelve a negociar resolución, fps y densidad.
        boolean renegotiate = !before.id.equals(after.id) || before.fps != after.fps || sizeChanged;
        L.i("ajustes desde el coche: perfil " + (pending.isEmpty() ? "automático" : pending) + " · fluidez " + pendingFluid
                + (renegotiate ? " (reconecta AA)" : ""));
        if (DemoMode.active()) {
            // Vista previa: el ajuste queda guardado, pero no hay sesión que reconectar (ni se arranca el enlace).
            refresh();
            return;
        }
        apply.setText(Str.get(R.string.hql_reconnecting_short));
        apply.setEnabled(false);
        // La sesión se cierra (y con ella esta pantalla); el coche vuelve a conectar solo.
        host.post(() -> c.startForegroundService(LinkService.fromApp(new Intent(c, LinkService.class)
                .setAction(LinkService.ACTION_APPLY).putExtra(LinkService.EXTRA_AA_RENEGOTIATE, renegotiate))));
    }
}
