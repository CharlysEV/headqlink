package com.headqlink.link;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plan de carga de la pestaña Ruta (gratis, sin depender de nadie): dónde parar y cuánto cargar para llegar al destino
 * con el margen elegido, con la energía de cada tramo de la ruta (RoutePlanner: cuestas, viento, temperatura y el
 * consumo real del coche), los cargadores que pasan el filtro y la curva de carga del C10 (la potencia baja a partir
 * del 50 %: mejor varias paradas cortas que una larga hasta arriba). Voraz como los planificadores conocidos: en cada
 * parada, el mejor cargador del último tramo alcanzable y solo lo que hace falta.
 *
 * Vivo: se rehace durante el viaje desde donde está el coche y con su % real, escalando lo que falta por cómo se está
 * gastando de verdad (Trend). Las paradas ya elegidas se mantienen mientras se lleguen con margen (no baila el plan con
 * cada km); si se va quedando sin batería, cambian, y change() dice qué cambió para avisar. Sin Android: se prueba en
 * el PC.
 */
final class ChargePlanner {
    /** Lo que elige el conductor y lo que se sabe del coche. */
    static final class Settings {
        /** % mínimo al llegar al destino. */
        double arriveMinPct = 15;
        /** % mínimo al llegar a un cargador. */
        double stopMinPct = 10;
        /** % máximo al que cargar en una parada (por encima la carga rápida va muy lenta). */
        double maxChargePct = 80;
        /** Potencia de carga rápida máxima del coche (kW) y su capacidad (kWh). */
        double carPeakKw = 84;
        double capacityKwh = 69.9;
    }

    static final class Stop {
        final RoutePlanner.Charger charger;
        final double km;
        final double arrivePct;
        final double departPct;
        final double minutes;
        final double kwh;

        Stop(RoutePlanner.Charger charger, double km, double arrivePct, double departPct, double minutes, double kwh) {
            this.charger = charger;
            this.km = km;
            this.arrivePct = arrivePct;
            this.departPct = departPct;
            this.minutes = minutes;
            this.kwh = kwh;
        }
    }

    enum Outcome {
        /** Se llega sin parar. */
        NO_STOPS,
        /** Con las paradas del plan se llega con el margen. */
        PLANNED,
        /** No hay cargador alcanzable (con estos filtros): no se llega. */
        NO_CHARGER
    }

    static final class Result {
        final Outcome outcome;
        final List<Stop> stops;
        /** % al llegar al destino con el plan (puede ser negativo si no se llega). */
        final double arrivalPct;
        final double chargeMinutes;
        // Con qué se calculó: la energía acumulada de la ruta (ya escalada por la tendencia), desde dónde y con qué %.
        final double[] km;
        final double[] kwhCum;
        final double fromKm;
        final double socNow;
        final double capacityKwh;
        /** Cómo se está gastando frente a lo previsto (1 = como se preveía; 1,12 = un 12 % más). */
        double trend = 1;

        Result(Outcome outcome, List<Stop> stops, double arrivalPct, double[] km, double[] kwhCum, double fromKm, double socNow,
               double capacityKwh) {
            this.outcome = outcome;
            this.stops = stops;
            this.arrivalPct = arrivalPct;
            this.km = km;
            this.kwhCum = kwhCum;
            this.fromKm = fromKm;
            this.socNow = socNow;
            this.capacityKwh = capacityKwh;
            double m = 0;
            for (Stop s : stops) m += s.minutes;
            this.chargeMinutes = m;
        }

        /** % de batería en el km x de la ruta según el plan (con las cargas). */
        double pctAt(double x) {
            double cur = fromKm;
            double soc = socNow;
            for (Stop s : stops) {
                if (s.km > x) break;
                soc = s.departPct;
                cur = s.km;
            }
            return soc - (kwhAt(km, kwhCum, x) - kwhAt(km, kwhCum, cur)) / capacityKwh * 100;
        }

        /** La primera parada que queda por delante, o null. */
        Stop next() {
            for (Stop s : stops) if (s.km > fromKm + HERE_KM) return s;
            return null;
        }
    }

    /** Minutos que se pierden en cada parada aparte de cargar (aparcar, enchufar, pagar). */
    static final double STOP_OVERHEAD_MIN = 3;
    /** Potencia supuesta de un cargador sin potencia en OpenStreetMap (kW). */
    static final double UNKNOWN_KW = 50;
    /** Eficiencia de la carga (lo que entra en la batería de lo que da el cargador). */
    static final double CHARGE_EFF = 0.93;
    static final int MAX_STOPS = 8;
    /** Desde aquí un cargador cuenta como rápido (kW). */
    static final double FAST_KW = 40;
    /** Una parada ya elegida se mantiene aunque se llegue con hasta este % menos del mínimo (que no baile el plan). */
    static final double KEEP_SLACK_PCT = 2;
    /** Un cargador a menos de esto del coche es «aquí» (se está parando o cargando en él), no una parada por delante. */
    static final double HERE_KM = 2;

    private ChargePlanner() {
    }

    /** Potencia que admite el C10 a ese % (kW): la máxima hasta el 50 %, luego baja hasta ~55 % al 80 % y ~25 % al 100 %. */
    static double carPowerKw(double pct, double peakKw) {
        if (pct <= 50) return peakKw;
        if (pct <= 80) return peakKw * (1 - 0.45 * (pct - 50) / 30);
        return peakKw * (0.55 - 0.30 * Math.min(20, pct - 80) / 20);
    }

    /** Minutos para cargar de fromPct a toPct en un cargador de chargerKw (sin la parada fija). */
    static double chargeMinutes(double fromPct, double toPct, double chargerKw, Settings s) {
        if (toPct <= fromPct) return 0;
        double kw = chargerKw > 0 ? chargerKw : UNKNOWN_KW;
        double minutes = 0;
        double step = 0.5;
        for (double p = fromPct; p < toPct; p += step) {
            double d = Math.min(step, toPct - p);
            double power = Math.min(kw * CHARGE_EFF, carPowerKw(p + d / 2, s.carPeakKw));
            minutes += s.capacityKwh * d / 100 / power * 60;
        }
        return minutes;
    }

    /** kWh acumulados en el km x (interpolando entre los puntos de la ruta). */
    static double kwhAt(double[] km, double[] kwhCum, double x) {
        int n = km.length;
        if (x <= km[0]) return kwhCum[0];
        if (x >= km[n - 1]) return kwhCum[n - 1];
        int lo = 0;
        int hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (km[mid] < x) lo = mid;
            else hi = mid;
        }
        double f = (x - km[lo]) / Math.max(1e-6, km[hi] - km[lo]);
        return kwhCum[lo] + (kwhCum[hi] - kwhCum[lo]) * f;
    }

    /** La energía acumulada con lo que falta desde fromKm escalado por la tendencia (lo ya hecho no cambia). */
    static double[] scaled(double[] km, double[] kwhCum, double fromKm, double trend) {
        if (Math.abs(trend - 1) < 1e-9) return kwhCum;
        double base = kwhAt(km, kwhCum, fromKm);
        double[] out = new double[kwhCum.length];
        for (int i = 0; i < out.length; i++) out[i] = km[i] <= fromKm ? kwhCum[i] : base + (kwhCum[i] - base) * trend;
        return out;
    }

    static Result plan(double[] km, double[] kwhCum, double fromKm, double socNow, List<RoutePlanner.Charger> chargers, Settings s) {
        return plan(km, kwhCum, fromKm, socNow, chargers, s, Collections.<RoutePlanner.Charger>emptyList());
    }

    /**
     * El plan desde fromKm (donde está el coche) con socNow, entre los cargadores ya filtrados (los de detrás no
     * cuentan). keep: las paradas del plan anterior, que se mantienen mientras se lleguen con margen.
     */
    static Result plan(double[] km, double[] kwhCum, double fromKm, double socNow, List<RoutePlanner.Charger> chargers, Settings s,
                       List<RoutePlanner.Charger> keep) {
        double end = km[km.length - 1];
        double cap = s.capacityKwh;
        double cur = fromKm;
        double soc = socNow;
        List<Stop> stops = new ArrayList<>();
        for (int k = 0; k <= MAX_STOPS; k++) {
            double toEnd = (kwhAt(km, kwhCum, end) - kwhAt(km, kwhCum, cur)) / cap * 100;
            if (soc - toEnd >= s.arriveMinPct - 1e-9) {
                return new Result(stops.isEmpty() ? Outcome.NO_STOPS : Outcome.PLANNED, stops, soc - toEnd, km, kwhCum, fromKm, socNow, cap);
            }
            if (k == MAX_STOPS) break;
            RoutePlanner.Charger best = kept(km, kwhCum, cur, soc, end, keep, s);
            if (best == null) best = pick(km, kwhCum, cur, soc, end, chargers, s, s.stopMinPct);
            // Ya justo de batería: el que se alcance aunque se llegue con menos del mínimo.
            if (best == null) best = pick(km, kwhCum, cur, soc, end, chargers, s, 3);
            if (best == null) return new Result(Outcome.NO_CHARGER, stops, soc - toEnd, km, kwhCum, fromKm, socNow, cap);
            double arrive = soc - (kwhAt(km, kwhCum, best.kmAlong) - kwhAt(km, kwhCum, cur)) / cap * 100;
            double need = (kwhAt(km, kwhCum, end) - kwhAt(km, kwhCum, best.kmAlong)) / cap * 100 + s.arriveMinPct + 2;
            double target = Math.min(s.maxChargePct, Math.max(arrive, need));
            if (target <= arrive + 0.5) target = Math.min(100, arrive + 5); // por si acaso: que la parada sirva de algo
            double min = chargeMinutes(arrive, target, best.maxKw, s) + STOP_OVERHEAD_MIN;
            stops.add(new Stop(best, best.kmAlong, arrive, target, min, (target - arrive) / 100 * cap));
            cur = best.kmAlong;
            soc = target;
        }
        double toEnd = (kwhAt(km, kwhCum, end) - kwhAt(km, kwhCum, cur)) / cap * 100;
        return new Result(Outcome.NO_CHARGER, stops, soc - toEnd, km, kwhCum, fromKm, socNow, cap);
    }

    /** La siguiente parada del plan anterior, si se sigue alcanzando (con un poco de holgura); null si no. */
    private static RoutePlanner.Charger kept(double[] km, double[] kwhCum, double cur, double soc, double end,
                                             List<RoutePlanner.Charger> keep, Settings s) {
        RoutePlanner.Charger next = null;
        for (RoutePlanner.Charger c : keep) {
            if (c.kmAlong <= cur + 0.5 || c.kmAlong >= end - 1) continue;
            if (next == null || c.kmAlong < next.kmAlong) next = c;
        }
        if (next == null) return null;
        double at = soc - (kwhAt(km, kwhCum, next.kmAlong) - kwhAt(km, kwhCum, cur)) / s.capacityKwh * 100;
        return at >= s.stopMinPct - KEEP_SLACK_PCT ? next : null;
    }

    /**
     * El cargador de la siguiente parada: de los alcanzables (llegando con al menos minPct), los del último 40 % del
     * tramo alcanzable; de ellos, el más potente (y, a igualdad, el más lejano). null si no hay.
     */
    private static RoutePlanner.Charger pick(double[] km, double[] kwhCum, double cur, double soc, double end,
                                             List<RoutePlanner.Charger> chargers, Settings s, double minPct) {
        List<RoutePlanner.Charger> ok = new ArrayList<>();
        double far = cur;
        for (RoutePlanner.Charger c : chargers) {
            if (c.kmAlong <= cur + 0.5 || c.kmAlong >= end - 1) continue;
            double at = soc - (kwhAt(km, kwhCum, c.kmAlong) - kwhAt(km, kwhCum, cur)) / s.capacityKwh * 100;
            if (at < minPct) continue;
            ok.add(c);
            far = Math.max(far, c.kmAlong);
        }
        if (ok.isEmpty()) return null;
        double window = cur + 0.6 * (far - cur);
        RoutePlanner.Charger best = null;
        for (RoutePlanner.Charger c : ok) {
            if (c.kmAlong < window) continue;
            if (best == null || effKw(c, s) > effKw(best, s) + 1e-9
                    || (Math.abs(effKw(c, s) - effKw(best, s)) < 1e-9 && c.kmAlong > best.kmAlong)) {
                best = c;
            }
        }
        // Si en el último tramo solo hay cargadores lentos (de corriente alterna), mejor uno rápido un poco antes.
        if (best != null && effKw(best, s) < FAST_KW) {
            RoutePlanner.Charger fast = null;
            for (RoutePlanner.Charger c : ok) {
                if (effKw(c, s) >= FAST_KW && (fast == null || c.kmAlong > fast.kmAlong)) fast = c;
            }
            if (fast != null) best = fast;
        }
        return best;
    }

    private static double effKw(RoutePlanner.Charger c, Settings s) {
        return Math.min(c.maxKw > 0 ? c.maxKw : UNKNOWN_KW, s.carPeakKw);
    }

    // ------------------------------------------------------------------ cambios del plan (para avisar)

    enum Change {
        NONE,
        /** Hay otra parada (o más) que antes: se gasta más de lo previsto, o la elegida ya no se alcanza. */
        CHANGED,
        /** Sobra alguna parada (se gasta menos). */
        FEWER,
        /** Con este consumo ya no hay cargador alcanzable para llegar. */
        NO_CHARGER
    }

    /** Qué ha cambiado de before a now en las paradas por delante del coche (las ya hechas o en curso no cuentan). */
    static Change change(Result before, Result now) {
        if (before == null || now == null) return Change.NONE;
        if (now.outcome == Outcome.NO_CHARGER) return before.outcome == Outcome.NO_CHARGER ? Change.NONE : Change.NO_CHARGER;
        List<RoutePlanner.Charger> a = ahead(before, now.fromKm);
        List<RoutePlanner.Charger> b = ahead(now, now.fromKm);
        if (a.equals(b)) return Change.NONE;
        if (b.size() < a.size() && a.containsAll(b)) return Change.FEWER;
        return Change.CHANGED;
    }

    /** La primera parada de now que no estaba en before (la nueva), o null. */
    static Stop added(Result before, Result now) {
        List<RoutePlanner.Charger> a = before == null ? Collections.<RoutePlanner.Charger>emptyList() : ahead(before, now.fromKm);
        for (Stop s : now.stops) if (s.km > now.fromKm + HERE_KM && !a.contains(s.charger)) return s;
        return null;
    }

    /** La primera parada de before (por delante) que ya no está en now, o null. */
    static Stop dropped(Result before, Result now) {
        List<RoutePlanner.Charger> b = ahead(now, now.fromKm);
        for (Stop s : before.stops) if (s.km > now.fromKm + HERE_KM && !b.contains(s.charger)) return s;
        return null;
    }

    private static List<RoutePlanner.Charger> ahead(Result r, double fromKm) {
        List<RoutePlanner.Charger> out = new ArrayList<>();
        for (Stop s : r.stops) if (s.km > fromKm + HERE_KM) out.add(s.charger);
        return out;
    }

    // ------------------------------------------------------------------ tendencia del viaje

    /**
     * Cómo se está gastando en este viaje frente a lo previsto: la bajada del % real (la nube de Leapmotor) frente a
     * la energía prevista de los km recorridos (según el cuentakilómetros de esas mismas lecturas, para que % y km sean
     * del mismo instante). Una carga por el camino cierra el tramo y empieza otro. Hasta llevar unos km se fía de la
     * previsión.
     */
    static final class Trend {
        /** Hasta haber previsto esto (kWh, unos 10 km) no se corrige nada. */
        static final double MIN_PRED_KWH = 2;
        /** Peso de la previsión (kWh «a 1»): suaviza los primeros km y el redondeo del %. */
        static final double PRIOR_KWH = 3;
        static final double MIN = 0.8;
        static final double MAX = 1.6;

        private Object route;
        private long lastAtMs = Long.MIN_VALUE;
        private double refSoc = Double.NaN;
        private double refOdo;
        private double refKm;
        private double sumPred;
        private double sumReal;
        private double curPred;
        private double curReal;

        /**
         * Una lectura: % y cuentakilómetros del coche a la hora atMs, con el coche en el km routeKm de la ruta (route:
         * la ruta, para empezar de cero si cambia).
         */
        void observe(Object route, double[] km, double[] kwhCum, double routeKm, double soc, double odoKm, double capKwh,
                     boolean charging, long atMs) {
            if (route != this.route) {
                reset();
                this.route = route;
            }
            if (atMs == lastAtMs || Double.isNaN(soc) || Double.isNaN(odoKm) || Double.isNaN(capKwh)) return;
            lastAtMs = atMs;
            if (charging || Double.isNaN(refSoc) || soc > refSoc + 0.5 || odoKm < refOdo) {
                close();
                if (!charging) {
                    refSoc = soc;
                    refOdo = odoKm;
                    refKm = routeKm;
                }
                return;
            }
            double driven = odoKm - refOdo;
            curPred = kwhAt(km, kwhCum, refKm + driven) - kwhAt(km, kwhCum, refKm);
            curReal = (refSoc - soc) / 100 * capKwh;
        }

        private void close() {
            sumPred += curPred;
            sumReal += curReal;
            curPred = 0;
            curReal = 0;
            refSoc = Double.NaN;
        }

        void reset() {
            route = null;
            lastAtMs = Long.MIN_VALUE;
            refSoc = Double.NaN;
            sumPred = sumReal = curPred = curReal = 0;
        }

        /** kWh previstos de lo recorrido (lo que respalda la tendencia). */
        double predictedKwh() {
            return sumPred + curPred;
        }

        /** Real / previsto (1 si aún no hay bastante). */
        double factor() {
            double pred = sumPred + curPred;
            if (pred < MIN_PRED_KWH) return 1;
            double f = (sumReal + curReal + PRIOR_KWH) / (pred + PRIOR_KWH);
            return Math.max(MIN, Math.min(MAX, f));
        }
    }
}
