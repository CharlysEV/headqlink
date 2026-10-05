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
 * Servicio en primer plano: mantiene la red con el coche (Wi-Fi Direct o la zona Wi-Fi del móvil) + escucha UDP y
 * abre una sesión por cada coche que anuncia por broadcast. Funciona con la pantalla apagada (wakelock + wifilock).
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
    /** Tras perder al coche, cuánto esperamos a que vuelva antes de cerrarlo todo (por defecto; ver Config.carGoneMs). */
    private static final long CAR_GONE_MS = 30_000;
    /** Si al vencer la espera hay una sesión o un intento en marcha, se vuelve a mirar en este tiempo. */
    private static final long CAR_GONE_RECHECK_MS = 5_000;

    static volatile String status = "parado";

    private Config cfg;
    private P2pLink p2p;
    /** Solo en el modo «Punto de acceso del móvil» (qdauto §5.3). */
    private HotspotWatcher hotspot;
    private volatile HotspotWatcher.State hotspotState = HotspotWatcher.State.UNKNOWN;
    /** El transporte (red + descubrimiento) se arranca una sola vez por servicio. */
    private boolean transportStarted;
    private UdpDiscovery udp;
    private SspSession session;
    /** Motor QDAuto (qdauto §4.3): dueño del PhoneLink del núcleo, y el vídeo que vive entre sesiones. */
    private QdLinkHost qd;
    private VideoHub video;
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
    /** Adaptación térmica del vídeo y temperatura de la batería cada minuto. */
    private ThermalGuard thermal;
    /** Pantalla encendida/apagada/desbloqueada: con la pantalla encendida Android escanea WiFi cada 10 s. */
    private final android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            String what = Intent.ACTION_SCREEN_ON.equals(a) ? "screen_on" : Intent.ACTION_SCREEN_OFF.equals(a) ? "screen_off" : "unlocked";
            PerfTrace.event(what, 0);
            CarTrace.note("PANTALLA", what);
            QdTrace.i("HQL/Sistema", "pantalla: " + what + " · " + PhoneScreen.describe(c));
        }
    };
    private final android.content.BroadcastReceiver scanReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            boolean updated = i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            PerfTrace.event("wifi_scan", updated ? 1 : 0);
            QdTrace.i("HQL/Sistema", "escaneo Wi-Fi del sistema" + (updated ? "" : " (sin resultados nuevos)"));
        }
    };
    /** Sin llegar a conectar con el coche en este tiempo, se cierra todo (no se queda buscando). */
    private static final long NO_CAR_MS = 5 * 60_000;
    private final Runnable noCar = () -> {
        if (stopping || LinkState.car == LinkState.Car.CONNECTED) return;
        L.i("sin coche en " + NO_CAR_MS / 60_000 + " min: cierro todo");
        shutdownAll();
    };

    private final Runnable carGone = new Runnable() {
        @Override
        public void run() {
            if (stopping) return;
            if (qd != null && qd.isBusy()) {
                // El coche ha vuelto (o está conectando) justo ahora: no se apaga nada.
                L.i("espera del coche vencida con una sesión o un intento en marcha: vuelvo a mirar en "
                        + CAR_GONE_RECHECK_MS / 1000 + " s");
                main.postDelayed(this, CAR_GONE_RECHECK_MS);
                return;
            }
            L.i("coche desconectado más de " + carGoneMs() / 1000 + " s: cierro todo");
            shutdownAll();
        }
    };

    /** Espera a que vuelva el coche: la del ajuste car_gone_ms con el motor QDAuto; 30 s con el original. */
    private long carGoneMs() {
        return qd != null ? cfg.carGoneMs() : CAR_GONE_MS;
    }

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
        boolean applyQd = intent != null && ACTION_APPLY.equals(intent.getAction()) && qd != null
                && (qd.isBusy() || video.hasVideo());
        if (applyQd) {
            // Motor QDAuto: se cierran la sesión y el vídeo; la sesión nueva usa los ajustes nuevos. Mientras AA termina
            // de desconectar (si renegocia), no se reconecta.
            boolean renegotiate = intent.getBooleanExtra(EXTRA_AA_RENEGOTIATE, false) && Config.isAa(cfg.mode());
            L.i("aplicando ajustes: cierro la sesión con el coche y el vídeo (motor QDAuto)");
            qd.closeSession("aplicar ajustes");
            video.stop("ajustes");
            qd.pauseReconnect(renegotiate ? 3000 : 1000);
            if (renegotiate) {
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
        if (!transportStarted) {
            transportStarted = true;
            L.i("servicio iniciado. " + cfg.summary());
            SessionSummary.INSTANCE.startTrip(System.currentTimeMillis(), AaPassthroughSource.AA_LAUNCHES.get());
            acquireLocks();
            startTransport();
            LinkState.setRunning(true);
            LinkState.setCar(LinkState.Car.SEARCHING, "");
            sysMonitor = new SystemMonitor(this);
            sysMonitor.start();
            // Con el motor QDAuto, el nivel térmico baja fps y bitrate del vídeo vivo; con el original, solo se registra.
            VideoHub hub = video;
            thermal = new ThermalGuard(this, hub != null ? hub::setThermalLevel : null);
            thermal.start();
            main.postDelayed(noCar, NO_CAR_MS);
            setStatus(Str.get(R.string.hql_waiting_car));
        } else {
            L.i("ajustes actualizados: " + cfg.summary() + " (se aplican en la próxima sesión)");
        }
        // Sin relanzamiento automático: HeadQLink solo arranca cuando el usuario abre la app.
        return START_NOT_STICKY;
    }

    /**
     * Red y descubrimiento, según los ajustes al arrancar (se aplican al volver a conectar). Wi-Fi Direct: P2pLink une
     * el móvil al grupo del coche, como siempre. Zona Wi-Fi: P2pLink no arranca (no se crean grupos ni se toca la zona
     * Wi-Fi; tampoco se ata el proceso a ninguna red) y HotspotWatcher informa de si está activa.
     */
    private void startTransport() {
        // Una sola lectura: lo que se cambie en marcha se aplica al volver a conectar, y diagnóstico e interfaz dicen esto.
        String linkMode = cfg.linkMode();
        String engine = cfg.linkEngine();
        LinkState.setActiveTransport(linkMode, engine);
        if (Config.LINK_HOTSPOT.equals(linkMode)) {
            L.i("conexión: punto de acceso del móvil (sin Wi-Fi Direct)");
            hotspot = new HotspotWatcher(this, this::onHotspotState);
            hotspot.start();
        } else {
            L.i("conexión: Wi-Fi Direct");
            p2p = new P2pLink(this);
            p2p.start();
        }
        if (Config.ENGINE_QDAUTO.equals(engine)) {
            // Los dos motores escuchan en el UDP 18463: nunca conviven.
            L.i("motor de protocolo: QDAuto");
            video = new VideoHub(this, cfg);
            qd = new QdLinkHost(this, cfg, linkMode, video, new QdCallbacks());
            qd.start();
        } else {
            L.i("motor de protocolo: original de headqlink");
            udp = new UdpDiscovery(this);
            udp.start();
        }
    }

    /** Avisos del motor QDAuto (hilo principal). */
    private final class QdCallbacks implements QdLinkHost.Callbacks {
        @Override
        public void onCarSeen(String name) {
            if (LinkState.car == LinkState.Car.CONNECTED || LinkState.car == LinkState.Car.SEEN) return;
            L.i("coche anunciado: " + name);
            setStatus(Str.get(R.string.hql_car_detected));
            LinkState.setCar(LinkState.Car.SEEN, name);
        }

        @Override
        public void onCarConnected(String detail) {
            hadSession = true;
            // Si el tamaño del coche (CAR_INFO) ya llegó, se conserva.
            LinkState.setCar(LinkState.Car.CONNECTED, LinkState.car == LinkState.Car.CONNECTED ? LinkState.carDetail : "");
            main.removeCallbacks(carGone);
            main.removeCallbacks(noCar);
            setStatus(Str.get(R.string.hql_car_connected));
        }

        @Override
        public void onCarSize(String detail) {
            LinkState.setCar(LinkState.Car.CONNECTED, detail);
            setStatus(Str.get(R.string.hql_car_connected));
        }

        @Override
        public void onCarLost(String reason) {
            L.i("sesión con el coche terminada: " + reason);
            if (video != null && video.hasVideo() && cfg.qdKeepVideo()) {
                // El vídeo y Android Auto siguen vivos esperando al coche (qdauto §6): sin reiniciar nada.
                setStatus(Str.get(R.string.hql_reconnecting));
                LinkState.setCar(LinkState.Car.RECONNECTING, "");
            } else {
                setStatus(Str.get(R.string.hql_session_ended));
                LinkState.setCar(LinkState.Car.SEARCHING, "");
            }
            if (hadSession && !stopping) {
                main.removeCallbacks(carGone);
                main.postDelayed(carGone, carGoneMs());
            }
        }

        @Override
        public void onLinkError(String message) {
            setStatus(message);
        }

        @Override
        public void onLinkReady() {
            setStatus(Str.get(R.string.hql_waiting_car));
            if (hotspot != null) hotspot.republish();
            else if (p2p != null) p2p.republish();
            else LinkState.setNetwork(LinkState.Level.IDLE, "");
        }
    }

    /** Hilo principal: la zona Wi-Fi se enciende o se apaga (o no se sabe). */
    private void onHotspotState(HotspotWatcher.State state, String detail) {
        hotspotState = state;
        if (stopping || isLinkConnected()) return;
        if (state == HotspotWatcher.State.OFF) {
            L.w("zona Wi-Fi apagada: el coche no puede conectar hasta que se active");
            setStatus(Str.get(R.string.hql_hotspot_off_notif));
        } else if (state == HotspotWatcher.State.ON) {
            setStatus(Str.get(R.string.hql_waiting_car));
        }
    }

    /** Hay una sesión con el coche ahora mismo. */
    private boolean isLinkConnected() {
        if (qd != null) return qd.isConnected();
        SspSession s = session;
        return s != null && s.isConnected();
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
        boolean connected = isLinkConnected();
        String title = connected ? Str.get(R.string.hql_notif_connected) : "HeadQLink";
        // Con la zona Wi-Fi apagada, tocar la notificación abre sus ajustes; si no, la app.
        boolean hotspotOff = hotspot != null && hotspotState == HotspotWatcher.State.OFF && !connected;
        Intent open = hotspotOff ? new Intent("android.settings.TETHER_SETTINGS")
                : new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (hotspotOff && open.resolveActivity(getPackageManager()) == null) {
            open = new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS);
        }
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, hotspotOff ? 2 : 1, open,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setContentTitle(title)
                .setContentText(connected ? null : text)
                .setContentIntent(pi)
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
        if (qd != null) {
            // Primero se cierra la sesión en curso y se espera su resumen (como mucho 500 ms), para que el del viaje
            // (qdauto §7.4) la incluya; los dos antes de cerrar el diario del coche.
            qd.stop();
            if (!qd.awaitStopped(500)) L.w("la sesión con el coche no terminó en 500 ms; el resumen del viaje puede no incluirla");
            String trip = SessionSummary.INSTANCE.tripSummary(System.currentTimeMillis(), AaPassthroughSource.AA_LAUNCHES.get());
            QdTrace.block("HQL/Viaje", trip);
            for (String line : trip.split("\n")) L.quiet("I", line);
            CarTrace.note("VIAJE", trip.replace('\n', ' '));
        }
        // Después del resumen de la última sesión, que lleva el estado térmico.
        if (thermal != null) thermal.stop();
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
        if (qd != null) qd.stop();
        if (video != null) video.quit();
        if (udp != null) udp.shutdown();
        if (p2p != null) p2p.stop();
        if (hotspot != null) hotspot.stop();
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
