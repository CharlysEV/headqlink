package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Ajustes desde el coche: perfil de imagen (con el recomendado marcado), panel que se oculta solo,
 * optimizaciones de latencia y el precio de la electricidad (coste de los viajes en la sección Coche). Lo mismo que
 * en el móvil (Ajustes de imagen), sin tener que cogerlo.
 * Cambiar de perfil reconecta coche y Android Auto (unos segundos).
 */
final class SettingsScreen implements CarScreen {
    private Host host;
    private Config cfg;
    private String pending;
    /** Fluidez pendiente de aplicar (VideoProfile.FLUID_30 o FLUID_60). */
    private int pendingFluid;
    private TextView apply;
    private TextView detail;
    private final java.util.List<TextView> profilePills = new java.util.ArrayList<>();
    private final java.util.List<TextView> fluidPills = new java.util.ArrayList<>();

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        cfg = new Config(c);
        String rec = VideoProfile.recommended(c);
        pending = cfg.videoProfileChoice();
        pendingFluid = cfg.fluidity();

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(40, 30, 40, 30);

        // Imagen.
        LinearLayout img = CarStyle.card(c);
        img.addView(title(c, Str.get(R.string.hql_image)));
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 18, 0, 8);
        String[] ids = new String[VideoProfile.ALL.length + 1];
        ids[0] = "";
        System.arraycopy(VideoProfile.ALL, 0, ids, 1, VideoProfile.ALL.length);
        for (String id : ids) {
            String name = id.isEmpty() ? Str.get(R.string.hql_profile_auto) : VideoProfile.title(id);
            if (rec.equals(id)) name += "  ★";
            TextView p = CarStyle.pill(c, name);
            p.setTag(id);
            p.setPadding(34, 18, 34, 18);
            p.setOnClickListener(v -> select(id));
            profilePills.add(p);
            row.addView(p);
        }
        // Con el perfil Coche ya son 7: si no caben, la fila se desplaza.
        android.widget.HorizontalScrollView rowScroll = new android.widget.HorizontalScrollView(c);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.addView(row);
        img.addView(rowScroll);
        detail = CarStyle.text(c, "", 22, CarStyle.TEXT_DIM);
        detail.setPadding(6, 6, 0, 0);
        img.addView(detail);
        // Fluidez: 30 fps (lo que pide el coche, menos calor) o 60; solo cuenta con Coche (y Automático si es Coche).
        LinearLayout fluidRow = new LinearLayout(c);
        fluidRow.setOrientation(LinearLayout.HORIZONTAL);
        fluidRow.setGravity(Gravity.CENTER_VERTICAL);
        fluidRow.setPadding(0, 14, 0, 4);
        TextView fluidLabel = CarStyle.text(c, Str.get(R.string.hql_fluidity), 24, CarStyle.TEXT);
        fluidLabel.setPadding(6, 0, 20, 0);
        fluidRow.addView(fluidLabel);
        for (int f : new int[]{VideoProfile.FLUID_30, VideoProfile.FLUID_60}) {
            TextView p = CarStyle.pill(c, Str.get(f == VideoProfile.FLUID_60 ? R.string.hql_fluidity_60 : R.string.hql_fluidity_30));
            p.setTag(f);
            p.setPadding(34, 18, 34, 18);
            p.setOnClickListener(v -> {
                pendingFluid = f;
                refresh();
            });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.rightMargin = 12;
            fluidPills.add(p);
            fluidRow.addView(p, plp);
        }
        android.widget.HorizontalScrollView fluidScroll = new android.widget.HorizontalScrollView(c);
        fluidScroll.setHorizontalScrollBarEnabled(false);
        fluidScroll.addView(fluidRow);
        img.addView(fluidScroll);
        apply = CarStyle.pill(c, Str.get(R.string.hql_apply_reconnect));
        apply.setBackground(CarStyle.accent(28));
        apply.setTextColor(CarStyle.ON_ACCENT);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = 16;
        img.addView(apply, alp);
        apply.setOnClickListener(v -> applyProfile());
        col.addView(img, cardLp());

        // Panel e interacción.
        LinearLayout ui = CarStyle.card(c);
        ui.addView(title(c, Str.get(R.string.hql_screen)));
        ui.addView(toggle(c, Str.get(R.string.hql_autohide_panel), cfg.panelAutoHide(), on -> {
            cfg.putBool(Config.PANEL_AUTOHIDE, on);
            CarUi.applyAutoHide(on);
        }));
        ui.addView(toggle(c, Str.get(R.string.hql_low_latency_reconnect), cfg.lowLatency(),
                on -> cfg.putBool(Config.LOW_LATENCY, on)));
        col.addView(ui, cardLp());

        // Energía: €/kWh para el coste de los viajes y la pestaña Eficiencia.
        LinearLayout energy = CarStyle.card(c);
        energy.addView(title(c, Str.get(R.string.hql_settings_energy)));
        LinearLayout priceRow = new LinearLayout(c);
        priceRow.setOrientation(LinearLayout.HORIZONTAL);
        priceRow.setGravity(Gravity.CENTER_VERTICAL);
        priceRow.setPadding(0, 16, 0, 4);
        TextView priceLabel = CarStyle.text(c, Str.get(R.string.hql_settings_price_kwh), 24, CarStyle.TEXT);
        priceRow.addView(priceLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView minus = CarStyle.pill(c, "−");
        TextView value = CarStyle.text(c, "", 30, CarStyle.TEXT);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(190);
        TextView plus = CarStyle.pill(c, "+");
        minus.setMinWidth(80);
        plus.setMinWidth(80);
        priceRow.addView(minus);
        priceRow.addView(value);
        priceRow.addView(plus);
        Runnable showPrice = () -> value.setText(String.format(java.util.Locale.getDefault(), "%.2f €/kWh", cfg.electricityPrice()));
        showPrice.run();
        minus.setOnClickListener(v -> {
            cfg.setElectricityPrice(cfg.electricityPrice() - 0.01);
            showPrice.run();
        });
        plus.setOnClickListener(v -> {
            cfg.setElectricityPrice(cfg.electricityPrice() + 0.01);
            showPrice.run();
        });
        energy.addView(priceRow);
        TextView priceHelp = CarStyle.text(c, Str.get(R.string.hql_settings_price_help), 20, CarStyle.TEXT_DIM);
        priceHelp.setPadding(6, 6, 0, 0);
        energy.addView(priceHelp);
        col.addView(energy, cardLp());

        // Conexión (solo lectura): modo, motor, coche e interfaz local (qdauto §5.2).
        LinearLayout conn = CarStyle.card(c);
        conn.addView(title(c, Str.get(R.string.hql_settings_connection)));
        // La del servicio en marcha, no la configurada (un cambio en marcha se aplica al volver a conectar).
        String engineName = Config.ENGINE_QDAUTO.equals(LinkState.engineFor(cfg)) ? Str.get(R.string.hql_engine_qdauto) : Str.get(R.string.hql_engine_original);
        TextView connLine = CarStyle.text(c, Str.get(R.string.hql_settings_link_line, Ui.linkTitle(LinkState.linkModeFor(cfg)), engineName), 22, CarStyle.TEXT);
        connLine.setPadding(6, 14, 0, 0);
        conn.addView(connLine);
        String detail = LinkState.linkDetail;
        String net = LinkState.network;
        TextView connDetail = CarStyle.text(c, (detail.isEmpty() ? "" : Str.get(R.string.hql_settings_car_line, detail))
                + (!detail.isEmpty() && !net.isEmpty() ? "\n" : "") + net, 20, CarStyle.TEXT_DIM);
        connDetail.setPadding(6, 6, 0, 0);
        if (detail.isEmpty() && net.isEmpty()) connDetail.setVisibility(View.GONE);
        conn.addView(connDetail);
        col.addView(conn, cardLp());

        TextView info = CarStyle.text(c, Str.get(R.string.hql_settings_info, LinkState.video.isEmpty() ? "—" : LinkState.video, VideoProfile.reason()), 20, CarStyle.TEXT_DIM);
        info.setPadding(8, 8, 8, 0);
        col.addView(info);

        refresh();
        ScrollView sv = new ScrollView(c);
        sv.addView(col);
        return sv;
    }

    private static LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 20;
        return lp;
    }

    private static TextView title(Context c, String s) {
        TextView t = CarStyle.text(c, s, 30, CarStyle.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** Fila con texto y un interruptor en píldora (Sí / No). */
    private static View toggle(Context c, String label, boolean initial, java.util.function.Consumer<Boolean> onChange) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, 16, 0, 4);
        TextView t = CarStyle.text(c, label, 24, CarStyle.TEXT);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sw = CarStyle.pill(c, "");
        boolean[] on = {initial};
        Runnable style = () -> {
            sw.setText(on[0] ? Str.get(R.string.hql_on_yes) : Str.get(R.string.hql_on_no));
            sw.setBackground(on[0] ? CarStyle.accent(28) : CarStyle.round(CarStyle.PILL_BG, 28));
            sw.setTextColor(on[0] ? CarStyle.ON_ACCENT : CarStyle.TEXT);
        };
        style.run();
        sw.setMinWidth(110);
        sw.setOnClickListener(v -> {
            on[0] = !on[0];
            style.run();
            onChange.accept(on[0]);
        });
        r.addView(sw);
        return r;
    }

    private void select(String id) {
        pending = id;
        refresh();
    }

    private void refresh() {
        for (TextView p : profilePills) {
            boolean sel = pending.equals(p.getTag());
            p.setBackground(CarStyle.round(sel ? CarStyle.ACCENT_BG : CarStyle.PILL_BG, 28));
        }
        String eff = pending.isEmpty() ? VideoProfile.recommended(host.context()) : pending;
        detail.setText((pending.isEmpty() ? Str.get(R.string.hql_follows_recommended, VideoProfile.title(eff)) + " · " : "")
                + VideoProfile.detail(eff));
        // La fluidez solo cuenta en Coche: con otro perfil, las píldoras se atenúan.
        boolean fluidCounts = VideoProfile.of(eff).followsCar;
        for (TextView p : fluidPills) {
            boolean sel = pendingFluid == (int) p.getTag();
            p.setBackground(CarStyle.round(sel ? CarStyle.ACCENT_BG : CarStyle.PILL_BG, 28));
            p.setAlpha(fluidCounts ? 1f : 0.5f);
        }
        boolean changed = !pending.equals(cfg.videoProfileChoice()) || pendingFluid != cfg.fluidity();
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
        VideoProfile after = cfg.videoProfile();
        // Otro perfil, u otros fps (fluidez con Coche): AA vuelve a negociar resolución y fps.
        boolean renegotiate = !before.id.equals(after.id) || before.fps != after.fps;
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
        host.post(() -> c.startForegroundService(new Intent(c, LinkService.class).setAction(LinkService.ACTION_APPLY)
                .putExtra(LinkService.EXTRA_AA_RENEGOTIATE, renegotiate)));
    }
}
