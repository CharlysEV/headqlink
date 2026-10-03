package com.c10link.link;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Pantalla principal de C10Link: modo elegido, estado (coche, imagen, Android Auto/app) y
 * Conectar/Desconectar. Abrirla ya configurada lo pone todo en marcha; al desconectarse el
 * coche, LinkService lo cierra todo.
 */
public class C10Activity extends Activity implements LinkState.Listener {
    private Config cfg;
    private TextView modeView;
    private TextView modeDetail;
    private TextView hint;
    private MaterialButton toggle;
    private View carRow;
    private View videoRow;
    private View sourceRow;
    private boolean starting;
    private com.google.android.material.button.MaterialButtonToggleGroup aaVariant;
    private View panelColorButton;
    private View tvListButton;
    private View pdfsButton;
    private static final int REQ_IPTV_FILE = 10;
    private static final int REQ_PDF = 11;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        cfg = new Config(this);
        if (!cfg.setupDone()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.c10_activity_home);
        modeView = findViewById(R.id.c10_home_mode);
        modeDetail = findViewById(R.id.c10_home_mode_detail);
        hint = findViewById(R.id.c10_home_hint);
        toggle = findViewById(R.id.c10_home_toggle);
        aaVariant = findViewById(R.id.c10_home_aa_variant);
        panelColorButton = findViewById(R.id.c10_home_panel_color);
        panelColorButton.setOnClickListener(v -> showPanelColor());
        tvListButton = findViewById(R.id.c10_home_tv_list);
        tvListButton.setOnClickListener(v -> showTvList());
        pdfsButton = findViewById(R.id.c10_home_pdfs);
        pdfsButton.setOnClickListener(v -> showPdfs());
        aaVariant.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            String m = id == R.id.c10_home_aa_ext ? Config.MODE_AA_EXT : Config.MODE_AA;
            if (m.equals(cfg.mode())) return;
            cfg.setMode(m);
            if (Config.MODE_AA_EXT.equals(m) && !Ui.mediaPermsGranted(this)) requestPermissions(Ui.MEDIA_PERMS, 2);
            if (LinkState.running) {
                // La sesión con el coche se reinicia con el modo nuevo; AA sigue conectado.
                startForegroundService(new Intent(this, LinkService.class).setAction(LinkService.ACTION_APPLY));
            }
            render();
        });

        LinearLayout status = findViewById(R.id.c10_home_status);
        carRow = statusRow(status, "Coche");
        videoRow = statusRow(status, "Imagen");
        sourceRow = statusRow(status, "");

        findViewById(R.id.c10_home_change).setOnClickListener(v ->
                startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_STEP, 1)));
        findViewById(R.id.c10_home_video).setOnClickListener(v -> showVideoSettings());
        findViewById(R.id.c10_home_log).setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        toggle.setOnClickListener(v -> {
            if (LinkState.running) {
                startService(new Intent(this, LinkService.class).setAction(LinkService.ACTION_STOP));
            } else {
                connect();
            }
        });

        if (b == null && !LinkState.running) connect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (modeView == null) return;
        LinkState.addListener(this);
        render();
    }

    @Override
    protected void onPause() {
        LinkState.removeListener(this);
        super.onPause();
    }

    @Override
    public void onLinkStateChanged() {
        render();
    }

    /**
     * Arranca el enlace. En modo Android Auto, antes se asegura de que el servidor de AA está en
     * marcha (ahora el móvil está desbloqueado; la automatización queda tapada por una capa).
     */
    private void connect() {
        Intent link = new Intent(this, LinkService.class).setAction(LinkService.ACTION_APPLY);
        if (Config.isAa(cfg.mode()) && TouchService.instance == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Falta la accesibilidad")
                    .setMessage("C10Link necesita su servicio de accesibilidad para arrancar Android Auto sin que lo veas y para recibir los toques del coche. Android lo desactiva a veces al actualizar la app.")
                    .setPositiveButton("Activar", (d, w) -> startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        if (!Config.isAa(cfg.mode()) || AaServerStarter.cannotRunReason(this) != null) {
            startForegroundService(link);
            return;
        }
        starting = true;
        render();
        new Thread(() -> {
            boolean ok = AaServerStarter.startAndWait(this);
            L.i("conectar: servidor de Android Auto " + (ok ? "listo" : "no confirmado"));
            runOnUiThread(() -> {
                starting = false;
                startForegroundService(link);
                render();
            });
        }, "aa-connect").start();
    }

    private void render() {
        String mode = cfg.mode();
        modeView.setText(Ui.modeTitle(mode));
        aaVariant.setVisibility(Config.isAa(mode) ? View.VISIBLE : View.GONE);
        int ext = Config.MODE_AA_EXT.equals(mode) ? View.VISIBLE : View.GONE;
        panelColorButton.setVisibility(ext);
        tvListButton.setVisibility(ext);
        pdfsButton.setVisibility(ext);
        if (Config.isAa(mode)) {
            int want = Config.MODE_AA_EXT.equals(mode) ? R.id.c10_home_aa_ext : R.id.c10_home_aa_plain;
            if (aaVariant.getCheckedButtonId() != want) aaVariant.check(want);
        }
        if (Config.MODE_APP.equals(mode)) {
            String label = Ui.appLabel(this, cfg.targetPackage());
            modeDetail.setText(label != null ? "Se abre " + label + " en el coche." : "Sin app elegida.");
        } else {
            modeDetail.setText(Ui.modeDetail(mode));
        }

        boolean running = LinkState.running;
        switch (LinkState.car) {
            case CONNECTED:
                setRow(carRow, LinkState.Level.OK, "Conectado" + (LinkState.carDetail.isEmpty() ? "" : " · " + LinkState.carDetail));
                break;
            case SEEN:
                setRow(carRow, LinkState.Level.BUSY, "Encontrado, conectando…");
                break;
            case SEARCHING:
                setRow(carRow, LinkState.Level.BUSY, "Buscando…");
                break;
            default:
                setRow(carRow, LinkState.Level.IDLE, "Desconectado");
        }
        String video = LinkState.video;
        setRow(videoRow, video.isEmpty() ? LinkState.Level.IDLE : LinkState.Level.OK, video.isEmpty() ? "—" : video);

        boolean showSource = !Config.MODE_PATTERN.equals(mode);
        sourceRow.setVisibility(showSource ? View.VISIBLE : View.GONE);
        if (showSource) {
            ((TextView) sourceRow.findViewById(R.id.c10_status_name)).setText(Config.isAa(mode) ? "Android Auto" : "App");
            String src = starting ? "Preparando…" : LinkState.source;
            setRow(sourceRow, starting ? LinkState.Level.BUSY : LinkState.sourceLevel, src.isEmpty() ? "—" : src);
        }

        toggle.setText(starting ? "Preparando…" : running ? "Desconectar" : "Conectar");
        toggle.setEnabled(!starting);
        if (!running) {
            hint.setText("Con el coche encendido y QDLink en su pantalla, pulsa Conectar.");
        } else if (LinkState.car != LinkState.Car.CONNECTED) {
            hint.setText("Abre QDLink en la pantalla del coche. El móvil se puede bloquear en cualquier momento.");
        } else {
            hint.setText("Puedes bloquear el móvil: la imagen sigue llegando al coche. Si el coche se desconecta, C10Link se detiene solo.");
        }
    }

    private View statusRow(LinearLayout parent, String name) {
        if (parent.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(getColor(R.color.c10_outline));
            parent.addView(div, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
        View r = LayoutInflater.from(this).inflate(R.layout.c10_status_row, parent, false);
        ((TextView) r.findViewById(R.id.c10_status_name)).setText(name);
        parent.addView(r);
        return r;
    }

    private void setRow(View row, LinkState.Level level, String value) {
        Ui.dot(row.findViewById(R.id.c10_status_dot), level);
        ((TextView) row.findViewById(R.id.c10_status_value)).setText(value);
    }

    /** Lista de canales M3U para la TV del coche: una URL o un archivo del móvil. */
    private void showTvList() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText url = new EditText(this);
        url.setHint("https://…/lista.m3u");
        url.setSingleLine(true);
        String cur = cfg.iptvSource();
        if (cur.startsWith("http")) url.setText(cur);
        TextView info = new TextView(this);
        info.setText(cur.startsWith("content://") ? "Ahora: un archivo del móvil." : "Pega la URL de tu lista M3U o elige un archivo.");
        box.addView(info);
        box.addView(url);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Lista de TV")
                .setView(box)
                .setPositiveButton("Guardar", (d, w) -> {
                    String s = url.getText().toString().trim();
                    if (!s.isEmpty()) cfg.setIptvSource(s);
                })
                .setNeutralButton("Elegir archivo", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    startActivityForResult(i, REQ_IPTV_FILE);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Documentos PDF que se podrán leer en el coche. */
    private void showPdfs() {
        java.util.List<String[]> docs = cfg.pdfDocuments();
        String[] names = new String[docs.size()];
        for (int i = 0; i < docs.size(); i++) names[i] = docs.get(i)[1];
        new MaterialAlertDialogBuilder(this)
                .setTitle("Documentos PDF" + (docs.isEmpty() ? "" : " · toca para quitar"))
                .setItems(names, (d, which) -> {
                    docs.remove(which);
                    cfg.setPdfDocuments(docs);
                })
                .setMessage(docs.isEmpty() ? "Aún no hay documentos." : null)
                .setPositiveButton("Añadir", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/pdf").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, REQ_PDF);
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        for (android.net.Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                L.w("sin permiso persistente para " + u + ": " + e.getMessage());
            }
        }
        if (req == REQ_IPTV_FILE && !uris.isEmpty()) {
            cfg.setIptvSource(uris.get(0).toString());
        } else if (req == REQ_PDF) {
            java.util.List<String[]> docs = cfg.pdfDocuments();
            for (android.net.Uri u : uris) docs.add(new String[]{u.toString(), displayName(u)});
            cfg.setPdfDocuments(docs);
        }
    }

    private String displayName(android.net.Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return "Documento";
    }

    /** Gris del panel propio, en vivo en el coche, para que no se distinga de sus barras. */
    private void showPanelColor() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView hint = new TextView(this);
        hint.setText("Mueve hasta que el panel no se distinga de las barras del coche. Se ve en directo si estás conectado en modo ampliado.");
        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(80);
        bar.setProgress(cfg.panelGray());
        TextView value = new TextView(this);
        value.setText("Gris " + cfg.panelGray());
        box.addView(hint);
        box.addView(bar);
        box.addView(value);
        int original = cfg.panelGray();
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                cfg.putInt(Config.PANEL_GRAY, p);
                value.setText("Gris " + p);
                CarUi.applyPanelColor(cfg.panelColor());
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {
            }
        });
        new MaterialAlertDialogBuilder(this)
                .setTitle("Color del panel")
                .setView(box)
                .setPositiveButton("Guardar", null)
                .setNegativeButton("Cancelar", (d, w) -> {
                    cfg.putInt(Config.PANEL_GRAY, original);
                    CarUi.applyPanelColor(cfg.panelColor());
                })
                .show();
    }

    private void showVideoSettings() {
        View v = LayoutInflater.from(this).inflate(R.layout.c10_dialog_video, null);
        EditText fps = v.findViewById(R.id.c10_v_fps);
        EditText kbps = v.findViewById(R.id.c10_v_kbps);
        EditText w = v.findViewById(R.id.c10_v_w);
        EditText h = v.findViewById(R.id.c10_v_h);
        RadioGroup profile = v.findViewById(R.id.c10_v_profile);
        android.widget.CheckBox brake = v.findViewById(R.id.c10_v_brake);
        android.widget.CheckBox reencode = v.findViewById(R.id.c10_v_reencode);
        reencode.setChecked(cfg.aaReencode());
        EditText window = v.findViewById(R.id.c10_v_window);
        brake.setChecked(cfg.aaBrake());
        fill(window, Config.AA_WINDOW);
        fill(fps, Config.FPS);
        fill(kbps, Config.KBPS);
        fill(w, Config.WIDTH);
        fill(h, Config.HEIGHT);
        profile.check("main".equals(cfg.profile()) ? R.id.c10_v_main
                : "high".equals(cfg.profile()) ? R.id.c10_v_high : R.id.c10_v_baseline);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Ajustes de imagen")
                .setView(v)
                .setPositiveButton("Guardar", (d, which) -> {
                    save(fps, Config.FPS);
                    save(kbps, Config.KBPS);
                    save(w, Config.WIDTH);
                    save(h, Config.HEIGHT);
                    save(window, Config.AA_WINDOW);
                    cfg.putBool(Config.AA_BRAKE, brake.isChecked());
                    cfg.putBool(Config.AA_REENCODE, reencode.isChecked());
                    int id = profile.getCheckedRadioButtonId();
                    cfg.setProfile(id == R.id.c10_v_main ? "main" : id == R.id.c10_v_high ? "high" : "baseline");
                    if (LinkState.running) {
                        // Se aplican en la siguiente sesión: el coche reconecta en unos segundos.
                        startForegroundService(new Intent(this, LinkService.class).setAction(LinkService.ACTION_APPLY));
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void fill(EditText e, String key) {
        int v = cfg.getInt(key);
        if (v > 0) e.setText(String.valueOf(v));
    }

    private void save(EditText e, String key) {
        String s = e.getText().toString().trim();
        cfg.putInt(key, s.isEmpty() ? 0 : Integer.parseInt(s));
    }
}
