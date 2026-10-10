package com.headqlink.link;

import android.app.Activity;
import android.content.Context;
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
 * Pantalla principal de HeadQLink: modo elegido, estado (coche, imagen, Android Auto/app) y
 * Conectar/Desconectar. Abrirla ya configurada lo pone todo en marcha; si el coche se va,
 * LinkService lo espera con Android Auto en pausa («Esperar al coche») y después lo cierra todo.
 */
public class HomeActivity extends Activity implements LinkState.Listener {
    private Config cfg;
    private TextView modeView;
    private TextView modeDetail;
    private TextView hint;
    private MaterialButton toggle;
    private View carRow;
    private View videoRow;
    private View sourceRow;
    private View networkRow;
    /** Estado de la zona Wi-Fi visto desde esta pantalla (antes de conectar). */
    private volatile HotspotWatcher.Probe hotspotProbe;
    private android.widget.CompoundButton btAuto;
    /** Fila «Requisitos»: «Todo listo» o «Faltan N cosas»; al tocarla, la pantalla «Comprobación». */
    private View reqRow;
    /** Panel «En directo»: fps y Mbps del vídeo al coche, con sus barras. */
    private View live;
    private View liveDot;
    private TextView fpsValue;
    private TextView mbpsValue;
    private MeterView fpsMeter;
    private MeterView mbpsMeter;
    /** Máximos esperados de las barras (los del perfil de imagen); se calculan al primer dato de vídeo. */
    private float fpsMax;
    private float mbpsMax;
    private android.animation.ObjectAnimator livePulse;
    /** Desde la configuración terminada «igualmente»: ya se avisó de lo que falta, se conecta sin volver a avisar. */
    static final String EXTRA_SKIP_CHECK = "skip_check";
    /**
     * Abrir sin conectar solo: desde la cabecera del widget o manteniendo pulsado el botón de los ajustes rápidos (para
     * conectar ya están sus botones).
     */
    static final String EXTRA_NO_AUTOCONNECT = "no_autoconnect";

    /** La app sin conectar sola (marca del widget, notificaciones de Android Auto): para quien solo quiere verla. */
    public static Intent openIntent(Context c) {
        return new Intent(c, HomeActivity.class).putExtra(EXTRA_NO_AUTOCONNECT, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    /** TileService.ACTION_QS_TILE_PREFERENCES: el botón de los ajustes rápidos, mantenido pulsado. */
    private static final String ACTION_TILE_PREFERENCES = "android.service.quicksettings.action.QS_TILE_PREFERENCES";
    private static final int REQ_IPTV_FILE = 10;
    private static final int REQ_RADIO_FILE = 11;
    private static final int REQ_NES_TREE = 21;
    private static final int REQ_CHECKLIST = 12;

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
        setContentView(R.layout.hql_activity_home);
        modeView = findViewById(R.id.hql_home_mode);
        modeDetail = findViewById(R.id.hql_home_mode_detail);
        hint = findViewById(R.id.hql_home_hint);
        toggle = findViewById(R.id.hql_home_toggle);
        ((TextView) findViewById(R.id.hql_home_version)).setText(Ui.appVersionLabel(this));
        updateRow = findViewById(R.id.hql_home_update);
        // Novedades de la versión recién instalada (solo la primera vez que arranca).
        UpdateCheck.whatsNew(this, r -> {
            if (r != null && !isFinishing()) showReleaseNotes(Str.get(R.string.hql_whats_new_title, r.version), r, false);
        });
        View menu = findViewById(R.id.hql_home_menu);
        menu.setOnClickListener(v -> showMenu(menu));
        btAuto = findViewById(R.id.hql_home_bt_auto);
        btAuto.setChecked(cfg.btAutoConnect());
        btAuto.setOnCheckedChangeListener((b2, on) -> setBtAuto(on));

        live = findViewById(R.id.hql_home_live);
        liveDot = findViewById(R.id.hql_home_live_dot);
        fpsValue = findViewById(R.id.hql_meter_fps_value);
        mbpsValue = findViewById(R.id.hql_meter_mbps_value);
        fpsMeter = findViewById(R.id.hql_meter_fps);
        mbpsMeter = findViewById(R.id.hql_meter_mbps);

        LinearLayout status = findViewById(R.id.hql_home_status);
        carRow = statusRow(status, Str.get(R.string.hql_car), R.drawable.hql_ln_car);
        networkRow = statusRow(status, Str.get(R.string.hql_network), R.drawable.hql_ln_wifi);
        networkRow.setOnClickListener(v -> {
            if (hotspotNow() && networkLevel() != LinkState.Level.OK) HotspotWatcher.openSettings(this);
        });
        videoRow = statusRow(status, Str.get(R.string.hql_image), R.drawable.hql_ln_screen);
        sourceRow = statusRow(status, "", R.drawable.hql_ln_phone);
        // Arranque manual: «Esperando al servidor de Android Auto» (los intentos fallan); al tocar, los ajustes de AA.
        sourceRow.setOnClickListener(v -> {
            if (waitingForAaServer()) openAaForServer();
        });
        reqRow = statusRow(status, Str.get(R.string.hql_req_row), R.drawable.hql_ln_shield);
        reqRow.setOnClickListener(v -> startActivity(new Intent(this, ChecklistActivity.class)));
        setRow(reqRow, LinkState.Level.IDLE, Str.get(R.string.hql_checking));

        findViewById(R.id.hql_home_change).setOnClickListener(v ->
                startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_STEP, 1)));
        toggle.setOnClickListener(v -> {
            if (LinkState.running) {
                LinkControl.stop(this, "pantalla principal");
            } else {
                LinkControl.connect(this, REQ_CHECKLIST, "pantalla principal", null);
            }
        });

        Intent start = getIntent();
        boolean noAuto = start.getBooleanExtra(EXTRA_NO_AUTOCONNECT, false) || ACTION_TILE_PREFERENCES.equals(start.getAction());
        if (b == null && !LinkState.running && !noAuto) {
            if (start.getBooleanExtra(EXTRA_SKIP_CHECK, false)) LinkControl.start(this, "pantalla principal", null);
            else LinkControl.connect(this, REQ_CHECKLIST, "pantalla principal", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (modeView == null) return;
        LinkState.addListener(this);
        // El perfil de imagen pudo cambiar: los máximos de las barras se recalculan con el próximo dato.
        fpsMax = 0;
        mbpsMax = 0;
        render();
        // El idioma de la app pudo cambiar: el widget lo repinta si hace falta (si no cambia nada, no hace nada).
        WidgetUpdater.poke();
        // De vuelta (quizá de los ajustes de AA) esperando al servidor del arranque manual: el reintento, ya (sin sondear).
        AaServerManual.checkSoon(this);
        // Con HeadQLink delante, el servicio recupera la ubicación «mientras se usa» si pasó a primer plano sin ella.
        LinkService.appShown("pantalla principal");
        // Versión nueva en GitHub (como mucho una consulta cada 6 h; con el extra force_update_check, ahora: pruebas).
        boolean forceCheck = getIntent().getBooleanExtra("force_update_check", false);
        getIntent().removeExtra("force_update_check");
        UpdateCheck.check(this, forceCheck, r -> {
            if (updateRow == null) return;
            updateRow.setVisibility(r == null ? View.GONE : View.VISIBLE);
            if (r == null) return;
            updateRow.setText(Str.get(R.string.hql_update_available, r.version));
            updateRow.setOnClickListener(v -> showReleaseNotes(Str.get(R.string.hql_update_title, r.version), r, true));
        });
        if (hotspotNow()) {
            // Estado de la zona Wi-Fi también sin conectar (fuera del hilo principal: escanea interfaces).
            new Thread(() -> {
                hotspotProbe = HotspotWatcher.probe(this);
                runOnUiThread(this::render);
            }, "hql-hotspot-probe").start();
        }
        // Requisitos (también lo lento: zona Wi-Fi y puerto UDP), fuera del hilo principal.
        new Thread(() -> {
            java.util.List<Requirements.Item> items = Checklist.evaluateNow(this);
            int n = Requirements.missingCount(items);
            boolean blocks = !Requirements.blocking(items).isEmpty();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                setRow(reqRow, n == 0 ? LinkState.Level.OK : blocks ? LinkState.Level.ERROR : LinkState.Level.BUSY,
                        n == 0 ? Str.get(R.string.hql_req_all_ok) : getResources().getQuantityString(R.plurals.hql_req_missing, n, n));
            });
        }, "hql-req-home").start();
    }

    @Override
    protected void onPause() {
        LinkState.removeListener(this);
        pulse(false);
        super.onPause();
    }

    @Override
    public void onLinkStateChanged() {
        render();
    }

    /**
     * Arranque manual con los intentos fallando (AA no atiende): la fila «Auto» («Esperando al servidor de Android Auto»)
     * y el texto de debajo (cómo pararlo y volver a iniciarlo) lo dicen.
     */
    private boolean waitingForAaServer() {
        return LinkState.running && Config.isAa(cfg.mode()) && AaServerManual.isWaiting();
    }

    private void openAaForServer() {
        L.i("pantalla principal: abro Android Auto para arrancar su servidor (arranque manual)");
        try {
            AaServerStarter.openAaSettings(this);
        } catch (RuntimeException e) {
            L.w("no se pudieron abrir los ajustes de Android Auto: " + e.getMessage());
            PowerHelper.openAppDetails(this, AaServerStarter.AA_PKG);
        }
    }

    /** La conexión es la zona Wi-Fi: la del servicio en marcha o, parado, la configurada. */
    private boolean hotspotNow() {
        return Config.LINK_HOTSPOT.equals(LinkState.linkModeFor(cfg));
    }

    /** Nivel de la fila «Red»: el del servicio en marcha o, sin él, el de la comprobación de esta pantalla. */
    private LinkState.Level networkLevel() {
        if (LinkState.running && !LinkState.network.isEmpty()) return LinkState.networkLevel;
        HotspotWatcher.Probe p = hotspotProbe;
        return p != null ? HotspotWatcher.level(p.getState()) : LinkState.Level.IDLE;
    }

    private TextView updateRow;

    /** Mando Bluetooth con un juego NES en el coche: con HeadQLink delante llegan las teclas y la cruceta (ejes). */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (NesInput.pad != null && e.getKeyCode() != android.view.KeyEvent.KEYCODE_BACK
                && NesInput.key(e.getKeyCode(), e.getAction())) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        if (NesInput.pad != null && NesInput.motion(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    private void render() {
        String mode = cfg.mode();
        // La conexión con la que va el servicio; si se cambió en marcha, la nueva se aplica al volver a conectar.
        String link = Ui.linkTitle(LinkState.linkModeFor(cfg));
        if (LinkState.running && !LinkState.usbOverride && !LinkState.activeLinkMode.isEmpty() && !LinkState.activeLinkMode.equals(cfg.linkMode())) {
            link = Str.get(R.string.hql_link_pending, link, Ui.linkTitle(cfg.linkMode()));
        }
        modeView.setText(Ui.modeTitle(mode) + " · " + link);
        if (Config.MODE_APP.equals(mode)) {
            String label = Ui.appLabel(this, cfg.targetPackage());
            modeDetail.setText(label != null ? Str.get(R.string.hql_home_opens_app, label) : Str.get(R.string.hql_home_no_app));
        } else {
            modeDetail.setText(Ui.modeDetail(mode));
        }

        boolean running = LinkState.running;
        // «Preparando…»: arrancando el servidor de Android Auto antes del servicio (también desde el widget).
        boolean starting = LinkState.preparing;
        switch (LinkState.car) {
            case CONNECTED:
                setRow(carRow, LinkState.Level.OK, Str.get(R.string.hql_connected) + (LinkState.carDetail.isEmpty() ? "" : " · " + LinkState.carDetail));
                break;
            case SEEN:
                setRow(carRow, LinkState.Level.BUSY, Str.get(R.string.hql_found_connecting));
                break;
            case RECONNECTING:
                setRow(carRow, LinkState.Level.BUSY, Str.get(R.string.hql_reconnecting));
                break;
            case SEARCHING:
                setRow(carRow, LinkState.Level.BUSY, Str.get(R.string.hql_searching));
                break;
            default:
                setRow(carRow, LinkState.Level.IDLE, Str.get(R.string.hql_disconnected));
        }
        String net = LinkState.running ? LinkState.network : "";
        if (net.isEmpty() && hotspotNow() && hotspotProbe != null) net = HotspotWatcher.text(hotspotProbe);
        setRow(networkRow, networkLevel(), net.isEmpty() ? "—" : net);
        String video = LinkState.video;
        setRow(videoRow, video.isEmpty() ? LinkState.Level.IDLE : LinkState.Level.OK, video.isEmpty() ? "—" : video);
        renderMeters(video);

        boolean showSource = !Config.MODE_PATTERN.equals(mode);
        sourceRow.setVisibility(showSource ? View.VISIBLE : View.GONE);
        if (showSource) {
            // «Auto» sale de la cadena: en neerlandés «auto» es coche y sería «Android Auto».
            ((TextView) sourceRow.findViewById(R.id.hql_status_name)).setText(Config.isAa(mode) ? Str.get(R.string.hql_mode_aa) : "App");
            String src = starting ? Str.get(R.string.hql_preparing) : LinkState.source;
            setRow(sourceRow, starting ? LinkState.Level.BUSY : LinkState.sourceLevel, src.isEmpty() ? "—" : src);
        }

        toggle.setText(starting ? Str.get(R.string.hql_preparing) : running ? Str.get(R.string.hql_disconnect) : Str.get(R.string.hql_connect));
        toggle.setEnabled(!starting);
        // CONECTAR en degradado con brillo cian; en marcha, DESCONECTAR oscuro con filo rojo (hql_btn_primary).
        toggle.setActivated(running && !starting);
        toggle.setElevation(starting ? 0 : 10 * getResources().getDisplayMetrics().density);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            int glow = getColor(running ? R.color.hql_error : R.color.hql_accent);
            toggle.setOutlineSpotShadowColor(glow);
            toggle.setOutlineAmbientShadowColor(glow);
        }
        boolean waitServer = waitingForAaServer();
        hint.setOnClickListener(waitServer ? v -> openAaForServer() : null);
        hint.setClickable(waitServer);
        if (!running) {
            hint.setText(Str.get(R.string.hql_hint_idle));
        } else if (waitServer) {
            hint.setText(Str.get(R.string.hql_aa_server_wait_hint));
        } else if (LinkState.car != LinkState.Car.CONNECTED) {
            hint.setText(Str.get(hotspotNow() ? R.string.hql_hint_searching_hotspot : R.string.hql_hint_searching));
        } else {
            hint.setText(Str.get(R.string.hql_hint_connected));
        }
    }

    /**
     * Panel «En directo»: las cifras de LinkState.video (fps y Mbps) en grande, con barras sobre lo que da el perfil de
     * imagen (o el ajuste manual). Sin vídeo, «—» y las barras apagadas.
     */
    private void renderMeters(String video) {
        float[] v = Meters.parse(video);
        if (v == null) {
            fpsValue.setText("—");
            mbpsValue.setText("—");
            fpsMeter.setLevel(0);
            mbpsMeter.setLevel(0);
            Ui.dot(liveDot, LinkState.Level.IDLE);
            pulse(false);
            live.setContentDescription(Str.get(R.string.hql_live) + ": —");
            return;
        }
        if (fpsMax <= 0) {
            VideoProfile p = cfg.videoProfile();
            int manualFps = cfg.getInt(Config.FPS);
            int manualKbps = cfg.getInt(Config.KBPS);
            fpsMax = manualFps > 0 ? manualFps : p.fps;
            mbpsMax = manualKbps > 0 ? manualKbps / 1000f : Math.max(p.maxBitrate, p.bitrate) / 1e6f;
            if (mbpsMax <= 0) mbpsMax = 8;
        }
        java.util.Locale loc = java.util.Locale.getDefault();
        fpsValue.setText(String.format(loc, "%.0f", v[0]));
        mbpsValue.setText(String.format(loc, "%.1f", v[1]));
        fpsMeter.setLevel(Meters.level(v[0], fpsMax));
        mbpsMeter.setLevel(Meters.level(v[1], mbpsMax));
        Ui.dot(liveDot, LinkState.Level.OK);
        pulse(true);
        live.setContentDescription(Str.get(R.string.hql_live) + ": " + video);
    }

    /** El punto de «En directo» late mientras llega vídeo. */
    private void pulse(boolean on) {
        if (on) {
            if (livePulse == null) {
                livePulse = android.animation.ObjectAnimator.ofFloat(liveDot, View.ALPHA, 1f, 0.25f);
                livePulse.setDuration(900);
                livePulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                livePulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            }
            if (!livePulse.isStarted()) livePulse.start();
        } else if (livePulse != null) {
            livePulse.cancel();
            liveDot.setAlpha(1f);
        }
    }

    private View statusRow(LinearLayout parent, String name, int icon) {
        if (parent.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(getColor(R.color.hql_outline));
            parent.addView(div, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
        View r = LayoutInflater.from(this).inflate(R.layout.hql_status_row, parent, false);
        ((TextView) r.findViewById(R.id.hql_status_name)).setText(name);
        ((android.widget.ImageView) r.findViewById(R.id.hql_status_icon)).setImageResource(icon);
        parent.addView(r);
        return r;
    }

    private void setRow(View row, LinkState.Level level, String value) {
        Ui.chip(row.findViewById(R.id.hql_status_value), level, value);
    }

    /**
     * Conexión automática por Bluetooth: necesita leer el nombre de los dispositivos (permiso
     * «Dispositivos cercanos») y, para que Android deje arrancar en segundo plano, que HeadQLink no
     * tenga la optimización de batería. Se puede cambiar el texto que se busca en el nombre.
     */
    private void setBtAuto(boolean on) {
        cfg.putBool(Config.BT_AUTO, on);
        if (!on) return;
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Checklist.markAsked(this, Checklist.KEY_BT);
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 3);
        }
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView info = new TextView(this);
        info.setText(Str.get(R.string.hql_bt_auto_info)
                + (pm.isIgnoringBatteryOptimizations(getPackageName()) ? "" : "\n\n" + Str.get(R.string.hql_bt_auto_battery)));
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(cfg.btAutoName());
        box.addView(info);
        box.addView(name);
        new MaterialAlertDialogBuilder(this)
                .setTitle(Str.get(R.string.hql_bt_auto_title))
                .setView(box)
                .setPositiveButton(Str.get(R.string.hql_save), (d, w) -> {
                    String n = name.getText().toString().trim();
                    cfg.setBtAutoName(n.isEmpty() ? "Leapmotor_BT" : n);
                    if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (RuntimeException e) {
                            L.w("no se pudo abrir el ajuste de batería: " + e.getMessage());
                        }
                    }
                })
                .setNegativeButton(Str.get(R.string.hql_cancel), (d, w) -> {
                    cfg.putBool(Config.BT_AUTO, false);
                    btAuto.setChecked(false);
                })
                .show();
    }

    /**
     * Menú del engranaje: comprobación de requisitos, ajustes de imagen, listas de TV y radio (solo
     * Auto extendido), datos del coche (cuenta Leapmotor), idioma, añadir el widget (y, con Android 13+, el botón de los
     * ajustes rápidos) y diagnóstico.
     * (Sin «Tema»: la app va siempre en oscuro, estilo «Eléctrico».)
     */
    /** Notas de una versión (de GitHub, en texto llano) con el enlace a la release; download: botón «Descargar». */
    private void showReleaseNotes(String title, UpdateCheck.Release r, boolean download) {
        String notes = UpdateCheck.plainNotes(r.notes);
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(notes.isEmpty() ? Str.get(R.string.hql_update_no_notes) : notes)
                .setNegativeButton(Str.get(R.string.hql_close), null);
        if (download && r.hasApk()) {
            // Dentro de la app: baja el APK y abre el instalador. GitHub en el botón del medio, por si se prefiere.
            b.setPositiveButton(Str.get(R.string.hql_update_install), (d, w) -> downloadAndInstall(r));
            b.setNeutralButton(Str.get(R.string.hql_update_on_github), (d, w) ->
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(r.url))));
        } else {
            b.setPositiveButton(Str.get(download ? R.string.hql_update_download : R.string.hql_update_on_github), (d, w) ->
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(r.url))));
        }
        b.show();
    }

    /** «Buscar versión nueva»: consulta GitHub ahora; si hay una más nueva, la fila y su diálogo; si no, un aviso. */
    private void checkUpdateNow() {
        android.widget.Toast.makeText(this, Str.get(R.string.hql_update_checking), android.widget.Toast.LENGTH_SHORT).show();
        UpdateCheck.check(this, true, r -> {
            if (isFinishing()) return;
            if (r == null) {
                android.widget.Toast.makeText(this, Str.get(R.string.hql_update_latest, Ui.appVersionLabel(this)),
                        android.widget.Toast.LENGTH_LONG).show();
                if (updateRow != null) updateRow.setVisibility(View.GONE);
                return;
            }
            updateRow.setVisibility(View.VISIBLE);
            updateRow.setText(Str.get(R.string.hql_update_available, r.version));
            updateRow.setOnClickListener(v -> showReleaseNotes(Str.get(R.string.hql_update_title, r.version), r, true));
            showReleaseNotes(Str.get(R.string.hql_update_title, r.version), r, true);
        });
    }

    /** Baja el APK con una barra de progreso y abre el instalador; antes, si hace falta, el permiso de apps desconocidas. */
    private void downloadAndInstall(UpdateCheck.Release r) {
        if (!UpdateInstaller.canInstall(this)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(Str.get(R.string.hql_update_install))
                    .setMessage(Str.get(R.string.hql_update_allow_unknown))
                    .setPositiveButton(Str.get(R.string.hql_update_open_setting), (d, w) -> UpdateInstaller.openUnknownSourcesSetting(this))
                    .setNegativeButton(Str.get(R.string.hql_cancel), null)
                    .show();
            return;
        }
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad / 2, pad, 0);
        androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(Str.get(R.string.hql_update_downloading, r.version))
                .setView(bar)
                .setCancelable(false)
                .show();
        UpdateInstaller.download(this, r, new UpdateInstaller.Listener() {
            @Override
            public void onProgress(int pct) {
                if (pct >= 0) {
                    bar.setIndeterminate(false);
                    bar.setProgress(pct);
                }
            }

            @Override
            public void onDone(java.io.File apk, String error) {
                dlg.dismiss();
                if (isFinishing()) return;
                if (apk == null) {
                    new MaterialAlertDialogBuilder(HomeActivity.this)
                            .setTitle(Str.get(R.string.hql_update_install))
                            .setMessage(Str.get(R.string.hql_update_download_failed, error))
                            .setPositiveButton(Str.get(R.string.hql_update_on_github), (d, w) ->
                                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(r.url))))
                            .setNegativeButton(Str.get(R.string.hql_close), null)
                            .show();
                    return;
                }
                UpdateInstaller.install(HomeActivity.this, apk);
            }
        });
    }

    private void showMenu(View anchor) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        android.view.Menu m = pm.getMenu();
        boolean ext = Config.MODE_AA_EXT.equals(cfg.mode());
        m.add(0, 7, 0, Str.get(R.string.hql_req_title));
        m.add(0, 1, 1, Str.get(R.string.hql_image_settings));
        if (ext) {
            m.add(0, 2, 2, Str.get(R.string.hql_tv_list));
            m.add(0, 3, 3, Str.get(R.string.hql_radio_list));
            m.add(0, 12, 3, Str.get(R.string.hql_web_bookmarks));
            m.add(0, 14, 3, Str.get(R.string.hql_nes_folder));
        }
        // Datos reales del coche (cuenta Leapmotor, opcional y de solo lectura): CarCloudActivity.
        m.add(0, 10, 3, Str.get(R.string.hql_cloud_menu));
        m.add(0, 13, 3, Str.get(R.string.hql_update_check));
        if (android.os.Build.VERSION.SDK_INT >= 33) m.add(0, 4, 4, Str.get(R.string.hql_language));
        if (android.os.Build.VERSION.SDK_INT >= 26) m.add(0, 8, 5, Str.get(R.string.hql_w_add_widget));
        if (android.os.Build.VERSION.SDK_INT >= 33) m.add(0, 9, 5, Str.get(R.string.hql_w_add_tile));
        // Coche virtual: la pantalla del coche en el móvil (Android Auto, nuestras pantallas y rutas reales), sin coche.
        m.add(0, 11, 6, Str.get(R.string.hql_sim_menu));
        m.add(0, 6, 6, Str.get(R.string.hql_diagnostics));
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    showVideoSettings();
                    break;
                case 2:
                    showList(false);
                    break;
                case 3:
                    showList(true);
                    break;
                case 4:
                    showLanguage();
                    break;
                case 7:
                    startActivity(new Intent(this, ChecklistActivity.class));
                    break;
                case 8:
                    WidgetUpdater.requestPin(this);
                    break;
                case 9:
                    LinkTileService.requestAdd(this);
                    break;
                case 10:
                    startActivity(new Intent(this, CarCloudActivity.class));
                    break;
                case 12:
                    showBookmarks();
                    break;
                case 13:
                    checkUpdateNow();
                    break;
                case 14:
                    // Carpeta de ROMs: permiso persistente sobre el árbol elegido (ver onActivityResult).
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_NES_TREE);
                    break;
                case 11:
                    if (LinkState.running && (LinkState.car == LinkState.Car.CONNECTED || LinkState.car == LinkState.Car.RECONNECTING)) {
                        // Con el coche de verdad conectado, no.
                        android.widget.Toast.makeText(this, Str.get(R.string.hql_preview_busy), android.widget.Toast.LENGTH_LONG).show();
                    } else {
                        // Si solo está buscando el coche, el coche virtual para la búsqueda y entra.
                        startActivity(new Intent(this, CarSimActivity.class));
                    }
                    break;
                default:
                    startActivity(new Intent(this, LogActivity.class));
            }
            return true;
        });
        pm.show();
    }

    /**
     * Idioma de la app (Android 13+): el del sistema, español, inglés, portugués (Portugal o Brasil), italiano, francés, alemán
     * o neerlandés. Android recrea las pantallas
     * y la interfaz del coche usa el nuevo al volver a conectar.
     */
    @android.annotation.TargetApi(33)
    private void showLanguage() {
        android.app.LocaleManager lm = getSystemService(android.app.LocaleManager.class);
        String cur = lm.getApplicationLocales().isEmpty() ? "" : lm.getApplicationLocales().get(0).getLanguage();
        String[] tags = {"", "es", "en", "pt-PT", "pt-BR", "it", "fr", "de", "nl"};
        String[] names = {Str.get(R.string.hql_language_system), "Español", "English", "Português (Portugal)", "Português (Brasil)",
                "Italiano", "Français", "Deutsch", "Nederlands"};
        String curTag = lm.getApplicationLocales().isEmpty() ? "" : lm.getApplicationLocales().get(0).toLanguageTag();
        int checked = 0;
        for (int i = 1; i < tags.length; i++) {
            if (tags[i].equals(curTag) || (tags[i].indexOf('-') < 0 && tags[i].equals(cur) && !cur.equals("pt"))) checked = i;
        }
        if (checked == 0 && cur.equals("pt")) checked = curTag.equals("pt-BR") ? 4 : 3;
        new MaterialAlertDialogBuilder(this)
                .setTitle(Str.get(R.string.hql_language))
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    d.dismiss();
                    lm.setApplicationLocales(tags[which].isEmpty() ? android.os.LocaleList.getEmptyLocaleList()
                            : android.os.LocaleList.forLanguageTags(tags[which]));
                })
                .setNegativeButton(Str.get(R.string.hql_cancel), null)
                .show();
    }

    /** Lista M3U para la TV o la radio del coche: una URL o un archivo del móvil. */
    /**
     * Marcadores de la pantalla Web del coche: lista con subir, bajar y quitar, y un formulario para añadir. Cada cambio
     * se guarda al momento; «Los de serie» vuelve a los cuatro de siempre.
     */
    private void showBookmarks() {
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView help = new TextView(this);
        help.setText(Str.get(R.string.hql_bookmarks_help));
        box.addView(help);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        box.addView(rows);
        EditText name = new EditText(this);
        name.setHint(Str.get(R.string.hql_bookmark_name));
        name.setSingleLine(true);
        EditText url = new EditText(this);
        url.setHint(Str.get(R.string.hql_bookmark_url));
        url.setSingleLine(true);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(name);
        box.addView(url);
        android.widget.Button add = new android.widget.Button(this);
        add.setText(Str.get(R.string.hql_bookmark_add));
        box.addView(add);
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            rows.removeAllViews();
            String[][] list = cfg.webShortcuts();
            for (int i = 0; i < list.length; i++) {
                int idx = i;
                LinearLayout r = new LinearLayout(this);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(android.view.Gravity.CENTER_VERTICAL);
                TextView t = new TextView(this);
                t.setText(list[i][0] + "\n" + list[i][1]);
                t.setSingleLine(false);
                t.setMaxLines(2);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                String[] glyphs = {"\u25b2", "\u25bc", "\u2715"};
                for (int k = 0; k < 3; k++) {
                    int op = k;
                    android.widget.Button b = new android.widget.Button(this, null, android.R.attr.borderlessButtonStyle);
                    b.setText(glyphs[k]);
                    b.setMinWidth(0);
                    b.setMinimumWidth(0);
                    b.setEnabled(op == 2 || (op == 0 ? idx > 0 : idx < list.length - 1));
                    b.setOnClickListener(v -> {
                        java.util.List<String[]> l = new java.util.ArrayList<>(java.util.Arrays.asList(cfg.webShortcuts()));
                        if (op == 2) l.remove(idx);
                        else {
                            String[] e = l.remove(idx);
                            l.add(op == 0 ? idx - 1 : idx + 1, e);
                        }
                        cfg.setWebShortcuts(l.toArray(new String[0][]));
                        render[0].run();
                    });
                    r.addView(b);
                }
                rows.addView(r);
            }
        };
        add.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            if (u.isEmpty()) return;
            java.util.List<String[]> l = new java.util.ArrayList<>(java.util.Arrays.asList(cfg.webShortcuts()));
            l.add(new String[]{name.getText().toString().trim(), u});
            // Por webShortcutsFrom: completa «https://» y el nombre si falta.
            cfg.setWebShortcuts(Config.webShortcutsFrom(Config.webShortcutsJson(l.toArray(new String[0][]))));
            name.setText("");
            url.setText("");
            render[0].run();
        });
        render[0].run();
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new MaterialAlertDialogBuilder(this)
                .setTitle(Str.get(R.string.hql_web_bookmarks))
                .setView(sv)
                .setPositiveButton(Str.get(R.string.hql_close), null)
                .setNeutralButton(Str.get(R.string.hql_bookmark_defaults), (d, w) -> cfg.setWebShortcuts(WebScreen.DEFAULT_SHORTCUTS))
                .show();
    }

    private void showList(boolean radio) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText url = new EditText(this);
        url.setHint(Str.get(R.string.hql_list_url_hint));
        url.setSingleLine(true);
        String cur = radio ? cfg.radioSource() : cfg.iptvSource();
        if (cur.startsWith("http")) url.setText(cur);
        TextView info = new TextView(this);
        info.setText(cur.startsWith("content://") ? Str.get(R.string.hql_list_now_file) : Str.get(R.string.hql_list_paste)
                + (radio ? " " + Str.get(R.string.hql_list_radio_default) : ""));
        box.addView(info);
        box.addView(url);
        new MaterialAlertDialogBuilder(this)
                .setTitle(radio ? Str.get(R.string.hql_radio_list) : Str.get(R.string.hql_tv_list))
                .setView(box)
                .setPositiveButton(Str.get(R.string.hql_save), (d, w) -> {
                    String s = url.getText().toString().trim();
                    if (radio) cfg.setRadioSource(s.isEmpty() && cur.startsWith("http") ? "" : s.isEmpty() ? cur : s);
                    else if (!s.isEmpty()) cfg.setIptvSource(s);
                })
                .setNeutralButton(Str.get(R.string.hql_choose_file), (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    startActivityForResult(i, radio ? REQ_RADIO_FILE : REQ_IPTV_FILE);
                })
                .setNegativeButton(Str.get(R.string.hql_cancel), null)
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CHECKLIST) {
            // «Conectar» o «Conectar igualmente» en la comprobación.
            if (res == RESULT_OK && !LinkState.running) LinkControl.start(this, "pantalla principal", null);
            return;
        }
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
        if (req == REQ_NES_TREE && !uris.isEmpty()) {
            cfg.setNesTree(uris.get(0).toString());
            L.i("NES: carpeta de ROMs elegida");
            android.widget.Toast.makeText(this, Str.get(R.string.hql_nes_folder_set), android.widget.Toast.LENGTH_LONG).show();
        }
        if (req == REQ_IPTV_FILE && !uris.isEmpty()) {
            cfg.setIptvSource(uris.get(0).toString());
        }
        if (req == REQ_RADIO_FILE && !uris.isEmpty()) {
            cfg.setRadioSource(uris.get(0).toString());
        }
    }

    /**
     * Ajustes de imagen: perfil (Alto / Medio / Básico, o el recomendado para este móvil, marcado
     * como «Recomendado») y, plegado, lo avanzado. Cambiar de perfil reconecta coche y AA solos.
     */
    private void showVideoSettings() {
        View v = LayoutInflater.from(this).inflate(R.layout.hql_dialog_video, null);
        EditText fps = v.findViewById(R.id.hql_v_fps);
        EditText kbps = v.findViewById(R.id.hql_v_kbps);
        EditText w = v.findViewById(R.id.hql_v_w);
        EditText h = v.findViewById(R.id.hql_v_h);
        RadioGroup profile = v.findViewById(R.id.hql_v_profile);
        RadioGroup profiles = v.findViewById(R.id.hql_v_profiles);
        android.widget.CheckBox brake = v.findViewById(R.id.hql_v_brake);
        android.widget.CheckBox lowLat = v.findViewById(R.id.hql_v_lowlatency);
        android.widget.CheckBox autoHide = v.findViewById(R.id.hql_v_autohide);
        android.widget.CheckBox keepScreen = v.findViewById(R.id.hql_v_keep_screen);
        View advanced = v.findViewById(R.id.hql_v_advanced);
        TextView advToggle = v.findViewById(R.id.hql_v_adv_toggle);
        advToggle.setOnClickListener(x -> {
            boolean show = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(show ? View.VISIBLE : View.GONE);
            advToggle.setText(show ? Str.get(R.string.hql_advanced) + " ▴" : Str.get(R.string.hql_advanced) + " ▾");
        });

        // Perfiles: "Automático" sigue al recomendado; el recomendado lleva la marca aunque se elija otro.
        String rec = VideoProfile.recommended(this);
        String chosen = cfg.videoProfileChoice();
        String[] ids = new String[VideoProfile.ALL.length + 1];
        ids[0] = "";
        System.arraycopy(VideoProfile.ALL, 0, ids, 1, VideoProfile.ALL.length);
        for (int i = 0; i < ids.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(ids[i]);
            String title = ids[i].isEmpty() ? Str.get(R.string.hql_profile_auto_named, VideoProfile.title(rec)) : VideoProfile.title(ids[i]);
            String tag = rec.equals(ids[i]) ? "  · " + Str.get(R.string.hql_recommended) : "";
            String detail = ids[i].isEmpty() ? Str.get(R.string.hql_profile_auto_detail) : VideoProfile.detail(ids[i]);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(title + tag + "\n" + detail);
            if (!tag.isEmpty()) {
                sb.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.hql_ok)), title.length(), title.length() + tag.length(), 0);
            }
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.82f), title.length() + tag.length() + 1, sb.length(), 0);
            rb.setText(sb);
            rb.setPadding(rb.getPaddingLeft(), 8, 0, 8);
            profiles.addView(rb);
            if (ids[i].equals(chosen)) rb.setChecked(true);
        }
        ((TextView) v.findViewById(R.id.hql_v_reason)).setText(Str.get(R.string.hql_this_phone, VideoProfile.reason()));
        // Fluidez: 30 fps (lo que pide el coche, menos calor) o 60 fps; cuenta con Coche y con Automático si es Coche.
        RadioGroup fluidity = v.findViewById(R.id.hql_v_fluidity);
        int fluidBefore = cfg.fluidity();
        fluidity.check(fluidBefore == VideoProfile.FLUID_60 ? R.id.hql_v_fluid_60 : R.id.hql_v_fluid_30);

        boolean lowLatBefore = cfg.lowLatency();
        lowLat.setChecked(lowLatBefore);
        autoHide.setChecked(cfg.panelAutoHide());
        boolean keepScreenBefore = cfg.keepScreenOn();
        keepScreen.setChecked(keepScreenBefore);
        // Protección térmica: Normal, Suave o Apagada (ThermalPolicy); se aplica al vídeo vivo al guardar.
        RadioGroup thermal = v.findViewById(R.id.hql_v_thermal);
        String thermalBefore = cfg.thermalMode();
        thermal.check(ThermalPolicy.MODE_SOFT.equals(thermalBefore) ? R.id.hql_v_thermal_soft
                : ThermalPolicy.MODE_OFF.equals(thermalBefore) ? R.id.hql_v_thermal_off : R.id.hql_v_thermal_normal);
        EditText window = v.findViewById(R.id.hql_v_window);
        brake.setChecked(cfg.aaBrake());
        fill(window, Config.AA_WINDOW);
        fill(fps, Config.FPS);
        fill(kbps, Config.KBPS);
        fill(w, Config.WIDTH);
        fill(h, Config.HEIGHT);
        profile.check("main".equals(cfg.profile()) ? R.id.hql_v_main
                : "high".equals(cfg.profile()) ? R.id.hql_v_high : R.id.hql_v_baseline);
        RadioGroup engine = v.findViewById(R.id.hql_v_engine);
        String engineBefore = cfg.linkEngine();
        engine.check(Config.ENGINE_QDAUTO.equals(engineBefore) ? R.id.hql_v_engine_qdauto : R.id.hql_v_engine_original);
        // «Esperar al coche»: una opción por minuto de Config.CAR_WAIT_CHOICES; la de por defecto, marcada.
        RadioGroup carWait = v.findViewById(R.id.hql_v_car_wait);
        int waitBefore = cfg.carWaitMin();
        for (int min : Config.CAR_WAIT_CHOICES) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(min);
            rb.setText(min == Config.DEFAULT_CAR_WAIT_MIN
                    ? Str.get(R.string.hql_car_wait_option_default, min) : Str.get(R.string.hql_car_wait_option, min));
            carWait.addView(rb);
            if (min == waitBefore) rb.setChecked(true);
        }
        // «Arranque del servidor de Android Auto» (solo en los modos con Android Auto).
        v.findViewById(R.id.hql_v_aa_server_box).setVisibility(Config.isAa(cfg.mode()) ? View.VISIBLE : View.GONE);
        RadioGroup aaServer = v.findViewById(R.id.hql_v_aa_server);
        boolean manualBefore = cfg.aaServerManual();
        aaServer.check(manualBefore ? R.id.hql_v_aa_server_manual : R.id.hql_v_aa_server_auto);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(v);
        new MaterialAlertDialogBuilder(this)
                .setTitle(Str.get(R.string.hql_image_settings))
                .setView(scroll)
                .setPositiveButton(Str.get(R.string.hql_save), (d, which) -> {
                    VideoProfile before = cfg.videoProfile();
                    View sel = profiles.findViewById(profiles.getCheckedRadioButtonId());
                    String pick = sel != null ? (String) sel.getTag() : chosen;
                    boolean profileChanged = !pick.equals(chosen);
                    // Elegir perfil quita los ajustes manuales viejos (fps, tamaño, recodificar).
                    if (profileChanged) cfg.setVideoProfile(pick);
                    int fluidAfter = fluidity.getCheckedRadioButtonId() == R.id.hql_v_fluid_60 ? VideoProfile.FLUID_60 : VideoProfile.FLUID_30;
                    if (fluidAfter != fluidBefore) {
                        cfg.setFluidity(fluidAfter);
                        L.i("fluidez: " + fluidAfter + " fps (perfil " + cfg.videoProfile().id + "; "
                                + (LinkState.running ? "reconecta ahora" : "se aplica en la próxima sesión") + ")");
                    }
                    save(fps, Config.FPS);
                    save(kbps, Config.KBPS);
                    save(w, Config.WIDTH);
                    save(h, Config.HEIGHT);
                    save(window, Config.AA_WINDOW);
                    cfg.putBool(Config.AA_BRAKE, brake.isChecked());
                    // Ajuste manual solo si el usuario lo ha tocado; si no, manda el perfil.
                    if (lowLat.isChecked() != lowLatBefore) cfg.putBool(Config.LOW_LATENCY, lowLat.isChecked());
                    cfg.putBool(Config.PANEL_AUTOHIDE, autoHide.isChecked());
                    if (keepScreen.isChecked() != keepScreenBefore) {
                        // Se aplica ya si Android Auto está en marcha (modo coche con o sin ALLOW_SLEEP).
                        cfg.putBool(Config.KEEP_SCREEN_ON, keepScreen.isChecked());
                        L.i("pantalla: «Mantener la pantalla del móvil encendida» " + (keepScreen.isChecked() ? "sí" : "no"));
                        com.andrerinas.openheadunit.aap.AapService.refreshCarModeFlags();
                    }
                    int thermalId = thermal.getCheckedRadioButtonId();
                    String thermalAfter = thermalId == R.id.hql_v_thermal_soft ? ThermalPolicy.MODE_SOFT
                            : thermalId == R.id.hql_v_thermal_off ? ThermalPolicy.MODE_OFF : ThermalPolicy.MODE_NORMAL;
                    if (!thermalAfter.equals(thermalBefore)) {
                        cfg.setThermalMode(thermalAfter);
                        L.i("protección térmica: " + ThermalPolicy.modeName(thermalAfter) + " (se aplica en el acto)");
                        ThermalGuard.onModeChanged();
                    }
                    int id = profile.getCheckedRadioButtonId();
                    cfg.setProfile(id == R.id.hql_v_main ? "main" : id == R.id.hql_v_high ? "high" : "baseline");
                    View waitSel = carWait.findViewById(carWait.getCheckedRadioButtonId());
                    if (waitSel != null && (int) waitSel.getTag() != waitBefore) {
                        // Se lee al perder al coche: vale ya para la próxima espera, sin reconectar.
                        cfg.setCarWaitMin((int) waitSel.getTag());
                        L.life("«Esperar al coche»: " + cfg.carWaitMin() + " min (vale para la próxima espera)");
                    }
                    boolean manualAfter = aaServer.getCheckedRadioButtonId() == R.id.hql_v_aa_server_manual;
                    if (manualAfter != manualBefore) {
                        // Se lee en cada decisión: vale para lo siguiente que necesite el servidor, sin reconectar.
                        cfg.setAaServerManual(manualAfter);
                        AaServerManual.onModeChanged(this, manualAfter, "Ajustes de imagen");
                    }
                    String engineAfter = engine.getCheckedRadioButtonId() == R.id.hql_v_engine_qdauto
                            ? Config.ENGINE_QDAUTO : Config.ENGINE_ORIGINAL;
                    if (!engineAfter.equals(engineBefore)) {
                        cfg.setLinkEngine(engineAfter);
                        L.i("motor de protocolo: " + engineAfter + " (se aplica al volver a conectar)");
                        if (LinkState.running) {
                            android.widget.Toast.makeText(this, Str.get(R.string.hql_applies_on_reconnect), android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                    // Las barras de «En directo» se recalculan con el perfil (y la fluidez) nuevos.
                    fpsMax = 0;
                    mbpsMax = 0;
                    if (LinkState.running) {
                        // Sesión nueva con los ajustes; con otro perfil o fluidez, también AA (resolución y fps se
                        // negocian al conectar).
                        VideoProfile after = cfg.videoProfile();
                        boolean renegotiate = !before.id.equals(after.id) || before.fps != after.fps;
                        startForegroundService(LinkService.fromApp(new Intent(this, LinkService.class).setAction(LinkService.ACTION_APPLY)
                                .putExtra(LinkService.EXTRA_AA_RENEGOTIATE, renegotiate)));
                    }
                })
                .setNegativeButton(Str.get(R.string.hql_cancel), null)
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
