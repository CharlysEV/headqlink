package com.headqlink.link;

import android.content.Context;
import android.view.Surface;

/** Origen de la imagen que se envía al coche. */
interface VideoSource {
    /** Receptor de vídeo ya codificado (modo reenvío directo). */
    interface EncodedSink {
        void onAccessUnit(byte[] data, int offset, int length);
    }

    /** true: dibuja en la Surface de nuestro encoder; false: entrega H.264 ya codificado. */
    default boolean usesEncoder() {
        return true;
    }

    /** Solo fuentes sin encoder: tamaño del vídeo que entregan, o null si aún no se conoce. */
    default int[] passthroughSize() {
        return null;
    }

    /** Solo fuentes sin encoder: empezar a entregar unidades H.264. */
    default void startPassthrough(EncodedSink sink) {
    }

    /** Solo fuentes sin encoder: pedir un fotograma clave al origen. */
    default void requestKeyFrame() {
    }

    /** fps objetivo (lo que pide el coche en VIDEO_ARGS, o el ajuste manual). */
    default void setTargetFps(int fps) {
    }

    /** El coche cambia entre modo día y noche (Global/DarkModeOn). */
    default void onCarDarkMode(boolean dark) {
    }

    /** Tamaño de la pantalla del coche (CAR_INFO), para traducir coordenadas táctiles. */
    default void setCarSize(int width, int height) {
    }

    /** Tamaño del vídeo que se envía al coche (puede ser menor que su pantalla: perfiles a 720p). */
    default void setVideoSize(int width, int height) {
    }

    /**
     * Toque del coche con todos sus dedos. action: acción global con el formato de MotionEvent
     * (acción | índice << 8). Por defecto, solo el primer dedo (id 0) por touch().
     */
    default void touchMulti(int action, Proto.Finger[] fingers) {
        for (Proto.Finger f : fingers) {
            if (f != null && f.id == 0) touch(f.action, f.x, f.y);
        }
    }

    /** Fuentes con encoder que dibujan solo cuando el enlace tiene sitio ("último frame"). */
    default void setLinkGate(GlFrameRelay.Gate gate) {
    }

    /** Empieza a dibujar en la Surface del encoder. */
    void start(Surface surface, int width, int height, int fps, String info);

    /** Toque del coche, en píxeles del vídeo. action: 1 down, 2 up, 3 move. */
    void touch(int action, float x, float y);

    void setStatus(String status);

    /** Resumen de jitter de dibujo, o null si la fuente no lo mide. */
    String takeJitterSummary();

    void stop();

    static VideoSource create(Context ctx, Config cfg) {
        switch (cfg.mode()) {
            case Config.MODE_APP:
                return new AppSource(ctx, cfg.targetPackage(), cfg.dpi());
            case Config.MODE_AA:
                return new AaPassthroughSource(ctx, cfg.aaDpi(), cfg.aaReencode());
            case Config.MODE_AA_EXT:
                return new AaPassthroughSource(ctx, cfg.aaDpi(), true, true);
            default:
                return new PatternSource(null);
        }
    }
}
