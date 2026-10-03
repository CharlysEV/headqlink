package com.c10link.link;

import android.content.Context;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Una sesión QDLink/SSPLink en modo WiFi: el móvil es servidor TCP y el coche se conecta.
 * Implementa el lado móvil del formato "5A5A" (ver docs/SSPLINK_PROTOCOL.md §4).
 */
final class SspSession {
    interface Listener {
        void onSessionEnded(SspSession s);

        void onStatus(String status);

        /** El coche ha abierto el TCP (sesión real, no solo anunciada). */
        default void onSessionConnected(SspSession s) {
        }
    }

    private static final long HEARTBEAT_MS = 3000;
    private static final long WATCHDOG_MS = 10_000;
    private static final long ACCEPT_TIMEOUT_MS = 20_000;
    /** Buffer de envío pequeño: el atasco se queda en nuestra cola (donde podemos descartar), no en el kernel. */
    private static final int SEND_BUFFER = 192 * 1024;
    private static final int BRAKE_SEND_BUFFER = 48 * 1024;
    /** Freno: el ack sale cuando la cola del kernel baja de esto (lo escrito ya salió por la radio). */
    private static final int BRAKE_OUTQ = 24 * 1024;
    private static final long BRAKE_MAX_WAIT_MS = 250;
    /**
     * Reenvío de AA: forzar un IDR cuesta un ciclo de foco que para la imagen ~0,7 s, así que no se
     * repite mientras haya uno en curso. No basta con que acabe de salir un keyframe: al conectar,
     * el coche descarta el primero y pide otro; ignorarlo dejó la pantalla en negro (2026-10-03 20:46).
     */
    private static final long KEY_DEBOUNCE_MS = 600;
    /**
     * "Último frame": se codifica uno nuevo solo con la cola del kernel por debajo de esto. Con
     * 24 KB se descartaba un tercio de los frames en cada ventana de radio y el arrastre iba a
     * tirones (prueba del 2026-10-03 20:49); con ~2 frames de margen solo se descarta en cortes de verdad.
     */
    private static final int GATE_OUTQ = 64 * 1024;
    /** Frames dibujados que puede tener el encoder a la vez (uno codificándose mientras sale otro). */
    private static final int GATE_MAX_PENDING = 3;
    /** Si el encoder no devuelve un frame dibujado en este tiempo, se deja de esperarlo. */
    private static final long GATE_PENDING_TIMEOUT_NS = 200_000_000L;
    /** Bitrate por defecto del modo "último frame" (si no hay ajuste manual). */
    private static final int REENCODE_DEFAULT_BPS = 8_000_000;
    /** Un frame que lleva más de esto esperando se considera atrasado: vaciamos y pedimos IDR. */
    private static final long MAX_LAG_NS = 150_000_000L;
    /** DSCP CS5 (0xA0) -> categoría WMM de vídeo (AC_VI). */
    private static final int TOS_VIDEO = 0xA0;

    private final Context ctx;
    private final Config cfg;
    private final Listener listener;
    private final ServerSocket server;
    private final int port;
    private final Object writeLock = new Object();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();

    private volatile Socket sock;
    private volatile OutputStream out;
    private volatile boolean closed;
    private volatile long lastRx;

    private JSONObject carInfo;
    private JSONObject videoArgs;
    private int videoW;
    private int videoH;

    private VideoEncoder encoder;
    private VideoSource source;
    /** Último modo día/noche informado por el coche (null = aún no informado). */
    private volatile Boolean carDark;
    private final Proto.VideoHeader vh = new Proto.VideoHeader();
    private volatile byte[] spsPps;
    private volatile boolean needConfig = true;

    // estadísticas de envío
    private long statStart;
    private int statFrames;
    private long statBytes;
    private long statMaxWriteMs;
    private Jitter encJitter;
    private Jitter sendJitter;

    /** Frame codificado esperando a salir por el socket. */
    private static final class Frame {
        final byte[] packet;
        final int dataLen;
        final boolean key;
        final long enqNs = System.nanoTime();
        /** Ack de AA retenido hasta que este frame salga hacia el coche (freno). Bajo el candado de la cola. */
        Runnable ack;
        long ackNs;
        boolean sent;

        Frame(byte[] packet, int dataLen, boolean key) {
            this.packet = packet;
            this.dataLen = dataLen;
            this.key = key;
        }
    }

    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private Thread sender;
    private boolean waitingIdr;
    private volatile boolean allowDrop = true;
    /** Prueba 2 de fluidez: los acks de vídeo de AA esperan a que el frame salga hacia el coche. */
    private volatile boolean aaBrake;
    private Frame lastEnqueued;
    /** Frame que el emisor está escribiendo (para soltar su ack si el socket se cierra a medias). */
    private volatile Frame inFlight;
    /** "Último frame": frames dibujados que el encoder aún no ha devuelto. */
    private final java.util.concurrent.atomic.AtomicInteger pendingEnc = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long pendingSinceNs;
    private volatile boolean writing;
    /** Solo desde el hilo GL (linkReady). */
    private NetStat gateNs;
    // Por qué se cerró la puerta (hilo GL; se vuelcan cada 5 s con las estadísticas de envío).
    private volatile int denyPending;
    private volatile int denyQueue;
    private volatile int denyKernel;
    private volatile long lastKeyAskMs;
    private int statAcksHeld;
    private long statMaxAckMs;
    private int statDropped;
    private int statFlushes;
    private long statMaxLagMs;

    SspSession(Context ctx, Config cfg, Listener listener) throws IOException {
        this.ctx = ctx;
        this.cfg = cfg;
        this.listener = listener;
        ServerSocket ss = null;
        Random r = new Random();
        int p = 0;
        for (int i = 0; i < 20 && ss == null; i++) {
            p = 10001 + r.nextInt(55535);
            try {
                ss = new ServerSocket(p);
            } catch (IOException ignored) {
            }
        }
        if (ss == null) throw new IOException("no hay puerto TCP libre");
        server = ss;
        port = p;
        server.setSoTimeout((int) ACCEPT_TIMEOUT_MS);
    }

    int port() {
        return port;
    }

    boolean isConnected() {
        return sock != null && !closed;
    }

    /** JSON del Broadcast_ACK que anuncia nuestro puerto. */
    String ackJson() {
        try {
            JSONObject feat = new JSONObject().put("PassistMobileNum", "");
            return new JSONObject()
                    .put("ControlPort", 0)
                    .put("MirrorPort", port)
                    .put("AudioPort", 0)
                    .put("OS", 0)
                    .put("DeviceName", cfg.deviceName())
                    .put("DeviceUUID", cfg.deviceUuid())
                    .put("DeviceFeature", feat)
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    void start() {
        new Thread(this::acceptAndRead, "ssp-session").start();
    }

    private void acceptAndRead() {
        try {
            L.i("TCP esperando al coche en puerto " + port);
            Socket s = server.accept();
            server.close();
            s.setTcpNoDelay(true);
            boolean brake = Config.isAa(cfg.mode()) && cfg.aaBrake();
            // Con el freno, la cola real tiene que ser la nuestra: si el kernel guarda 192 KB, los acks
            // saldrían ~80 ms antes de que el coche reciba nada.
            s.setSendBufferSize(brake ? BRAKE_SEND_BUFFER : SEND_BUFFER);
            try {
                s.setTrafficClass(TOS_VIDEO);
            } catch (IOException e) {
                L.w("no se pudo marcar DSCP de vídeo: " + e);
            }
            s.setReceiveBufferSize(6 * 1024 * 1024);
            s.setKeepAlive(true);
            sock = s;
            out = s.getOutputStream();
            lastRx = System.currentTimeMillis();
            L.i("TCP coche conectado desde " + s.getRemoteSocketAddress());
            CarTrace.beginSession(String.valueOf(s.getRemoteSocketAddress()));
            PerfTrace.beginSession();
            PerfTrace.event(ctx.getSystemService(android.os.PowerManager.class).isInteractive() ? "screen_on" : "screen_off", 1);
            startMonitor(s);
            listener.onSessionConnected(this);
            listener.onStatus("coche conectado " + s.getInetAddress().getHostAddress());
            byte[] hello = Proto.appStatus(Build.VERSION.SDK_INT);
            write(hello);
            L.i("-> !BIN AppStatus " + L.hex(hello, 0, 96));
            CarTrace.tx("!BIN AppStatus", L.hex(hello, 0, 96));
            timers.scheduleWithFixedDelay(this::heartbeat, 1000, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
            readLoop(new DataInputStream(s.getInputStream()));
        } catch (SocketTimeoutException e) {
            L.w("TCP: el coche no se conectó en " + ACCEPT_TIMEOUT_MS / 1000 + " s");
        } catch (IOException e) {
            if (!closed) L.e("TCP sesión terminada", e);
        } finally {
            close();
        }
    }

    private void readLoop(DataInputStream in) throws IOException {
        byte[] hdr = new byte[Proto.HEADER_SIZE];
        int dumped = 0;
        while (!closed) {
            in.readFully(hdr);
            lastRx = System.currentTimeMillis();
            Proto.Header h = Proto.Header.parse(hdr);
            if (!Proto.FMT_NEW.equals(h.magic)) {
                // Formato antiguo !BIN u otro: volcamos para analizar y seguimos en bloques de 512.
                byte[] rest = new byte[512 - Proto.HEADER_SIZE];
                in.readFully(rest);
                if (dumped++ < 20) L.w("paquete no-5A5A: " + L.hex(hdr, 0, 16) + " | " + L.hex(rest, 0, 112));
                byte[] whole = new byte[512];
                System.arraycopy(hdr, 0, whole, 0, Proto.HEADER_SIZE);
                System.arraycopy(rest, 0, whole, Proto.HEADER_SIZE, rest.length);
                CarTrace.raw(h.magic, whole);
                continue;
            }
            int len = h.totalSize - Proto.HEADER_SIZE;
            if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("totalSize inválido: " + h);
            byte[] payload = new byte[len];
            in.readFully(payload);
            PerfTrace.rx(h.msgType, len);
            int off = Math.min(h.extSize, len);
            CarTrace.packet(h, payload, off, len - off);
            try {
                dispatch(h, payload, off, len - off);
            } catch (Exception e) {
                L.e("error procesando " + h, e);
            }
        }
    }

    private void dispatch(Proto.Header h, byte[] p, int off, int len) throws JSONException, IOException {
        switch (h.msgType) {
            case Proto.MSG_CMD:
                if (h.payloadFormat == Proto.PAYLOAD_JSON) onCommand(new String(p, off, len, StandardCharsets.UTF_8));
                else L.w("CMD binario: " + h + " " + L.hex(p, off, 64));
                break;
            case Proto.MSG_TOUCH:
                onTouch(p, off, len);
                break;
            case Proto.MSG_APP:
                onAppMessage(new String(p, off, len, StandardCharsets.UTF_8));
                break;
            case Proto.MSG_SPEECH:
                L.i("<- SPEECH " + len + " bytes");
                break;
            default:
                L.i("<- " + h + " " + L.hex(p, off, Math.min(64, len)));
        }
    }

    private void onCommand(String json) throws JSONException, IOException {
        JSONObject o = new JSONObject(json);
        String cmd = o.optString("CMD");
        JSONObject para = o.optJSONObject("PARA");
        L.i("<- " + json);
        switch (cmd) {
            case "CAR_INFO":
                carInfo = para;
                sendPhoneInfo();
                sendCmd("UPDATE_NOTIFY", new JSONObject().put("UpdateStatus", 5));
                listener.onStatus("CAR_INFO " + (para != null ? para.optString("CarType") + " " + para.optInt("CarWidth") + "x" + para.optInt("CarHeight") : ""));
                if (para != null) LinkState.setCar(LinkState.Car.CONNECTED, para.optInt("CarWidth") + "×" + para.optInt("CarHeight"));
                break;
            case "VIDEO_ARGS":
                videoArgs = para;
                sendCmd("SPEECH_ARGS", new JSONObject()
                        .put("EncodingType", 1).put("SampleRate", 16000)
                        .put("ChannelConfig", 1).put("AudioFormat", 16));
                break;
            case "VIDEO_SUP_REQ":
                sendCmd("VIDEO_SUP_RSP", new JSONObject().put("VideoFormat", 3).put("VideoSupport", 1));
                break;
            case "VIDEO_CTRL":
                int play = para != null ? para.optInt("PlayStatus") : 0;
                if (play == 1) startVideo();
                else L.i("VIDEO_CTRL PlayStatus=" + play + " (se mantiene el vídeo)");
                break;
            case "KEY_FRAME_REQ":
                PerfTrace.event("idr_req", 0);
                needConfig = true;
                askKeyFrame("el coche lo pide");
                break;
            case "LAND_MODE_REQ":
                int ori = para != null ? para.optInt("Orientation") : 0;
                sendCmd("LAND_MODE_RSP", new JSONObject().put("Orientation", ori).put("Authority", 1).put("StatusArg", 0));
                break;
            case "DISCONNECT_REQ":
                sendCmd("DISCONNECT_RSP", new JSONObject().put("CanDisconnect", 1));
                timers.schedule(this::close, 300, TimeUnit.MILLISECONDS);
                break;
            default:
                break;
        }
    }

    /**
     * Cada 50 ms: estado del socket (cola del kernel, RTT, retransmisiones) y detector de
     * congelación: si entre dos vueltas pasan más de 150 ms, el proceso no estuvo corriendo.
     */
    private void startMonitor(Socket s) {
        Thread t = new Thread(() -> {
            NetStat ns = NetStat.open(s);
            if (ns == null) L.w("traza: sin estado del socket (librería c10net no disponible)");
            long last = android.os.SystemClock.elapsedRealtime();
            try {
                while (!closed) {
                    Thread.sleep(50);
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - last > 150) PerfTrace.event("stall", now - last);
                    last = now;
                    if (ns != null && ns.sample()) PerfTrace.net(ns.v);
                }
            } catch (InterruptedException ignored) {
            } finally {
                if (ns != null) {
                    try {
                        ns.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }, "net-monitor");
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    /** Mensajes de app del coche: {"AppID":…,"FunctionID":…,"Para":{…}}. */
    private void onAppMessage(String json) {
        L.i("<- APP " + json);
        try {
            JSONObject o = new JSONObject(json);
            String app = o.optString("AppID");
            String fn = o.optString("FunctionID");
            JSONObject para = o.optJSONObject("Para");
            if ("Global".equals(app) && "DarkModeOn".equals(fn) && para != null) {
                // Valor sin confirmar en el coche: se asume 1 = noche.
                boolean dark = para.optInt("DarkModeOn") == 1;
                carDark = dark;
                L.i("coche en modo " + (dark ? "noche" : "día"));
                if (source != null) source.onCarDarkMode(dark);
            }
        } catch (JSONException e) {
            L.w("APP JSON inválido: " + e.getMessage());
        }
    }

    private void sendPhoneInfo() throws JSONException, IOException {
        int cw = carInfo != null ? carInfo.optInt("CarWidth") : 0;
        int ch = carInfo != null ? carInfo.optInt("CarHeight") : 0;
        if (cw <= 0 || ch <= 0) {
            cw = 800;
            ch = 480;
        }
        int[] dims = cfg.videoSize(cw, ch, 0, 0);
        int w = dims[0];
        int hgt = dims[1];
        JSONObject para = new JSONObject()
                .put("PhoneWidth", w).put("PhoneHeight", hgt)
                .put("MirrorWidth", w).put("MirrorHeight", hgt)
                .put("PhoneWidthInApp", w).put("PhoneHeightInApp", hgt)
                .put("MirrorWidthInApp", w).put("MirrorHeightInApp", hgt)
                .put("PhoneFeature", new JSONObject().put("PassistMobileNum", ""))
                .put("PhoneUUID", cfg.deviceUuid())
                .put("PhoneName", cfg.deviceName())
                .put("Version", Config.QDLINK_VERSION)
                .put("PhoneBrand", Build.MANUFACTURER)
                .put("PhoneModel", Build.MODEL)
                .put("Platform", 0)
                .put("PlatformVersion", String.valueOf(Build.VERSION.SDK_INT))
                .put("PhoneSystemTime", 0)
                .put("MirrorTypeSupport", carInfo != null ? carInfo.optInt("MirrorTypeReq") : 0);
        sendCmd("PHONE_INFO", para);
    }

    private void sendCmd(String cmd, JSONObject para) throws JSONException, IOException {
        JSONObject o = new JSONObject().put("CMD", cmd);
        if (para != null) o.put("PARA", para);
        String json = o.toString();
        L.i("-> " + json);
        CarTrace.tx("CMD", json);
        write(Proto.jsonPacket(Proto.MSG_CMD, json));
    }

    private void heartbeat() {
        try {
            write(Proto.jsonPacket(Proto.MSG_CMD, "{\"CMD\":\"HEARTBEAT\"}"));
            CarTrace.tx("CMD", "{\"CMD\":\"HEARTBEAT\"}");
        } catch (IOException e) {
            L.e("heartbeat", e);
            close();
            return;
        }
        long idle = System.currentTimeMillis() - lastRx;
        if (idle > WATCHDOG_MS) {
            L.w("watchdog: " + idle + " ms sin datos del coche");
            close();
        }
    }

    private void write(byte[] b) throws IOException {
        OutputStream o = out;
        if (o == null || closed) throw new IOException("socket cerrado");
        synchronized (writeLock) {
            o.write(b);
            o.flush();
        }
    }

    // ---------------------------------------------------------------- vídeo

    /**
     * Fuentes que ya entregan H.264 (Android Auto): sin encoder propio y sin descartar frames,
     * porque no podemos pedir un IDR inmediato. Devuelve true si se ha usado este camino.
     */
    private boolean startPassthroughIfAny(int carW, int carH) {
        source = VideoSource.create(ctx, cfg);
        source.setCarSize(carW, carH);
        int carFrameRate = videoArgs != null ? videoArgs.optInt("FrameRate") : 0;
        source.setTargetFps(source instanceof AaPassthroughSource ? cfg.aaFps(carFrameRate) : cfg.fps(carFrameRate));
        if (carDark != null) source.onCarDarkMode(carDark);
        if (source.usesEncoder()) return false;
        int[] size = source.passthroughSize();
        vh.width = size != null ? size[0] : videoW;
        vh.height = size != null ? size[1] : videoH;
        vh.encodingType = videoArgs != null ? videoArgs.optInt("EncodingType") : 0;
        vh.frameRate = videoArgs != null ? videoArgs.optInt("FrameRate") : 30;
        vh.bitRate = videoArgs != null ? videoArgs.optInt("BitRate") : 0;
        vh.frameInterval = videoArgs != null ? videoArgs.optInt("FrameInterval") : 3;
        allowDrop = false;
        aaBrake = source instanceof AaPassthroughSource && cfg.aaBrake();
        if (aaBrake) {
            com.andrerinas.openheadunit.decoder.video.VideoTap.setAckGate(this::holdAck);
            L.i("VIDEO freno a AA activo: ventana pedida " + cfg.aaWindow() + ", anunciada "
                    + com.andrerinas.openheadunit.decoder.video.VideoTap.getAnnouncedWindow()
                    + " (si AA ya estaba conectado, la ventana nueva se aplica al reconectar AA)");
        }
        encJitter = new Jitter("origen", Math.max(1, vh.frameRate));
        sendJitter = new Jitter("socket", Math.max(1, vh.frameRate));
        L.i("VIDEO reenvío directo desde " + source.getClass().getSimpleName() + ", cabecera " + vh.width + "x" + vh.height);
        source.startPassthrough((data, off, len) -> {
            encJitter.tick();
            // El tamaño lo decide la negociación con el origen (AA), que llega después de arrancar.
            int[] sz = source.passthroughSize();
            if (sz != null && (sz[0] != vh.width || sz[1] != vh.height)) {
                L.i("VIDEO cabecera " + vh.width + "x" + vh.height + " -> " + sz[0] + "x" + sz[1]);
                vh.width = sz[0];
                vh.height = sz[1];
            }
            enqueueFrame(data, off, len, isKeyFrame(data, off, len));
        });
        statStart = System.currentTimeMillis();
        sender = new Thread(this::senderLoop, "video-sender");
        sender.start();
        listener.onStatus("vídeo: reenvío directo " + vh.width + "x" + vh.height);
        return true;
    }

    /**
     * "Último frame" (hilo GL): ¿cabe un frame nuevo? Como mucho GATE_MAX_PENDING-1 en el encoder,
     * como mucho uno en nuestra cola y la cola del kernel por debajo de GATE_OUTQ.
     */
    private boolean linkReady() {
        if (closed) return false;
        if (pendingEnc.get() >= GATE_MAX_PENDING) {
            if (System.nanoTime() - pendingSinceNs < GATE_PENDING_TIMEOUT_NS) {
                denyPending++;
                return false;
            }
            pendingEnc.set(0);
        }
        synchronized (queue) {
            if (queue.size() > 1) {
                denyQueue++;
                return false;
            }
        }
        if (gateNs == null && sock != null) gateNs = NetStat.open(sock);
        NetStat ns = gateNs;
        if (ns != null && ns.sample() && ns.v[0] >= GATE_OUTQ) {
            denyKernel++;
            return false;
        }
        return true;
    }

    /** Pide un IDR al encoder o al origen; en reenvío, sin repetirlo dentro de KEY_DEBOUNCE_MS. */
    private void askKeyFrame(String why) {
        if (encoder != null) {
            encoder.requestKeyFrame();
            return;
        }
        if (source == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        long sinceAsk = now - lastKeyAskMs;
        if (sinceAsk < KEY_DEBOUNCE_MS) {
            L.i("IDR (" + why + ") omitido: ya hay uno pedido hace " + sinceAsk + " ms");
            PerfTrace.event("idr_skip", sinceAsk);
            return;
        }
        lastKeyAskMs = now;
        source.requestKeyFrame();
    }

    /** IDR (5) o SPS (7) en algún NAL de la unidad Annex-B. */
    private static boolean isKeyFrame(byte[] d, int off, int len) {
        int end = off + len - 3;
        for (int i = off; i < end; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                int t = d[i + 3] & 0x1f;
                if (t == 5 || t == 7) return true;
            }
        }
        return false;
    }

    private synchronized void startVideo() {
        if (encoder != null || (source != null && !source.usesEncoder())) {
            L.i("vídeo ya activo; pido IDR");
            needConfig = true;
            askKeyFrame("vídeo ya activo");
            return;
        }
        int cw = carInfo != null ? carInfo.optInt("CarWidth") : 0;
        int ch = carInfo != null ? carInfo.optInt("CarHeight") : 0;
        int aw = videoArgs != null ? videoArgs.optInt("Width") : 0;
        int ah = videoArgs != null ? videoArgs.optInt("Height") : 0;
        int[] dims = cfg.videoSize(cw, ch, aw, ah);
        videoW = dims[0];
        videoH = dims[1];

        if (startPassthroughIfAny(cw, ch)) return;

        int carFps = videoArgs != null ? videoArgs.optInt("FrameRate") : 0;
        int carBitrate = videoArgs != null ? videoArgs.optInt("BitRate") : 0;
        int carInterval = videoArgs != null ? videoArgs.optInt("FrameInterval") : 0;

        VideoEncoder.Params vp = new VideoEncoder.Params();
        vp.width = videoW;
        vp.height = videoH;
        vp.fps = source instanceof AaPassthroughSource ? cfg.aaFps(carFps) : cfg.fps(carFps);
        vp.bitrate = cfg.bitrate(carBitrate);
        vp.profile = cfg.profile();
        vp.prependSpsPps = cfg.prependSpsPps();
        boolean latestFrame = source instanceof AaPassthroughSource;
        if (latestFrame) {
            // Sin IDR periódicos: el coche pide uno cuando lo necesita y sale al instante. VBR con tope:
            // con CBR el encoder rellenaba hasta 8 Mbps con la pantalla quieta (radio siempre cargada).
            vp.cbr = false;
            vp.intraRefreshFrames = vp.fps;
            vp.iFrameIntervalSec = 30;
            vp.repeatAfterUs = 100_000;
            if (cfg.getInt(Config.KBPS) <= 0) vp.bitrate = REENCODE_DEFAULT_BPS;
            source.setLinkGate(new GlFrameRelay.Gate() {
                @Override
                public boolean ready() {
                    return linkReady();
                }

                @Override
                public void submitted() {
                    pendingSinceNs = System.nanoTime();
                    pendingEnc.incrementAndGet();
                }
            });
        }

        vh.width = videoW;
        vh.height = videoH;
        vh.encodingType = videoArgs != null ? videoArgs.optInt("EncodingType") : 0;
        vh.frameRate = carFps > 0 ? carFps : vp.fps;
        vh.bitRate = carBitrate > 0 ? carBitrate : vp.bitrate;
        vh.frameInterval = carInterval > 0 ? carInterval : 4;

        L.i("VIDEO start: coche pide " + aw + "x" + ah + "@" + carFps + " " + carBitrate + "bps intervalo " + carInterval
                + " | usamos " + vp);
        encJitter = new Jitter("encoder", vp.fps);
        sendJitter = new Jitter("socket", vp.fps);
        try {
            encoder = new VideoEncoder(vp, new VideoEncoder.Sink() {
                @Override
                public void onCodecConfig(byte[] csd) {
                    spsPps = csd;
                    needConfig = true;
                    L.i("SPS/PPS " + csd.length + " bytes: " + L.hex(csd, 0, Math.min(48, csd.length)));
                }

                @Override
                public void onFrame(byte[] data, int len, boolean key, long ptsUs) {
                    encJitter.tick();
                    pendingEnc.getAndUpdate(v -> v > 0 ? v - 1 : 0);
                    enqueueFrame(data, 0, len, key);
                }
            });
            L.i("fuente de vídeo: " + cfg.mode() + " -> " + source.getClass().getSimpleName());
            source.start(encoder.start(), videoW, videoH, vp.fps, vp.toString());
            statStart = System.currentTimeMillis();
            sender = new Thread(this::senderLoop, "video-sender");
            sender.start();
            listener.onStatus("vídeo " + vp);
        } catch (IOException e) {
            L.e("no se pudo arrancar el encoder", e);
        }
    }

    /** Hilo del encoder: empaqueta y encola. Tras un vaciado, descarta hasta el siguiente IDR. */
    private void enqueueFrame(byte[] data, int off, int len, boolean key) {
        Frame f = new Frame(Proto.videoPacket(vh, data, off, len), len, key);
        synchronized (queue) {
            if (waitingIdr) {
                if (!key) {
                    statDropped++;
                    lastEnqueued = null;
                    return;
                }
                waitingIdr = false;
                needConfig = true;
            }
            queue.addLast(f);
            lastEnqueued = f;
            queue.notifyAll();
        }
    }

    /**
     * Puerta del freno (hilo de vídeo de AA, justo después de entregarnos el frame): retiene el ack
     * en el último frame encolado. false = confirmar ya (frame ya enviado, descartado o sesión cerrada).
     */
    private boolean holdAck(Runnable ack) {
        synchronized (queue) {
            Frame f = lastEnqueued;
            if (closed || !aaBrake || f == null || f.sent || f.ack != null) return false;
            f.ack = ack;
            f.ackNs = System.nanoTime();
            return true;
        }
    }

    /** Suelta el ack retenido en f (si lo hay). Llamar fuera del candado de la cola. */
    private void releaseAck(Frame f, boolean logIt) {
        Runnable r;
        long held;
        synchronized (queue) {
            f.sent = true;
            r = f.ack;
            f.ack = null;
            held = (System.nanoTime() - f.ackNs) / 1_000_000;
        }
        if (r == null) return;
        try {
            r.run();
        } catch (RuntimeException e) {
            L.w("ack a AA: " + e);
        }
        if (logIt) {
            statAcksHeld++;
            statMaxAckMs = Math.max(statMaxAckMs, held);
            PerfTrace.event("aa_ack", held);
        }
    }

    /** Suelta todos los acks retenidos (vaciado o cierre): si no, AA dejaría de enviar vídeo. */
    private void releaseAllAcks(java.util.List<Frame> frames) {
        for (Frame f : frames) releaseAck(f, false);
    }

    /** Hilo emisor: envía en orden; si un frame llega atrasado, vacía la cola y pide un IDR. */
    private void senderLoop() {
        NetStat brakeNs = aaBrake ? NetStat.open(sock) : null;
        if (aaBrake && brakeNs == null) L.w("freno: sin estado del socket, el ack sale al escribir");
        try {
            while (!closed) {
                Frame f;
                synchronized (queue) {
                    while (queue.isEmpty() && !closed) queue.wait(500);
                    if (closed) break;
                    f = queue.pollFirst();
                }
                inFlight = f;
                long lagNs = System.nanoTime() - f.enqNs;
                statMaxLagMs = Math.max(statMaxLagMs, lagNs / 1_000_000);
                if (allowDrop && lagNs > MAX_LAG_NS && !f.key) {
                    int n;
                    java.util.List<Frame> dropped;
                    synchronized (queue) {
                        n = queue.size() + 1;
                        dropped = new java.util.ArrayList<>(queue);
                        dropped.add(f);
                        queue.clear();
                        waitingIdr = true;
                    }
                    releaseAllAcks(dropped);
                    statDropped += n;
                    statFlushes++;
                    PerfTrace.event("flush", n);
                    if (encoder != null) encoder.requestKeyFrame();
                    continue;
                }
                byte[] csd = spsPps;
                if (needConfig && csd != null) {
                    write(Proto.videoPacket(vh, csd, 0, csd.length));
                    needConfig = false;
                }
                long t0 = System.nanoTime();
                writing = true;
                try {
                    write(f.packet);
                } finally {
                    writing = false;
                }
                if (brakeNs != null) waitKernelDrain(brakeNs);
                releaseAck(f, true);
                sendJitter.tick();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                int queued;
                synchronized (queue) {
                    queued = queue.size();
                }
                PerfTrace.frame(f.dataLen, f.key, lagNs / 1_000_000, ms, queued);
                statFrames++;
                statBytes += f.dataLen;
                statMaxWriteMs = Math.max(statMaxWriteMs, ms);
                maybeLogStats();
            }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
            if (!closed) L.e("envío de vídeo", e);
            close();
        } finally {
            if (brakeNs != null) {
                try {
                    brakeNs.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Freno: espera (como mucho BRAKE_MAX_WAIT_MS) a que la cola del kernel baje de BRAKE_OUTQ. */
    private void waitKernelDrain(NetStat ns) throws InterruptedException {
        long deadline = System.nanoTime() + BRAKE_MAX_WAIT_MS * 1_000_000L;
        while (!closed && ns.sample() && ns.v[0] > BRAKE_OUTQ && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
    }

    private void maybeLogStats() {
        long now = System.currentTimeMillis();
        if (now - statStart < 5000) return;
        float secs = (now - statStart) / 1000f;
        String s = String.format(java.util.Locale.US,
                "TX %.1f fps  %.0f kbps  max write %d ms  max lag %d ms  descartados %d (vaciados %d)",
                statFrames / secs, statBytes * 8 / secs / 1000, statMaxWriteMs, statMaxLagMs, statDropped, statFlushes);
        L.i(s);
        CarTrace.note("VIDEO", s + " · cabecera " + vh.width + "x" + vh.height);
        String rj = source != null ? source.takeJitterSummary() : null;
        if (rj != null) L.i("  " + rj);
        if (aaBrake) L.i(String.format(java.util.Locale.US, "  freno AA: %d acks retenidos, máx %d ms", statAcksHeld, statMaxAckMs));
        if (denyPending + denyQueue + denyKernel > 0) {
            L.i(String.format(java.util.Locale.US, "  puerta cerrada: encoder lleno %d, cola %d, kernel %d", denyPending, denyQueue, denyKernel));
            denyPending = 0;
            denyQueue = 0;
            denyKernel = 0;
        }
        L.i("  " + encJitter.takeSummary());
        L.i("  " + sendJitter.takeSummary());
        if (source != null) source.setStatus(s);
        listener.onStatus(s);
        LinkState.setVideo(String.format(java.util.Locale.getDefault(), "%.0f fps · %.1f Mbps",
                statFrames / secs, statBytes * 8 / secs / 1_000_000));
        statStart = now;
        statFrames = 0;
        statBytes = 0;
        statMaxWriteMs = 0;
        statMaxLagMs = 0;
        statDropped = 0;
        statFlushes = 0;
        statAcksHeld = 0;
        statMaxAckMs = 0;
    }

    private void onTouch(byte[] p, int off, int len) {
        Proto.Finger[] fingers = Proto.parseTouch(p, off, len);
        StringBuilder sb = new StringBuilder("<- TOUCH");
        for (Proto.Finger f : fingers) {
            if (f == null) continue;
            sb.append(String.format(java.util.Locale.US, " [id%d a%d %.1f,%.1f]", f.id, f.action, f.x, f.y));
            if (f.id == 0) PerfTrace.touch("touch", f.action, f.x, f.y);
            if (source != null && f.id == 0) source.touch(f.action, f.x, f.y);
        }
        L.i(sb + "  raw=" + L.hex(p, off, Math.min(len, 32)));
    }

    // ---------------------------------------------------------------- cierre

    void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        L.i("cerrando sesión");
        CarTrace.endSession();
        PerfTrace.endSession();
        timers.shutdownNow();
        java.util.List<Frame> pending;
        synchronized (queue) {
            pending = new java.util.ArrayList<>(queue);
            if (lastEnqueued != null) pending.add(lastEnqueued);
            Frame fl = inFlight;
            if (fl != null) pending.add(fl);
            queue.clear();
            queue.notifyAll();
        }
        if (aaBrake) {
            com.andrerinas.openheadunit.decoder.video.VideoTap.setAckGate(null);
            releaseAllAcks(pending);
        }
        NetStat g = gateNs;
        if (g != null) {
            try {
                g.close();
            } catch (IOException ignored) {
            }
        }
        if (source != null) source.stop();
        if (encoder != null) encoder.stop();
        try {
            server.close();
        } catch (IOException ignored) {
        }
        Socket s = sock;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        listener.onSessionEnded(this);
    }
}
