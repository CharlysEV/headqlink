package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Servicio en primer plano: mantiene P2P + escucha UDP y abre una SspSession por cada coche
 * que anuncia por broadcast. Funciona con la pantalla apagada (wakelock + wifilock).
 */
public class LinkService extends Service implements UdpDiscovery.Listener, SspSession.Listener {
    static final String ACTION_STOP = "com.headqlink.link.STOP";
    static final String ACTION_APPLY = "com.headqlink.link.APPLY";
    /** El Bluetooth del coche se ha conectado (CarBtReceiver): ponerse a esperar al coche. */
    static final String ACTION_BT_CAR = "com.headqlink.link.BT_CAR";
    /** El Bluetooth del coche se ha ido: parar si no llegamos a conectar. */
    static final String ACTION_BT_CAR_GONE = "com.headqlink.link.BT_CAR_GONE";
    /** Con ACTION_APPLY: reconectar también Android Auto (cambio de perfil: resolución y fps). */
    static final String EXTRA_AA_RENEGOTIATE = "aa_renegotiate";
    /** Prueba sin coche: arranca la cadena de Android Auto con la geometría del coche y cuenta frames. */
    static final String ACTION_AA_TEST = "com.headqlink.link.AA_TEST";
    /** Diagnóstico sin coche: dibuja la interfaz propia (CarUi) y guarda capturas en files/ui-preview-*.png. */
    static final String ACTION_UI_PREVIEW = "com.headqlink.link.UI_PREVIEW";
    /** Reinicia el servidor de head unit de Android Auto con la automatización de accesibilidad. */
    static final String ACTION_AA_SERVER_RESTART = "com.headqlink.link.AA_SERVER_RESTART";
    private static final String CHANNEL = "link";
    /** Tras perder al coche, cuánto esperamos a que vuelva antes de cerrarlo todo. */
    private static final long CAR_GONE_MS = 30_000;

    static volatile String status = "parado";

    private Config cfg;
    private P2pLink p2p;
    private UdpDiscovery udp;
    private SspSession session;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock mcLock;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean hadSession;
    /**
     * El servicio se está cerrando. Al cerrar la sesión en onDestroy, onSessionEnded volvía a armar
     * el temporizador de 30 s en esta instancia ya muerta; si el usuario reconectaba, ese temporizador
     * huérfano cerraba Android Auto en mitad de la sesión nueva.
     */
    private volatile boolean stopping;
    private SystemMonitor sysMonitor;
    /** Pantalla encendida/apagada/desbloqueada: con la pantalla encendida Android escanea WiFi cada 10 s. */
    private final android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            String what = Intent.ACTION_SCREEN_ON.equals(a) ? "screen_on" : Intent.ACTION_SCREEN_OFF.equals(a) ? "screen_off" : "unlocked";
            PerfTrace.event(what, 0);
            CarTrace.note("PANTALLA", what);
        }
    };
    private final android.content.BroadcastReceiver scanReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            boolean updated = i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            PerfTrace.event("wifi_scan", updated ? 1 : 0);
        }
    };
    /** Sin llegar a conectar con el coche en este tiempo, se cierra todo (no se queda buscando). */
    private static final long NO_CAR_MS = 5 * 60_000;
    private final Runnable noCar = () -> {
        if (stopping || LinkState.car == LinkState.Car.CONNECTED) return;
        L.i("sin coche en " + NO_CAR_MS / 60_000 + " min: cierro todo");
        shutdownAll();
    };

    private final Runnable carGone = () -> {
        if (stopping) return;
        L.i("coche desconectado más de " + CAR_GONE_MS / 1000 + " s: cierro todo");
        shutdownAll();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        L.init(this);
        CarTrace.init(this);
        PerfTrace.init(this);
        CarTrace.open();
        // Escaneos WiFi del sistema: sacan la radio del canal del coche (se registran en la traza).
        registerReceiver(scanReceiver, new android.content.IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                Context.RECEIVER_NOT_EXPORTED);
        android.content.IntentFilter screen = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        screen.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, screen, Context.RECEIVER_NOT_EXPORTED);
        cfg = new Config(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // Relanzado por el sistema tras matar el proceso (p. ej. al abrir la cámara): no
            // arrancamos nada solos. Pasar a primer plano desde segundo plano está prohibido y la
            // caída tumbaría también el servicio de accesibilidad (Android lo desactiva si cae en bucle).
            L.w("servicio relanzado por el sistema sin orden: me detengo");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // Desconectar a mano: el usuario está mirando el móvil, se apaga también el servidor de AA.
            // Primero en primer plano: si llega con startForegroundService y el servicio estaba
            // parado, sin esto Android cierra la app (y con ella el apagado del servidor a medias).
            goForeground(Str.get(R.string.hql_disconnect));
            shutdownAll(true);
            return START_NOT_STICKY;
        }
        if (ACTION_BT_CAR_GONE.equals(intent.getAction())) {
            if (LinkState.car != LinkState.Car.CONNECTED) {
                L.i("Bluetooth del coche fuera sin sesión: me detengo");
                shutdownAll();
            } else {
                L.i("Bluetooth del coche fuera con la sesión en marcha: sigo (se cierra si el coche se va)");
            }
            return START_NOT_STICKY;
        }
        if (ACTION_BT_CAR.equals(intent.getAction())) {
            L.i("conexión automática: Bluetooth del coche detectado");
            if (Config.isAa(cfg.mode()) && TouchService.instance == null) {
                L.w("conexión automática: falta la accesibilidad (Android la desactiva al actualizar la app)");
            }
        }
        cfg.applyExtras(intent);
        LowLatency.apply(cfg);
        if (intent != null && ACTION_AA_SERVER_RESTART.equals(intent.getAction())) {
            if (!goForeground(Str.get(R.string.hql_fg_restart_aa))) return START_NOT_STICKY;
            new Thread(() -> L.i("AA server reinicio: " + (AaServerStarter.restartAndWait(this) ? "ok" : "fallo")), "aa-restart").start();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_UI_PREVIEW.equals(intent.getAction())) {
            // Lanzada con startForegroundService: hay que pasar a primer plano o Android cierra la app.
            if (!goForeground(Str.get(R.string.hql_fg_preview))) return START_NOT_STICKY;
            UiPreview.run(this, intent.getStringExtra("screen"), () -> {
                if (!LinkState.running) stopSelf();
            });
            return START_NOT_STICKY;
        }
        if (intent != null && "com.headqlink.link.ENC_PROBE".equals(intent.getAction())) {
            // Diagnóstico sin coche: crea el encoder como en una sesión (1920x882 a 60 fps, baja
            // latencia) para registrar qué parámetros de fabricante admite, y lo cierra.
            if (!goForeground(Str.get(R.string.hql_fg_encoder))) return START_NOT_STICKY;
            new Thread(() -> {
                VideoEncoder.Params p = new VideoEncoder.Params();
                p.width = 1920;
                p.height = 882;
                p.fps = 60;
                p.bitrate = 8_000_000;
                p.intraRefreshFrames = 60;
                p.iFrameIntervalSec = 30;
                p.lowLatency = true;
                VideoEncoder enc = new VideoEncoder(p, new VideoEncoder.Sink() {
                    @Override
                    public void onCodecConfig(byte[] spsPps) {
                    }

                    @Override
                    public void onFrame(byte[] data, int len, boolean keyFrame, long ptsUs) {
                    }
                });
                try {
                    enc.start();
                } catch (Exception e) {
                    L.e("diagnóstico del encoder", e);
                }
                enc.stop();
                if (!LinkState.running) stopSelf();
            }, "enc-probe").start();
            return START_NOT_STICKY;
        }
        if ("com.headqlink.link.ENC_BENCH".equals(intent.getAction())) {
            // Banco de pruebas del encoder sin coche (EncBench): resultados en el registro.
            if (!goForeground(Str.get(R.string.hql_fg_encoder))) return START_NOT_STICKY;
            if (LinkState.running) {
                L.w("ENC_BENCH: no con una sesión en marcha");
                return START_NOT_STICKY;
            }
            new Thread(() -> {
                LowLatency.boostCurrentThread();
                EncBench.run();
                if (!LinkState.running) stopSelf();
            }, "enc-bench").start();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_AA_TEST.equals(intent.getAction())) {
            if (!goForeground(Str.get(R.string.hql_fg_aa_test))) return START_NOT_STICKY;
            startAaTest();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_APPLY.equals(intent.getAction()) && session != null) {
            // Cerrar la sesión: el coche reconecta en ~5 s y la nueva sesión usa los ajustes nuevos.
            L.i("aplicando ajustes: reinicio la sesión con el coche");
            session.close();
            if (intent.getBooleanExtra(EXTRA_AA_RENEGOTIATE, false) && Config.isAa(cfg.mode())) {
                // AA negocia resolución y fps al conectar: se desconecta y la sesión nueva lo vuelve a lanzar.
                L.i("aplicando ajustes: reconecto Android Auto (perfil " + cfg.videoProfile().id + ")");
                try {
                    startService(new Intent(this, com.andrerinas.openheadunit.aap.AapService.class)
                            .setAction(com.andrerinas.openheadunit.aap.AapService.ACTION_DISCONNECT));
                    startService(new Intent(this, com.andrerinas.openheadunit.aap.AapService.class)
                            .setAction(com.andrerinas.openheadunit.aap.AapService.ACTION_STOP_SELF_MODE));
                } catch (RuntimeException e) {
                    L.e("no se pudo desconectar Android Auto", e);
                }
            }
        }
        if (!goForeground(Str.get(R.string.hql_waiting_car))) return START_NOT_STICKY;
        if (udp == null) {
            L.i("servicio iniciado. " + cfg.summary());
            acquireLocks();
            p2p = new P2pLink(this);
            p2p.start();
            udp = new UdpDiscovery(this);
            udp.start();
            LinkState.setRunning(true);
            LinkState.setCar(LinkState.Car.SEARCHING, "");
            sysMonitor = new SystemMonitor(this);
            sysMonitor.start();
            main.postDelayed(noCar, NO_CAR_MS);
            setStatus(Str.get(R.string.hql_waiting_car));
        } else {
            L.i("ajustes actualizados: " + cfg.summary() + " (se aplican en la próxima sesión)");
        }
        // Sin relanzamiento automático: HeadQLink solo arranca cuando el usuario abre la app.
        return START_NOT_STICKY;
    }

    /** Pasa a primer plano; si Android no lo permite, el servicio se detiene sin tumbar la app. */
    private boolean goForeground(String text) {
        Notification n = buildNotification(text);
        // Con permiso de ubicación, también tipo "ubicación": el GPS de Instrumentos/Eficiencia sigue
        // con la pantalla apagada. Si Android no lo admite ahora, solo "dispositivo conectado".
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                return true;
            } catch (RuntimeException e) {
                L.w("primer plano sin ubicación: " + e.getMessage());
            }
        }
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            return true;
        } catch (RuntimeException e) {
            L.e("Android no deja pasar a primer plano ahora; me detengo", e);
            stopSelf();
            return false;
        }
    }

    @Override
    public synchronized void onCarBroadcast(InetAddress carIp, JSONObject info) {
        if (session != null && session.isConnected()) return;
        if (session == null) {
            L.i("coche anunciado: " + info + " desde " + carIp.getHostAddress());
            try {
                session = new SspSession(this, cfg, this);
                session.start();
            } catch (IOException e) {
                L.e("no se pudo abrir el servidor TCP", e);
                return;
            }
            setStatus(Str.get(R.string.hql_car_detected));
            LinkState.setCar(LinkState.Car.SEEN, info.optString("DeviceName"));
        }
        // Reenviamos el ACK en cada broadcast mientras el coche no se conecte.
        udp.sendAck(carIp, session.ackJson());
    }

    @Override
    public void onSessionConnected(SspSession s) {
        hadSession = true;
        LinkState.setCar(LinkState.Car.CONNECTED, "");
        main.removeCallbacks(carGone);
        main.removeCallbacks(noCar);
    }

    @Override
    public synchronized void onSessionEnded(SspSession s) {
        if (session == s) session = null;
        setStatus(Str.get(R.string.hql_session_ended));
        LinkState.setCar(LinkState.Car.SEARCHING, "");
        if (hadSession && !stopping) {
            main.removeCallbacks(carGone);
            main.postDelayed(carGone, CAR_GONE_MS);
        }
    }

    /**
     * Cierra todo: sesión con el coche, P2P/UDP, Android Auto y su servidor de head unit
     * (ahora si el móvil está desbloqueado, o en cuanto se desbloquee).
     */
    private void shutdownAll() {
        shutdownAll(false);
    }

    /**
     * userAction: «Desconectar» pulsado en el móvil. El servidor de head unit de AA se apaga siempre
     * (Config.stopAaServerOnExit): ahora si el móvil está desbloqueado, o al desbloquearlo, tras la
     * capa y sin que se vean sus ajustes.
     */
    private void shutdownAll(boolean userAction) {
        stopping = true;
        main.removeCallbacks(carGone);
        main.removeCallbacks(noCar);
        if (Config.isAa(cfg.mode())) {
            android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
            boolean locked = km != null && km.isKeyguardLocked();
            boolean aaConnected = com.andrerinas.openheadunit.App.Companion.provide(this).getCommManager().isConnected();
            // Con el botón «Detener» de la notificación del servidor se apaga ya, bloqueado o no; sin
            // él, con el móvil bloqueado se aparca la sesión hasta desbloquear.
            if (cfg.stopAaServerOnExit() && locked && aaConnected && TouchService.instance != null
                    && !AaServerStarter.canStopWithoutUi()) {
                // Móvil bloqueado: el servidor (abierto en toda la red) no se puede apagar hasta
                // desbloquear. Nuestra head unit lo sigue ocupando, sin vídeo, hasta entonces.
                AaServerStarter.requestStop(this);
                AaGuardService.park(this);
                stopSelf();
                return;
            }
            try {
                startService(new Intent(this, com.andrerinas.openheadunit.aap.AapService.class)
                        .setAction(com.andrerinas.openheadunit.aap.AapService.ACTION_STOP_SERVICE));
            } catch (RuntimeException e) {
                L.e("no se pudo parar Android Auto", e);
            }
            if (cfg.stopAaServerOnExit()) {
                AaServerStarter.requestStop(this);
            } else {
                AaServerStarter.stopIfUnlocked(this);
            }
        }
        stopSelf();
    }

    @Override
    public void onStatus(String st) {
        setStatus(st);
    }

    private void setStatus(String st) {
        status = st;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(1, buildNotification(st));
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_channel_link), NotificationManager.IMPORTANCE_LOW));
        boolean connected = session != null && session.isConnected();
        String title = connected ? Str.get(R.string.hql_notif_connected) : "HeadQLink";
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setContentTitle(title)
                .setContentText(connected ? null : text)
                .setOngoing(true)
                .build();
    }

    private void acquireLocks() {
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "headqlink:link");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "headqlink:wifi");
        wifiLock.acquire();
        mcLock = wm.createMulticastLock("headqlink:mc");
        mcLock.acquire();
    }

    private AaPassthroughSource aaTest;

    private void startAaTest() {
        if (aaTest != null) aaTest.stop();
        L.init(this);
        aaTest = new AaPassthroughSource(this, cfg.aaDpi());
        aaTest.setCarSize(1920, 882);
        long[] stats = new long[3]; // unidades, bytes, t0
        stats[2] = System.currentTimeMillis();
        aaTest.startPassthrough((data, off, len) -> {
            stats[0]++;
            stats[1] += len;
            long now = System.currentTimeMillis();
            if (now - stats[2] >= 5000) {
                int[] sz = aaTest.passthroughSize();
                L.i(String.format(java.util.Locale.US, "AA_TEST %.1f fps %.0f kbps, vídeo %dx%d",
                        stats[0] * 1000f / (now - stats[2]), stats[1] * 8f / (now - stats[2]), sz[0], sz[1]));
                stats[0] = 0;
                stats[1] = 0;
                stats[2] = now;
            }
        });
    }

    @Override
    public void onDestroy() {
        stopping = true;
        main.removeCallbacks(carGone);
        main.removeCallbacks(noCar);
        if (sysMonitor != null) sysMonitor.stop();
        CarTrace.close();
        try {
            unregisterReceiver(scanReceiver);
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        LinkState.setRunning(false);
        if (aaTest != null) aaTest.stop();
        L.i("servicio parado");
        if (session != null) session.close();
        if (udp != null) udp.shutdown();
        if (p2p != null) p2p.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (mcLock != null && mcLock.isHeld()) mcLock.release();
        status = "parado";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
