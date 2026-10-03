package com.c10link.link;

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
        setContentView(R.layout.c10_activity_setup);
        container = findViewById(R.id.c10_step_container);
        steps = findViewById(R.id.c10_steps);
        stepLabel = findViewById(R.id.c10_step_label);
        back = findViewById(R.id.c10_back);
        next = findViewById(R.id.c10_next);
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
            steps.getChildAt(i).setBackgroundResource(i <= step ? R.drawable.c10_step_on : R.drawable.c10_step_off);
        }
        stepLabel.setText("Paso " + (step + 1) + " de " + STEPS);
        back.setVisibility(step == 0 ? View.INVISIBLE : View.VISIBLE);
        container.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        switch (step) {
            case 0:
                inf.inflate(R.layout.c10_setup_welcome, container, true);
                next.setText("Empezar");
                break;
            case 1:
                inf.inflate(R.layout.c10_setup_mode, container, true);
                renderModes();
                next.setText("Continuar");
                break;
            default:
                inf.inflate(R.layout.c10_setup_check, container, true);
                next.setText("Terminar");
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
                    .setTitle("Falta un paso")
                    .setMessage(missingRequired() + "\n\nPuedes terminar igualmente y completarlo más tarde desde \"Cambiar\".")
                    .setPositiveButton("Terminar igualmente", (d, w) -> finishSetup())
                    .setNegativeButton("Volver", null)
                    .show();
            return;
        }
        finishSetup();
    }

    private void finishSetup() {
        cfg.setMode(mode);
        cfg.setSetupDone(true);
        startActivity(new Intent(this, C10Activity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    // ---------------------------------------------------------------- paso 2: modo

    private void renderModes() {
        LinearLayout list = container.findViewById(R.id.c10_mode_options);
        String[] modes = {Config.MODE_AA, Config.MODE_AA_EXT, Config.MODE_APP, Config.MODE_PATTERN};
        for (String m : modes) {
            View opt = LayoutInflater.from(this).inflate(R.layout.c10_mode_option, list, false);
            ((TextView) opt.findViewById(R.id.c10_opt_title)).setText(Ui.modeTitle(m));
            ((TextView) opt.findViewById(R.id.c10_opt_detail)).setText(Ui.modeDetail(m));
            if (Config.MODE_AA.equals(m)) {
                TextView tag = opt.findViewById(R.id.c10_opt_tag);
                tag.setText("Recomendado");
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
        TextView title = container.findViewById(R.id.c10_check_title);
        TextView body = container.findViewById(R.id.c10_check_body);
        LinearLayout list = container.findViewById(R.id.c10_check_list);
        TextView guideLabel = container.findViewById(R.id.c10_check_guide_label);
        TextView guide = container.findViewById(R.id.c10_check_guide);
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
                title.setText(Config.MODE_AA_EXT.equals(mode) ? "Preparar Android Auto ampliado" : "Preparar Android Auto");
                body.setText("Android Auto solo acepta una pantalla de coche dentro del propio móvil a través de su modo para desarrolladores. Se activa una vez; después C10Link lo gestiona solo.");
                String ver = Ui.aaVersion(this);
                row(list, ver != null ? LinkState.Level.OK : LinkState.Level.ERROR, "Android Auto",
                        ver != null ? "Versión " + ver : "No está instalado",
                        ver != null ? null : "Instalar", v -> openStore(AaServerStarter.AA_PKG));
                row(list, accOn ? LinkState.Level.OK : LinkState.Level.BUSY, "Accesibilidad de C10Link",
                        accOn ? "Activa" : "Para recibir los toques del coche y arrancar Android Auto",
                        accOn ? null : "Activar", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
                int dev = AaServerStarter.devModeState(this);
                String devText = checkingDevMode ? "Comprobando…"
                        : dev == 1 ? "Activo" : dev == 0 ? "No está activado" : "Sin comprobar";
                LinkState.Level devLevel = checkingDevMode ? LinkState.Level.BUSY
                        : dev == 1 ? LinkState.Level.OK : LinkState.Level.BUSY;
                row(list, devLevel, "Modo desarrollador de Android Auto", devText,
                        dev == 1 || checkingDevMode ? null : (dev == 0 ? "Abrir AA" : "Comprobar"),
                        v -> {
                            if (AaServerStarter.devModeState(this) == 0) {
                                awaitingDevMode = true;
                                AaServerStarter.openAaSettings(this);
                            } else {
                                checkDevMode();
                            }
                        });
                row(list, overlay ? LinkState.Level.OK : LinkState.Level.BUSY, "Mostrar sobre otras apps",
                        overlay ? "Permitido" : "Para abrir Android Auto con el móvil en segundo plano",
                        overlay ? null : "Permitir", v -> openOverlaySettings());
                permsRow(list, perms);
                if (Config.MODE_AA_EXT.equals(mode)) {
                    boolean media = Ui.mediaPermsGranted(this);
                    row(list, media ? LinkState.Level.OK : LinkState.Level.BUSY, "Fotos, vídeos y ubicación",
                            media ? "Permitido" : "Para la galería y los paneles de conducción en el coche",
                            media ? null : "Permitir", v -> requestPermissions(Ui.MEDIA_PERMS, 2));
                }
                if (dev != 1) {
                    guideLabel.setText("Cómo activar el modo desarrollador");
                    guideLabel.setVisibility(View.VISIBLE);
                    guide.setText("1.  Pulsa \"Abrir AA\" (si no aparece, primero \"Comprobar\").\n"
                            + "2.  Baja hasta el final de los ajustes de Android Auto.\n"
                            + "3.  Toca 10 veces \"Versión\" y acepta el aviso.\n"
                            + "4.  Vuelve a C10Link: se comprueba solo.");
                    guide.setVisibility(View.VISIBLE);
                }
                break;
            }
            case Config.MODE_APP: {
                title.setText("Elegir la app");
                body.setText("La app se abre en una pantalla virtual que se envía al coche, así que en el móvil no se ve. Los toques del coche llegan a través del servicio de accesibilidad.");
                String label = Ui.appLabel(this, cfg.targetPackage());
                row(list, label != null ? LinkState.Level.OK : LinkState.Level.BUSY, "App que se abrirá",
                        label != null ? label : "Sin elegir", label != null ? "Cambiar" : "Elegir",
                        v -> Ui.pickApp(this, pkg -> {
                            cfg.setTargetPackage(pkg);
                            renderChecks();
                        }));
                row(list, accOn ? LinkState.Level.OK : LinkState.Level.BUSY, "Accesibilidad de C10Link",
                        accOn ? "Activa" : "Para que los toques del coche lleguen a la app",
                        accOn ? null : "Activar", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
                row(list, overlay ? LinkState.Level.OK : LinkState.Level.BUSY, "Mostrar sobre otras apps",
                        overlay ? "Permitido" : "Para abrir la app con el móvil en segundo plano",
                        overlay ? null : "Permitir", v -> openOverlaySettings());
                permsRow(list, perms);
                break;
            }
            default:
                title.setText("Modo diagnóstico");
                body.setText("Muestra en el coche una imagen de prueba con la fluidez y los toques en tiempo real. Útil para comprobar la conexión antes de usar Android Auto.");
                permsRow(list, perms);
        }
    }

    private void permsRow(LinearLayout list, boolean perms) {
        row(list, perms ? LinkState.Level.OK : LinkState.Level.BUSY, "Permisos",
                perms ? "WiFi cercano, Bluetooth y notificaciones" : "Para encontrar el coche y registrar el estado del Bluetooth",
                perms ? null : "Conceder", v -> requestPermissions(Ui.RUNTIME_PERMS, 1));
    }

    private void row(LinearLayout list, LinkState.Level level, String title, String detail, String action, View.OnClickListener onAction) {
        if (list.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(getColor(R.color.c10_outline));
            list.addView(div, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        View r = LayoutInflater.from(this).inflate(R.layout.c10_row_check, list, false);
        Ui.dot(r.findViewById(R.id.c10_row_dot), level);
        ((TextView) r.findViewById(R.id.c10_row_title)).setText(title);
        ((TextView) r.findViewById(R.id.c10_row_detail)).setText(detail);
        MaterialButton b = r.findViewById(R.id.c10_row_action);
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
        if (!Ui.permsGranted(this)) return "Faltan los permisos para encontrar el coche.";
        switch (mode) {
            case Config.MODE_AA:
            case Config.MODE_AA_EXT:
                if (Ui.aaVersion(this) == null) return "Android Auto no está instalado.";
                if (TouchService.instance == null) return "Falta activar la accesibilidad de C10Link.";
                if (AaServerStarter.devModeState(this) != 1) return "Falta activar el modo desarrollador de Android Auto.";
                return null;
            case Config.MODE_APP:
                if (Ui.appLabel(this, cfg.targetPackage()) == null) return "Falta elegir la app.";
                if (!Settings.canDrawOverlays(this)) return "Falta permitir que C10Link se muestre sobre otras apps.";
                return null;
            default:
                return null;
        }
    }

    private void checkDevMode() {
        if (TouchService.instance == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Primero, la accesibilidad")
                    .setMessage("C10Link necesita su servicio de accesibilidad para comprobar el modo desarrollador de Android Auto.")
                    .setPositiveButton("Activar", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton("Cancelar", null)
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
