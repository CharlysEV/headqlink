package com.headqlink.link;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pUpnpServiceRequest;
import android.os.Handler;
import android.os.Looper;

import com.andrerinas.openheadunit.R;

/**
 * Une el móvil al grupo WiFi Direct del coche (el coche es Group Owner).
 * Descubrimiento UPnP (servicio del coche) y connect con groupOwnerIntent=0.
 * Como respaldo, cualquier peer con el nombre de dispositivo del coche (NAME_PREFIX).
 */
@SuppressLint("MissingPermission")
final class P2pLink {
    private static final String UPNP_MARK = "QDLink_UPnP_Device";
    private static final String NAME_PREFIX = "LeapMotor";
    private static final long RETRY_MS = 15_000;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiP2pManager mgr;
    private WifiP2pManager.Channel ch;
    private volatile boolean inGroup;
    private boolean connecting;
    private boolean started;
    /** Lo último publicado en la fila «Red» (para {@link #republish}). */
    private volatile LinkState.Level lastLevel = LinkState.Level.IDLE;
    private volatile String lastText = "";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String a = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                WifiP2pInfo info = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo.class);
                onInfo(info);
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)) {
                if (!inGroup && !connecting) mgr.requestPeers(ch, list -> {
                    for (WifiP2pDevice d : list.getDeviceList()) {
                        if (d.deviceName != null && d.deviceName.startsWith(NAME_PREFIX)) {
                            L.i("P2P peer coche: " + d.deviceName + " " + d.deviceAddress + " status=" + d.status);
                            connect(d);
                            return;
                        }
                    }
                });
            }
        }
    };

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            if (!inGroup) {
                connecting = false;
                discover();
            }
            handler.postDelayed(this, RETRY_MS);
        }
    };

    P2pLink(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    boolean isInGroup() {
        return inGroup;
    }

    private void publish(LinkState.Level level, String text) {
        lastLevel = level;
        lastText = text;
        LinkState.setNetwork(level, text);
    }

    /** Vuelve a publicar el estado del grupo (p. ej. tras un error del UDP que ocupó la fila «Red»). */
    void republish() {
        LinkState.setNetwork(lastLevel, lastText);
    }

    void start() {
        if (started) return;
        started = true;
        mgr = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        ch = mgr.initialize(ctx, Looper.getMainLooper(), () -> L.w("P2P channel perdido"));
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);

        mgr.setUpnpServiceResponseListener(ch, (services, device) -> {
            for (String s : services) {
                if (s.contains(UPNP_MARK)) {
                    L.i("UPnP del coche " + device.deviceName + " " + device.deviceAddress + ": " + s);
                    if (!inGroup) connect(device);
                    return;
                }
            }
        });
        // Solo descubrimos si no estamos ya en el grupo del coche.
        mgr.requestConnectionInfo(ch, info -> {
            onInfo(info);
            handler.post(retry);
        });
    }

    private void onInfo(WifiP2pInfo info) {
        boolean was = inGroup;
        inGroup = info != null && info.groupFormed && !info.isGroupOwner;
        if (inGroup && !was) {
            connecting = false;
            L.i("P2P en grupo del coche, GO=" + info.groupOwnerAddress + "; paro el descubrimiento");
            publish(LinkState.Level.OK, Str.get(R.string.hql_p2p_in_group,
                    info.groupOwnerAddress != null ? info.groupOwnerAddress.getHostAddress() : "?"));
            // El descubrimiento P2P saca la radio del canal y provoca picos de 100-350 ms.
            mgr.stopPeerDiscovery(ch, null);
            mgr.clearServiceRequests(ch, null);
        } else if (!inGroup && was) {
            L.w("P2P fuera del grupo");
            publish(LinkState.Level.BUSY, Str.get(R.string.hql_p2p_searching));
        } else if (info != null && info.groupFormed && info.isGroupOwner) {
            L.w("P2P: el móvil es GO de otro grupo; el protocolo espera que el coche sea GO");
        }
    }

    private void discover() {
        L.i("P2P buscando coche (UPnP + peers)...");
        publish(LinkState.Level.BUSY, Str.get(R.string.hql_p2p_searching));
        mgr.clearServiceRequests(ch, null);
        mgr.addServiceRequest(ch, WifiP2pUpnpServiceRequest.newInstance(), listener("addServiceRequest"));
        mgr.discoverServices(ch, listener("discoverServices"));
        mgr.discoverPeers(ch, listener("discoverPeers"));
    }

    private void connect(WifiP2pDevice d) {
        if (connecting) return;
        connecting = true;
        WifiP2pConfig cfg = new WifiP2pConfig();
        cfg.deviceAddress = d.deviceAddress;
        cfg.groupOwnerIntent = 0;
        L.i("P2P connect -> " + d.deviceName + " " + d.deviceAddress);
        mgr.connect(ch, cfg, listener("connect"));
    }

    private WifiP2pManager.ActionListener listener(String what) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                L.i("P2P " + what + " ok");
            }

            @Override
            public void onFailure(int reason) {
                L.w("P2P " + what + " fallo reason=" + reason);
                if ("connect".equals(what)) connecting = false;
            }
        };
    }

    void stop() {
        if (!started) return;
        started = false;
        handler.removeCallbacks(retry);
        try {
            ctx.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        mgr.clearServiceRequests(ch, null);
        mgr.stopPeerDiscovery(ch, null);
        ch.close();
    }
}
