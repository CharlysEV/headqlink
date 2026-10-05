package com.headqlink.link;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.andrerinas.openheadunit.R;
import com.andrerinas.openheadunit.utils.ToastUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Diagnóstico: el log de HeadQLink en vivo, con la ruta de los registros, un botón para copiarlo, «Exportar log» (ZIP en
 * Descargas/HeadQLink, o en la carpeta de la app en Android 9 o menos, con todos los registros, qdauto §7.5) y la prueba
 * sin Android Auto (patrón), para probar el enlace con un coche simulado sin adb.
 */
public class LogActivity extends Activity {
    private static final int MAX_LINES = 600;

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private TextView text;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        setContentView(R.layout.hql_activity_log);
        text = findViewById(R.id.hql_log_text);
        scroll = findViewById(R.id.hql_log_scroll);
        File f = L.currentFile();
        File base = getExternalFilesDir(null);
        ((TextView) findViewById(R.id.hql_log_file)).setText(Str.get(R.string.hql_log_paths, base != null ? base.getAbsolutePath() : ""));
        Config cfg = new Config(this);
        // Motor y conexión del servicio en marcha; lo cambiado en marcha, aparte (se aplica al volver a conectar).
        String info = Str.get(R.string.hql_log_info, engineName(LinkState.engineFor(cfg)), Ui.linkTitle(LinkState.linkModeFor(cfg)));
        if (LinkState.transportChangePending(cfg)) {
            info += "\n" + Str.get(R.string.hql_log_pending, engineName(cfg.linkEngine()), Ui.linkTitle(cfg.linkMode()));
        }
        ((TextView) findViewById(R.id.hql_log_info)).setText(info);
        android.widget.CompoundButton pattern = findViewById(R.id.hql_log_pattern);
        pattern.setChecked(Config.MODE_PATTERN.equals(cfg.mode()));
        pattern.setOnCheckedChangeListener((b2, on) -> setPatternTest(cfg, on));
        findViewById(R.id.hql_log_export).setOnClickListener(v -> exportLog());
        findViewById(R.id.hql_log_qd_options).setOnClickListener(v -> showQdOptions(cfg));
        if (f != null) {
            try {
                List<String> all = Files.readAllLines(f.toPath());
                for (int i = Math.max(0, all.size() - MAX_LINES); i < all.size(); i++) lines.addLast(all.get(i));
            } catch (Exception ignored) {
            }
        }
        refresh();
        findViewById(R.id.hql_log_latency).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, LatencyActivity.class)));
        findViewById(R.id.hql_log_copy).setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("HeadQLink", String.join("\n", lines)));
            ToastUtils.showToast(this, Str.get(R.string.hql_log_copied), Toast.LENGTH_SHORT, true);
        });
    }

    private static String engineName(String engine) {
        return Config.ENGINE_QDAUTO.equals(engine) ? "QDAuto" : Str.get(R.string.hql_engine_original);
    }

    /**
     * Prueba sin Android Auto: guarda el modo actual y pone el patrón de diagnóstico (lo dibuja nuestro encoder, sin AA);
     * al apagarlo, vuelve el modo guardado. Con el servicio en marcha, se reinicia la sesión con el modo nuevo.
     */
    private void setPatternTest(Config cfg, boolean on) {
        String mode = cfg.mode();
        if (on) {
            if (Config.MODE_PATTERN.equals(mode)) return;
            cfg.putString(Config.MODE_BEFORE_PATTERN, mode);
            cfg.setMode(Config.MODE_PATTERN);
        } else {
            if (!Config.MODE_PATTERN.equals(mode)) return;
            String before = cfg.getString(Config.MODE_BEFORE_PATTERN);
            cfg.setMode(before != null && !Config.MODE_PATTERN.equals(before) ? before : Config.MODE_AA_EXT);
            cfg.putString(Config.MODE_BEFORE_PATTERN, null);
        }
        L.i("prueba con patrón: " + (on ? "SÍ" : "no") + " (modo " + cfg.mode() + ")");
        if (LinkState.running) {
            startForegroundService(new android.content.Intent(this, LinkService.class).setAction(LinkService.ACTION_APPLY));
        }
    }

    /**
     * Opciones de prueba del motor QDAuto: las claves del Anexo A que no tienen ajuste propio. El diseño las dejaba para
     * extras de adb, pero desde el parche de seguridad LinkService no es exportado y adb ya no puede pasárselas.
     */
    private void showQdOptions(Config cfg) {
        android.view.View v = getLayoutInflater().inflate(R.layout.hql_dialog_qd_options, null);
        android.widget.CheckBox keep = v.findViewById(R.id.hql_qd_keep_video);
        android.widget.CheckBox supersede = v.findViewById(R.id.hql_qd_supersede);
        android.widget.CheckBox strict = v.findViewById(R.id.hql_qd_peer_strict);
        android.widget.CheckBox qdlink = v.findViewById(R.id.hql_qd_phone_info);
        android.widget.EditText gone = v.findViewById(R.id.hql_qd_car_gone);
        keep.setChecked(cfg.qdKeepVideo());
        supersede.setChecked(cfg.qdSupersede());
        strict.setChecked(cfg.peerStrict());
        qdlink.setChecked("qdlink".equals(cfg.qdPhoneInfo()));
        int goneMs = cfg.getInt(Config.CAR_GONE_MS);
        if (goneMs > 0) gone.setText(String.valueOf(goneMs / 1000));
        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(Str.get(R.string.hql_qd_test_options))
                .setView(sv)
                .setPositiveButton(Str.get(R.string.hql_save), (d, which) -> {
                    cfg.putBool(Config.QD_KEEP_VIDEO, keep.isChecked());
                    cfg.putBool(Config.QD_SUPERSEDE, supersede.isChecked());
                    cfg.putBool(Config.PEER_STRICT, strict.isChecked());
                    cfg.putString(Config.QD_PHONE_INFO, qdlink.isChecked() ? "qdlink" : "fork");
                    int secs = 0;
                    try {
                        String s = gone.getText().toString().trim();
                        if (!s.isEmpty()) secs = Integer.parseInt(s);
                    } catch (NumberFormatException ignored) {
                    }
                    cfg.putInt(Config.CAR_GONE_MS, secs > 0 ? Math.max(5, Math.min(600, secs)) * 1000 : 0);
                    L.i("opciones de prueba de QDAuto: mantener vídeo " + cfg.qdKeepVideo() + " · relevo " + cfg.qdSupersede()
                            + " · filtro estricto " + cfg.peerStrict() + " · PHONE_INFO " + cfg.qdPhoneInfo()
                            + " · espera del coche " + cfg.carGoneMs() / 1000 + " s (se aplica al volver a conectar)");
                    if (LinkState.running) {
                        ToastUtils.showToast(this, Str.get(R.string.hql_applies_on_reconnect), Toast.LENGTH_LONG, true);
                    }
                })
                .setNegativeButton(Str.get(R.string.hql_cancel), null)
                .show();
    }

    /** Exportar log: en otro hilo, con avisos; al acabar, la hoja de compartir. */
    private void exportLog() {
        ToastUtils.showToast(this, Str.get(R.string.hql_export_running), Toast.LENGTH_SHORT, true);
        new Thread(() -> {
            try {
                HqlLogExport.Exported ex = HqlLogExport.INSTANCE.export(this);
                runOnUiThread(() -> {
                    ToastUtils.showToast(this, Str.get(R.string.hql_export_done, ex.getWhere()), Toast.LENGTH_LONG, true);
                    try {
                        startActivity(HqlLogExport.INSTANCE.shareIntent(ex));
                    } catch (RuntimeException e) {
                        L.w("no se pudo abrir la hoja de compartir: " + e);
                    }
                });
            } catch (Exception e) {
                L.e("exportar log", e);
                runOnUiThread(() -> ToastUtils.showToast(this, Str.get(R.string.hql_export_failed, String.valueOf(e.getMessage())),
                        Toast.LENGTH_LONG, true));
            }
        }, "hql-export").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        L.setListener(line -> runOnUiThread(() -> {
            lines.addLast(line);
            while (lines.size() > MAX_LINES) lines.removeFirst();
            refresh();
        }));
    }

    @Override
    protected void onPause() {
        L.setListener(null);
        super.onPause();
    }

    private void refresh() {
        text.setText(String.join("\n", lines));
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
