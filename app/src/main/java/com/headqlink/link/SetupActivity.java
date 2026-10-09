package com.headqlink.link;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Configuración inicial (y cambio de modo): bienvenida → qué ver en el coche → lo que necesita
 * ese modo ({@link Checklist}, la misma comprobación que la pantalla «Comprobación»). Cada requisito
 * muestra su estado y un botón para resolverlo.
 */
public class SetupActivity extends Activity {
    static final String EXTRA_STEP = "step";
    private static final int STEPS = 3;

    private Config cfg;
    private int step;
    private String mode;
    /** Conexión elegida (Config.LINK_P2P o LINK_HOTSPOT). */
    private String linkMode;
    private FrameLayout container;
    private LinearLayout steps;
    private TextView stepLabel;
    private MaterialButton back;
    private MaterialButton next;
    /** Comprobación de requisitos del paso 3 (la misma que la pantalla «Comprobación»). */
    private Checklist checklist;
    private boolean resumed;
    /** Ya se avisó de que la conexión nueva se aplica al volver a conectar. */
    private boolean warnedPending;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        cfg = new Config(this);
        mode = cfg.mode();
        linkMode = cfg.linkMode();
        setContentView(R.layout.hql_activity_setup);
        container = findViewById(R.id.hql_step_container);
        steps = findViewById(R.id.hql_steps);
        stepLabel = findViewById(R.id.hql_step_label);
        back = findViewById(R.id.hql_back);
        next = findViewById(R.id.hql_next);
        back.setOnClickListener(v -> show(step - 1));
        next.setOnClickListener(v -> onNext());
        checklist = new Checklist(this, () -> {
            if (step == 2) renderChecks();
        });
        for (int i = 0; i < STEPS; i++) {
            View bar = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(6), 1);
            if (i > 0) lp.setMarginStart(dp(8));
            steps.addView(bar, lp);
        }
        show(b != null ? b.getInt(EXTRA_STEP) : getIntent().getIntExtra(EXTRA_STEP, 0));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(EXTRA_STEP, step);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (step == 2) checklist.start();
    }

    @Override
    protected void onPause() {
        resumed = false;
        checklist.stop();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (step == 2) checklist.onPermissionsResult();
    }

    private void show(int s) {
        step = Math.max(0, Math.min(STEPS - 1, s));
        for (int i = 0; i < STEPS; i++) {
            steps.getChildAt(i).setBackgroundResource(i <= step ? R.drawable.hql_step_on : R.drawable.hql_step_off);
        }
        stepLabel.setText(Str.get(R.string.hql_setup_step, step + 1, STEPS));
        back.setVisibility(step == 0 ? View.INVISIBLE : View.VISIBLE);
        container.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        switch (step) {
            case 0:
                inf.inflate(R.layout.hql_setup_welcome, container, true);
                next.setText(Str.get(R.string.hql_setup_start));
                break;
            case 1:
                inf.inflate(R.layout.hql_setup_mode, container, true);
                renderModes();
                next.setText(Str.get(R.string.hql_continue));
                break;
            default:
                inf.inflate(R.layout.hql_setup_check, container, true);
                next.setText(Str.get(R.string.hql_finish));
        }
        if (step == 2) {
            checklist.refresh();
            if (resumed) checklist.start();
        } else {
            checklist.stop();
        }
    }

    private void onNext() {
        if (step < STEPS - 1) {
            if (step == 1) {
                cfg.setMode(mode);
                cfg.setLinkMode(linkMode);
                warnIfPending();
            }
            show(step + 1);
            return;
        }
        if (!checklist.blocking().isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(Str.get(R.string.hql_req_missing_title))
                    .setMessage(Str.get(R.string.hql_setup_missing_msg, checklist.blockingText()))
                    .setPositiveButton(Str.get(R.string.hql_finish_anyway), (d, w) -> finishSetup(true))
                    .setNegativeButton(Str.get(R.string.hql_back), null)
                    .show();
            return;
        }
        finishSetup(false);
    }

    /** anyway: terminado con algo obligatorio pendiente (ya avisado: la pantalla principal no vuelve a avisar al conectar). */
    private void finishSetup(boolean anyway) {
        cfg.setMode(mode);
        cfg.setLinkMode(linkMode);
        warnIfPending();
        cfg.setSetupDone(true);
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(HomeActivity.EXTRA_SKIP_CHECK, anyway));
        finish();
    }

    /** Con el servicio en marcha, otra conexión se aplica al volver a conectar: se dice una vez. */
    private void warnIfPending() {
        if (warnedPending || !LinkState.running || LinkState.activeLinkMode.isEmpty()
                || LinkState.activeLinkMode.equals(linkMode)) {
            return;
        }
        warnedPending = true;
        L.i("conexión: " + linkMode + " (se aplica al volver a conectar; ahora " + LinkState.activeLinkMode + ")");
        android.widget.Toast.makeText(this, Str.get(R.string.hql_applies_on_reconnect), android.widget.Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- paso 2: modo

    private void renderModes() {
        LinearLayout list = container.findViewById(R.id.hql_mode_options);
        // «Una app concreta» y «Diagnóstico» siguen disponibles por adb (extra mode), no en el menú.
        // Auto, el recomendado, primero; Auto extendido queda como opción.
        String[] modes = {Config.MODE_AA, Config.MODE_AA_EXT};
        if (!Config.isAa(mode)) mode = Config.MODE_AA;
        for (String m : modes) {
            View opt = LayoutInflater.from(this).inflate(R.layout.hql_mode_option, list, false);
            ((TextView) opt.findViewById(R.id.hql_opt_title)).setText(Ui.modeTitle(m));
            ((TextView) opt.findViewById(R.id.hql_opt_detail)).setText(Ui.modeDetail(m));
            if (Config.MODE_AA.equals(m)) {
                TextView tag = opt.findViewById(R.id.hql_opt_tag);
                tag.setText(Str.get(R.string.hql_recommended));
                tag.setVisibility(View.VISIBLE);
            }
            opt.setSelected(m.equals(mode));
            opt.setOnClickListener(v -> {
                mode = m;
                for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).setSelected(list.getChildAt(i) == v);
                // La conexión recomendada depende del modo.
                renderLinkOptions();
            });
            list.addView(opt);
        }
        renderLinkOptions();
    }

    /**
     * Conexión con el coche: la zona Wi-Fi del móvil (qdauto §5.2), Wi-Fi Direct o el cable USB. «Recomendado» según el
     * modo ({@link Config#recommendedLink}): el cable en Auto extendido y el punto de acceso en Auto.
     */
    private void renderLinkOptions() {
        LinearLayout list = container.findViewById(R.id.hql_link_options);
        if (list == null) return;
        list.removeAllViews();
        for (String lm : new String[]{Config.LINK_HOTSPOT, Config.LINK_P2P, Config.LINK_USB}) {
            View opt = LayoutInflater.from(this).inflate(R.layout.hql_mode_option, list, false);
            ((TextView) opt.findViewById(R.id.hql_opt_title)).setText(Ui.linkTitle(lm));
            ((TextView) opt.findViewById(R.id.hql_opt_detail)).setText(Ui.linkDetail(lm));
            if (Config.recommendedLink(mode).equals(lm)) {
                TextView tag = opt.findViewById(R.id.hql_opt_tag);
                tag.setText(Str.get(R.string.hql_recommended));
                tag.setVisibility(View.VISIBLE);
            }
            opt.setSelected(lm.equals(linkMode));
            opt.setOnClickListener(v -> {
                linkMode = lm;
                for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).setSelected(list.getChildAt(i) == v);
            });
            list.addView(opt);
        }
    }

    // ---------------------------------------------------------------- paso 3: requisitos

    /** Título y explicación del modo, y la comprobación de requisitos (la misma que la pantalla «Comprobación»). */
    private void renderChecks() {
        TextView title = container.findViewById(R.id.hql_check_title);
        if (title == null) return;
        TextView body = container.findViewById(R.id.hql_check_body);
        TextView summary = container.findViewById(R.id.hql_check_summary);
        TextView summaryDetail = container.findViewById(R.id.hql_check_summary_detail);
        LinearLayout list = container.findViewById(R.id.hql_check_list);
        TextView guideLabel = container.findViewById(R.id.hql_check_guide_label);
        TextView guide = container.findViewById(R.id.hql_check_guide);
        switch (mode) {
            case Config.MODE_AA:
            case Config.MODE_AA_EXT:
                title.setText(Config.MODE_AA_EXT.equals(mode) ? Str.get(R.string.hql_setup_prepare_ext) : Str.get(R.string.hql_setup_prepare));
                body.setText(Str.get(R.string.hql_setup_aa_body));
                break;
            case Config.MODE_APP:
                title.setText(Str.get(R.string.hql_setup_pick_app));
                body.setText(Str.get(R.string.hql_setup_app_body));
                break;
            default:
                title.setText(Str.get(R.string.hql_setup_diag));
                body.setText(Str.get(R.string.hql_setup_diag_body));
        }
        summary.setVisibility(View.VISIBLE);
        summary.setText(checklist.summaryTitle());
        summary.setTextColor(checklist.summaryColor());
        summaryDetail.setVisibility(View.VISIBLE);
        summaryDetail.setText(checklist.summaryDetail());
        checklist.render(list);
        String gt = checklist.guideTitle();
        guideLabel.setVisibility(gt != null ? View.VISIBLE : View.GONE);
        guide.setVisibility(gt != null ? View.VISIBLE : View.GONE);
        if (gt != null) {
            guideLabel.setText(gt);
            guide.setText(checklist.guideText());
        }
    }


    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
