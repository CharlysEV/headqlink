package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.List;
import java.util.Locale;

/**
 * Perfiles de imagen:
 * - Muy alto: "último frame" (recodificar en el móvil) a 60 fps y resolución completa, con las
 *   optimizaciones de latencia y los relojes del encoder al máximo; bitrate adaptable 5-16 Mbps.
 * - Alto: lo mismo sin optimizaciones de latencia ni relojes al máximo (menos batería y calor; el
 *   encoder tarda unos ms más: 16,9 frente a 11,9 ms de mediana en el banco de pruebas); 5-14 Mbps.
 * - Medio: "último frame" a 45 fps fijos (AA a 60, un frame por tic) y 720p, 5 Mbps.
 * - Básico: reenvío directo del vídeo de AA con freno, a 30 fps y 720p (no recodifica).
 * - Muy bajo: "último frame" a 20 fps fijos, 720p y 2,5 Mbps (lo mínimo para el enlace y la batería).
 * El recomendado se elige según lo que declara el codificador por hardware del móvil y su RAM.
 */
final class VideoProfile {
    static final String MAX = "muy_alto";
    static final String HIGH = "alto";
    static final String MEDIUM = "medio";
    static final String BASIC = "basico";
    static final String LOW = "muy_bajo";
    static final String[] ALL = {MAX, HIGH, MEDIUM, BASIC, LOW};

    final String id;
    final boolean reencode;
    final int fps;
    /** true: 720p (el vídeo al coche a 1280 de ancho, que el coche escala a su pantalla). */
    final boolean hd720;
    /** Cadencia fija: fps por debajo de los de AA, repartidos en intervalos iguales (GlFrameRelay). */
    final boolean fixedRate;
    /** Optimizaciones de latencia (LowLatency) y relojes del encoder al máximo, salvo ajuste manual. */
    final boolean boost;
    /** Bitrate inicial del encoder (bps). */
    final int bitrate;
    /** > bitrate: adaptable entre minBitrate y maxBitrate según el enlace (SspSession.adaptBitrate). */
    final int minBitrate;
    final int maxBitrate;

    private VideoProfile(String id, boolean reencode, int fps, boolean hd720, boolean fixedRate, boolean boost,
                         int bitrate, int minBitrate, int maxBitrate) {
        this.id = id;
        this.reencode = reencode;
        this.fps = fps;
        this.hd720 = hd720;
        this.fixedRate = fixedRate;
        this.boost = boost;
        this.bitrate = bitrate;
        this.minBitrate = minBitrate;
        this.maxBitrate = maxBitrate;
    }

    boolean adaptiveBitrate() {
        return maxBitrate > minBitrate;
    }

    static VideoProfile of(String id) {
        switch (id == null ? "" : id) {
            case HIGH:
                return new VideoProfile(HIGH, true, 60, false, false, false, 10_000_000, 5_000_000, 14_000_000);
            case MEDIUM:
                return new VideoProfile(MEDIUM, true, 45, true, true, true, 5_000_000, 5_000_000, 5_000_000);
            case BASIC:
                return new VideoProfile(BASIC, false, 30, true, false, false, 0, 0, 0);
            case LOW:
                return new VideoProfile(LOW, true, 20, true, true, false, 2_500_000, 2_500_000, 2_500_000);
            default:
                return new VideoProfile(MAX, true, 60, false, false, true, 10_000_000, 5_000_000, 16_000_000);
        }
    }

    /** Tamaño del vídeo que se envía al coche, con la proporción de su pantalla (p. ej. 1920x882 → 1280x588). */
    int[] videoSize(int carW, int carH) {
        if (!hd720 || carW <= 1280) return new int[]{carW, carH};
        int w = 1280;
        int h = Math.round(1280f * carH / carW / 2) * 2;
        return new int[]{w, h};
    }

    static String title(String id) {
        switch (id) {
            case HIGH:
                return Str.get(R.string.hql_profile_high);
            case MEDIUM:
                return Str.get(R.string.hql_profile_medium);
            case BASIC:
                return Str.get(R.string.hql_profile_basic);
            case LOW:
                return Str.get(R.string.hql_profile_low);
            default:
                return Str.get(R.string.hql_profile_max);
        }
    }

    static String detail(String id) {
        switch (id) {
            case HIGH:
                return Str.get(R.string.hql_profile_high_detail);
            case MEDIUM:
                return Str.get(R.string.hql_profile_medium_detail);
            case BASIC:
                return Str.get(R.string.hql_profile_basic_detail);
            case LOW:
                return Str.get(R.string.hql_profile_low_detail);
            default:
                return Str.get(R.string.hql_profile_max_detail);
        }
    }

    private static String recommended;
    private static String reason = "";

    /** Perfil recomendado para este móvil (se calcula una vez). */
    static synchronized String recommended(Context ctx) {
        if (recommended != null) return recommended;
        boolean enc1080p60 = hwSupports(true, 1920, 1088, 60);
        boolean dec1080p60 = hwSupports(false, 1920, 1088, 60);
        boolean enc720p30 = hwSupports(true, 1280, 720, 45);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ctx.getSystemService(ActivityManager.class).getMemoryInfo(mi);
        long ramGb = Math.round(mi.totalMem / 1e9);
        String soc = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : Build.HARDWARE;
        if (enc1080p60 && dec1080p60 && ramGb >= 6) recommended = MAX;
        else if (enc720p30 && ramGb >= 3) recommended = MEDIUM;
        else recommended = BASIC;
        reasonSoc = soc;
        reasonRam = ramGb;
        reasonFlags = new boolean[]{enc1080p60, dec1080p60, enc720p30};
        reason = String.format(Locale.US, "%s · %d GB · enc1080p60 %b · dec1080p60 %b · enc720p45 %b",
                soc, ramGb, enc1080p60, dec1080p60, enc720p30);
        L.i("perfil recomendado: " + recommended + " (" + reason + ")");
        return recommended;
    }

    private static String reasonSoc = "";
    private static long reasonRam;
    private static boolean[] reasonFlags;

    /** Por qué se recomienda ese perfil (en el idioma de la app). */
    static String reason() {
        boolean[] f = reasonFlags;
        if (f == null) return reason;
        String y = Str.get(R.string.hql_yes);
        String n = Str.get(R.string.hql_no);
        return Str.get(R.string.hql_profile_reason, reasonSoc, reasonRam, f[0] ? y : n, f[1] ? y : n, f[2] ? y : n);
    }

    /** ¿Hay un códec AVC por hardware que declare poder con w x h a fps? */
    private static boolean hwSupports(boolean encoder, int w, int h, int fps) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder() != encoder) continue;
                if (Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated()) continue;
                boolean avc = false;
                for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase("video/avc")) avc = true;
                if (!avc) continue;
                MediaCodecInfo.VideoCapabilities vc = info.getCapabilitiesForType("video/avc").getVideoCapabilities();
                if (vc == null) continue;
                if (Build.VERSION.SDK_INT >= 29) {
                    List<MediaCodecInfo.VideoCapabilities.PerformancePoint> pps = vc.getSupportedPerformancePoints();
                    if (pps != null && !pps.isEmpty()) {
                        MediaCodecInfo.VideoCapabilities.PerformancePoint want =
                                new MediaCodecInfo.VideoCapabilities.PerformancePoint(w, h, fps);
                        for (MediaCodecInfo.VideoCapabilities.PerformancePoint pp : pps) if (pp.covers(want)) return true;
                        continue;
                    }
                }
                if (vc.areSizeAndRateSupported(w, h, fps)) return true;
            }
        } catch (RuntimeException e) {
            L.w("perfil: no se pudo consultar los códecs: " + e);
        }
        return false;
    }
}
