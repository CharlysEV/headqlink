package com.headqlink.link;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Cuentas de los viajes guardados (TripLog) para la pestaña Viajes: consumo y velocidad media de cada uno, km por día
 * de las dos últimas semanas, totales de un periodo y récords. Sin Android: se prueba en el PC.
 */
final class TripStats {
    /** Viajes más cortos que esto no cuentan para el récord de eficiencia (el consumo de 1 km no dice nada). */
    static final double MIN_KM_FOR_RECORD = 5;

    private TripStats() {
    }

    /** kWh/100 km estimados del viaje, o NaN si es demasiado corto. */
    static double kwhPer100(TripLog.Trip t) {
        return t.km >= 0.5 ? t.kwh / t.km * 100 : Double.NaN;
    }

    /** Velocidad media (km/h, con las paradas), o NaN sin duración. */
    static double avgKmh(TripLog.Trip t) {
        return t.minutes > 0 ? t.km / (t.minutes / 60.0) : Double.NaN;
    }

    /** Medianoche local del día de ms. */
    static long startOfDay(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Km por día de los últimos days días; el último índice es hoy (según nowMs). */
    static double[] dailyKm(List<TripLog.Trip> trips, long nowMs, int days, TimeZone tz) {
        double[] out = new double[days];
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(startOfDay(nowMs, tz));
        c.add(Calendar.DAY_OF_YEAR, -(days - 1));
        long[] starts = new long[days + 1];
        for (int i = 0; i <= days; i++) {
            starts[i] = c.getTimeInMillis();
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        for (TripLog.Trip t : trips) {
            for (int i = 0; i < days; i++) {
                if (t.startMs >= starts[i] && t.startMs < starts[i + 1]) {
                    out[i] += t.km;
                    break;
                }
            }
        }
        return out;
    }

    /** Totales de los viajes que empiezan en [fromMs, toMs). */
    static final class Totals {
        int trips;
        double km;
        double kwh;
        long minutes;

        double kwhPer100() {
            return km >= 0.5 ? kwh / km * 100 : Double.NaN;
        }
    }

    static Totals totals(List<TripLog.Trip> trips, long fromMs, long toMs) {
        Totals t = new Totals();
        for (TripLog.Trip x : trips) {
            if (x.startMs < fromMs || x.startMs >= toMs) continue;
            t.trips++;
            t.km += x.km;
            t.kwh += x.kwh;
            t.minutes += x.minutes;
        }
        return t;
    }

    /** Índice del viaje más largo (km), o -1. */
    static int longest(List<TripLog.Trip> trips) {
        int best = -1;
        for (int i = 0; i < trips.size(); i++) {
            if (best < 0 || trips.get(i).km > trips.get(best).km) best = i;
        }
        return best;
    }

    /** Índice del viaje de menos kWh/100 km entre los de al menos MIN_KM_FOR_RECORD, o -1. */
    static int mostEfficient(List<TripLog.Trip> trips) {
        int best = -1;
        double bestV = Double.MAX_VALUE;
        for (int i = 0; i < trips.size(); i++) {
            TripLog.Trip t = trips.get(i);
            double v = kwhPer100(t);
            if (t.km < MIN_KM_FOR_RECORD || Double.isNaN(v) || t.kwh <= 0) continue;
            if (v < bestV) {
                bestV = v;
                best = i;
            }
        }
        return best;
    }

    /** Índice del viaje con más desnivel subido, o -1. */
    static int mostClimb(List<TripLog.Trip> trips) {
        int best = -1;
        for (int i = 0; i < trips.size(); i++) {
            if (best < 0 || trips.get(i).climb > trips.get(best).climb) best = i;
        }
        return best;
    }

    /** kWh/100 km de todos los viajes juntos (ponderado por km), o NaN. */
    static double avgKwhPer100(List<TripLog.Trip> trips) {
        double km = 0;
        double kwh = 0;
        for (TripLog.Trip t : trips) {
            km += t.km;
            kwh += t.kwh;
        }
        return km >= 0.5 ? kwh / km * 100 : Double.NaN;
    }
}
