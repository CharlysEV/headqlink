package com.headqlink.link;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Encoder H.264 con entrada Surface. Entrega SPS/PPS y frames Annex-B a un Sink. */
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

        @Override
        public String toString() {
            return width + "x" + height + "@" + fps + " " + (bitrate / 1000) + "kbps " + profile
                    + (cbr ? " CBR" : " VBR") + " gop=" + iFrameIntervalSec + "s"
                    + (intraRefreshFrames > 0 ? " intra-refresh=" + intraRefreshFrames : "") + " prepend=" + prependSpsPps
                    + (noRepeat ? " sin-repetir" : "") + (maxClocks ? " relojes-max" : "");
        }
    }

    private final Params p;
    private final Sink sink;
    private MediaCodec codec;
    private Surface input;
    private Thread drain;
    private volatile boolean running;
    private byte[] outBuf = new byte[512 * 1024];

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
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, p.cbr
                ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
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

        String name = pickHardwareEncoder(p.width, p.height, p.fps);
        codec = name != null ? MediaCodec.createByCodecName(name) : MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        L.i("encoder " + codec.getName() + " " + p + (p.lowLatency ? " · baja latencia" : ""));
        if (p.lowLatency) {
            MediaFormat fast = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, p.width, p.height);
            for (String k : f.getKeys()) copyKey(f, fast, k);
            // Claves de baja latencia conocidas por fabricante (un códec ignora las que no son suyas);
            // en Android 12+ además se activan las que el propio encoder declare (enableVendorLowLatency).
            for (String k : LOW_LATENCY_KEYS) fast.setInteger(k, 1);
            if (android.os.Build.VERSION.SDK_INT >= 30) fast.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            if (!p.maxClocks) fast.setInteger(MediaFormat.KEY_OPERATING_RATE, p.fps * 2);
            try {
                codec.configure(fast, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (RuntimeException e) {
                // Un encoder que no admite las claves de baja latencia: configuración normal.
                L.w("encoder sin modo de baja latencia: " + e.getMessage());
                codec.reset();
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
        } else {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
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

    void requestKeyFrame() {
        MediaCodec c = codec;
        if (c == null) return;
        Bundle b = new Bundle();
        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        try {
            c.setParameters(b);
        } catch (IllegalStateException ignored) {
        }
    }

    void setBitrate(int bps) {
        MediaCodec c = codec;
        if (c == null) return;
        Bundle b = new Bundle();
        b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps);
        try {
            c.setParameters(b);
        } catch (IllegalStateException ignored) {
        }
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
                break;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                L.i("encoder output format: " + of);
                byte[] csd = concat(of.getByteBuffer("csd-0"), of.getByteBuffer("csd-1"));
                if (csd.length > 0) sink.onCodecConfig(csd);
            } else if (idx >= 0) {
                ByteBuffer bb = codec.getOutputBuffer(idx);
                if (bb != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    if (outBuf.length < info.size) outBuf = new byte[info.size * 2];
                    bb.position(info.offset);
                    bb.get(outBuf, 0, info.size);
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
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
