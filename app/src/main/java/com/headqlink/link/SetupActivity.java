package com.headqlink.link;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Configuración inicial (y cambio de modo): bienvenida → qué ver en el coche → lo que necesita
 * ese modo. Cada requisito muestra su estado y un botón para resolverlo.
 */
public class SetupActivity extends Activity {
    static final String EXTRA_STEP = "step";
    private static final int STEPS = 3;

    private Config cfg;
    private int step;
    private String mode;
    private FrameLayout container;
    private LinearLayout steps;
    private TextView stepLabel;
    private MaterialButton back;
    private MaterialButton next;
    /** El usuario fue a los ajustes de AA a activar el modo desarrollador: comprobar al volver. */
    private boolean awaitingDevMode;
    private boolean checkingDevMode;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        cfg = new Config(this);
        mode = cfg.mode();
        setContentView(R.layout.hql_activity_setup);
        container = findViewById(R.id.hql_step_container);
        steps = findViewById(R.id.hql_steps);
        stepLabel = findViewById(R.id.hql_step_label);
        back = findViewById(R.id.hql_back);
        next = findViewById(R.id.hql_next);
        back.setOnClickListener(v -> show(step - 1));
        next.setOnClickListener(v -> onNext());
        for (int i = 0; i < STEPS; i++) {
            View bar = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(4), 1);
            if (i > 0) lp.setMarginStart(dp(6));
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
        if (step == 2) {
            if (awaitingDevMode) {
                awaitingDevMode = false;
                checkDevMode();
            }
            renderChecks();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (step == 2) renderChecks();
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
                renderChecks();
        }
    }

    private void onNext() {
        if (step < STEPS - 1) {
            if (step == 1) cfg.setMode(mode);
            show(step + 1);
            return;
        }
        if (missingRequired() != null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(Str.get(R.string.hql_setup_missing_title))
                    .setMessage(Str.get(R.string.hql_setup_missing_msg, missingRequired()))
                    .setPositiveButton(Str.get(R.string.hql_finish_anyway), (d, w) -> finishSetup())
                    .setNegativeButton(Str.get(R.string.hql_back), null)
                    .show();
            return;
        }
        finishSetup();
    }

    private void finishSetup() {
        cfg.setMode(mode);
        cfg.setSetupDone(true);
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    // ---------------------------------------------------------------- paso 2: modo

    private void renderModes() {
        LinearLayout list = container.findViewById(R.id.hql_mode_options);
        // «Una app concreta» y «Diagnóstico» siguen disponibles por adb (extra mode), no en el menú.
        String[] modes = {Config.MODE_AA_EXT, Config.MODE_AA};
        if (!Config.isAa(mode)) mode = Config.MODE_AA_EXT;
        for (String m : modes) {
            View opt = LayoutInflater.from(this).inflate(R.layout.hql_mode_option, list, false);
            ((TextView) opt.findViewById(R.id.hql_opt_title)).setText(Ui.modeTitle(m));
            ((TextView) opt.findViewById(R.id.hql_opt_detail)).setText(Ui.modeDetail(m));
            if (Config.MODE_AA_EXT.equals(m)) {
                TextView tag = opt.findViewById(R.id.hql_opt_tag);
                tag.setText(Str.get(R.string.hql_recommended));
                tag.setVisibility(View.VISIBLE);
            }
            opt.setSelected(m.equals(mode));
            opt.setOnClickListener(v -> {
                mode = m;
                for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).setSelected(list.getChildAt(i) == v);
            });
            list.addView(opt);
        }
    }

    // ---------------------------------------------------------------- paso 3: requisitos

    private void renderChecks() {
        TextView title = container.findViewById(R.id.hql_check_title);
        TextView body = container.findViewById(R.id.hql_check_body);
        LinearLayout list = container.findViewById(R.id.hql_check_list);
        TextView guideLabel = container.findViewById(R.id.hql_check_guide_label);
        TextView guide = container.findViewById(R.id.hql_check_guide);
        if (title == null) return;
        list.removeAllViews();
        guideLabel.setVisibility(View.GONE);
        guide.setVisibility(View.GONE);

        boolean accOn = TouchService.instance != null;
        boolean overlay = Settings.canDrawOverlays(this);
        boolean perms = Ui.permsGranted(this);

        switch (mode) {
            case Config.MODE_AA:
            case Config.MODE_AA_EXT: {
                title.setText(Config.MODE_AA_EXT.equals(mode) ? Str.get(R.string.hql_setup_prepare_ext) : Str.get(R.string.hql_setup_prepare));
                body.setText(Str.get(R.string.hql_setup_aa_body));
                String ver = Ui.aaVersion(this);
                row(list, ver != null ? LinkState.Level.OK : LinkState.Level.ERROR, "Android Auto",
                        ver != null ? Str.get(R.string.hql_setup_version, ver) : Str.get(R.string.hql_not_installed),
                        ver != null ? null : Str.get(R.string.hql_install), v -> openStore(AaServerStarter.AA_PKG));
                row(list, accOn ? LinkState.Level.OK : LinkState.Level.BUSY, Str.get(R.string.hql_setup_accessibility),
                        accOn ? Str.get(R.string.hql_active) : Str.get(R.string.hql_setup_accessibility_aa),
                        accOn ? null : Str.get(R.string.hql_enable), v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
                int dev = AaServerStarter.devModeState(this);
                String devText = checkingDevMode ? Str.get(R.string.hql_checking)
                        : dev == 1 ? Str.get(R.string.hql_active) : dev == 0 ? Str.get(R.string.hql_not_enabled) : Str.get(R.string.hql_not_checked);
                LinkState.Level devLevel = checkingDevMode ? LinkState.Level.BUSY
                        : dev == 1 ? LinkState.Level.OK : LinkState.Level.BUSY;
                row(list, devLevel, Str.get(R.string.hql_setup_devmode), devText,
                        dev == 1 || checkingDevMode ? null : (dev == 0 ? Str.get(R.string.hql_open_aa) : Str.get(R.string.hql_check)),
                        v -> {
                            if (AaServerStarter.devModeState(this) == 0) {
                                awaitingDevMode = true;
                                AaServerStarter.openAaSettings(this);
                            } else {
                                checkDevMode();
                            }
                        });
                row(list, overlay ? LinkState.Level.OK : LinkState.Level.BUSY, Str.get(R.string.hql_setup_overlay),
                        overlay ? Str.get(R.string.hql_allowed) : Str.get(R.string.hql_setup_overlay_aa),
                        overlay ? null : Str.get(R.string.hql_allow), v -> openOverlaySettings());
                permsRow(list, perms);
                if (Config.MODE_AA_EXT.equals(mode)) {
                    boolean loc = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    if (!loc) {
                        row(list, LinkState.Level.BUSY, Str.get(R.string.hql_setup_media),
                                Str.get(R.string.hql_setup_media_why),
                                Str.get(R.string.hql_allow), v -> requestPermissions(new String[]{
                                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                                        android.Manifest.permission.ACCESS_COARSE_LOCATION}, 2));
                    }
                }
                if (dev != 1) {
                    guideLabel.setText(Str.get(R.string.hql_setup_guide_title));
                    guideLabel.setVisibility(View.VISIBLE);
                    guide.setText(Str.get(R.string.hql_setup_guide));
                    guide.setVisibility(View.VISIBLE);
                }
                break;
            }
            case Config.MODE_APP: {
                title.setText(Str.get(R.string.hql_setup_pick_app));
                body.setText(Str.get(R.string.hql_setup_app_body));
                String label = Ui.appLabel(this, cfg.targetPackage());
                row(list, label != null ? LinkState.Level.OK : LinkState.Level.BUSY, Str.get(R.string.hql_setup_app_row),
                        label != null ? label : Str.get(R.string.hql_not_chosen), label != null ? Str.get(R.string.hql_change) : Str.get(R.string.hql_choose),
                        v -> Ui.pickApp(this, pkg -> {
                            cfg.setTargetPackage(pkg);
                            renderChecks();
                        }));
                row(list, accOn ? LinkState.Level.OK : LinkState.Level.BUSY, Str.get(R.string.hql_setup_accessibility),
                        accOn ? Str.get(R.string.hql_active) : Str.get(R.string.hql_setup_accessibility_app),
                        accOn ? null : Str.get(R.string.hql_enable), v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
                row(list, overlay ? LinkState.Level.OK : LinkState.Level.BUSY, Str.get(R.string.hql_setup_overlay),
                        overlay ? Str.get(R.string.hql_allowed) : Str.get(R.string.hql_setup_overlay_app),
                        overlay ? null : Str.get(R.string.hql_allow), v -> openOverlaySettings());
                permsRow(list, perms);
                break;
            }
            default:
                title.setText(Str.get(R.string.hql_setup_diag));
                body.setText(Str.get(R.string.hql_setup_diag_body));
                permsRow(list, perms);
        }
    }

    private void permsRow(LinearLayout list, boolean perms) {
        row(list, perms ? LinkState.Level.OK : LinkState.Level.BUSY, Str.get(R.string.hql_permissions),
                perms ? Str.get(R.string.hql_setup_perms_ok) : Str.get(R.string.hql_setup_perms_why),
                perms ? null : Str.get(R.string.hql_grant), v -> requestPermissions(Ui.RUNTIME_PERMS, 1));
        // Ahorro de batería: sin la exención, Android (y algunas capas de fabricante) cierran la app en
        // segundo plano y fallan la conexión automática y la sesión con la pantalla apagada.
        boolean free = PowerHelper.unrestricted(this);
        row(list, free ? LinkState.Level.OK : LinkState.Level.BUSY, Str.get(R.string.hql_battery_row),
                free ? Str.get(R.string.hql_battery_ok) : Str.get(R.string.hql_battery_why),
                free ? null : Str.get(R.string.hql_allow), v -> PowerHelper.requestUnrestricted(this));
        if (PowerHelper.oemIntent(this) != null) {
            row(list, LinkState.Level.IDLE, Str.get(R.string.hql_battery_oem, PowerHelper.brand()),
                    Str.get(R.string.hql_battery_oem_why), Str.get(R.string.hql_open), v -> PowerHelper.openOem(this));
        }
    }

    private void row(LinearLayout list, LinkState.Level level, String title, String detail, String action, View.OnClickListener onAction) {
        if (list.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(getColor(R.color.hql_outline));
            list.addView(div, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        View r = LayoutInflater.from(this).inflate(R.layout.hql_row_check, list, false);
        Ui.dot(r.findViewById(R.id.hql_row_dot), level);
        ((TextView) r.findViewById(R.id.hql_row_title)).setText(title);
        ((TextView) r.findViewById(R.id.hql_row_detail)).setText(detail);
        MaterialButton b = r.findViewById(R.id.hql_row_action);
        if (action == null) {
            b.setVisibility(View.GONE);
        } else {
            b.setText(action);
            b.setOnClickListener(onAction);
        }
        list.addView(r);
    }

    /** Lo que falta para que el modo elegido funcione, o null si está todo. */
    private String missingRequired() {
        if (!Ui.permsGranted(this)) return Str.get(R.string.hql_missing_perms);
        switch (mode) {
            case Config.MODE_AA:
            case Config.MODE_AA_EXT:
                if (Ui.aaVersion(this) == null) return Str.get(R.string.hql_missing_aa);
                if (TouchService.instance == null) return Str.get(R.string.hql_missing_accessibility);
                if (AaServerStarter.devModeState(this) != 1) return Str.get(R.string.hql_missing_devmode);
                return null;
            case Config.MODE_APP:
                if (Ui.appLabel(this, cfg.targetPackage()) == null) return Str.get(R.string.hql_missing_app);
                if (!Settings.canDrawOverlays(this)) return Str.get(R.string.hql_missing_overlay);
                return null;
            default:
                return null;
        }
    }

    private void checkDevMode() {
        if (TouchService.instance == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(Str.get(R.string.hql_accessibility_first))
                    .setMessage(Str.get(R.string.hql_accessibility_first_msg))
                    .setPositiveButton(Str.get(R.string.hql_enable), (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton(Str.get(R.string.hql_cancel), null)
                    .show();
            return;
        }
        checkingDevMode = true;
        renderChecks();
        new Thread(() -> {
            AaServerStarter.checkDevModeAndWait(this);
            runOnUiThread(() -> {
                checkingDevMode = false;
                renderChecks();
            });
        }, "aa-devmode-check").start();
    }

    private void openOverlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private void openStore(String pkg) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
