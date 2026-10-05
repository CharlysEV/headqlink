package com.headqlink.link;

import java.util.Arrays;
import java.util.Locale;

/**
 * Qué vídeo hace falta para una sesión (qdauto §4.7): modo, perfil, tamaños, fps, lo que pidió el coche y la huella de
 * los ajustes. Si el plan de una sesión nueva es igual al de la VideoPipeline viva (y esta está sana), se reutiliza
 * sin reiniciar Android Auto; si no, se recrea. Clase de valor sin Android (la prueban los tests); compute() lee los
 * ajustes y calcula los tamaños exactamente como SspSession.startVideo.
 */
final class VideoPlan {
    /** Lo que el coche dijo en CAR_INFO y VIDEO_ARGS (0 = no llegó). */
    static final class Car {
        final int carW;
        final int carH;
        final int argsW;
        final int argsH;
        final int argsFps;
        final int argsBitrate;
        final int argsInterval;
        final int encodingType;
        final boolean hasArgs;

        Car(int carW, int carH, int argsW, int argsH, int argsFps, int argsBitrate, int argsInterval, int encodingType, boolean hasArgs) {
            this.carW = carW;
            this.carH = carH;
            this.argsW = argsW;
            this.argsH = argsH;
            this.argsFps = argsFps;
            this.argsBitrate = argsBitrate;
            this.argsInterval = argsInterval;
            this.encodingType = encodingType;
            this.hasArgs = hasArgs;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Car)) return false;
            Car c = (Car) o;
            return carW == c.carW && carH == c.carH && argsW == c.argsW && argsH == c.argsH && argsFps == c.argsFps
                    && argsBitrate == c.argsBitrate && argsInterval == c.argsInterval && encodingType == c.encodingType
                    && hasArgs == c.hasArgs;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(new Object[]{carW, carH, argsW, argsH, argsFps, argsBitrate, argsInterval, encodingType, hasArgs});
        }

        @Override
        public String toString() {
            return "coche " + carW + "x" + carH + (hasArgs ? " · VIDEO_ARGS " + argsW + "x" + argsH + "@" + argsFps + " "
                    + argsBitrate + "bps intervalo " + argsInterval + " tipo " + encodingType : " · sin VIDEO_ARGS");
        }
    }

    final String mode;
    final String profileId;
    final boolean reencode;
    final boolean extended;
    /** Reenvío directo del H.264 de AA (sin encoder propio). */
    final boolean passthrough;
    final int videoW;
    final int videoH;
    final int fps;
    final Car car;
    /** Config.videoFingerprint(): todo lo de los ajustes que cambia el vídeo. */
    final String settings;

    VideoPlan(String mode, String profileId, boolean reencode, boolean extended, boolean passthrough,
              int videoW, int videoH, int fps, Car car, String settings) {
        this.mode = mode;
        this.profileId = profileId;
        this.reencode = reencode;
        this.extended = extended;
        this.passthrough = passthrough;
        this.videoW = videoW;
        this.videoH = videoH;
        this.fps = fps;
        this.car = car;
        this.settings = settings;
    }

    /** Plan para los ajustes actuales y lo que pidió el coche. */
    static VideoPlan compute(Config cfg, Car car) {
        String mode = cfg.mode();
        int[] dims = cfg.videoSize(car.carW, car.carH, car.argsW, car.argsH);
        if (Config.isAa(mode) && cfg.getInt(Config.WIDTH) <= 0) {
            // Android Auto: el tamaño lo decide el perfil (completo o 720p con la proporción del coche).
            dims = cfg.videoProfile().videoSize(car.carW > 0 ? car.carW : 1920, car.carH > 0 ? car.carH : 882);
        }
        boolean extended = Config.MODE_AA_EXT.equals(mode);
        boolean aa = Config.isAa(mode);
        boolean reencode = extended || (Config.MODE_AA.equals(mode) && cfg.aaReencode());
        boolean passthrough = Config.MODE_AA.equals(mode) && !cfg.aaReencode();
        int fps = aa ? cfg.aaFps(car.argsFps) : cfg.fps(car.argsFps);
        return new VideoPlan(mode, aa ? cfg.videoProfile().id : "", reencode, extended, passthrough,
                dims[0], dims[1], fps, car, cfg.videoFingerprint());
    }

    /** Por qué este plan no sirve donde otro (para el log al recrear), o "" si son iguales. */
    String differenceFrom(VideoPlan o) {
        if (o == null) return "sin vídeo";
        StringBuilder sb = new StringBuilder();
        if (!mode.equals(o.mode)) sb.append("modo ").append(o.mode).append("→").append(mode).append(' ');
        if (!profileId.equals(o.profileId)) sb.append("perfil ").append(o.profileId).append("→").append(profileId).append(' ');
        if (reencode != o.reencode || passthrough != o.passthrough || extended != o.extended) sb.append("forma de enviar ");
        if (videoW != o.videoW || videoH != o.videoH) sb.append("tamaño ").append(o.videoW).append('x').append(o.videoH)
                .append("→").append(videoW).append('x').append(videoH).append(' ');
        if (fps != o.fps) sb.append("fps ").append(o.fps).append("→").append(fps).append(' ');
        if (!car.equals(o.car)) sb.append("coche (").append(o.car).append(" → ").append(car).append(") ");
        if (!settings.equals(o.settings)) sb.append("ajustes ");
        return sb.toString().trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VideoPlan)) return false;
        VideoPlan p = (VideoPlan) o;
        return reencode == p.reencode && extended == p.extended && passthrough == p.passthrough && videoW == p.videoW
                && videoH == p.videoH && fps == p.fps && mode.equals(p.mode) && profileId.equals(p.profileId)
                && car.equals(p.car) && settings.equals(p.settings);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{mode, profileId, reencode, extended, passthrough, videoW, videoH, fps, car, settings});
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "%s%s %s %dx%d@%d · %s", mode, profileId.isEmpty() ? "" : "/" + profileId,
                passthrough ? "reenvío directo" : reencode ? "último frame" : "encoder", videoW, videoH, fps, car);
    }
}
