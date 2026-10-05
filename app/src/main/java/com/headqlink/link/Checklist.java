package com.headqlink.link;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;

/**
 * Comprobación de requisitos en pantalla: lee el estado del móvil, lo pasa por {@link Requirements} y pinta una fila
 * por requisito (icono, importancia, título, explicación y botón). Cada botón lleva a la pantalla exacta de Ajustes o
 * pide el permiso. Se actualiza sola: al volver a la pantalla ({@link #start}), con los avisos del sistema (Wi-Fi, zona
 * Wi-Fi, apps instaladas, notificaciones, accesibilidad) y con los del enlace (puerto UDP ocupado). La usan la pantalla
 * «Comprobación» ({@link ChecklistActivity}) y el paso 3 de la configuración; {@link #evaluateNow} sirve a «Conectar».
 */
final class Checklist {
    static final String QDLINK_PKG = "com.neusoft.qdrivelink";
    private static final int REQ_PERMS = 40;
    /** Prefijo de «este permiso ya se pidió» (para distinguir «se puede pedir» de «denegado para siempre»). */
    private static final String ASKED = "req_asked_";
    private static final String KEY_NOTIF = "notif";
    private static final String KEY_NEARBY = "nearby";
    static final String KEY_BT = "bt";
    private static final String KEY_MEDIA = "media";

    private final Activity act;
    private final Config cfg;
    private final Runnable onChange;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Lo lento (zona Wi-Fi y puerto), en un hilo aparte; mientras, «Comprobando…». */
    private volatile Requirements.Hotspot hotspot = Requirements.Hotspot.CHECKING;
    private volatile HotspotWatcher.Probe hotspotProbe;
    private volatile Requirements.Port port = Requirements.Port.UNKNOWN;
    /** Último wifi_state del aviso de la zona Wi-Fi (-1 = ninguno). */
    private volatile int apState = -1;
    private boolean probing;
    private boolean probeAgain;

    private boolean started;
    /** El usuario fue a los ajustes de AA a activar el modo desarrollador: comprobar al volver. */
    private boolean awaitingDevMode;
    private boolean checkingDevMode;
    private List<Requirements.Item> items = Collections.emptyList();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (HotspotWatcher.ACTION_AP_STATE.equals(i.getAction())) apState = i.getIntExtra("wifi_state", -1);
            refresh();
        }
    };
    private final Runnable refreshLater = this::refresh;
    private final ContentObserver accessibilityObserver = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            refresh();
            // TouchService se conecta un poco después de cambiar el ajuste.
            main.removeCallbacks(refreshLater);
            main.postDelayed(refreshLater, 1200);
        }
    };
    /** Del enlace solo importan «en marcha» y «puerto ocupado» (el resto cambia a menudo durante una sesión). */
    private boolean lastRunning;
    private boolean lastUdpBusy;
    private final LinkState.Listener linkListener = () -> {
        if (LinkState.running == lastRunning && LinkState.udpBusy == lastUdpBusy) return;
        lastRunning = LinkState.running;
        lastUdpBusy = LinkState.udpBusy;
        refresh();
    };

    Checklist(Activity act, Runnable onChange) {
        this.act = act;
        this.cfg = new Config(act);
        this.onChange = onChange;
    }

    // ---------------------------------------------------------------- ciclo de vida

    /** En onResume: escucha los cambios y vuelve a comprobar. */
    void start() {
        if (!started) {
            started = true;
            IntentFilter f = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
            f.addAction(HotspotWatcher.ACTION_AP_STATE);
            if (Build.VERSION.SDK_INT >= 28) f.addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED);
            IntentFilter pkgs = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            pkgs.addAction(Intent.ACTION_PACKAGE_REMOVED);
            pkgs.addAction(Intent.ACTION_PACKAGE_CHANGED);
            pkgs.addAction(Intent.ACTION_PACKAGE_REPLACED);
            pkgs.addDataScheme("package");
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    act.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
                    act.registerReceiver(receiver, pkgs, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    act.registerReceiver(receiver, f);
                    act.registerReceiver(receiver, pkgs);
                }
            } catch (RuntimeException e) {
                L.w("requisitos: sin avisos del sistema (" + e.getMessage() + ")");
            }
            act.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, accessibilityObserver);
            lastRunning = LinkState.running;
            lastUdpBusy = LinkState.udpBusy;
            LinkState.addListener(linkListener);
        }
        if (awaitingDevMode) {
            awaitingDevMode = false;
            checkDevMode();
        }
        refresh();
    }

    /** En onPause. */
    void stop() {
        if (!started) return;
        started = false;
        try {
            act.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        act.getContentResolver().unregisterContentObserver(accessibilityObserver);
        LinkState.removeListener(linkListener);
        main.removeCallbacks(refreshLater);
        // Sin el receptor se pierden los avisos: al volver, el wifi_state guardado ya no vale.
        apState = -1;
    }

    void onPermissionsResult() {
        refresh();
    }

    /** Vuelve a evaluar con lo rápido ya y lanza lo lento (zona Wi-Fi y puerto) en un hilo. */
    void refresh() {
        if (act.isDestroyed()) return;
        items = Requirements.evaluate(read(act, cfg, hotspot, port, checkingDevMode));
        onChange.run();
        probeSlow();
    }

    private void probeSlow() {
        if (probing) {
            probeAgain = true;
            return;
        }
        probing = true;
        int ap = apState;
        new Thread(() -> {
            HotspotWatcher.Probe p = HotspotWatcher.probe(act, ap);
            Requirements.Port pt = currentPort(true);
            main.post(() -> {
                probing = false;
                hotspotProbe = p;
                Requirements.Hotspot h = hotspotOf(p.getState());
                boolean changed = h != hotspot || pt != port;
                hotspot = h;
                port = pt;
                if (probeAgain) {
                    probeAgain = false;
                    refresh();
                } else if (changed && !act.isDestroyed()) {
                    items = Requirements.evaluate(read(act, cfg, hotspot, port, checkingDevMode));
                    onChange.run();
                }
            });
        }, "hql-req-probe").start();
    }

    List<Requirements.Item> items() {
        return items;
    }

    List<Requirements.Item> blocking() {
        return Requirements.blocking(items);
    }

    // ---------------------------------------------------------------- lectura del estado

    /** Comprobación completa ahora (también lo lento): no en el hilo principal. */
    static List<Requirements.Item> evaluateNow(Activity a) {
        HotspotWatcher.Probe p = HotspotWatcher.probe(a);
        return Requirements.evaluate(read(a, new Config(a), hotspotOf(p.getState()), currentPort(true), false));
    }

    private static Requirements.Hotspot hotspotOf(HotspotWatcher.State s) {
        switch (s) {
            case ON:
                return Requirements.Hotspot.ON;
            case OFF:
                return Requirements.Hotspot.OFF;
            default:
                return Requirements.Hotspot.UNKNOWN;
        }
    }

    /**
     * El UDP 18463: con el servicio en marcha, lo que dijo el motor al abrirlo; parado, se prueba a abrirlo un instante
     * con las mismas opciones que el motor (reuseAddress). Si no se deja, otra app (QDLink) lo tiene.
     */
    private static Requirements.Port currentPort(boolean probe) {
        if (LinkState.running) return LinkState.udpBusy ? Requirements.Port.BUSY : Requirements.Port.FREE;
        if (!probe) return Requirements.Port.UNKNOWN;
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(Proto.UDP_LISTEN_PORT));
            return Requirements.Port.FREE;
        } catch (BindException e) {
            return Requirements.Port.BUSY;
        } catch (IOException | RuntimeException e) {
            String m = e.getMessage();
            return m != null && m.toLowerCase(java.util.Locale.ROOT).contains("in use") ? Requirements.Port.BUSY : Requirements.Port.UNKNOWN;
        } finally {
            if (s != null) s.close();
        }
    }

    private static Requirements.Snapshot read(Activity a, Config cfg, Requirements.Hotspot hotspot, Requirements.Port port,
                                              boolean checkingDev) {
        Requirements.Snapshot s = new Requirements.Snapshot();
        int sdk = Build.VERSION.SDK_INT;
        s.sdk = sdk;
        s.mode = cfg.mode();
        s.linkMode = cfg.linkMode();
        s.btAuto = cfg.btAutoConnect();
        s.forceLegacyLaunch = cfg.getBool("force_legacy_launch");

        s.accessibilityRunning = TouchService.instance != null;
        s.accessibilityEnabled = accessibilityEnabled(a);
        s.restrictedSettings = !s.accessibilityEnabled && restrictedSettings(a);

        PackageManager pm = a.getPackageManager();
        try {
            PackageInfo pi = pm.getPackageInfo(AaServerStarter.AA_PKG, 0);
            s.aaVersion = pi.versionName != null ? pi.versionName : "?";
            s.aaEnabled = pi.applicationInfo == null || pi.applicationInfo.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            s.aaVersion = null;
        }
        s.devMode = AaServerStarter.devModeState(a);
        s.devModeChecking = checkingDev;
        s.targetAppChosen = Ui.appLabel(a, cfg.targetPackage()) != null;

        NotificationManager nm = a.getSystemService(NotificationManager.class);
        boolean notifOn = nm == null || nm.areNotificationsEnabled();
        if (sdk >= 33) {
            s.notifications = perm(a, cfg, KEY_NOTIF, Manifest.permission.POST_NOTIFICATIONS);
            if (s.notifications == Requirements.Perm.GRANTED && !notifOn) s.notifications = Requirements.Perm.BLOCKED;
        } else {
            s.notifications = notifOn ? Requirements.Perm.GRANTED : Requirements.Perm.BLOCKED;
        }
        s.nearby = perm(a, cfg, KEY_NEARBY, nearbyPerms());
        s.bluetooth = sdk >= 31 ? perm(a, cfg, KEY_BT, Manifest.permission.BLUETOOTH_CONNECT) : Requirements.Perm.GRANTED;
        s.media = perm(a, cfg, KEY_MEDIA, mediaPerms());
        s.overlay = sdk < 23 || Settings.canDrawOverlays(a);

        WifiManager wm = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        s.wifiOn = wm == null || wm.isWifiEnabled();
        s.hotspot = hotspot;

        s.batteryUnrestricted = PowerHelper.unrestricted(a);
        s.oem = PowerHelper.isSamsung() ? Requirements.Oem.SAMSUNG
                : PowerHelper.oemIntent(a) != null ? Requirements.Oem.OTHER : Requirements.Oem.NONE;

        s.qdlinkInstalled = installed(pm, QDLINK_PKG);
        // En marcha manda lo que dijo el motor (al momento); parado, la última prueba.
        s.port = LinkState.running ? currentPort(false) : port;
        return s;
    }

    private static String[] nearbyPerms() {
        return Build.VERSION.SDK_INT >= 33 ? new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
    }

    private static String[] mediaPerms() {
        return Build.VERSION.SDK_INT >= 33 ? Ui.MEDIA_PERMS
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
    }

    private static Requirements.Perm perm(Activity a, Config cfg, String key, String... perms) {
        boolean granted = true;
        boolean rationale = false;
        for (String p : perms) {
            if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                if (a.shouldShowRequestPermissionRationale(p)) rationale = true;
            }
        }
        return Requirements.permState(granted, cfg.getBool(ASKED + key), rationale);
    }

    /** Se pidió el permiso (también desde otra pantalla): a partir de ahora, sin diálogo es que se denegó para siempre. */
    static void markAsked(Context ctx, String key) {
        new Config(ctx).putBool(ASKED + key, true);
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Activada en Ajustes (aunque Android no la tenga en marcha). */
    static boolean accessibilityEnabled(Context ctx) {
        String s = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return Requirements.serviceEnabled(s, ctx.getPackageName(), TouchService.class.getName());
    }

    /**
     * Android 13+: ¿bloqueará Android activar la accesibilidad («Ajuste restringido»)? Lo dice la operación
     * access_restricted_settings; si este Android no la deja leer, se supone que sí cuando la app no viene de Play.
     */
    static boolean restrictedSettings(Context ctx) {
        if (Build.VERSION.SDK_INT < 33) return false;
        try {
            AppOpsManager ao = ctx.getSystemService(AppOpsManager.class);
            int m = ao.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), ctx.getPackageName());
            if (m == AppOpsManager.MODE_ERRORED) return true;
            if (m == AppOpsManager.MODE_ALLOWED) return false;
        } catch (RuntimeException ignored) {
        }
        try {
            String installer = ctx.getPackageManager().getInstallSourceInfo(ctx.getPackageName()).getInstallingPackageName();
            return !"com.android.vending".equals(installer);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return true;
        }
    }

    // ---------------------------------------------------------------- textos

    /** «Todo listo», «Faltan N cosas» o «Comprobando…». */
    String summaryTitle() {
        int n = Requirements.missingCount(items);
        if (n > 0) return act.getResources().getQuantityString(R.plurals.hql_req_missing, n, n);
        for (Requirements.Item i : items) {
            if (i.status == Requirements.Status.CHECKING) return Str.get(R.string.hql_checking);
        }
        return Str.get(R.string.hql_req_all_ok);
    }

    int summaryColor() {
        if (!blocking().isEmpty()) return act.getColor(R.color.hql_error);
        if (Requirements.missingCount(items) > 0) return act.getColor(R.color.hql_warn);
        for (Requirements.Item i : items) {
            if (i.status == Requirements.Status.CHECKING) return act.getColor(R.color.hql_text_dim);
        }
        return act.getColor(R.color.hql_ok);
    }

    String summaryDetail() {
        if (!blocking().isEmpty()) return Str.get(R.string.hql_req_sub_required);
        if (Requirements.missingCount(items) > 0) return Str.get(R.string.hql_req_sub_recommended);
        return Str.get(R.string.hql_req_sub_ok);
    }

    /** Para el aviso de «Terminar»/«Conectar»: lo obligatorio que falta, una línea por cosa. */
    String blockingText() {
        StringBuilder sb = new StringBuilder();
        for (Requirements.Item i : blocking()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("•  ").append(row(i).title);
        }
        return sb.toString();
    }

    /** Título de la guía bajo la lista, o null si no hace falta. */
    String guideTitle() {
        if (Config.LINK_HOTSPOT.equals(cfg.linkMode())) return Str.get(R.string.hql_hotspot_guide_title);
        return needsDevGuide() ? Str.get(R.string.hql_setup_guide_title) : null;
    }

    /** La guía: cómo unir el coche a la zona Wi-Fi y, si falta, cómo activar el modo desarrollador de AA. */
    String guideText() {
        String dev = needsDevGuide() ? Str.get(R.string.hql_setup_guide) : null;
        if (Config.LINK_HOTSPOT.equals(cfg.linkMode())) {
            String text = Str.get(R.string.hql_hotspot_guide) + "\n\n" + Str.get(R.string.hql_hotspot_tips);
            return dev == null ? text : text + "\n\n" + Str.get(R.string.hql_setup_guide_title) + "\n" + dev;
        }
        return dev;
    }

    private boolean needsDevGuide() {
        Requirements.Item d = Requirements.find(items, Requirements.Id.AA_DEVMODE);
        return d != null && d.status != Requirements.Status.OK;
    }

    // ---------------------------------------------------------------- filas

    /** Texto y botones de una fila. */
    private static final class Row {
        String title;
        String detail;
        String action;
        Runnable onAction;
        String action2;
        Runnable onAction2;

        Row(String title, String detail) {
            this.title = title;
            this.detail = detail;
        }

        Row action(String label, Runnable r) {
            if (action == null) {
                action = label;
                onAction = r;
            } else {
                action2 = label;
                onAction2 = r;
            }
            return this;
        }
    }

    private Row row(Requirements.Item it) {
        boolean ok = it.status == Requirements.Status.OK;
        boolean blocked = it.hint == Requirements.Hint.BLOCKED;
        switch (it.id) {
            case ANDROID_AUTO: {
                if (it.hint == Requirements.Hint.NOT_INSTALLED) {
                    return new Row("Android Auto", Str.get(R.string.hql_not_installed))
                            .action(Str.get(R.string.hql_install), () -> openStore(AaServerStarter.AA_PKG));
                }
                if (it.hint == Requirements.Hint.DISABLED) {
                    return new Row("Android Auto", Str.get(R.string.hql_req_aa_disabled))
                            .action(Str.get(R.string.hql_req_app_info), () -> PowerHelper.openAppDetails(act, AaServerStarter.AA_PKG));
                }
                return new Row("Android Auto", Str.get(R.string.hql_setup_version, String.valueOf(Ui.aaVersion(act))));
            }
            case ACCESSIBILITY: {
                Row r = new Row(Str.get(R.string.hql_setup_accessibility), Str.get(R.string.hql_active));
                if (ok) return r;
                boolean app = Config.MODE_APP.equals(cfg.mode());
                if (it.hint == Requirements.Hint.NOT_RUNNING) {
                    r.detail = Str.get(R.string.hql_req_acc_stuck);
                } else {
                    r.detail = Str.get(app ? R.string.hql_setup_accessibility_app : R.string.hql_setup_accessibility_aa);
                    if (it.hint == Requirements.Hint.RESTRICTED) r.detail += "\n" + Str.get(R.string.hql_req_acc_restricted);
                }
                r.action(Str.get(R.string.hql_enable), this::openAccessibility);
                if (it.hint == Requirements.Hint.RESTRICTED) {
                    r.action(Str.get(R.string.hql_req_app_info), () -> PowerHelper.openAppDetails(act, act.getPackageName()));
                }
                return r;
            }
            case AA_DEVMODE: {
                Row r = new Row(Str.get(R.string.hql_setup_devmode), "");
                if (it.status == Requirements.Status.CHECKING) {
                    r.detail = Str.get(R.string.hql_checking);
                } else if (ok) {
                    r.detail = Str.get(R.string.hql_active);
                } else if (it.hint == Requirements.Hint.NOT_CHECKED) {
                    r.detail = Str.get(R.string.hql_req_dev_unchecked);
                    r.action(Str.get(R.string.hql_check), this::checkDevMode).action(Str.get(R.string.hql_open_aa), this::openAaForDevMode);
                } else {
                    r.detail = Str.get(R.string.hql_req_dev_off);
                    r.action(Str.get(R.string.hql_open_aa), this::openAaForDevMode).action(Str.get(R.string.hql_check), this::checkDevMode);
                }
                return r;
            }
            case TARGET_APP: {
                String label = Ui.appLabel(act, cfg.targetPackage());
                return new Row(Str.get(R.string.hql_setup_app_row), label != null ? label : Str.get(R.string.hql_not_chosen))
                        .action(Str.get(label != null ? R.string.hql_change : R.string.hql_choose), () -> Ui.pickApp(act, pkg -> {
                            cfg.setTargetPackage(pkg);
                            refresh();
                        }));
            }
            case HOTSPOT_ON: {
                Row r = new Row(Str.get(R.string.hql_hotspot_row), hotspotText(it.status, true));
                if (!ok) r.action(Str.get(R.string.hql_open), () -> HotspotWatcher.openSettings(act));
                return r;
            }
            case HOTSPOT_BAND:
                return new Row(Str.get(R.string.hql_req_band), Str.get(R.string.hql_req_band_why))
                        .action(Str.get(R.string.hql_open), () -> HotspotWatcher.openSettings(act));
            case HOTSPOT_OFF: {
                Row r = new Row(Str.get(R.string.hql_hotspot_off_row), hotspotText(it.status, false));
                if (!ok && it.status != Requirements.Status.CHECKING) {
                    r.action(Str.get(R.string.hql_open), () -> HotspotWatcher.openSettings(act));
                }
                return r;
            }
            case NEARBY_WIFI: {
                Row r = new Row(Str.get(Build.VERSION.SDK_INT >= 33 ? R.string.hql_req_nearby : R.string.hql_req_location),
                        ok ? Str.get(R.string.hql_allowed) : blocked ? Str.get(R.string.hql_req_blocked) : Str.get(R.string.hql_req_nearby_why));
                return ok ? r : permAction(r, blocked, KEY_NEARBY, nearbyPerms());
            }
            case WIFI_ON: {
                Row r = new Row(Str.get(R.string.hql_req_wifi), ok ? Str.get(R.string.hql_active) : Str.get(R.string.hql_req_wifi_why));
                if (!ok) r.action(Str.get(R.string.hql_open), () -> start(new Intent(Settings.ACTION_WIFI_SETTINGS)));
                return r;
            }
            case QDLINK: {
                boolean qd = installed(act.getPackageManager(), QDLINK_PKG);
                Row r = new Row(qd ? "QDLink" : Str.get(R.string.hql_req_port_title),
                        it.hint == Requirements.Hint.PORT_BUSY ? Str.get(R.string.hql_req_port_busy) : Str.get(R.string.hql_req_qdlink_why));
                if (qd) {
                    r.action(Str.get(it.hint == Requirements.Hint.PORT_BUSY ? R.string.hql_req_force_stop : R.string.hql_req_app_info),
                            () -> PowerHelper.openAppDetails(act, QDLINK_PKG));
                }
                return r;
            }
            case NOTIFICATIONS: {
                Row r = new Row(Str.get(R.string.hql_req_notif),
                        ok ? Str.get(R.string.hql_allowed) : blocked ? Str.get(R.string.hql_req_notif_blocked) : Str.get(R.string.hql_req_notif_why));
                if (ok) return r;
                if (blocked) return r.action(Str.get(R.string.hql_open), this::openNotificationSettings);
                return r.action(Str.get(R.string.hql_allow), () -> request(KEY_NOTIF, Manifest.permission.POST_NOTIFICATIONS));
            }
            case BLUETOOTH: {
                Row r = new Row(Str.get(R.string.hql_req_bt),
                        ok ? Str.get(R.string.hql_allowed) : blocked ? Str.get(R.string.hql_req_blocked) : Str.get(R.string.hql_req_bt_why));
                return ok ? r : permAction(r, blocked, KEY_BT, Manifest.permission.BLUETOOTH_CONNECT);
            }
            case BATTERY: {
                Row r = new Row(Str.get(R.string.hql_battery_row), ok ? Str.get(R.string.hql_battery_ok)
                        : Str.get(it.hint == Requirements.Hint.BT_AUTO ? R.string.hql_req_battery_bt : R.string.hql_battery_why));
                if (!ok) r.action(Str.get(R.string.hql_allow), () -> PowerHelper.requestUnrestricted(act));
                return r;
            }
            case BATTERY_OEM:
                if (it.hint == Requirements.Hint.SAMSUNG) {
                    return new Row(Str.get(R.string.hql_req_samsung), Str.get(R.string.hql_req_samsung_why))
                            .action(Str.get(R.string.hql_open), () -> PowerHelper.openAppBattery(act));
                }
                return new Row(Str.get(R.string.hql_battery_oem, PowerHelper.brand()), Str.get(R.string.hql_battery_oem_why))
                        .action(Str.get(R.string.hql_open), () -> PowerHelper.openOem(act));
            case OVERLAY: {
                boolean app = Config.MODE_APP.equals(cfg.mode());
                Row r = new Row(Str.get(R.string.hql_setup_overlay), ok ? Str.get(R.string.hql_allowed)
                        : Str.get(app ? R.string.hql_setup_overlay_app : R.string.hql_setup_overlay_aa));
                if (!ok) {
                    r.action(Str.get(R.string.hql_allow), () -> start(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + act.getPackageName()))));
                }
                return r;
            }
            default: { // MEDIA
                Row r = new Row(Str.get(R.string.hql_setup_media),
                        ok ? Str.get(R.string.hql_allowed) : blocked ? Str.get(R.string.hql_req_blocked) : Str.get(R.string.hql_setup_media_why));
                return ok ? r : permAction(r, blocked, KEY_MEDIA, mediaPerms());
            }
        }
    }

    private Row permAction(Row r, boolean blocked, String key, String... perms) {
        if (blocked) return r.action(Str.get(R.string.hql_open), () -> PowerHelper.openAppDetails(act, act.getPackageName()));
        return r.action(Str.get(R.string.hql_allow), () -> request(key, perms));
    }

    private String hotspotText(Requirements.Status st, boolean wantOn) {
        switch (st) {
            case CHECKING:
                return Str.get(R.string.hql_checking);
            case UNKNOWN:
                return Str.get(R.string.hql_hotspot_unknown);
            case OK:
                if (!wantOn) return Str.get(R.string.hql_hotspot_is_off);
                HotspotWatcher.Probe p = hotspotProbe;
                return p != null ? HotspotWatcher.text(p) : Str.get(R.string.hql_active);
            default:
                return Str.get(wantOn ? R.string.hql_hotspot_off : R.string.hql_p2p_needs_hotspot_off);
        }
    }

    /** Pinta las filas en list (el panel de hql_setup_check). */
    void render(LinearLayout list) {
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(act);
        for (Requirements.Item it : items) {
            Row r = row(it);
            if (list.getChildCount() > 0) {
                View div = new View(act);
                div.setBackgroundColor(act.getColor(R.color.hql_outline));
                list.addView(div, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
            View v = inf.inflate(R.layout.hql_row_req, list, false);
            icon(v.findViewById(R.id.hql_req_icon), it);
            TextView tag = v.findViewById(R.id.hql_req_tag);
            tag.setText(tag(it));
            tag.setTextColor(act.getColor(it.blocks() ? R.color.hql_error : it.counts() ? R.color.hql_warn : R.color.hql_text_faint));
            ((TextView) v.findViewById(R.id.hql_req_title)).setText(r.title);
            ((TextView) v.findViewById(R.id.hql_req_detail)).setText(r.detail);
            button(v.findViewById(R.id.hql_req_action), r.action, r.onAction);
            button(v.findViewById(R.id.hql_req_action2), r.action2, r.onAction2);
            v.findViewById(R.id.hql_req_actions).setVisibility(r.action == null ? View.GONE : View.VISIBLE);
            v.setContentDescription(tag.getText() + ". " + r.title + ". " + r.detail);
            list.addView(v);
        }
    }

    private static String tag(Requirements.Item it) {
        if (it.status == Requirements.Status.TIP) return Str.get(R.string.hql_req_tip);
        switch (it.importance) {
            case REQUIRED:
                return Str.get(R.string.hql_req_required);
            case RECOMMENDED:
                return Str.get(R.string.hql_recommended);
            default:
                return Str.get(R.string.hql_req_optional);
        }
    }

    /** Icono de estado: ✓ cumplido, ! falta, ✕ falla, ? sin saber, … comprobando, i consejo. */
    private void icon(TextView v, Requirements.Item it) {
        String glyph;
        int bg;
        switch (it.status) {
            case OK:
                glyph = "✓";
                bg = R.color.hql_ok;
                break;
            case MISSING:
                glyph = "!";
                bg = it.importance == Requirements.Importance.REQUIRED ? R.color.hql_error
                        : it.importance == Requirements.Importance.RECOMMENDED ? R.color.hql_warn : 0;
                break;
            case ERROR:
                glyph = "✕";
                bg = R.color.hql_error;
                break;
            case WARN:
                glyph = "!";
                bg = R.color.hql_warn;
                break;
            case CHECKING:
                glyph = "…";
                bg = 0;
                break;
            case UNKNOWN:
                glyph = "?";
                bg = 0;
                break;
            default:
                glyph = "i";
                bg = 0;
        }
        v.setText(glyph);
        // Insignia «Eléctrico»: el símbolo en el color del estado sobre ese color translúcido (sin estado: gris).
        int fg = act.getColor(bg != 0 ? bg : R.color.hql_text_dim);
        v.setBackgroundTintList(ColorStateList.valueOf(bg != 0 ? Ui.levelTint(fg) : act.getColor(R.color.hql_surface_top)));
        v.setTextColor(fg);
    }

    private static void button(MaterialButton b, String label, Runnable r) {
        if (label == null) {
            b.setVisibility(View.GONE);
            return;
        }
        b.setVisibility(View.VISIBLE);
        b.setText(label);
        b.setOnClickListener(x -> r.run());
    }

    // ---------------------------------------------------------------- acciones

    private void request(String key, String... perms) {
        markAsked(act, key);
        act.requestPermissions(perms, REQ_PERMS);
    }

    private void start(Intent i) {
        try {
            act.startActivity(i);
        } catch (RuntimeException e) {
            L.w("requisitos: no se pudo abrir " + i.getAction() + ": " + e.getMessage());
            PowerHelper.openAppDetails(act, act.getPackageName());
        }
    }

    /** La página de la accesibilidad de HeadQLink (Android 13+) o la lista de servicios. */
    private void openAccessibility() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                act.startActivity(new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                        .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName(act, TouchService.class).flattenToString()));
                return;
            } catch (RuntimeException e) {
                L.w("requisitos: sin la página de la accesibilidad de HeadQLink (" + e.getMessage() + ")");
            }
        }
        start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void openNotificationSettings() {
        try {
            act.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, act.getPackageName()));
        } catch (RuntimeException e) {
            PowerHelper.openAppDetails(act, act.getPackageName());
        }
    }

    private void openStore(String pkg) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (RuntimeException e) {
            start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
        }
    }

    private void openAaForDevMode() {
        awaitingDevMode = true;
        try {
            AaServerStarter.openAaSettings(act);
        } catch (RuntimeException e) {
            awaitingDevMode = false;
            L.w("requisitos: no se pudieron abrir los ajustes de AA: " + e.getMessage());
            PowerHelper.openAppDetails(act, AaServerStarter.AA_PKG);
        }
    }

    /** Comprueba el modo desarrollador de AA (abre sus ajustes un momento, tapados por la capa). */
    void checkDevMode() {
        if (TouchService.instance == null) {
            new MaterialAlertDialogBuilder(act)
                    .setTitle(Str.get(R.string.hql_accessibility_first))
                    .setMessage(Str.get(R.string.hql_accessibility_first_msg))
                    .setPositiveButton(Str.get(R.string.hql_enable), (d, w) -> openAccessibility())
                    .setNegativeButton(Str.get(R.string.hql_cancel), null)
                    .show();
            return;
        }
        checkingDevMode = true;
        refresh();
        new Thread(() -> {
            AaServerStarter.checkDevModeAndWait(act);
            main.post(() -> {
                checkingDevMode = false;
                refresh();
            });
        }, "aa-devmode-check").start();
    }
}
