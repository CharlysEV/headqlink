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
 *
 * Cable USB (experimental, UsbLink): con la conexión «Cable USB» o en cuanto se enchufa un accesorio del coche
 * (Neusoft / QDriveLink: UsbAccessoryActivity → ACTION_USB_ATTACHED), el cable tiene prioridad: se para el Wi-Fi
 * (descubrimiento, intento y sesión) y la sesión va por el accesorio, con el mismo Android Auto y el mismo ciclo de
 * vida. Al quitar el cable, «coche perdido» y, si la conexión elegida era otra, vuelve el Wi-Fi.
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
    /** Aviso «El servidor de Android Auto sigue encendido · Tocar para apagarlo» (AaServerStarter). */
    static final String ACTION_AA_SERVER_OFF = "com.headqlink.link.AA_SERVER_OFF";
    /** El coche puso el móvil en modo accesorio (UsbAccessoryActivity), con UsbManager.EXTRA_ACCESSORY: cable USB. */
    static final String ACTION_USB_ATTACHED = "com.headqlink.link.USB_ATTACHED";
    private static final String CHANNEL = "link";

    static volatile String status = "parado";

    private Config cfg;
    private P2pLink p2p;
    /** Solo en el modo «Punto de acceso del móvil» (qdauto §5.3). */
    private HotspotWatcher hotspot;
    private volatile HotspotWatcher.State hotspotState = HotspotWatcher.State.UNKNOWN;
    /** El transporte (red + descubrimiento) se arranca una sola vez por servicio. */
    private boolean transportStarted;
    private UdpDiscovery udp;
    private volatile SspSession session;
    /** Motor QDAuto (qdauto §4.3): dueño del PhoneLink del núcleo, y el vídeo que vive entre sesiones. */
    private QdLinkHost qd;
    private VideoHub video;
    /** Cable USB (experimental): sondeo siempre; enlace por cable cuando está activo (usbActive). */
    private UsbLink usb;
    /** El enlace va por el cable: el Wi-Fi está parado mientras tanto. Se cambia en el hilo principal. */
    private volatile boolean usbActive;
    /** Hubo alguna sesión por el cable (para el resumen del viaje). */
    private boolean usbUsed;
    /** El servicio arranca por el cable (ACTION_USB_ATTACHED antes de arrancar el transporte). */
    private boolean usbAtStart;
    /** Conexión y motor configurados al arrancar: los del Wi-Fi, al que se vuelve al quitar el cable. */
    private String wifiLinkMode;
    private String wifiEngine;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock mcLock;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    /**
     * El servicio se está cerrando. Al cerrar la sesión en onDestroy, onSessionEnded volvía a armar
     * el temporizador de 30 s en esta instancia ya muerta; si el usuario reconectaba, ese temporizador
     * huérfano cerraba Android Auto en mitad de la sesión nueva.
     */
    private volatile boolean stopping;
    /**
     * Ciclo de vida (puro): búsqueda, sesión, vídeo vivo, AA en pausa esperando al coche y cierre. Solo en el hilo
     * principal; su temporizador es lifeTimer.
     */
    private final LinkLifecycle life = new LinkLifecycle();
    private final Runnable lifeTimer = this::onLifeTimer;
    /** Motor original: último anuncio del coche pasado al ciclo de vida (onCarBroadcast, con su candado). */
    private long lastHeardPostMs;
    /** Android Auto ya cerrado o aparcado para el guardián (shutdownAll): onDestroy no lo repite. */
    private boolean aaClosed;
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
    // ---------------------------------------------------------------- ciclo de vida (LinkLifecycle)

    private static long now() {
        return android.os.SystemClock.elapsedRealtime();
    }

    /**
     * Vídeo vivo tras perder al coche: car_gone_ms con el motor QDAuto y «mantener Android Auto»; si no, 0 (la sesión
     * se lleva su vídeo, y AA, sin imagen que recibir, pasa a la pausa en el acto).
     */
    private long graceMs() {
        return (qd != null || usbActive) && cfg.qdKeepVideo() ? cfg.carGoneMs() : 0;
    }

    private boolean aaConnected() {
        return com.andrerinas.openheadunit.App.Companion.provide(this).getCommManager().isConnected();
    }

    /** Hay una sesión o un intento con el coche (con el motor original, una sesión abierta tras su broadcast). */
    private boolean linkBusy() {
        if (usbActive) return usb != null && usb.isBusy();
        if (qd != null) return qd.isBusy();
        return session != null;
    }

    /** Lo que se ve del móvil ahora, para las decisiones del ciclo de vida. */
    private LinkLifecycle.Env env() {
        LinkLifecycle.Env e = new LinkLifecycle.Env();
        // Con AA aparcado cuenta como modo AA aunque se haya cambiado el modo mientras tanto: hay que cerrarlo igual.
        e.aaMode = Config.isAa(cfg.mode()) || AaPark.parked;
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        e.locked = km != null && km.isKeyguardLocked();
        e.aaConnected = aaConnected();
        e.aaParked = AaPark.parked;
        e.guardActive = AaGuardService.active;
        e.stopPending = AaServerStarter.stopPending(this);
        e.serverOn = e.aaConnected || AaServerStarter.serverLikelyOn(this);
        e.linkBusy = linkBusy();
        e.canAutomate = TouchService.instance != null;
        e.canStopWithoutUi = AaServerStarter.canStopWithoutUi();
        e.stopServerOnExit = cfg.stopAaServerOnExit();
        e.manualServer = cfg.aaServerManual();
        return e;
    }

    private void onLifeTimer() {
        if (stopping) return;
        apply(life.timer(now(), env()));
    }

    /** Hilo principal: registra los motivos y ejecuta, en orden, las acciones de una decisión del ciclo de vida. */
    private void apply(LinkLifecycle.Decision d) {
        if (stopping) return;
        LinkLifecycle.Phase before = life.phase();
        for (String r : d.reasons()) L.life(r);
        for (LinkLifecycle.Action a : d.actions()) {
            switch (a) {
                case ADOPT_PARK:
                    // AaPark sigue aparcado y con su ping: solo se va el guardián (sin apagar el servidor).
                    AaGuardService.handOver(this, "el enlace vuelve a escuchar al coche");
                    break;
                case CANCEL_PENDING_STOP:
                    AaServerStarter.cancelPendingStop(this);
                    break;
                case RESUME_AA:
                    AaPark.resume("vuelve el coche");
                    break;
                case PARK_AA:
                    // Antes de parar el vídeo: así la fuente, al pararse, no devuelve la vista al móvil.
                    AaPark.park(this, "esperando al coche");
                    LinkState.setSource(LinkState.Level.BUSY, Str.get(R.string.hql_auto_paused));
                    break;
                case STOP_VIDEO:
                    if (video != null) video.stop("sin coche: vídeo en pausa");
                    break;
                case START_SERVER:
                    startAaServerInBackground();
                    break;
                case START_SERVER_ON_UNLOCK:
                    AaServerStarter.requestStartOnUnlock(this, "el coche lo necesitará y el móvil está bloqueado");
                    break;
                case CHECK_SERVER:
                    // Arranque manual: se mira 127.0.0.1:5277 en otro hilo; si está apagado, aviso y espera (cada 2 s).
                    AaServerManual.need(this, needFor(before), false);
                    break;
                case SHUTDOWN:
                    shutdownAll();
                    return;
            }
        }
        scheduleLife();
        if (life.phase() == LinkLifecycle.Phase.PARKED && before != LinkLifecycle.Phase.PARKED) {
            // Sin vídeo y escuchando al coche: «Reconectando… (Android Auto en espera)» si AA quedó en pausa.
            setStatus(Str.get(AaPark.parked ? R.string.hql_waiting_car_paused : R.string.hql_waiting_car));
            LinkState.setCar(AaPark.parked ? LinkState.Car.RECONNECTING : LinkState.Car.SEARCHING, "");
        }
    }

    /** Por qué arrancó el enlace, para el log del arranque manual del servidor de AA. */
    private AaServerPolicy.Need startNeed = AaServerPolicy.Need.CONNECT;

    /** Para el log del arranque manual: la comprobación del arranque, del coche anunciado o de la vuelta del coche. */
    private AaServerPolicy.Need needFor(LinkLifecycle.Phase before) {
        switch (before) {
            case CLOSED:
                return startNeed;
            case GRACE:
            case PARKED:
                return AaServerPolicy.Need.RESUME;
            default:
                return AaServerPolicy.Need.CAR_SEEN;
        }
    }

    private void scheduleLife() {
        main.removeCallbacks(lifeTimer);
        long dl = life.deadlineMs();
        if (dl >= 0 && !stopping) main.postDelayed(lifeTimer, Math.max(0, dl - now()));
    }

    /** Arranque anticipado del servidor de AA (Bluetooth del coche o coche anunciado, móvil desbloqueado). */
    private void startAaServerInBackground() {
        new Thread(() -> {
            boolean ok = AaServerStarter.startAndWait(this);
            L.life("servidor de Android Auto " + (ok ? "listo" : "no confirmado") + " (arranque anticipado)");
        }, "aa-prestart").start();
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
            L.life("Desconectar pulsado: cierro todo");
            shutdownAll(true);
            return START_NOT_STICKY;
        }
        if (ACTION_AA_SERVER_OFF.equals(intent.getAction())) {
            // Tocado el aviso «El servidor de Android Auto sigue encendido»: llega con startForegroundService.
            AaServerStarter.cancelServerStillOn(this);
            if (!goForeground(transportStarted ? status : Str.get(R.string.hql_cover_stopping))) return START_NOT_STICKY;
            if (transportStarted) {
                L.life("aviso «servidor encendido» tocado con el enlace en marcha: lo apagará el cierre del enlace");
                return START_NOT_STICKY;
            }
            L.life("aviso «servidor encendido» tocado: apago Android Auto y su servidor");
            // El guardián se va sin avisar (si no, al caer AA diría «servidor abierto»); el cierre decide de nuevo.
            AaGuardService.handOver(this, "apagado pedido desde el aviso");
            shutdownAll(true);
            return START_NOT_STICKY;
        }
        if (ACTION_BT_CAR_GONE.equals(intent.getAction())) {
            if (!transportStarted) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            apply(life.btGone(now(), env()));
            return START_NOT_STICKY;
        }
        boolean usbAttach = ACTION_USB_ATTACHED.equals(intent.getAction());
        if (usbAttach) {
            android.hardware.usb.UsbAccessory acc = UsbProbe.accessoryFrom(intent);
            if (transportStarted) {
                // startForegroundService: hay que volver a pasar a primer plano aunque ya lo esté.
                if (!goForeground(status)) return START_NOT_STICKY;
                onUsbCar(acc, "accesorio " + UsbProbe.shortName(acc) + " conectado");
                return START_NOT_STICKY;
            }
            usbAtStart = true;
            L.i("cable USB: accesorio del coche " + UsbProbe.shortName(acc) + " conectado: arranco el enlace por cable");
        }
        if (ACTION_BT_CAR.equals(intent.getAction())) {
            L.i("conexión automática: Bluetooth del coche detectado");
            if (Config.isAa(cfg.mode()) && cfg.aaServerManual()) {
                L.i("conexión automática: arranque manual del servidor de Android Auto (sin accesibilidad): si ya está"
                        + " encendido, no hace falta desbloquear");
            } else if (Config.isAa(cfg.mode()) && TouchService.instance == null) {
                L.w("conexión automática: falta la accesibilidad (Android la desactiva al actualizar la app)");
            }
        }
        cfg.applyExtras(intent);
        LowLatency.apply(cfg);
        if (intent != null && ACTION_AA_SERVER_RESTART.equals(intent.getAction())) {
            if (!goForeground(Str.get(R.string.hql_fg_restart_aa))) return START_NOT_STICKY;
            if (cfg.aaServerManual()) {
                L.w("AA server reinicio: no con el arranque manual (sin accesibilidad no se pulsa nada)");
                if (!LinkState.running) stopSelf(startId);
                return START_NOT_STICKY;
            }
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
        boolean applyUsb = ACTION_APPLY.equals(intent.getAction()) && usbActive && usb != null && video != null
                && (usb.isBusy() || video.hasVideo());
        if (applyUsb) {
            // Por el cable no se cierra la sesión (no se sabe si el coche repite el saludo por el cable): se rehace el vídeo.
            boolean renegotiate = intent.getBooleanExtra(EXTRA_AA_RENEGOTIATE, false) && Config.isAa(cfg.mode());
            L.i("aplicando ajustes: rehago el vídeo sin cerrar la sesión por cable");
            video.stop("ajustes");
            usb.restartVideo(renegotiate ? 3000 : 1000, "ajustes");
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
            // Con el motor QDAuto, el nivel térmico baja fps y bitrate del vídeo vivo; con el original, solo se registra
            // (hasta que el cable USB traiga el vídeo del motor QDAuto: onUsbCar).
            VideoHub hub = video;
            thermal = new ThermalGuard(this, hub != null ? hub::setThermalLevel : null);
            thermal.start();
            setStatus(Str.get(R.string.hql_waiting_car));
            // El enlace va a usar el servidor de AA: un aviso viejo de «sigue encendido» ya no vale.
            AaServerStarter.cancelServerStillOn(this);
            String action = intent.getAction();
            LinkLifecycle.Trigger trigger = ACTION_BT_CAR.equals(action) ? LinkLifecycle.Trigger.BLUETOOTH
                    : usbAttach ? LinkLifecycle.Trigger.USB
                    : ACTION_APPLY.equals(action) ? LinkLifecycle.Trigger.USER : LinkLifecycle.Trigger.OTHER;
            startNeed = trigger == LinkLifecycle.Trigger.BLUETOOTH ? AaServerPolicy.Need.BLUETOOTH
                    : trigger == LinkLifecycle.Trigger.USB ? AaServerPolicy.Need.USB : AaServerPolicy.Need.CONNECT;
            apply(life.start(now(), trigger, env(), cfg.carWaitMs()));
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
        wifiLinkMode = cfg.linkMode();
        wifiEngine = cfg.linkEngine();
        // El sondeo del cable USB va siempre (HQL/USB); el enlace por cable, si se eligió o si el coche ya lo puso.
        usb = new UsbLink(this, cfg, new UsbCallbacks());
        usb.start();
        if (usbAtStart || Config.LINK_USB.equals(wifiLinkMode)) {
            startUsb(usbAtStart ? "accesorio del coche conectado" : "conexión elegida: cable USB");
        } else {
            startWifi();
        }
    }

    /** Wi-Fi (zona Wi-Fi o Wi-Fi Direct) con el motor configurado al arrancar; también al volver de un cable quitado. */
    private void startWifi() {
        String linkMode = wifiLinkMode;
        String engine = wifiEngine;
        usbActive = false;
        LinkState.setActiveTransport(linkMode, engine, false);
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
            // El vídeo (y Android Auto) sigue de una sesión por cable a la siguiente por Wi-Fi.
            if (video == null) video = new VideoHub(this, cfg);
            qd = new QdLinkHost(this, cfg, linkMode, video, new QdCallbacks());
            qd.start();
        } else {
            L.i("motor de protocolo: original de headqlink");
            udp = new UdpDiscovery(this);
            udp.start();
        }
    }

    /**
     * Cable USB con prioridad: se para el Wi-Fi (descubrimiento, intento y sesión) y se abre el accesorio del coche. El
     * enlace por cable usa siempre el motor QDAuto (el original no sabe de bloques de 512 B).
     */
    private void startUsb(String why) {
        boolean qdWasConnected = qd != null && qd.isConnected();
        boolean wifi = qd != null || udp != null || p2p != null || hotspot != null;
        stopWifi(why);
        usbActive = true;
        usbUsed = true;
        boolean override = !Config.LINK_USB.equals(wifiLinkMode);
        L.i("conexión: cable USB (" + why + ")" + (override ? "; el Wi-Fi (" + Ui.linkTitle(wifiLinkMode)
                + ") vuelve al quitar el cable" : "") + (wifi ? "; Wi-Fi en pausa mientras tanto" : ""));
        LinkState.setActiveTransport(Config.LINK_USB, Config.ENGINE_QDAUTO, override);
        if (video == null) {
            video = new VideoHub(this, cfg);
            if (thermal != null) thermal.setSink(video::setThermalLevel);
        }
        usb.activate(video, why);
        if (qdWasConnected) {
            // La sesión por Wi-Fi se ha cerrado con el motor (sin aviso): el vídeo sigue vivo para la del cable.
            setStatus(Str.get(R.string.hql_reconnecting));
            LinkState.setCar(LinkState.Car.RECONNECTING, "");
            apply(life.carLost(now(), env(), graceMs(), cfg.carWaitMs()));
        }
    }

    /** Para el Wi-Fi (el cable tiene prioridad). Hilo principal. */
    private void stopWifi(String why) {
        if (qd != null) {
            L.i("Wi-Fi en pausa (" + why + "): paro el motor QDAuto por Wi-Fi");
            qd.stop();
            qd = null;
        }
        synchronized (this) {
            if (udp != null) {
                udp.shutdown();
                udp = null;
            }
            if (session != null) {
                session.close();
                session = null;
            }
        }
        if (p2p != null) {
            p2p.stop();
            p2p = null;
        }
        if (hotspot != null) {
            hotspot.stop();
            hotspot = null;
        }
    }

    /** Hilo principal: accesorio del coche (actividad del accesorio o sondeo) con el servicio en marcha. */
    private void onUsbCar(android.hardware.usb.UsbAccessory acc, String why) {
        if (stopping || usb == null) return;
        if (!usbActive) {
            L.i("cable USB: " + why + ": tiene prioridad sobre el Wi-Fi");
            startUsb(why);
            // El coche está delante: Android Auto para él (como con su anuncio por Wi-Fi).
            apply(life.carSeen(now(), env(), cfg.carWaitMs()));
        }
        usb.offer(acc, why);
    }

    /** Avisos del enlace por cable (hilo principal): los del coche, como el motor QDAuto; y el cable que se va. */
    private final class UsbCallbacks extends QdCallbacks implements UsbLink.Callbacks {
        @Override
        public void onUsbCarPresent(String description) {
            onUsbCar(null, "accesorio del coche " + description + " presente");
        }

        @Override
        public void onUsbGone(String why) {
            if (stopping || !usbActive) return;
            if (Config.LINK_USB.equals(wifiLinkMode)) {
                L.i("cable USB fuera (" + why + "): espero a que se vuelva a enchufar");
                return;
            }
            L.i("cable USB fuera (" + why + "): vuelvo al Wi-Fi (" + Ui.linkTitle(wifiLinkMode) + ")");
            usb.deactivate(why);
            startWifi();
        }
    }

    /** Avisos del motor QDAuto (hilo principal). */
    private class QdCallbacks implements QdLinkHost.Callbacks {
        @Override
        public void onCarSeen(String name) {
            if (LinkState.car == LinkState.Car.CONNECTED || LinkState.car == LinkState.Car.SEEN) return;
            L.i("coche anunciado: " + name);
            setStatus(Str.get(R.string.hql_car_detected));
            LinkState.setCar(LinkState.Car.SEEN, name);
            apply(life.carSeen(now(), env(), cfg.carWaitMs()));
        }

        @Override
        public void onCarHeard() {
            // Otro anuncio sin sesión: la búsqueda o «Esperar al coche» vuelve a contar desde ahora.
            apply(life.carHeard(now(), cfg.carWaitMs()));
        }

        @Override
        public void onCarConnected(String detail) {
            // Si el tamaño del coche (CAR_INFO) ya llegó, se conserva.
            LinkState.setCar(LinkState.Car.CONNECTED, LinkState.car == LinkState.Car.CONNECTED ? LinkState.carDetail : "");
            setStatus(Str.get(R.string.hql_car_connected));
            apply(life.carConnected(now(), env()));
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
            apply(life.carLost(now(), env(), graceMs(), cfg.carWaitMs()));
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

        @Override
        public void onRefreshMulticast(String why) {
            refreshMulticastLock(why);
        }
    }

    /**
     * Hilo principal: suelta y vuelve a coger el MulticastLock (esperando al coche tras perder la sesión, o con el UDP
     * reabierto). Tras un corte de radio no se sabe si los broadcasts del coche siguen llegando al socket con la pantalla
     * apagada; coger el candado otra vez no cuesta nada.
     */
    private void refreshMulticastLock(String why) {
        if (stopping) return;
        try {
            if (mcLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                mcLock = wm.createMulticastLock("headqlink:mc");
            }
            while (mcLock.isHeld()) mcLock.release();
            mcLock.acquire();
            L.i("MulticastLock cogido otra vez (" + why + ")");
        } catch (RuntimeException e) {
            L.w("no se pudo volver a coger el MulticastLock (" + why + "): " + e.getMessage());
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
        if (usbActive) return usb != null && usb.isConnected();
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
        // Con el cable USB el Wi-Fi está parado: un anuncio que llega tarde no abre nada.
        if (udp == null || usbActive) return;
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
            lastHeardPostMs = now();
            main.post(() -> apply(life.carSeen(now(), env(), cfg.carWaitMs())));
        } else if (now() - lastHeardPostMs >= 2_000) {
            // Sigue anunciándose sin conectar: «sin coche» vuelve a contar (como mucho un aviso cada 2 s).
            lastHeardPostMs = now();
            main.post(() -> apply(life.carHeard(now(), cfg.carWaitMs())));
        }
        // Reenviamos el ACK en cada broadcast mientras el coche no se conecte.
        udp.sendAck(carIp, session.ackJson());
    }

    @Override
    public void onSessionConnected(SspSession s) {
        LinkState.setCar(LinkState.Car.CONNECTED, "");
        main.post(() -> apply(life.carConnected(now(), env())));
    }

    @Override
    public synchronized void onSessionEnded(SspSession s) {
        if (session == s) session = null;
        setStatus(Str.get(R.string.hql_session_ended));
        LinkState.setCar(LinkState.Car.SEARCHING, "");
        // El motor original se lleva el vídeo con la sesión: AA pasa a la pausa en el acto (sin vídeo vivo).
        main.post(() -> apply(life.carLost(now(), env(), graceMs(), cfg.carWaitMs())));
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
        life.close();
        main.removeCallbacks(lifeTimer);
        closeAa(userAction);
        stopSelf();
    }

    /** Cierra Android Auto por el camino de siempre (LinkLifecycle.shutdownPlan). Una vez por servicio. */
    private void closeAa(boolean userAction) {
        if (aaClosed) return;
        aaClosed = true;
        AaServerStarter.cancelStartOnUnlock(this, "el enlace se cierra");
        LinkLifecycle.ShutdownPlan plan = LinkLifecycle.shutdownPlan(env());
        switch (plan) {
            case PARK_UNTIL_UNLOCK:
                // Móvil bloqueado: el servidor (abierto en toda la red) no se puede apagar hasta
                // desbloquear. Nuestra head unit lo sigue ocupando, sin vídeo, hasta entonces.
                L.life("cierre" + (userAction ? " (a mano)" : "") + " con el móvil bloqueado: Android Auto aparcado"
                        + " hasta desbloquear, y entonces apago su servidor");
                AaServerStarter.requestStop(this);
                AaGuardService.park(this);
                return;
            case STOP_SERVER:
            case STOP_SERVER_IF_UNLOCKED:
                L.life("cierre" + (userAction ? " (a mano)" : "") + ": paro Android Auto y "
                        + (plan == LinkLifecycle.ShutdownPlan.STOP_SERVER ? "apago su servidor (ya o al desbloquear)"
                        : "su servidor solo si se puede ahora"));
                AaPark.release("cierre del enlace");
                try {
                    startService(new Intent(this, com.andrerinas.openheadunit.aap.AapService.class)
                            .setAction(com.andrerinas.openheadunit.aap.AapService.ACTION_STOP_SERVICE));
                } catch (RuntimeException e) {
                    L.e("no se pudo parar Android Auto", e);
                }
                if (plan == LinkLifecycle.ShutdownPlan.STOP_SERVER) {
                    AaServerStarter.requestStop(this);
                } else {
                    AaServerStarter.stopIfUnlocked(this);
                }
                return;
            case LEAVE_SERVER_ON:
                // Arranque manual: no se pulsa nada en los ajustes de AA. Se para nuestra head unit y, si el servidor
                // sigue encendido, un aviso dice cómo pararlo (recomendable en una Wi-Fi pública).
                L.life("cierre" + (userAction ? " (a mano)" : "") + ": paro Android Auto; su servidor queda encendido"
                        + " (arranque manual: HeadQLink no lo para)");
                AaPark.release("cierre del enlace");
                try {
                    startService(new Intent(this, com.andrerinas.openheadunit.aap.AapService.class)
                            .setAction(com.andrerinas.openheadunit.aap.AapService.ACTION_STOP_SERVICE));
                } catch (RuntimeException e) {
                    L.e("no se pudo parar Android Auto", e);
                }
                AaServerManual.onLinkClosed(this, userAction ? "Desconectar" : "fin del viaje");
                return;
            default:
                L.life("cierre" + (userAction ? " (a mano)" : "") + ": sin Android Auto que cerrar");
                // Modo sin Android Auto con el arranque manual: la espera del servidor (si la había) ya no hace falta.
                if (AaServerManual.isWaiting()) AaServerManual.stop(this, "el enlace se cierra");
        }
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
                .setColor(getColor(R.color.hql_accent))
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
        life.close();
        main.removeCallbacks(lifeTimer);
        // Cerrado sin pasar por shutdownAll (p. ej. sin poder pasar a primer plano) con AA en pausa: que no quede
        // aparcado y con su servidor encendido sin nadie que lo cierre.
        if (!aaClosed && AaPark.parked && !AaGuardService.active) closeAa(false);
        if (sysMonitor != null) sysMonitor.stop();
        if (usb != null) usb.stop();
        if (qd != null || usbUsed) {
            // Primero se cierra la sesión en curso y se espera su resumen (como mucho 500 ms), para que el del viaje
            // (qdauto §7.4) la incluya; los dos antes de cerrar el diario del coche.
            if (qd != null) qd.stop();
            if (qd != null && !qd.awaitStopped(500)) L.w("la sesión con el coche no terminó en 500 ms; el resumen del viaje puede no incluirla");
            if (usb != null && !usb.awaitStopped(500)) L.w("la sesión por cable no terminó en 500 ms; el resumen del viaje puede no incluirla");
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
