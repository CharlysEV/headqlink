package com.headqlink.link;

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
 * Pantalla principal de HeadQLink: modo elegido, estado (coche, imagen, Android Auto/app) y
 * Conectar/Desconectar. Abrirla ya configurada lo pone todo en marcha; al desconectarse el
 * coche, LinkService lo cierra todo.
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
    private boolean starting;
    private android.widget.CompoundButton btAuto;
    private static final int REQ_IPTV_FILE = 10;
    private static final int REQ_RADIO_FILE = 11;

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
        View menu = findViewById(R.id.hql_home_menu);
        menu.setOnClickListener(v -> showMenu(menu));
        btAuto = findViewById(R.id.hql_home_bt_auto);
        btAuto.setChecked(cfg.btAutoConnect());
        btAuto.setOnCheckedChangeListener((b2, on) -> setBtAuto(on));

        LinearLayout status = findViewById(R.id.hql_home_status);
        carRow = statusRow(status, Str.get(R.string.hql_car));
        videoRow = statusRow(status, Str.get(R.string.hql_image));
        sourceRow = statusRow(status, "");

        findViewById(R.id.hql_home_change).setOnClickListener(v ->
                startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_STEP, 1)));
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
                    .setTitle(Str.get(R.string.hql_accessibility_missing))
                    .setMessage(Str.get(R.string.hql_accessibility_missing_msg))
                    .setPositiveButton(Str.get(R.string.hql_enable), (d, w) -> startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton(Str.get(R.string.hql_cancel), null)
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
        if (Config.MODE_APP.equals(mode)) {
            String label = Ui.appLabel(this, cfg.targetPackage());
            modeDetail.setText(label != null ? Str.get(R.string.hql_home_opens_app, label) : Str.get(R.string.hql_home_no_app));
        } else {
            modeDetail.setText(Ui.modeDetail(mode));
        }

        boolean running = LinkState.running;
        switch (LinkState.car) {
            case CONNECTED:
                setRow(carRow, LinkState.Level.OK, Str.get(R.string.hql_connected) + (LinkState.carDetail.isEmpty() ? "" : " · " + LinkState.carDetail));
                break;
            case SEEN:
                setRow(carRow, LinkState.Level.BUSY, Str.get(R.string.hql_found_connecting));
                break;
            case SEARCHING:
                setRow(carRow, LinkState.Level.BUSY, Str.get(R.string.hql_searching));
                break;
            default:
                setRow(carRow, LinkState.Level.IDLE, Str.get(R.string.hql_disconnected));
        }
        String video = LinkState.video;
        setRow(videoRow, video.isEmpty() ? LinkState.Level.IDLE : LinkState.Level.OK, video.isEmpty() ? "—" : video);

        boolean showSource = !Config.MODE_PATTERN.equals(mode);
        sourceRow.setVisibility(showSource ? View.VISIBLE : View.GONE);
        if (showSource) {
            ((TextView) sourceRow.findViewById(R.id.hql_status_name)).setText(Config.isAa(mode) ? "Auto" : "App");
            String src = starting ? Str.get(R.string.hql_preparing) : LinkState.source;
            setRow(sourceRow, starting ? LinkState.Level.BUSY : LinkState.sourceLevel, src.isEmpty() ? "—" : src);
        }

        toggle.setText(starting ? Str.get(R.string.hql_preparing) : running ? Str.get(R.string.hql_disconnect) : Str.get(R.string.hql_connect));
        toggle.setEnabled(!starting);
        if (!running) {
            hint.setText(Str.get(R.string.hql_hint_idle));
        } else if (LinkState.car != LinkState.Car.CONNECTED) {
            hint.setText(Str.get(R.string.hql_hint_searching));
        } else {
            hint.setText(Str.get(R.string.hql_hint_connected));
        }
    }

    private View statusRow(LinearLayout parent, String name) {
        if (parent.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(getColor(R.color.hql_outline));
            parent.addView(div, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
        View r = LayoutInflater.from(this).inflate(R.layout.hql_status_row, parent, false);
        ((TextView) r.findViewById(R.id.hql_status_name)).setText(name);
        parent.addView(r);
        return r;
    }

    private void setRow(View row, LinkState.Level level, String value) {
        Ui.dot(row.findViewById(R.id.hql_status_dot), level);
        ((TextView) row.findViewById(R.id.hql_status_value)).setText(value);
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
     * Menú del engranaje: ajustes de imagen, listas de TV y radio (solo Auto extendido), idioma,
     * tema y diagnóstico.
     */
    private void showMenu(View anchor) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        android.view.Menu m = pm.getMenu();
        boolean ext = Config.MODE_AA_EXT.equals(cfg.mode());
        m.add(0, 1, 1, Str.get(R.string.hql_image_settings));
        if (ext) {
            m.add(0, 2, 2, Str.get(R.string.hql_tv_list));
            m.add(0, 3, 3, Str.get(R.string.hql_radio_list));
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) m.add(0, 4, 4, Str.get(R.string.hql_language));
        if (android.os.Build.VERSION.SDK_INT >= 31) m.add(0, 5, 5, Str.get(R.string.hql_theme));
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
                case 5:
                    showTheme();
                    break;
                default:
                    startActivity(new Intent(this, LogActivity.class));
            }
            return true;
        });
        pm.show();
    }

    /** Tema de la app (Android 12+): el del sistema, claro u oscuro. Android lo recuerda. */
    @android.annotation.TargetApi(31)
    private void showTheme() {
        android.app.UiModeManager um = getSystemService(android.app.UiModeManager.class);
        int[] modes = {android.app.UiModeManager.MODE_NIGHT_AUTO, android.app.UiModeManager.MODE_NIGHT_NO,
                android.app.UiModeManager.MODE_NIGHT_YES};
        String[] names = {Str.get(R.string.hql_theme_system), Str.get(R.string.hql_theme_light), Str.get(R.string.hql_theme_dark)};
        int cur = cfg.getInt(Config.THEME);
        new MaterialAlertDialogBuilder(this)
                .setTitle(Str.get(R.string.hql_theme))
                .setSingleChoiceItems(names, cur, (d, which) -> {
                    d.dismiss();
                    cfg.putInt(Config.THEME, which);
                    um.setApplicationNightMode(modes[which]);
                    recreate();
                })
                .setNegativeButton(Str.get(R.string.hql_cancel), null)
                .show();
    }

    /**
     * Idioma de la app (Android 13+): el del sistema, español o inglés. Android recrea las pantallas
     * y la interfaz del coche usa el nuevo al volver a conectar.
     */
    @android.annotation.TargetApi(33)
    private void showLanguage() {
        android.app.LocaleManager lm = getSystemService(android.app.LocaleManager.class);
        String cur = lm.getApplicationLocales().isEmpty() ? "" : lm.getApplicationLocales().get(0).getLanguage();
        String[] tags = {"", "es", "en"};
        String[] names = {Str.get(R.string.hql_language_system), "Español", "English"};
        int checked = cur.equals("es") ? 1 : cur.equals("en") ? 2 : 0;
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
    private void showList(boolean radio) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText url = new EditText(this);
        url.setHint("https://…/lista.m3u");
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
                sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF1E8E3E), title.length(), title.length() + tag.length(), 0);
            }
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.82f), title.length() + tag.length() + 1, sb.length(), 0);
            rb.setText(sb);
            rb.setPadding(rb.getPaddingLeft(), 8, 0, 8);
            profiles.addView(rb);
            if (ids[i].equals(chosen)) rb.setChecked(true);
        }
        ((TextView) v.findViewById(R.id.hql_v_reason)).setText(Str.get(R.string.hql_this_phone, VideoProfile.reason()));

        boolean lowLatBefore = cfg.lowLatency();
        lowLat.setChecked(lowLatBefore);
        autoHide.setChecked(cfg.panelAutoHide());
        EditText window = v.findViewById(R.id.hql_v_window);
        brake.setChecked(cfg.aaBrake());
        fill(window, Config.AA_WINDOW);
        fill(fps, Config.FPS);
        fill(kbps, Config.KBPS);
        fill(w, Config.WIDTH);
        fill(h, Config.HEIGHT);
        profile.check("main".equals(cfg.profile()) ? R.id.hql_v_main
                : "high".equals(cfg.profile()) ? R.id.hql_v_high : R.id.hql_v_baseline);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(v);
        new MaterialAlertDialogBuilder(this)
                .setTitle(Str.get(R.string.hql_image_settings))
                .setView(scroll)
                .setPositiveButton(Str.get(R.string.hql_save), (d, which) -> {
                    String before = cfg.videoProfile().id;
                    View sel = profiles.findViewById(profiles.getCheckedRadioButtonId());
                    String pick = sel != null ? (String) sel.getTag() : chosen;
                    boolean profileChanged = !pick.equals(chosen);
                    // Elegir perfil quita los ajustes manuales viejos (fps, tamaño, recodificar).
                    if (profileChanged) cfg.setVideoProfile(pick);
                    save(fps, Config.FPS);
                    save(kbps, Config.KBPS);
                    save(w, Config.WIDTH);
                    save(h, Config.HEIGHT);
                    save(window, Config.AA_WINDOW);
                    cfg.putBool(Config.AA_BRAKE, brake.isChecked());
                    // Ajuste manual solo si el usuario lo ha tocado; si no, manda el perfil.
                    if (lowLat.isChecked() != lowLatBefore) cfg.putBool(Config.LOW_LATENCY, lowLat.isChecked());
                    cfg.putBool(Config.PANEL_AUTOHIDE, autoHide.isChecked());
                    int id = profile.getCheckedRadioButtonId();
                    cfg.setProfile(id == R.id.hql_v_main ? "main" : id == R.id.hql_v_high ? "high" : "baseline");
                    if (LinkState.running) {
                        // Sesión nueva con los ajustes; con otro perfil, también AA (resolución y fps se negocian al conectar).
                        startForegroundService(new Intent(this, LinkService.class).setAction(LinkService.ACTION_APPLY)
                                .putExtra(LinkService.EXTRA_AA_RENEGOTIATE, !before.equals(cfg.videoProfile().id)));
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
