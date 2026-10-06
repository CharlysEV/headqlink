package com.headqlink.link;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Executor;

/**
 * Estado del sistema que puede explicar los tirones de radio, volcado a la traza de rendimiento y
 * al diario: WiFi del móvil (conectado o no: sin red, Android escanea cada 10 s), escaneos WiFi,
 * Bluetooth (coexistencia), ahorro de energía / Doze / temperatura y contadores del interfaz p2p0.
 */
final class SystemMonitor {
    private final Context ctx;
    private final HandlerThread thread = new HandlerThread("sys-monitor");
    private Handler h;
    private WifiManager.ScanResultsCallback scanCb;
    private ConnectivityManager.NetworkCallback wifiCb;
    private long[] lastP2p;
    private String lastIface;
    /** Interfaz de la sesión con el coche (la fija el puente del motor QDAuto); null = p2p0 en Wi-Fi Direct. */
    private static volatile String linkIface;
    /** Sesión que fijó linkIface (0 = ninguna), con SystemMonitor.class. */
    private static int linkIfaceOwner;
    private final boolean p2pMode;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            switch (a) {
                case PowerManager.ACTION_POWER_SAVE_MODE_CHANGED:
                case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                    logPower();
                    break;
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                case BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED:
                    logBluetooth();
                    break;
                default:
                    break;
            }
        }
    };

    private final Runnable p2pSampler = new Runnable() {
        @Override
        public void run() {
            sampleLinkIface();
            h.postDelayed(this, 1000);
        }
    };

    SystemMonitor(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        // La conexión de este servicio (la configurada puede haber cambiado en marcha).
        this.p2pMode = Config.LINK_P2P.equals(LinkState.linkModeFor(new Config(ctx)));
    }

    /** Interfaz por la que va la sesión owner (su id): swlan0, p2p0… */
    static synchronized void setLinkIface(String name, int owner) {
        linkIface = name;
        linkIfaceOwner = owner;
    }

    /** Sin sesión: solo si la interfaz sigue siendo la de owner (una sesión vieja que cierra tarde no borra la nueva). */
    static synchronized void clearLinkIface(int owner) {
        if (linkIfaceOwner != owner) return;
        linkIface = null;
        linkIfaceOwner = 0;
    }

    void start() {
        thread.start();
        h = new Handler(thread.getLooper());
        Executor ex = h::post;

        WifiManager wm = ctx.getSystemService(WifiManager.class);
        try {
            scanCb = new WifiManager.ScanResultsCallback() {
                @Override
                public void onScanResultsAvailable() {
                    PerfTrace.event("wifi_scan_cb", 1);
                }
            };
            wm.registerScanResultsCallback(ex, scanCb);
        } catch (RuntimeException e) {
            scanCb = null;
            PerfTrace.note("sin aviso de escaneos WiFi: " + e.getMessage());
        }

        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        wifiCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                PerfTrace.event("sta_wifi", 1);
                QdTrace.i("HQL/Red", "Wi-Fi cliente conectada");
                CarTrace.note("WIFI", "el móvil se conecta a una red WiFi (cesan los escaneos de búsqueda)");
            }

            @Override
            public void onLost(Network n) {
                PerfTrace.event("sta_wifi", 0);
                QdTrace.i("HQL/Red", "Wi-Fi cliente desconectada");
                CarTrace.note("WIFI", "el móvil queda sin red WiFi (Android buscará redes periódicamente)");
            }
        };
        try {
            cm.registerNetworkCallback(new android.net.NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCb, h);
        } catch (RuntimeException e) {
            wifiCb = null;
        }

        // El estado térmico lo vigila ThermalGuard (traza «thermal» y log HQL/Térmico).
        IntentFilter f = new IntentFilter();
        f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        f.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        ctx.registerReceiver(receiver, f, null, h, Context.RECEIVER_NOT_EXPORTED);

        h.post(() -> {
            boolean sta = isStaConnected(cm);
            PerfTrace.event("sta_wifi", sta ? 1 : 0);
            CarTrace.note("WIFI", sta ? "móvil conectado a una red WiFi" : "móvil SIN red WiFi: Android escaneará periódicamente");
            logPower();
            logBluetooth();
        });
        h.post(p2pSampler);
    }

    void stop() {
        if (h == null) return;
        h.removeCallbacksAndMessages(null);
        try {
            ctx.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (scanCb != null) ctx.getSystemService(WifiManager.class).unregisterScanResultsCallback(scanCb);
        if (wifiCb != null) ctx.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifiCb);
        thread.quitSafely();
    }

    private static boolean isStaConnected(ConnectivityManager cm) {
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }

    private void logPower() {
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        PerfTrace.event("power_save", pm.isPowerSaveMode() ? 1 : 0);
        PerfTrace.event("doze", pm.isDeviceIdleMode() ? 1 : 0);
        int thermal = android.os.Build.VERSION.SDK_INT >= 29 ? pm.getCurrentThermalStatus() : -1;
        PerfTrace.event("thermal", thermal);
        QdTrace.i("HQL/Sistema", "ahorro de energía " + (pm.isPowerSaveMode() ? "sí" : "no") + " · doze "
                + (pm.isDeviceIdleMode() ? "sí" : "no") + " · térmica " + thermal);
    }

    @SuppressWarnings("MissingPermission")
    private void logBluetooth() {
        try {
            BluetoothAdapter bt = ctx.getSystemService(BluetoothManager.class).getAdapter();
            if (bt == null) return;
            boolean on = bt.isEnabled();
            int a2dp = on ? bt.getProfileConnectionState(BluetoothProfile.A2DP) : 0;
            int hfp = on ? bt.getProfileConnectionState(BluetoothProfile.HEADSET) : 0;
            PerfTrace.event("bluetooth", on ? 1 : 0);
            PerfTrace.event("bt_a2dp", a2dp == BluetoothProfile.STATE_CONNECTED ? 1 : 0);
            PerfTrace.event("bt_hfp", hfp == BluetoothProfile.STATE_CONNECTED ? 1 : 0);
            String btText = (on ? "encendido" : "apagado") + " · audio " + (a2dp == BluetoothProfile.STATE_CONNECTED ? "conectado" : "no")
                    + " · manos libres " + (hfp == BluetoothProfile.STATE_CONNECTED ? "conectado" : "no");
            CarTrace.note("BLUETOOTH", btText);
            QdTrace.i("HQL/Sistema", "Bluetooth " + btText);
        } catch (SecurityException e) {
            PerfTrace.note("bluetooth sin permiso: " + e.getMessage());
        }
    }

    /**
     * Paquetes/errores/descartes por segundo de la interfaz de la sesión (si el sistema deja leerlos): la que fije el
     * puente; sin sesión, p2p0 en Wi-Fi Direct (nada en la zona Wi-Fi). p2p0 sale en la fila p2p0; las demás, en ifstat.
     */
    private void sampleLinkIface() {
        String iface = linkIface;
        if (iface == null) iface = p2pMode ? "p2p0" : null;
        if (iface == null) return;
        if (!iface.equals(lastIface)) {
            lastIface = iface;
            lastP2p = null;
        }
        File base = new File("/sys/class/net/" + iface + "/statistics");
        long[] v = new long[5];
        String[] names = {"tx_packets", "tx_errors", "tx_dropped", "rx_packets", "rx_dropped"};
        try {
            for (int i = 0; i < names.length; i++) {
                v[i] = Long.parseLong(new String(Files.readAllBytes(new File(base, names[i]).toPath())).trim());
            }
        } catch (Exception e) {
            if (lastP2p == null) {
                PerfTrace.note("contadores de " + iface + " no accesibles: " + e.getClass().getSimpleName());
                lastP2p = new long[0];
            }
            return;
        }
        if (lastP2p != null && lastP2p.length == v.length) {
            if ("p2p0".equals(iface)) {
                PerfTrace.p2p(v[0] - lastP2p[0], v[1] - lastP2p[1], v[2] - lastP2p[2], v[3] - lastP2p[3], v[4] - lastP2p[4]);
            } else {
                PerfTrace.ifstat(iface, v[0] - lastP2p[0], v[1] - lastP2p[1], v[2] - lastP2p[2], v[3] - lastP2p[3], v[4] - lastP2p[4]);
            }
        }
        lastP2p = v;
    }
}
