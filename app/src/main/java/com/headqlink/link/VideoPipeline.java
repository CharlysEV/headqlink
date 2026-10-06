package com.headqlink.link;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import com.andrerinas.openheadunit.decoder.video.VideoTap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.qdauto.core.h264.AnnexB;
import dev.qdauto.core.session.KeyframeReason;
import dev.qdauto.core.wire.VideoMessage;

/**
 * La mitad de vídeo de SspSession, sin socket, para el motor QDAuto (qdauto §4.7): la fuente (Android Auto, patrón o
 * app), el encoder, el relay GL, la interfaz propia del coche y el freno a AA. Vive entre sesiones con el coche: se
 * engancha a una SessionPort con attach y se desengancha con detach sin destruirse, así que la sesión siguiente
 * reutiliza Android Auto, el decodificador, el encoder y CarUi (radio, viaje, ruta) tal cual.
 *
 * Hilos: crear, enganchar, desenganchar, pedir IDR, ABR y parar, en hql-video (VideoHub). El camino caliente (sink de
 * AA, sink del encoder, puerta GL) solo lee la referencia volatile active: sin sesión, el frame se tira y se cuenta, y
 * el ack de AA sale en el acto. Los tamaños y parámetros son exactamente los de SspSession.startVideo.
 *
 * Tamaño de los IDR (el C10 se cuelga con mensajes de más de ~512 KiB; el núcleo descarta los que pasan de
 * SessionConfigs.MAX_VIDEO_MESSAGE_BYTES): con encoder propio, IdrSizeController (QP-I adaptable y, si no basta, bitrate
 * bajado para el IDR pedido) e IdrRequestGate (antirrebote de las peticiones). En el reenvío directo no se controla el
 * encoder de AA: un IDR grande se descarta y se pide otro (KeyframePolicy); si se repite, aviso y peticiones espaciadas.
 */
final class VideoPipeline {
    /**
     * "Último frame": se codifica uno nuevo solo con la cola del kernel por debajo de esto (SspSession.GATE_OUTQ tenía
     * 64 KB: a 5 Mbit/s son ~100 ms de retardo en el kernel antes de contar nada; con 32 KB, la mitad). Configurable con
     * gate_outq_kb (Config.GATE_OUTQ_KB).
     */
    static final int GATE_OUTQ = 32 * 1024;
    private static final int GATE_MAX_PENDING = 3;
    private static final long GATE_PENDING_TIMEOUT_NS = 200_000_000L;
    static final long ENCODER_HEALTH_NS = 2_000_000_000L;
    /** Payload máximo de un frame que deja pasar el núcleo (su tope menos las cabeceras de 48 B). */
    static final int PAYLOAD_CAP = SessionConfigs.MAX_VIDEO_MESSAGE_BYTES - VideoMessage.HEADER_SIZE;
    /** Reenvío directo: IDR de AA descartados seguidos antes de avisar y espaciar las peticiones. */
    static final int AA_OVERSIZE_WARN_AFTER = 3;
    /** Reenvío directo, tras AA_OVERSIZE_WARN_AFTER descartes seguidos: como mucho un ciclo de foco cada tanto. */
    static final long AA_OVERSIZE_BACKOFF_MS = 5_000;
    private static final long AA_OVERSIZE_WARN_EVERY_MS = 60_000;
    /** IDR sin pedir y sin nada que contar (los periódicos del patrón y de la app): como mucho una línea cada tanto. */
    private static final long QUIET_IDR_LOG_MS = 10_000;

    final VideoPlan plan;
    private final Context ctx;
    private final Config cfg;
    private final Handler h;

    private VideoSource source;
    private VideoEncoder encoder;
    /**
     * csd y active se escriben juntos con csdLock (attach y SPS/PPS nuevo): o attach ve el SPS/PPS y lo manda, o el
     * SPS/PPS ve la sesión enganchada y se le manda. Sin el candado, uno que saliera entre las dos cosas se perdía.
     */
    private final Object csdLock = new Object();
    private volatile byte[] csd;
    private volatile SessionPort active;
    private AaAckBrake brake;
    private KeyframePolicy policy;
    private Jitter srcJitter;
    private Jitter encJitter;
    private VideoEncoder.Params vp;

    // Puerta «último frame» (hilo GL) y ABR.
    private final AtomicInteger pendingEnc = new AtomicInteger();
    private volatile long pendingSinceNs;
    private volatile int denyPending;
    private volatile int denyQueue;
    private volatile int denyKernel;
    private volatile int denyNoSession;
    private volatile int abrDenies;
    private int abrBps;
    private int abrInitial;
    private int abrMin;
    private int abrMax;
    private int abrCalm;
    /** Puerta: cola del kernel máxima (bytes) para codificar otro frame. */
    private int gateOutq = GATE_OUTQ;
    /** Esperas de la puerta por el enlace, para el controlador del enlace (se lee la diferencia cada 100 ms). */
    private volatile int linkWaits;
    private int linkWaitsSeen;
    /**
     * Bitrate según el enlace (LinkRateController, hilo hql-video) para los perfiles de bitrate fijo con relay GL;
     * null sin él (ABR clásico de Muy alto/Alto, patrón, app, reenvío directo o link_fixed).
     */
    private volatile LinkRateController link;
    /** Tope de fps del enlace (los de la sesión si no actúa). */
    private int linkFpsCap;
    /**
     * Adaptación térmica (ThermalPolicy, hilo hql-video): nivel, protección elegida, tope de bitrate (0 = sin tope) y
     * fps máximos. El tope de fps que se aplica es el menor del térmico y del enlace.
     */
    private int thermalLevel = ThermalPolicy.NORMAL;
    private String thermalMode = ThermalPolicy.MODE_NORMAL;
    private int thermalMaxBps;
    private int thermalFpsCap;
    private volatile int fpsCap;
    private final Runnable abrTick = new Runnable() {
        @Override
        public void run() {
            adaptBitrate();
            h.postDelayed(this, 1000);
        }
    };

    // Salud, métricas y estado del reenvío.
    private volatile long lastEncoderFrameNs;
    /** "Último frame": cuándo se dibujó el último frame para el encoder (puerta GL); 0 = ninguno. */
    private volatile long lastSubmitNs;
    /** La entrada del encoder pasa por la puerta GL: sin sesión no se le da ningún frame. */
    private boolean gated;
    private boolean warnedKeyMismatch;
    private volatile int headerW;
    private volatile int headerH;
    private int attaches;
    private volatile long attachedAtNs;
    private volatile boolean idrAfterAttach;
    private volatile long firstIdrAfterAttachMs = -1;
    private final AtomicLong droppedNoSession = new AtomicLong();
    private boolean stopped;

    // Tamaño de los IDR con encoder propio.
    private volatile IdrSizeController idrCtl;
    private final IdrRequestGate idrGate = new IdrRequestGate();
    /** Se pidió un IDR que aún no ha salido (para el log: pedido o periódico). */
    private volatile boolean idrRequested;
    private volatile String idrRequestWhy = "";
    /** IDR periódicos sin nada que contar desde la última línea, y cuándo fue (hilo enc-drain). */
    private int quietIdrs;
    private long lastQuietIdrLogMs;
    private final Runnable deferredIdr = () -> {
        if (!stopped && idrGate.onDue(SystemClock.elapsedRealtime())) fireEncoderIdr("aplazada por el antirrebote");
    };

    // Reenvío directo: IDR de AA descartados por grandes.
    private final AtomicInteger aaOversizeStreak = new AtomicInteger();
    private int aaOversizeTotal;
    private long lastAaCycleMs = Long.MIN_VALUE / 4;
    private long lastAaOversizeWarnMs = Long.MIN_VALUE / 4;

    private VideoPipeline(Context ctx, Config cfg, VideoPlan plan, Handler h) {
        this.ctx = ctx.getApplicationContext();
        this.cfg = cfg;
        this.plan = plan;
        this.h = h;
    }

    /** Hilo hql-video: crea la fuente y, según el modo, el reenvío directo o el encoder. */
    static VideoPipeline create(Context ctx, Config cfg, VideoPlan plan, Boolean carDark, Handler h) throws IOException {
        VideoPipeline p = new VideoPipeline(ctx, cfg, plan, h);
        try {
            p.start(carDark);
        } catch (IOException | RuntimeException e) {
            p.stop();
            throw e;
        }
        return p;
    }

    private void start(Boolean carDark) throws IOException {
        fpsCap = plan.fps;
        thermalFpsCap = plan.fps;
        linkFpsCap = plan.fps;
        thermalMode = cfg.thermalMode();
        int gateKb = cfg.getInt(Config.GATE_OUTQ_KB);
        gateOutq = gateKb > 0 ? gateKb * 1024 : GATE_OUTQ;
        source = VideoSource.create(ctx, cfg);
        source.setCarSize(plan.car.carW, plan.car.carH);
        source.setVideoSize(plan.videoW, plan.videoH);
        source.setTargetFps(plan.fps);
        if (carDark != null) source.onCarDarkMode(carDark);
        if (!source.usesEncoder()) {
            startPassthrough();
        } else {
            startEncoder();
        }
    }

    // ---------------------------------------------------------------- reenvío directo (AA Básico)

    private void startPassthrough() {
        policy = new KeyframePolicy();
        // startPassthrough ya pide un ciclo de foco.
        policy.noteRequested(SystemClock.elapsedRealtime());
        if (source instanceof AaPassthroughSource && cfg.aaBrake()) {
            brake = new AaAckBrake();
            VideoTap.setAckGate(brake.gate);
            L.i("VIDEO freno a AA activo: ventana pedida " + cfg.aaWindow() + ", anunciada " + VideoTap.getAnnouncedWindow()
                    + " (si AA ya estaba conectado, la ventana nueva se aplica al reconectar AA)");
        }
        int fps = plan.car.argsFps > 0 ? plan.car.argsFps : 30;
        srcJitter = new Jitter("origen", Math.max(1, fps));
        int[] size = source.passthroughSize();
        headerW = size != null ? size[0] : plan.videoW;
        headerH = size != null ? size[1] : plan.videoH;
        L.i("VIDEO reenvío directo desde " + source.getClass().getSimpleName() + ", cabecera " + headerW + "x" + headerH);
        source.startPassthrough(this::onAaUnit);
    }

    /** Hilo de vídeo de AA: una unidad H.264 (con el SPS ya recortado por AaPassthroughSource). */
    private void onAaUnit(byte[] data, int off, int len) {
        Jitter j = srcJitter;
        if (j != null) j.tick();
        AaAckBrake b = brake;
        if (AnnexB.INSTANCE.isCodecConfig(data, off, len)) {
            // SPS+PPS sueltos: se guardan y se mandan aparte; sin ranura, AA confirma en el acto.
            byte[] c = Arrays.copyOfRange(data, off, off + len);
            synchronized (csdLock) {
                csd = c;
                SessionPort p = active;
                if (p != null) p.sendConfig(c);
            }
            if (b != null) b.noSlot();
            return;
        }
        boolean key = AnnexB.INSTANCE.containsIdr(data, off, len);
        if (key) {
            KeyframePolicy pol = policy;
            if (pol != null) pol.onIdrSeen(SystemClock.elapsedRealtime());
            // Uno que cabe corta la racha de IDR grandes (los que no caben los descarta el núcleo: onOversized).
            if (len <= PAYLOAD_CAP) aaOversizeStreak.set(0);
        }
        // El tamaño lo decide la negociación con AA, que llega después de arrancar.
        int[] sz = source.passthroughSize();
        SessionPort p = active;
        if (sz != null && (sz[0] != headerW || sz[1] != headerH)) {
            L.i("VIDEO cabecera " + headerW + "x" + headerH + " -> " + sz[0] + "x" + sz[1]);
            headerW = sz[0];
            headerH = sz[1];
            if (p != null) p.setHeader(headerW, headerH, null, null, null);
        }
        if (p == null) {
            droppedNoSession.incrementAndGet();
            if (b != null) b.noSlot();
            return;
        }
        if (key) noteIdr();
        AckSlot slot = b != null ? b.newSlot(p) : null;
        p.sendFrame(data, off, len, key, VideoTap.getFrameTimestampUs(), slot != null ? b.completionFor(slot) : null);
    }

    // ---------------------------------------------------------------- encoder (último frame, patrón, app)

    private void startEncoder() throws IOException {
        int carFps = plan.car.argsFps;
        int carBitrate = plan.car.argsBitrate;
        vp = new VideoEncoder.Params();
        vp.width = plan.videoW;
        vp.height = plan.videoH;
        vp.fps = plan.fps;
        vp.bitrate = cfg.bitrate(carBitrate);
        vp.profile = cfg.profile();
        vp.prependSpsPps = cfg.prependSpsPps();
        vp.lowLatency = LowLatency.enabled;
        vp.noRepeat = cfg.getBool("enc_no_repeat");
        vp.maxClocks = cfg.encMaxClocks();
        // Tamaño de los IDR: QP mínimo de los I-frames de partida (Android 12+); lo ajusta IdrSizeController.
        vp.qpIMin = IdrSizeController.QP_START;
        vp.qpIMax = IdrSizeController.QP_MAX;
        boolean latestFrame = source instanceof AaPassthroughSource;
        if (latestFrame) {
            // Como SspSession: sin IDR periódicos (el coche pide uno cuando lo necesita), intra-refresh. VBR en todos los
            // perfiles salvo enc_cbr: en el viaje 6 (2026-10-06) el CBR del perfil Coche dio peor imagen que el VBR del
            // perfil Alto con el mismo bitrate, y el CBR rellena la radio con la pantalla quieta (ver SspSession). Las
            // ráfagas del VBR las frena el bitrate adaptable al enlace (LinkRateController).
            VideoProfile prof = cfg.videoProfile();
            vp.cbr = cfg.getBool("enc_cbr");
            vp.intraRefreshFrames = cfg.getBool("enc_no_ir") ? 0 : vp.fps;
            vp.iFrameIntervalSec = cfg.getBool("enc_no_ir") ? 10 : 30;
            vp.repeatAfterUs = 100_000;
            if (cfg.getInt(Config.KBPS) <= 0) {
                // El del perfil; en Coche, el que pide el coche (VIDEO_ARGS BitRate; con Fluidez 60, 8-12 Mbit/s).
                vp.bitrate = prof.startBitrate(carBitrate, plan.videoW);
                if (prof.adaptiveBitrate()) {
                    abrBps = vp.bitrate;
                    abrInitial = vp.bitrate;
                    abrMin = prof.minBitrate;
                    abrMax = prof.maxBitrate;
                }
            }
            if (prof.followsCar) {
                L.i(String.format(Locale.US, "VIDEO fluidez %d fps (perfil %s): %d fps · %.1f Mbit/s; el coche pide %d fps · %.1f Mbit/s",
                        prof.fluidity, prof.id, vp.fps, vp.bitrate / 1e6, carFps, carBitrate / 1e6));
            }
            gated = true;
            // Bitrate según el enlace para los perfiles de bitrate fijo (Coche a 30 y 60, Medio, Muy bajo).
            if (abrMax <= 0 && !cfg.getBool("link_fixed")) link = new LinkRateController(vp.bitrate, vp.fps);
            source.setLinkGate(new GlFrameRelay.Gate() {
                @Override
                public boolean ready() {
                    return gateReady();
                }

                @Override
                public void submitted() {
                    long now = System.nanoTime();
                    pendingSinceNs = now;
                    lastSubmitNs = now;
                    pendingEnc.incrementAndGet();
                }
            });
        }
        L.i("VIDEO start: coche pide " + plan.car.argsW + "x" + plan.car.argsH + "@" + carFps + " " + carBitrate + "bps intervalo "
                + plan.car.argsInterval + " | usamos " + vp);
        encJitter = new Jitter("encoder", Math.max(1, vp.fps));
        encoder = new VideoEncoder(vp, new VideoEncoder.Sink() {
            @Override
            public void onCodecConfig(byte[] c) {
                L.i("SPS/PPS " + c.length + " bytes: " + L.hex(c, 0, Math.min(48, c.length)));
                synchronized (csdLock) {
                    csd = c;
                    SessionPort p = active;
                    if (p != null) p.sendConfig(c);
                }
            }

            @Override
            public void onFrame(byte[] data, int len, boolean keyFrame, long ptsUs) {
                onEncodedFrame(data, len, keyFrame, ptsUs);
            }
        });
        L.i("fuente de vídeo: " + cfg.mode() + " -> " + source.getClass().getSimpleName());
        android.view.Surface in = encoder.start();
        IdrSizeController ctl = new IdrSizeController(encoder.qpControl(), PAYLOAD_CAP);
        idrCtl = ctl;
        L.i("VIDEO " + ctl.describe());
        source.start(in, plan.videoW, plan.videoH, vp.fps, vp.toString());
        if (gated) L.i("VIDEO puerta «último frame»: cola del kernel < " + gateOutq / 1024 + " KB · modo de tasa " + encoder.bitrateMode());
        LinkRateController lk = link;
        if (lk != null) L.i("VIDEO " + lk.describe());
        if (abrMax > 0) {
            L.i(String.format(Locale.US, "bitrate adaptable %.1f-%.1f Mbps, empieza en %.1f", abrMin / 1e6, abrMax / 1e6, abrBps / 1e6));
            h.postDelayed(abrTick, 1000);
        }
    }

    /** Hilo enc-drain: el búfer se reutiliza, pero el núcleo copia el frame al encolarlo. */
    private void onEncodedFrame(byte[] buf, int len, boolean flagKey, long ptsUs) {
        Jitter j = encJitter;
        if (j != null) j.tick();
        lastEncoderFrameNs = System.nanoTime();
        // pendingEnc - 1 sin bajar de 0 (bucle CAS: getAndUpdate es de API 24).
        while (true) {
            int v = pendingEnc.get();
            if (v <= 0 || pendingEnc.compareAndSet(v, v - 1)) break;
        }
        boolean key = AnnexB.INSTANCE.containsIdr(buf, 0, len);
        if (key != flagKey && !warnedKeyMismatch) {
            warnedKeyMismatch = true;
            L.w("encoder: la marca de keyframe (" + flagKey + ") no coincide con el contenido (IDR " + key + "); manda el contenido");
        }
        if (key) onEncoderIdr(len);
        SessionPort p = active;
        if (p == null) {
            droppedNoSession.incrementAndGet();
            return;
        }
        if (key) noteIdr();
        p.sendFrame(buf, 0, len, key, ptsUs, null);
    }

    /**
     * Hilo enc-drain, antes de mandar el IDR: tamaño al controlador, QP-I nuevo al encoder (antes de que nadie pida el
     * siguiente IDR) y una línea de log por IDR («IDR 312 KB · QP-I mín 30»). Si pasa del tope, el núcleo lo descartará
     * y pedirá otro (OVERSIZED); el controlador ya lo ha tenido en cuenta.
     */
    private void onEncoderIdr(int len) {
        idrGate.onIdr();
        boolean requested = idrRequested;
        idrRequested = false;
        String why = idrRequestWhy;
        IdrSizeController ctl = idrCtl;
        VideoEncoder enc = encoder;
        if (ctl == null || enc == null) return;
        IdrSizeController.Step st = ctl.onIdr(len, enc.lastIdrQp(), enc.lastIdrDip());
        boolean applied = !st.qpChanged() || enc.setQpIMin(st.qpAfter);
        PerfTrace.event("idr_kb", IdrSizeController.kb(len));
        if (st.qpChanged()) PerfTrace.event("idr_qp_min", st.qpAfter);
        long now = SystemClock.elapsedRealtime();
        if (!requested && !st.notable() && applied && now - lastQuietIdrLogMs < QUIET_IDR_LOG_MS) {
            quietIdrs++;
            return;
        }
        String line = "VIDEO " + st.line() + (requested ? " · pedido (" + why + ")" : " · periódico")
                + (applied ? "" : " · el encoder no aceptó el QP-I nuevo")
                + (quietIdrs > 0 ? " · +" + quietIdrs + " IDR periódicos sin cambios desde la línea anterior" : "");
        quietIdrs = 0;
        if (!requested) lastQuietIdrLogMs = now;
        if (st.overCap || !applied) L.w(line);
        else L.i(line);
    }

    /**
     * "Último frame" (hilo GL): ¿cabe un frame nuevo? Con sesión enganchada y en vídeo, como mucho GATE_MAX_PENDING-1
     * en el encoder, como mucho uno en la cola del núcleo y la cola del kernel por debajo de GATE_OUTQ.
     */
    private boolean gateReady() {
        SessionPort p = active;
        if (p == null || p.getClosed() || !p.isStreaming()) {
            denyNoSession++;
            return false;
        }
        if (pendingEnc.get() >= GATE_MAX_PENDING) {
            if (System.nanoTime() - pendingSinceNs < GATE_PENDING_TIMEOUT_NS) {
                denyPending++;
                return false;
            }
            pendingEnc.set(0);
        }
        if (p.videoQueueFrames() > 1) {
            denyQueue++;
            return false;
        }
        int q = p.gateOutq();
        if (q >= gateOutq) {
            denyKernel++;
            abrDenies++;
            linkWaits++;
            return false;
        }
        return true;
    }

    /**
     * Hilo hql-video, cada ~100 ms desde el monitor de red de la sesión activa: muestra de NetStat para el controlador
     * del enlace, con las esperas de la puerta por el enlace desde la muestra anterior. Cada paso (bitrate o fps) se
     * aplica al encoder o al relay GL y se registra («enlace: congestión (outq 96 KB, retrans +21) → bitrate 3.5 Mbit/s»).
     */
    void onLinkSample(SessionPort port, LinkRateController.Sample sample) {
        LinkRateController lk = link;
        VideoEncoder enc = encoder;
        if (lk == null || enc == null || stopped || active != port) return;
        int waits = linkWaits;
        int delta = Math.max(0, waits - linkWaitsSeen);
        linkWaitsSeen = waits;
        LinkRateController.Step st = lk.onSample(sample.with(delta, sample.lateFlushes));
        if (st == null) return;
        if (st.bitrateChanged()) {
            enc.setBitrate(st.bitrateAfter);
            PerfTrace.event("link_kbps", st.bitrateAfter / 1000);
        }
        if (st.fpsChanged()) {
            linkFpsCap = st.fpsAfter;
            applyFpsCap();
            PerfTrace.event("link_fps", st.fpsAfter);
        }
        if (st.congestion) PerfTrace.event("link_congestion", st.bitrateAfter / 1000);
        if (st.congestion) L.w("enlace: " + st.text);
        else L.i("enlace: " + st.text);
    }

    /** fps máximos: el menor del tope térmico y del enlace, al relay GL (con él) o los de la sesión (patrón, app). */
    private void applyFpsCap() {
        int fps = Math.min(thermalFpsCap, linkFpsCap);
        if (!gated) {
            fpsCap = vp != null ? vp.fps : plan.fps;
            return;
        }
        if (fps == fpsCap) return;
        fpsCap = fps;
        source.setMaxFps(fps);
    }

    /** ABR (AIMD) cada segundo en hql-video, como SspSession.adaptBitrate, solo con sesión enganchada. */
    private void adaptBitrate() {
        VideoEncoder enc = encoder;
        if (enc == null || stopped || abrMax <= 0 || active == null) return;
        int d = abrDenies;
        abrDenies = 0;
        int before = abrBps;
        int max = abrCeiling();
        int min = Math.min(abrMin, max);
        if (d >= 5) {
            abrBps = Math.max(min, (int) (abrBps * 0.8));
            abrCalm = 0;
        } else if (d == 0) {
            if (++abrCalm >= 2 && abrBps < max) {
                abrBps = Math.min(max, abrBps + 1_000_000);
                abrCalm = 0;
            }
        } else {
            abrCalm = 0;
        }
        abrBps = Math.min(abrBps, max);
        if (abrBps != before) {
            enc.setBitrate(abrBps);
            PerfTrace.event("abr_kbps", abrBps / 1000);
        }
    }

    /** Techo del ABR: el del perfil o, con calor, el tope térmico (lo que sea menor). */
    private int abrCeiling() {
        return thermalMaxBps > 0 ? Math.min(abrMax, thermalMaxBps) : abrMax;
    }

    /**
     * Hilo hql-video: nivel térmico nuevo (ThermalGuard), en marcha y sin reiniciar la sesión ni AA: bitrate del
     * encoder (setParameters) y ritmo del relay GL. En el reenvío directo de AA no hay encoder propio: solo se registra.
     */
    void applyThermal(int level, int status) {
        if (stopped) return;
        String mode = cfg.thermalMode();
        if (level == thermalLevel && mode.equals(thermalMode)) return;
        thermalLevel = level;
        thermalMode = mode;
        VideoEncoder enc = encoder;
        String head = "térmico " + status + " → perfil " + ThermalPolicy.name(level) + " (protección " + ThermalPolicy.modeName(mode) + ")";
        if (enc == null || vp == null) {
            L.i(head + ": reenvío directo de AA, sin encoder propio; no se cambia nada");
            return;
        }
        int base = abrMax > 0 ? abrInitial : vp.bitrate;
        int cap = ThermalPolicy.bitrateCap(mode, level, base);
        thermalMaxBps = cap >= base ? 0 : cap;
        int bps;
        LinkRateController lk = link;
        if (abrMax > 0) {
            abrBps = thermalMaxBps == 0 ? Math.max(abrBps, Math.min(abrInitial, abrMax)) : Math.min(abrBps, abrCeiling());
            bps = abrBps;
        } else if (lk != null) {
            // El techo del enlace es el térmico (o el del perfil al enfriarse); el bitrate que lleva, el del enlace.
            lk.setCeiling(thermalMaxBps > 0 ? thermalMaxBps : vp.bitrate);
            bps = lk.bitrate();
        } else {
            bps = thermalMaxBps > 0 ? thermalMaxBps : vp.bitrate;
        }
        enc.setBitrate(bps);
        thermalFpsCap = ThermalPolicy.fpsCap(mode, level, vp.fps);
        // Sin relay GL (patrón, app) la fuente dibuja a su ritmo: solo baja el bitrate.
        applyFpsCap();
        PerfTrace.event("thermal_fps", fpsCap);
        PerfTrace.event("thermal_kbps", bps / 1000);
        L.i(String.format(Locale.US, "%s: %d fps (sesión %d%s) · %.1f Mbit/s (sesión %.1f%s)%s", head, fpsCap, vp.fps,
                lk != null && linkFpsCap < vp.fps ? ", enlace " + linkFpsCap : "", bps / 1e6, base / 1e6,
                lk != null && lk.bitrate() < lk.ceiling() ? ", techo " + LinkRateController.mbit(lk.ceiling()) : "",
                gated ? "" : " · sin relay GL, solo el bitrate"));
    }

    /** fps máximos ahora (los de la sesión, o el tope térmico o del enlace). */
    int fpsCap() {
        return fpsCap;
    }

    /** Bitrate más bajo que aplicó el controlador del enlace en la sesión activa (bps), o 0 sin controlador. */
    int linkMinBps() {
        LinkRateController lk = link;
        return lk != null ? lk.minBitrate() : 0;
    }

    /** Pasos por congestión del enlace en la sesión activa, o 0 sin controlador. */
    int linkCongestionEvents() {
        LinkRateController lk = link;
        return lk != null ? lk.congestionEvents() : 0;
    }

    private void noteIdr() {
        if (!idrAfterAttach) {
            idrAfterAttach = true;
            long ms = (System.nanoTime() - attachedAtNs) / 1_000_000;
            firstIdrAfterAttachMs = ms;
            L.i("VIDEO primer IDR tras enganchar: " + ms + " ms");
        }
    }

    // ---------------------------------------------------------------- sesiones (hilo hql-video)

    /** Engancha la sesión: cabecera, SPS/PPS en caché delante del primer IDR y, en reconexiones, comprobar el origen. */
    void attach(SessionPort port) {
        attachedAtNs = System.nanoTime();
        idrAfterAttach = false;
        firstIdrAfterAttachMs = -1;
        if (source.usesEncoder()) {
            int carFps = plan.car.argsFps;
            int carBitrate = plan.car.argsBitrate;
            int carInterval = plan.car.argsInterval;
            port.setHeader(plan.videoW, plan.videoH, carFps > 0 ? null : vp.fps, carBitrate > 0 ? null : vp.bitrate,
                    carInterval > 0 ? null : 4);
        } else {
            port.setHeader(headerW, headerH, null, null, null);
        }
        pendingEnc.set(0);
        denyPending = 0;
        denyQueue = 0;
        denyKernel = 0;
        denyNoSession = 0;
        abrDenies = 0;
        if (abrMax > 0 && abrBps > Math.min(abrInitial, abrCeiling()) && encoder != null) {
            abrBps = Math.min(abrInitial, abrCeiling());
            encoder.setBitrate(abrBps);
        }
        LinkRateController lk = link;
        if (lk != null && encoder != null) {
            // Enlace nuevo: se empieza en el techo (el del perfil o el térmico) y con los fps de la sesión.
            boolean wasLow = lk.bitrate() < lk.ceiling() || lk.fpsCap() < lk.sessionFps();
            lk.beginSession();
            linkWaitsSeen = linkWaits;
            encoder.setBitrate(lk.bitrate());
            linkFpsCap = lk.fpsCap();
            applyFpsCap();
            if (wasLow) L.i("enlace: sesión nueva → bitrate " + LinkRateController.mbit(lk.bitrate()) + " · " + lk.fpsCap() + " fps");
        }
        port.setSocketJitter(new Jitter("socket", Math.max(1, vp != null ? vp.fps : plan.fps)));
        long lost = droppedNoSession.getAndSet(0);
        // SPS/PPS en caché delante del primer IDR, y la sesión enganchada, a la vez (csdLock).
        synchronized (csdLock) {
            byte[] c = csd;
            if (c != null) port.sendConfig(c);
            active = port;
        }
        if (attaches++ > 0) {
            L.i("VIDEO reenganchado a S" + port.getId() + (lost > 0 ? " (" + lost + " frames tirados sin sesión)" : ""));
            source.onReattached();
        } else {
            L.i("VIDEO enganchado a S" + port.getId());
        }
    }

    /** Desengancha la sesión (si es la activa): la puerta GL se cierra y los frames se tiran (y se cuentan). */
    void detach(SessionPort port) {
        if (active != port) return;
        active = null;
        L.i("VIDEO desenganchado de S" + port.getId() + " (el vídeo sigue vivo)");
    }

    SessionPort active() {
        return active;
    }

    /**
     * Hilo hql-video: IDR pedido por la sesión activa. Con encoder, IdrRequestGate: el primero de la sesión
     * (STREAM_START) y el que sustituye a uno descartado por grande (OVERSIZED), al momento; los demás (KEY_FRAME_REQ del
     * coche, atasco), como mucho uno cada IdrRequestGate.DEBOUNCE_MS. Sin encoder, KeyframePolicy.
     */
    void requestKeyFrame(KeyframeReason reason) {
        VideoEncoder enc = encoder;
        long now = SystemClock.elapsedRealtime();
        if (enc != null) {
            boolean urgent = reason == KeyframeReason.STREAM_START || reason == KeyframeReason.OVERSIZED;
            long v = idrGate.onRequest(now, urgent);
            if (v == IdrRequestGate.NOW) {
                fireEncoderIdr(String.valueOf(reason));
            } else if (v > 0) {
                h.removeCallbacks(deferredIdr);
                h.postDelayed(deferredIdr, v);
                PerfTrace.event("idr_defer", v);
            } else {
                PerfTrace.event("idr_skip", 0);
            }
            return;
        }
        KeyframePolicy pol = policy;
        if (pol == null) return;
        if (aaOversizeBackoff(now)) {
            PerfTrace.event("idr_skip", 0);
            return;
        }
        if (pol.onRequest(now)) {
            fireCycle(String.valueOf(reason));
        } else {
            PerfTrace.event("idr_skip", 0);
        }
    }

    /** Hilo hql-video: pide el IDR al encoder (con el bitrate bajado si el controlador lo dice) y dibuja un frame. */
    private void fireEncoderIdr(String why) {
        VideoEncoder enc = encoder;
        if (enc == null || stopped) return;
        IdrSizeController ctl = idrCtl;
        double dip = ctl != null ? ctl.takeDip() : 0;
        idrRequestWhy = dip > 0 ? why + ", bitrate al " + IdrSizeController.pct(dip) + " %" : why;
        idrRequested = true;
        enc.requestKeyFrame(dip);
        if (dip > 0) PerfTrace.event("idr_dip_pct", IdrSizeController.pct(dip));
        // Redraw para tener un frame que codificar (la imagen puede no haber cambiado).
        source.redraw();
    }

    /**
     * Hilo hql-video: el núcleo descartó un frame por pasar de su tope (después de onEncoderIdr, si es nuestro). En el
     * reenvío directo no se controla el encoder de AA: se cuenta y, si se repite, se avisa y se espacian los ciclos.
     */
    void onOversized(int messageBytes, boolean key) {
        if (stopped || encoder != null) return;
        int n = aaOversizeStreak.incrementAndGet();
        aaOversizeTotal++;
        long now = SystemClock.elapsedRealtime();
        if (n >= AA_OVERSIZE_WARN_AFTER && now - lastAaOversizeWarnMs >= AA_OVERSIZE_WARN_EVERY_MS) {
            lastAaOversizeWarnMs = now;
            L.w("Android Auto manda " + (key ? "IDR" : "frames") + " de " + IdrSizeController.kb(messageBytes)
                    + " KB, más de lo que admite el coche (" + IdrSizeController.kb(SessionConfigs.MAX_VIDEO_MESSAGE_BYTES)
                    + " KB): " + n + " seguidos descartados (" + aaOversizeTotal + " en total). La imagen puede quedarse"
                    + " parada; pido otro IDR como mucho cada " + AA_OVERSIZE_BACKOFF_MS / 1000 + " s. Mejor un perfil que"
                    + " recodifique en el móvil («Coche», el recomendado) en Ajustes de imagen.");
        }
    }

    /** Reenvío directo con IDR de AA grandes seguidos: no más de un ciclo de foco cada AA_OVERSIZE_BACKOFF_MS. */
    private boolean aaOversizeBackoff(long now) {
        return aaOversizeStreak.get() >= AA_OVERSIZE_WARN_AFTER && now - lastAaCycleMs < AA_OVERSIZE_BACKOFF_MS;
    }

    /** Pide un ciclo de foco a AA; si la palanca está ocupada, un único reintento a +150 ms. */
    private void fireCycle(String why) {
        if (stopped) return;
        lastAaCycleMs = SystemClock.elapsedRealtime();
        L.i("AA: ciclo de foco para IDR (" + why + ")");
        source.requestKeyFrame(started -> h.post(() -> {
            if (started || stopped) return;
            long delay = policy.onLeverRefused(SystemClock.elapsedRealtime());
            if (delay > 0) {
                h.postDelayed(() -> {
                    if (stopped) return;
                    policy.onRetry(SystemClock.elapsedRealtime());
                    fireCycle("reintento");
                }, delay);
            }
        }));
    }

    /** Hilo hql-video, cada 500 ms: vigilante del IDR en el reenvío directo. */
    void tick() {
        KeyframePolicy pol = policy;
        SessionPort p = active;
        if (pol == null || p == null || stopped) return;
        long now = SystemClock.elapsedRealtime();
        if (aaOversizeBackoff(now)) return;
        if (pol.watchdog(now, p.waitingForIdr())) fireCycle("vigilante: sin IDR en 1,5 s");
    }

    void setCarDark(boolean dark) {
        source.onCarDarkMode(dark);
    }

    /** Sin Android Auto ni encoder muertos: la pipeline sirve para una sesión nueva. */
    boolean healthy() {
        if (stopped) return false;
        if (!source.usesEncoder()) {
            return !(source instanceof AaPassthroughSource) || ((AaPassthroughSource) source).aaAlive();
        }
        if (encoder == null || encoder.failed()) return false;
        if (vp != null && vp.noRepeat) return true;
        return encoderResponsive(System.nanoTime(), lastEncoderFrameNs, lastSubmitNs, gated);
    }

    /**
     * ¿Responde el encoder? (puro, lo prueban los tests). Sin puerta (patrón, app) la fuente dibuja siempre: basta con
     * una salida en ENCODER_HEALTH_NS. Con la puerta GL ("último frame") no: sin sesión no se dibuja nada y, tras sus
     * repeticiones (KEY_REPEAT_PREVIOUS_FRAME_AFTER, acotadas), el encoder se calla aunque esté bien. Ahí solo está mal
     * si se le dio un frame después de su última salida y no ha devuelto nada en ENCODER_HEALTH_NS.
     */
    static boolean encoderResponsive(long nowNs, long lastOutNs, long lastInNs, boolean gated) {
        if (nowNs - lastOutNs < ENCODER_HEALTH_NS) return true;
        if (!gated) return false;
        return lastInNs - lastOutNs <= 0 || nowNs - lastInNs < ENCODER_HEALTH_NS;
    }

    /** Por qué no sirve (para el log). */
    String unhealthyReason() {
        if (stopped) return "parada";
        if (!source.usesEncoder()) return "Android Auto desconectado";
        if (encoder == null) return "sin encoder";
        if (encoder.failed()) return "el encoder ha fallado";
        long now = System.nanoTime();
        String r = "el encoder no da frames desde hace " + (now - lastEncoderFrameNs) / 1_000_000 + " ms";
        return gated ? r + " (el último frame se le dio hace " + (now - lastSubmitNs) / 1_000_000 + " ms)" : r;
    }

    /** Toque del coche, directo desde el hilo de eventos de la sesión (VideoHub serializa las llamadas). */
    void touch(int action, Proto.Finger[] fingers) {
        VideoSource s = source;
        if (s != null) s.touchMulti(action, fingers);
    }

    /** Líneas de la pipeline para las estadísticas de 5 s de la sesión activa (o null si port no es la activa). */
    List<String> takeStats(SessionPort port) {
        if (active != port) return null;
        List<String> out = new ArrayList<>();
        AaAckBrake b = brake;
        if (b != null) out.add(b.takeWindowLine());
        int dp = denyPending;
        int dq = denyQueue;
        int dk = denyKernel;
        if (dp + dq + dk > 0) {
            out.add(String.format(Locale.US, "puerta cerrada: encoder lleno %d, cola %d, kernel %d", dp, dq, dk));
            denyPending = 0;
            denyQueue = 0;
            denyKernel = 0;
        }
        StringBuilder jit = new StringBuilder();
        VideoSource s = source;
        String rj = s != null ? s.takeJitterSummary() : null;
        if (rj != null) jit.append(rj);
        Jitter j = srcJitter != null ? srcJitter : encJitter;
        if (j != null) {
            if (jit.length() > 0) jit.append(" · ");
            jit.append(j.takeSummary());
        }
        Jitter sj = port.getSocketJitter();
        if (sj != null) {
            if (jit.length() > 0) jit.append(" · ");
            jit.append(sj.takeSummary());
        }
        if (jit.length() > 0) out.add(jit.toString());
        if (abrMax > 0) out.add(String.format(Locale.US, "bitrate %.1f Mbps (%.1f-%.1f)", abrBps / 1e6, abrMin / 1e6, abrMax / 1e6));
        LinkRateController lk = link;
        if (lk != null && lk.active()) out.add(lk.statsLine());
        String gate = idrGate.takeWindowLine();
        if (gate != null) out.add(gate);
        return out;
    }

    void setStatus(String line) {
        VideoSource s = source;
        if (s != null) s.setStatus(line);
    }

    /** ms del primer IDR tras el último enganche, o -1. */
    long firstIdrAfterAttachMs() {
        return firstIdrAfterAttachMs;
    }

    int aaCycles() {
        KeyframePolicy p = policy;
        return p != null ? p.cycles() : 0;
    }

    String keyframeSummary() {
        KeyframePolicy p = policy;
        return p != null ? p.summary() : "";
    }

    /** Hilo hql-video: para todo (equivale a la parte de vídeo de SspSession.close). */
    void stop() {
        if (stopped) return;
        stopped = true;
        active = null;
        h.removeCallbacks(abrTick);
        h.removeCallbacks(deferredIdr);
        IdrSizeController ctl = idrCtl;
        if (ctl != null) L.i("VIDEO " + ctl.summary() + " · " + idrGate.summary());
        if (aaOversizeTotal > 0) L.i("AA: " + aaOversizeTotal + " IDR descartados por pasar del tope del coche");
        if (brake != null) {
            VideoTap.setAckGate(null);
            brake.stop();
        }
        if (source != null) source.stop();
        if (encoder != null) encoder.stop();
        if (policy != null && policy.cycles() > 0) L.i("AA: " + policy.summary());
    }
}
