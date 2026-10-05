package com.headqlink.link;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Encoder H.264 con entrada Surface. Entrega SPS/PPS y frames Annex-B a un Sink.
 *
 * Tamaño de los IDR (IdrSizeController): con Params.qpIMin y Android 12+, QP mínimo de los I-frames al configurar y en
 * marcha (setQpIMin); con Android 13+, el QP medio de cada IDR si el encoder lo informa (lastIdrQp); y, para un IDR
 * pedido, bajada temporal del bitrate (requestKeyFrame(dip)) que se repone en cuanto sale el IDR.
 */
final class VideoEncoder {
    interface Sink {
        void onCodecConfig(byte[] spsPps);

        void onFrame(byte[] data, int len, boolean keyFrame, long ptsUs);
    }

    static final class Params {
        int width;
        int height;
        int fps;
        int bitrate;
        int iFrameIntervalSec = 1;
        String profile = "baseline";
        boolean prependSpsPps;
        /** Tasa constante: frames de tamaño parecido, sin picos que atasquen la radio. */
        boolean cbr;
        /** > 0: refresco intra repartido en estos frames, en vez de IDR periódicos enormes. */
        int intraRefreshFrames;
        /** Repetir el último frame si no llega otro en este tiempo (0 = 3 frames). */
        long repeatAfterUs;
        /** Extensión de baja latencia de Qualcomm y velocidad de operación alta (LowLatency). */
        boolean lowLatency;
        // Variantes de prueba (adb: --ez enc_no_repeat true, --ez enc_max_clocks true).
        /** Sin "repetir el último frame" (KEY_REPEAT_PREVIOUS_FRAME_AFTER). */
        boolean noRepeat;
        /** Velocidad de operación al máximo (relojes del codificador altos siempre). */
        boolean maxClocks;
        /** > 0 (Android 12+): QP mínimo de los I-frames al configurar (KEY_VIDEO_QP_I_MIN); 0 = el del encoder. */
        int qpIMin;
        /** QP máximo de los I-frames (con qpIMin). */
        int qpIMax = 51;

        @Override
        public String toString() {
            return width + "x" + height + "@" + fps + " " + (bitrate / 1000) + "kbps " + profile
                    + (cbr ? " CBR" : " VBR") + " gop=" + iFrameIntervalSec + "s"
                    + (intraRefreshFrames > 0 ? " intra-refresh=" + intraRefreshFrames : "") + " prepend=" + prependSpsPps
                    + (noRepeat ? " sin-repetir" : "") + (maxClocks ? " relojes-max" : "")
                    + (qpIMin > 0 ? " qp-i=" + qpIMin + "-" + qpIMax : "");
        }
    }

    private final Params p;
    private final Sink sink;
    private MediaCodec codec;
    private Surface input;
    private Thread drain;
    private volatile boolean running;
    /** El códec dejó de funcionar sin que lo paráramos (error o reclamado por el sistema): ya no dará frames. */
    private volatile boolean failed;
    private byte[] outBuf = new byte[512 * 1024];
    /** Bitrate pedido (ABR, térmico); el que lleva el códec puede ser menor durante una bajada por IDR. */
    private volatile int bitrate;
    /** Bajada en curso para un IDR pedido (factor), o 0. */
    private volatile double dipFactor;
    private volatile long dipSinceNs;
    /** Se configuró con las claves de QP de los I-frames / con las estadísticas de codificación (QP medio). */
    private volatile boolean qpKeys;
    private volatile boolean qpStats;
    /** Último SPS/PPS entregado (hilo enc-drain). */
    private byte[] lastCsd;
    /** Del último IDR (hilo enc-drain, antes de Sink.onFrame): QP medio informado (-1 = no) y bajada con que salió. */
    private volatile int lastIdrQp = -1;
    private volatile double lastIdrDip;
    /** Una bajada sin IDR (el encoder no lo dio) se repone igualmente pasado esto. */
    private static final long DIP_TIMEOUT_NS = 1_000_000_000L;
    /** Modo de tasa con que se configuró (CBR, VBR o VBR con tope de picos), para el log. */
    private volatile String bitrateMode = "VBR";
    /** MediaFormat.KEY_MAX_BITRATE (constante de la API 33; la clave existe desde antes). */
    private static final String MAX_BITRATE = "max-bitrate";
    private static final String INTRA_REFRESH = "intra-refresh-period";
    private boolean intraRefreshChecked;

    /** CBR, VBR o VBR con tope de picos. */
    String bitrateMode() {
        return bitrateMode;
    }

    /** ¿Declara el códec que admite tasa constante? (API 21+; antes se da por bueno.) */
    private static boolean supportsCbr(MediaCodec c) {
        if (android.os.Build.VERSION.SDK_INT < 21) return true;
        try {
            MediaCodecInfo.CodecCapabilities caps = c.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaCodecInfo.EncoderCapabilities ec = caps != null ? caps.getEncoderCapabilities() : null;
            return ec == null || ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        } catch (RuntimeException e) {
            L.w("encoder: no se pudo consultar si admite CBR: " + e.getMessage());
            return true;
        }
    }

    VideoEncoder(Params p, Sink sink) {
        this.p = p;
        this.sink = sink;
    }

    Surface start() throws IOException {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, p.width, p.height);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, p.bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, p.fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, p.iFrameIntervalSec);
        String name = pickHardwareEncoder(p.width, p.height, p.fps);
        codec = name != null ? MediaCodec.createByCodecName(name) : MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        // Tasa constante si se pide y el códec la admite; si no, VBR con tope de picos (KEY_MAX_BITRATE) en el bitrate
        // pedido, para que las ráfagas (P-frames de 276 KB a 15-19 Mbit/s en el coche) no atasquen la radio.
        boolean cbr = p.cbr && supportsCbr(codec);
        bitrateMode = cbr ? "CBR" : p.cbr ? "VBR con tope de picos" : "VBR";
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, cbr
                ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        if (p.cbr && !cbr) f.setInteger(MAX_BITRATE, p.bitrate);
        if (p.intraRefreshFrames > 0) f.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, p.intraRefreshFrames);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);
        f.setInteger(MediaFormat.KEY_LATENCY, 1);
        if (!p.noRepeat) {
            f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, p.repeatAfterUs > 0 ? p.repeatAfterUs : 1_000_000L / p.fps * 3);
        }
        if (p.maxClocks) f.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);
        if (p.prependSpsPps) f.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
        f.setInteger(MediaFormat.KEY_PROFILE, profileConst(p.profile));
        f.setInteger(MediaFormat.KEY_LEVEL, levelFor(p.width, p.height, p.fps));

        bitrate = p.bitrate;

        L.i("encoder " + codec.getName() + " " + p + (p.lowLatency ? " · baja latencia" : "") + " · modo de tasa " + bitrateMode
                + (p.cbr && !cbr ? " (el códec no admite CBR; tope de picos " + p.bitrate / 1000 + " kbps)" : ""));
        // Intentos, de más a menos: baja latencia (si se pide) y control del tamaño de los IDR; se quita lo que el
        // códec rechace (un encoder que no admite las claves de baja latencia acaba con la configuración normal).
        MediaFormat sized = withIdrSizeControl(f);
        List<MediaFormat> tries = new ArrayList<>();
        if (p.lowLatency) {
            if (sized != null) tries.add(lowLatency(sized));
            tries.add(lowLatency(f));
        }
        if (sized != null) tries.add(sized);
        tries.add(f);
        MediaFormat used = null;
        RuntimeException last = null;
        for (MediaFormat t : tries) {
            try {
                codec.configure(t, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                used = t;
                break;
            } catch (RuntimeException e) {
                last = e;
                L.w("encoder: configuración rechazada (" + (t.containsKey(LOW_LATENCY_KEYS[0]) ? "baja latencia" : "normal")
                        + (t.containsKey(QP_I_MIN) ? " + QP-I" : "") + "): " + e.getMessage());
                codec.reset();
            }
        }
        if (used == null) throw last;
        if (p.lowLatency && !used.containsKey(LOW_LATENCY_KEYS[0])) L.w("encoder sin modo de baja latencia");
        qpKeys = android.os.Build.VERSION.SDK_INT >= 31 && used.containsKey(QP_I_MIN);
        qpStats = android.os.Build.VERSION.SDK_INT >= 33 && used.containsKey(STATS_LEVEL);
        if (p.qpIMin > 0) {
            L.i(qpKeys ? "encoder: QP-I " + p.qpIMin + "-" + p.qpIMax + " al configurar"
                    + (qpStats ? "; pide el QP medio de cada IDR" : "")
                    : "encoder: sin claves de QP (Android " + android.os.Build.VERSION.SDK_INT + " < 12 o rechazadas)");
        }
        logVendorParams();
        if (p.lowLatency) enableVendorLowLatency();
        input = codec.createInputSurface();
        codec.start();
        running = true;
        drain = new Thread(this::drainLoop, "enc-drain");
        drain.start();
        return input;
    }

    /** El códec falló por su cuenta (no por stop): no sirve para otra sesión. */
    boolean failed() {
        return failed;
    }

    void requestKeyFrame() {
        requestKeyFrame(0);
    }

    /**
     * Pide un IDR. dip (0 < dip < 1): baja antes el bitrate a ese factor para que el IDR salga pequeño; se repone al
     * salir el IDR (o pasado DIP_TIMEOUT_NS).
     */
    void requestKeyFrame(double dip) {
        MediaCodec c = codec;
        if (c == null) return;
        if (dip > 0 && dip < 1) {
            dipSinceNs = System.nanoTime();
            dipFactor = dip;
            applyBitrate((int) (bitrate * dip));
        }
        Bundle b = new Bundle();
        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        try {
            c.setParameters(b);
        } catch (IllegalStateException ignored) {
        }
    }

    /** Bitrate nuevo (ABR, térmico). Durante una bajada por IDR se aplica con la bajada y queda para reponerlo. */
    void setBitrate(int bps) {
        bitrate = bps;
        double d = dipFactor;
        applyBitrate(d > 0 ? (int) (bps * d) : bps);
    }

    private void applyBitrate(int bps) {
        MediaCodec c = codec;
        if (c == null) return;
        Bundle b = new Bundle();
        b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps);
        try {
            c.setParameters(b);
        } catch (IllegalStateException ignored) {
        }
    }

    /** Fin de la bajada por IDR: el bitrate pedido vuelve al códec. */
    private void endDip() {
        dipFactor = 0;
        applyBitrate(bitrate);
    }

    /** Se configuró con las claves de QP de los I-frames (Android 12+): setQpIMin puede servir. */
    boolean qpControl() {
        return qpKeys;
    }

    /**
     * QP mínimo de los I-frames en marcha (Android 12+). false si no se pudo pasar al códec (sin claves o lo rechaza);
     * que lo respete o no se ve en el tamaño (o el QP medio) de los IDR siguientes.
     */
    boolean setQpIMin(int min) {
        MediaCodec c = codec;
        if (c == null || !qpKeys || android.os.Build.VERSION.SDK_INT < 31) return false;
        Bundle b = new Bundle();
        b.putInt(QP_I_MIN, min);
        b.putInt(QP_I_MAX, Math.max(min, p.qpIMax));
        try {
            c.setParameters(b);
            return true;
        } catch (IllegalStateException | IllegalArgumentException e) {
            return false;
        }
    }

    /** QP medio del último IDR, si el encoder lo informa (Android 13+), o -1. Hilo enc-drain, dentro de Sink.onFrame. */
    int lastIdrQp() {
        return lastIdrQp;
    }

    /** Factor de bitrate con que salió el último IDR (0 = sin bajada). Hilo enc-drain, dentro de Sink.onFrame. */
    double lastIdrDip() {
        return lastIdrDip;
    }

    // MediaFormat.KEY_VIDEO_QP_I_MIN/MAX (Android 12), KEY_VIDEO_ENCODING_STATISTICS_LEVEL y KEY_VIDEO_QP_AVERAGE
    // (Android 13). Con su valor literal, que es fijo: así se pueden usar con minSdk 16 sin avisos de API.
    private static final String QP_I_MIN = "video-qp-i-min";
    private static final String QP_I_MAX = "video-qp-i-max";
    private static final String STATS_LEVEL = "video-encoding-statistics-level";
    private static final String QP_AVERAGE = "video-qp-average";

    /** Copia de f con el control del tamaño de los IDR (QP-I y, en Android 13+, el QP medio), o null si no aplica. */
    private MediaFormat withIdrSizeControl(MediaFormat f) {
        if (p.qpIMin <= 0 || android.os.Build.VERSION.SDK_INT < 31) return null;
        MediaFormat out = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, p.width, p.height);
        for (String k : f.getKeys()) copyKey(f, out, k);
        out.setInteger(QP_I_MIN, p.qpIMin);
        out.setInteger(QP_I_MAX, Math.max(p.qpIMin, p.qpIMax));
        // Sin VIDEO_ENCODING_STATISTICS_LEVEL: en el c2.qti.avc.encoder (S25) hace que el formato de salida cambie
        // en cada frame (picture-type, QP medio), y cada cambio acababa en un SPS/PPS reenviado al coche, que
        // reinicia su decodificador con cada uno y pinta artefactos (coche, 2026-10-05).
        return out;
    }

    /** Copia de f con las claves de baja latencia. */
    private MediaFormat lowLatency(MediaFormat f) {
        MediaFormat fast = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, p.width, p.height);
        for (String k : f.getKeys()) copyKey(f, fast, k);
        // Claves de baja latencia conocidas por fabricante (un códec ignora las que no son suyas);
        // en Android 12+ además se activan las que el propio encoder declare (enableVendorLowLatency).
        for (String k : LOW_LATENCY_KEYS) fast.setInteger(k, 1);
        if (android.os.Build.VERSION.SDK_INT >= 30) fast.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        if (!p.maxClocks) fast.setInteger(MediaFormat.KEY_OPERATING_RATE, p.fps * 2);
        return fast;
    }

    /** QP medio del frame en el búfer idx (Android 13+ con estadísticas), o -1. Antes de liberar el búfer. */
    private int readQp(int idx) {
        if (!qpStats) return -1;
        try {
            MediaFormat of = codec.getOutputFormat(idx);
            if (of != null && of.containsKey(QP_AVERAGE)) return of.getInteger(QP_AVERAGE);
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    /**
     * Claves de baja latencia de encoder por fabricante:
     * - Qualcomm (Snapdragon): verificada en el c2.qti.avc.encoder.
     * - Samsung Exynos ("rtc-ext", como su clave de decodificador vendor.rtc-ext-dec-low-latency.enable).
     * - Genérica de Codec2 que usan algunos MediaTek/Unisoc/Amlogic.
     * Las de Exynos y la genérica no están verificadas en un móvil real: en Android 12+ manda lo que
     * declare el encoder (enableVendorLowLatency) y queda en el registro.
     */
    private static final String[] LOW_LATENCY_KEYS = {
            "vendor.qti-ext-enc-low-latency.enable",
            "vendor.rtc-ext-enc-low-latency.enable",
            "vendor.low-latency.enable",
    };

    /**
     * Encoder AVC por hardware que declare poder con w x h a fps, prefiriendo el del fabricante del
     * chip (c2.qti, c2.mtk, c2.exynos, OMX.*...): createEncoderByType puede devolver el de software
     * (c2.android.avc.encoder) en móviles con el de hardware detrás en la lista. null: el de por defecto.
     */
    private static String pickHardwareEncoder(int w, int h, int fps) {
        String fallback = null;
        try {
            for (MediaCodecInfo info : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                boolean avc = false;
                for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) avc = true;
                if (!avc) continue;
                String n = info.getName();
                String l = n.toLowerCase(java.util.Locale.ROOT);
                boolean hw = android.os.Build.VERSION.SDK_INT >= 29
                        ? info.isHardwareAccelerated() && !info.isSoftwareOnly()
                        : !(l.startsWith("omx.google.") || l.startsWith("c2.android.") || l.contains(".sw."));
                if (!hw) continue;
                MediaCodecInfo.VideoCapabilities vc = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
                if (vc != null && !vc.areSizeAndRateSupported(w, h, fps)) {
                    if (fallback == null) fallback = n;
                    continue;
                }
                return n;
            }
        } catch (RuntimeException e) {
            L.w("encoder: no se pudo listar los códecs: " + e);
        }
        return fallback;
    }

    /**
     * Android 12+: activa los parámetros de baja latencia que el encoder declare (Qualcomm, MediaTek,
     * Exynos y otros usan nombres distintos), por ejemplo vendor.xxx-low-latency.enable = 1.
     */
    private void enableVendorLowLatency() {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            Bundle b = new Bundle();
            StringBuilder sb = new StringBuilder();
            for (String n : codec.getSupportedVendorParameters()) {
                String l = n.toLowerCase(java.util.Locale.ROOT);
                boolean lowLat = l.contains("low-latency") || l.contains("lowlatency") || l.contains("low_latency");
                if (!lowLat || l.contains("-dec") || l.contains(".dec")) continue;
                if (!(l.endsWith(".enable") || l.endsWith(".value") || l.endsWith(".mode") || l.endsWith("enable"))) continue;
                b.putInt(n, 1);
                sb.append(sb.length() > 0 ? ", " : "").append(n);
            }
            if (b.isEmpty()) {
                L.i("encoder: sin parámetro de baja latencia del fabricante");
                return;
            }
            codec.setParameters(b);
            L.i("encoder: baja latencia del fabricante: " + sb);
        } catch (RuntimeException e) {
            L.w("encoder: no se pudo activar la baja latencia del fabricante: " + e.getMessage());
        }
    }

    /**
     * Diagnóstico: parámetros de fabricante que admite este encoder (los de latencia, rendimiento o
     * prioridad) y si el de baja latencia ha quedado aplicado en la configuración de entrada.
     */
    private void logVendorParams() {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (String n : codec.getSupportedVendorParameters()) {
                String l = n.toLowerCase(java.util.Locale.ROOT);
                if (l.contains("latency") || l.contains("perf") || l.contains("priority") || l.contains("lowlat")
                        || l.contains("realtime") || l.contains("slice") || l.contains("operating")) {
                    sb.append(sb.length() > 0 ? ", " : "").append(n);
                }
            }
            L.i("encoder: parámetros de fabricante útiles: " + (sb.length() > 0 ? sb : "ninguno"));
            MediaFormat in = codec.getInputFormat();
            L.i("encoder: entrada configurada: " + in);
        } catch (RuntimeException e) {
            L.w("encoder: sin lista de parámetros de fabricante: " + e.getMessage());
        }
    }

    private static void copyKey(MediaFormat from, MediaFormat to, String k) {
        switch (from.getValueTypeForKey(k)) {
            case MediaFormat.TYPE_INTEGER:
                to.setInteger(k, from.getInteger(k));
                break;
            case MediaFormat.TYPE_LONG:
                to.setLong(k, from.getLong(k));
                break;
            case MediaFormat.TYPE_FLOAT:
                to.setFloat(k, from.getFloat(k));
                break;
            case MediaFormat.TYPE_STRING:
                to.setString(k, from.getString(k));
                break;
            default:
                break;
        }
    }

    private void drainLoop() {
        LowLatency.boostCurrentThread();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            int idx;
            try {
                idx = codec.dequeueOutputBuffer(info, 10_000);
            } catch (IllegalStateException e) {
                if (running) {
                    failed = true;
                    L.w("encoder: el códec ha dejado de funcionar: " + e);
                }
                break;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                byte[] csd = spsPps(of.getByteBuffer("csd-0"), of.getByteBuffer("csd-1"));
                // El coche reinicia su decodificador con cada SPS/PPS: solo se manda si cambia de verdad.
                if (csd.length > 0 && !java.util.Arrays.equals(csd, lastCsd)) {
                    L.i("encoder output format: " + of);
                    lastCsd = csd;
                    sink.onCodecConfig(csd);
                }
                if (!intraRefreshChecked && p.intraRefreshFrames > 0) {
                    // Con CBR algunos códecs apagan el refresco intra: si lo informa, se comprueba que sigue.
                    intraRefreshChecked = true;
                    if (!of.containsKey(INTRA_REFRESH)) {
                        L.i("encoder: el códec no informa del intra-refresh (pedido " + p.intraRefreshFrames + " frames, " + bitrateMode + ")");
                    } else if (of.getInteger(INTRA_REFRESH) <= 0) {
                        L.w("encoder: intra-refresh apagado por el códec (pedido " + p.intraRefreshFrames + " frames, " + bitrateMode + ")");
                    } else {
                        L.i("encoder: intra-refresh " + of.getInteger(INTRA_REFRESH) + " frames confirmado (" + bitrateMode + ")");
                    }
                }
            } else if (idx >= 0) {
                ByteBuffer bb = codec.getOutputBuffer(idx);
                if (bb != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    if (outBuf.length < info.size) outBuf = new byte[info.size * 2];
                    bb.position(info.offset);
                    bb.get(outBuf, 0, info.size);
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if (key) {
                        lastIdrQp = readQp(idx);
                        double d = dipFactor;
                        lastIdrDip = d;
                        if (d > 0) endDip();
                    } else if (dipFactor > 0 && System.nanoTime() - dipSinceNs > DIP_TIMEOUT_NS) {
                        endDip();
                    }
                    // Tiempo de codificación: la marca de presentación es la hora de dibujo (nanoTime).
                    long encMs = (System.nanoTime() / 1000 - info.presentationTimeUs) / 1000;
                    if (encMs >= 0 && encMs < 1000) PerfTrace.event("enc_ms", encMs);
                    sink.onFrame(outBuf, info.size, key, info.presentationTimeUs);
                }
                codec.releaseOutputBuffer(idx, false);
            }
        }
    }

    void stop() {
        running = false;
        if (drain != null) {
            try {
                drain.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
            }
            codec.release();
            codec = null;
        }
        if (input != null) input.release();
    }

    /** SPS‖PPS de csd-0 y csd-1; si csd-0 ya trae el PPS de csd-1 (pasa en algunos encoders), no lo repite. */
    static byte[] spsPps(ByteBuffer a, ByteBuffer b) {
        byte[] sps = concat(a, null);
        byte[] pps = concat(b, null);
        if (pps.length > 0 && indexOf(sps, pps) >= 0) return sps;
        return concat(a, b);
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] concat(ByteBuffer a, ByteBuffer b) {
        int la = a != null ? a.remaining() : 0;
        int lb = b != null ? b.remaining() : 0;
        byte[] out = new byte[la + lb];
        if (la > 0) a.duplicate().get(out, 0, la);
        if (lb > 0) b.duplicate().get(out, la, lb);
        return out;
    }

    private static int profileConst(String name) {
        switch (name) {
            case "main":
                return MediaCodecInfo.CodecProfileLevel.AVCProfileMain;
            case "high":
                return MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
            default:
                return MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
        }
    }

    /** Nivel mínimo H.264 por macrobloques/segundo. */
    private static int levelFor(int w, int h, int fps) {
        long mbs = (long) ((w + 15) / 16) * ((h + 15) / 16) * fps;
        if (mbs <= 108_000) return MediaCodecInfo.CodecProfileLevel.AVCLevel31;
        if (mbs <= 216_000) return MediaCodecInfo.CodecProfileLevel.AVCLevel32;
        if (mbs <= 245_760) return MediaCodecInfo.CodecProfileLevel.AVCLevel4;
        if (mbs <= 522_240) return MediaCodecInfo.CodecProfileLevel.AVCLevel42;
        return MediaCodecInfo.CodecProfileLevel.AVCLevel51;
    }
}
