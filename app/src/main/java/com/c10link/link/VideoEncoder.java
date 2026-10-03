package com.c10link.link;

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

        @Override
        public String toString() {
            return width + "x" + height + "@" + fps + " " + (bitrate / 1000) + "kbps " + profile
                    + (cbr ? " CBR" : " VBR") + " gop=" + iFrameIntervalSec + "s"
                    + (intraRefreshFrames > 0 ? " intra-refresh=" + intraRefreshFrames : "") + " prepend=" + prependSpsPps;
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
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, p.repeatAfterUs > 0 ? p.repeatAfterUs : 1_000_000L / p.fps * 3);
        if (p.prependSpsPps) f.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
        f.setInteger(MediaFormat.KEY_PROFILE, profileConst(p.profile));
        f.setInteger(MediaFormat.KEY_LEVEL, levelFor(p.width, p.height, p.fps));

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        L.i("encoder " + codec.getName() + " " + p);
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
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

    private void drainLoop() {
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
