package com.headqlink.link;

import com.andrerinas.openheadunit.R;

/**
 * Avisos de radar por voz (puro; lo prueban los tests). Con un radar delante a 700 m o menos: «Radar a 700 metros,
 * límite 80», una vez por radar. Y si a 400 m o menos se va por encima del límite: «Vas a 95, límite 80», una vez.
 * Los textos se dan como ids y argumentos, para que RoadInfo los diga con Str.
 */
final class RadarVoice {
    static final double ANNOUNCE_M = 700;
    static final double OVER_M = 400;
    static final int OVER_MARGIN = 4;

    /** Un aviso: id del texto y sus argumentos. */
    static final class Alert {
        final int text;
        final Object[] args;

        Alert(int text, Object... args) {
            this.text = text;
            this.args = args;
        }
    }

    private double saidLat = Double.NaN;
    private double saidLon = Double.NaN;
    private boolean saidOver;

    /**
     * Un tic con el radar más cercano delante (camM < 0: ninguno). Devuelve el aviso que toca ahora, o null.
     */
    Alert onTick(double camM, int camLimit, double camLat, double camLon, double speedKmh) {
        if (camM < 0 || camM > ANNOUNCE_M) return null;
        boolean same = !Double.isNaN(saidLat) && Math.abs(saidLat - camLat) < 0.0003 && Math.abs(saidLon - camLon) < 0.0003;
        if (!same) {
            saidLat = camLat;
            saidLon = camLon;
            saidOver = false;
            int m = (int) (Math.round(camM / 100.0) * 100);
            if (m <= 0) m = 100;
            return camLimit > 0 ? new Alert(R.string.hql_voice_camera_limit, m, camLimit) : new Alert(R.string.hql_voice_camera, m);
        }
        if (!saidOver && camLimit > 0 && camM <= OVER_M && speedKmh > camLimit + OVER_MARGIN) {
            saidOver = true;
            return new Alert(R.string.hql_voice_over_limit, (int) Math.round(speedKmh), camLimit);
        }
        return null;
    }
}
