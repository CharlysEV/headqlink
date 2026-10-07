package com.headqlink.link;

import java.util.ArrayList;
import java.util.List;

/**
 * Paradas de un viaje a partir de su recorrido con tiempos (TripLog: un punto cada vez que el coche se mueve más de
 * 30 m, con el segundo del viaje). Un hueco largo entre dos puntos casi en el mismo sitio es que el coche estuvo parado
 * ahí; un hueco largo con mucha distancia es el GPS sin datos (móvil bloqueado, túnel), no una parada. Sin Android: se
 * prueba en el PC.
 */
final class TripStops {
    /** Menos de esto parado no es una parada (un semáforo, un atasco). */
    static final double MIN_STOP_SEC = 120;
    /** Entre el punto de antes y el de después de la parada: si se separan más, fue el GPS sin datos. */
    static final double MAX_STOP_MOVE_M = 250;
    /** Dos paradas seguidas a menos de esto son la misma (el coche se movió un poco para aparcar). */
    static final double MERGE_M = 120;

    static final class Stop {
        final double lat;
        final double lon;
        /** Segundo del viaje en que empieza la parada y cuánto dura. */
        final double atSec;
        final double durationSec;
        /** Km del viaje (por el recorrido) donde está. */
        final double km;

        Stop(double lat, double lon, double atSec, double durationSec, double km) {
            this.lat = lat;
            this.lon = lon;
            this.atSec = atSec;
            this.durationSec = durationSec;
            this.km = km;
        }
    }

    private TripStops() {
    }

    /** Paradas del recorrido (puntos {lat, lon, segundo}); vacío si el recorrido no lleva tiempos (viajes antiguos). */
    static List<Stop> find(double[][] track) {
        List<Stop> out = new ArrayList<>();
        if (track == null || track.length < 2) return out;
        double km = 0;
        for (int i = 1; i < track.length; i++) {
            double[] a = track[i - 1];
            double[] b = track[i];
            double d = RoadInfo.dist(a[0], a[1], b[0], b[1]);
            if (a.length > 2 && b.length > 2 && !Double.isNaN(a[2]) && !Double.isNaN(b[2])) {
                double gap = b[2] - a[2];
                // Lo que se tarda en recorrer esa distancia a 15 km/h no cuenta como parado.
                double parked = gap - d / (15 / 3.6);
                if (parked >= MIN_STOP_SEC && d <= MAX_STOP_MOVE_M) {
                    Stop prev = out.isEmpty() ? null : out.get(out.size() - 1);
                    if (prev != null && RoadInfo.dist(prev.lat, prev.lon, a[0], a[1]) <= MERGE_M
                            && a[2] - (prev.atSec + prev.durationSec) < MIN_STOP_SEC) {
                        out.set(out.size() - 1, new Stop(prev.lat, prev.lon, prev.atSec, b[2] - prev.atSec, prev.km));
                    } else {
                        out.add(new Stop(a[0], a[1], a[2], parked, km / 1000));
                    }
                }
            }
            km += d;
        }
        return out;
    }

    /** Segundos parados en total. */
    static double totalSec(List<Stop> stops) {
        double t = 0;
        for (Stop s : stops) t += s.durationSec;
        return t;
    }
}
