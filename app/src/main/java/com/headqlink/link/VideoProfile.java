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
 * - Coche (el recomendado): lo que pide el coche en VIDEO_ARGS, con "último frame": sus fps (30 en el C10, y Android
 *   Auto también a 30) y su bitrate (5,08 Mbps en el C10), a la resolución de su pantalla, sin optimizaciones de
 *   latencia ni relojes al máximo. Es lo que el coche muestra, con el mínimo de calor y batería. Con «Fluidez» a 60
 *   (Ajustes de imagen), 60 fps (AA a 60) y bitrate max(el del coche, 8 Mbit/s) con tope de 12 Mbit/s: el C10 real
 *   acepta 60 fps hasta ~16 Mbit/s (como el HeadQLink original), a cambio de más calor (ThermalPolicy lo baja a 30).
 * - Muy alto: "último frame" (recodificar en el móvil) a 60 fps y resolución completa, con las
 *   optimizaciones de latencia y los relojes del encoder al máximo; bitrate adaptable 5-16 Mbps.
 * - Alto: lo mismo sin optimizaciones de latencia ni relojes al máximo (menos batería y calor; el
 *   encoder tarda unos ms más: 16,9 frente a 11,9 ms de mediana en el banco de pruebas); 5-14 Mbps.
 * - Medio: "último frame" a 45 fps fijos (AA a 60, un frame por tic) y 720p, 5 Mbps.
 * - Básico: reenvío directo del vídeo de AA con freno, a 30 fps y 720p (no recodifica).
 * - Muy bajo: "último frame" a 20 fps fijos, 720p y 2,5 Mbps (lo mínimo para el enlace y la batería).
 * El recomendado se elige según lo que declara el codificador por hardware del móvil y su RAM (recommend).
 */
final class VideoProfile {
    static final String CAR = "coche";
    static final String MAX = "muy_alto";
    static final String HIGH = "alto";
    static final String MEDIUM = "medio";
    static final String BASIC = "basico";
    static final String LOW = "muy_bajo";
    static final String[] ALL = {CAR, MAX, HIGH, MEDIUM, BASIC, LOW};
    /** Coche: fps y bitrate si el coche no manda VIDEO_ARGS (como el C10). */
    static final int CAR_DEFAULT_FPS = 30;
    static final int CAR_DEFAULT_BPS = 5_000_000;
    /** «Fluidez» (Config.FLUIDITY): 30 = lo que pide el coche (menos calor, recomendado); 60 = máxima fluidez. */
    static final int FLUID_30 = 30;
    static final int FLUID_60 = 60;
    /** Fluidez 60: bitrate max(el del coche, FLUID_60_MIN_BPS) con tope FLUID_60_MAX_BPS. */
    static final int FLUID_60_MIN_BPS = 8_000_000;
    static final int FLUID_60_MAX_BPS = 12_000_000;

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
    /** Coche: fps y bitrate, los que pida el coche en VIDEO_ARGS (los de arriba, si no los manda). */
    final boolean followsCar;
    /** Coche: «Fluidez» elegida (FLUID_30 o FLUID_60); en los demás perfiles, FLUID_30 sin efecto. */
    final int fluidity;

    private VideoProfile(String id, boolean reencode, int fps, boolean hd720, boolean fixedRate, boolean boost,
                         int bitrate, int minBitrate, int maxBitrate) {
        this(id, reencode, fps, hd720, fixedRate, boost, bitrate, minBitrate, maxBitrate, false, FLUID_30);
    }

    private VideoProfile(String id, boolean reencode, int fps, boolean hd720, boolean fixedRate, boolean boost,
                         int bitrate, int minBitrate, int maxBitrate, boolean followsCar, int fluidity) {
        this.id = id;
        this.reencode = reencode;
        this.fps = fps;
        this.hd720 = hd720;
        this.fixedRate = fixedRate;
        this.boost = boost;
        this.bitrate = bitrate;
        this.minBitrate = minBitrate;
        this.maxBitrate = maxBitrate;
        this.followsCar = followsCar;
        this.fluidity = fluidity;
    }

    /**
     * El perfil con la «Fluidez» elegida. Solo cambia en Coche (y en Automático cuando el recomendado es Coche): a 60,
     * fps 60 y bitrate de partida 8 Mbit/s (para las barras de «En directo»); los demás perfiles vuelven tal cual.
     */
    VideoProfile withFluidity(int fluidity) {
        int f = fluidity >= FLUID_60 ? FLUID_60 : FLUID_30;
        if (!followsCar || f == this.fluidity) return this;
        int fps = f == FLUID_60 ? FLUID_60 : CAR_DEFAULT_FPS;
        int bps = f == FLUID_60 ? FLUID_60_MIN_BPS : CAR_DEFAULT_BPS;
        return new VideoProfile(id, reencode, fps, hd720, fixedRate, boost, bps, bps, bps, true, f);
    }

    boolean adaptiveBitrate() {
        return maxBitrate > minBitrate;
    }

    /**
     * fps de la sesión: los del perfil o, en Coche, los que pide el coche (VIDEO_ARGS FrameRate, entre 10 y 60); con
     * Fluidez 60, 60 pida lo que pida el coche (la cabecera de vídeo sigue repitiendo su FrameRate).
     */
    int fpsFor(int carFps) {
        if (!followsCar) return fps;
        if (fluidity >= FLUID_60) return FLUID_60;
        if (carFps <= 0) return fps;
        return Math.max(10, Math.min(60, carFps));
    }

    /**
     * Bitrate inicial del "último frame": el del perfil o, en Coche, el que pide el coche (VIDEO_ARGS BitRate; con
     * Fluidez 60, max(el del coche, 8 Mbit/s) con tope de 12). Sin bitrate en el perfil (Básico recodificando por
     * ajuste manual), 5 Mbps a 720p y 8 a resolución completa.
     */
    int startBitrate(int carBitrate, int videoW) {
        if (followsCar) {
            int car = carBitrate > 0 ? carBitrate : CAR_DEFAULT_BPS;
            if (fluidity >= FLUID_60) return Math.min(FLUID_60_MAX_BPS, Math.max(car, FLUID_60_MIN_BPS));
            return car;
        }
        if (bitrate > 0) return bitrate;
        return videoW <= 1280 ? 5_000_000 : 8_000_000;
    }

    /** Perfil por su id; uno desconocido (de una versión vieja o mal escrito) es Coche. */
    static VideoProfile of(String id) {
        switch (id == null ? "" : id) {
            case MAX:
                return new VideoProfile(MAX, true, 60, false, false, true, 10_000_000, 5_000_000, 16_000_000);
            case HIGH:
                return new VideoProfile(HIGH, true, 60, false, false, false, 10_000_000, 5_000_000, 14_000_000);
            case MEDIUM:
                return new VideoProfile(MEDIUM, true, 45, true, true, true, 5_000_000, 5_000_000, 5_000_000);
            case BASIC:
                return new VideoProfile(BASIC, false, 30, true, false, false, 0, 0, 0);
            case LOW:
                return new VideoProfile(LOW, true, 20, true, true, false, 2_500_000, 2_500_000, 2_500_000);
            default:
                return new VideoProfile(CAR, true, CAR_DEFAULT_FPS, false, false, false,
                        CAR_DEFAULT_BPS, CAR_DEFAULT_BPS, CAR_DEFAULT_BPS, true, FLUID_30);
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
            case MAX:
                return Str.get(R.string.hql_profile_max);
            case HIGH:
                return Str.get(R.string.hql_profile_high);
            case MEDIUM:
                return Str.get(R.string.hql_profile_medium);
            case BASIC:
                return Str.get(R.string.hql_profile_basic);
            case LOW:
                return Str.get(R.string.hql_profile_low);
            default:
                return Str.get(R.string.hql_profile_car);
        }
    }

    static String detail(String id) {
        switch (id) {
            case MAX:
                return Str.get(R.string.hql_profile_max_detail);
            case HIGH:
                return Str.get(R.string.hql_profile_high_detail);
            case MEDIUM:
                return Str.get(R.string.hql_profile_medium_detail);
            case BASIC:
                return Str.get(R.string.hql_profile_basic_detail);
            case LOW:
                return Str.get(R.string.hql_profile_low_detail);
            default:
                return Str.get(R.string.hql_profile_car_detail);
        }
    }

    private static String recommended;
    private static String reason = "";

    /** Perfil recomendado para este móvil (se calcula una vez). */
    static synchronized String recommended(Context ctx) {
        if (recommended != null) return recommended;
        boolean enc1080p30 = hwSupports(true, 1920, 1088, 30);
        boolean dec1080p30 = hwSupports(false, 1920, 1088, 30);
        boolean enc720p45 = hwSupports(true, 1280, 720, 45);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ctx.getSystemService(ActivityManager.class).getMemoryInfo(mi);
        long ramGb = Math.round(mi.totalMem / 1e9);
        String soc = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : Build.HARDWARE;
        recommended = recommend(enc1080p30, dec1080p30, enc720p45, ramGb);
        reasonSoc = soc;
        reasonRam = ramGb;
        reasonFlags = new boolean[]{enc1080p30, dec1080p30, enc720p45};
        reason = String.format(Locale.US, "%s · %d GB · enc1080p30 %b · dec1080p30 %b · enc720p45 %b",
                soc, ramGb, enc1080p30, dec1080p30, enc720p45);
        L.i("perfil recomendado: " + recommended + " (" + reason + ")");
        return recommended;
    }

    /**
     * Regla del recomendado (pura, la prueban los tests). Coche si el móvil codifica y decodifica 1080p a 30 fps por
     * hardware y tiene al menos 4 GB: es lo que pide el C10 (30 fps, ~5 Mbps). Antes era Muy alto (60 fps, 5-16 Mbps):
     * el coche no mostraba más y el móvil llegaba al estado térmico crítico (viaje del 2026-10-05). Si no, Medio
     * (720p) o Básico.
     */
    static String recommend(boolean enc1080p30, boolean dec1080p30, boolean enc720p45, long ramGb) {
        if (enc1080p30 && dec1080p30 && ramGb >= 4) return CAR;
        if (enc720p45 && ramGb >= 3) return MEDIUM;
        return BASIC;
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
