package com.headqlink.link;

import java.util.List;

/**
 * Consejo de velocidad para lo que queda de ruta: el plan de carga otra vez, pero sin pasar de 110, 100 o 90 km/h en
 * los tramos que la ruta hace más deprisa. Más despacio se gasta menos (sobre todo por el aire) y se carga menos, o se
 * quita una parada; a cambio se tarda más conduciendo. Se aconseja si sale a cuenta en tiempo total o quita paradas
 * (o, si con el plan no se llega, si así se llega). Sin Android: se prueba en el PC.
 */
final class SpeedAdvice {
    /** Velocidades que se prueban, de más rápida a más lenta. */
    static final int[] CAPS = {110, 100, 90};
    /** Ganancia mínima en tiempo total para aconsejar (min). */
    static final double MIN_GAIN_MIN = 3;
    /**
     * Quitando paradas se aconseja aunque se tarde hasta esto más en total (min): con lo que carga el coche, ir más
     * despacio casi nunca ahorra tiempo (lo que se tarda de más conduciendo pesa más que lo que se carga de menos), pero
     * una parada menos puede compensar; el texto dice lo que cuesta.
     */
    static final double STOP_SAVED_SLACK_MIN = 15;
    /** Por debajo de esto de conducción de más (s), casi no hay tramos más rápidos: no hay nada que aconsejar. */
    static final double MIN_EXTRA_S = 60;

    final int capKmh;
    /** Minutos de más conduciendo. */
    final double extraDriveMin;
    /** Minutos de menos cargando (con lo que se pierde en cada parada). */
    final double chargeSavedMin;
    final int stopsBefore;
    final int stopsAfter;
    final double arrivalPct;
    /** Con el plan no se llega y así sí. */
    final boolean rescue;

    SpeedAdvice(int capKmh, double extraDriveMin, double chargeSavedMin, int stopsBefore, int stopsAfter, double arrivalPct,
                boolean rescue) {
        this.capKmh = capKmh;
        this.extraDriveMin = extraDriveMin;
        this.chargeSavedMin = chargeSavedMin;
        this.stopsBefore = stopsBefore;
        this.stopsAfter = stopsAfter;
        this.arrivalPct = arrivalPct;
        this.rescue = rescue;
    }

    /** Minutos que se ahorran en total (negativo: se tarda más). */
    double gainMin() {
        return chargeSavedMin - extraDriveMin;
    }

    /**
     * El mejor consejo para el plan now (desde now.fromKm con now.socNow y su tendencia), o null si no hay ninguno que
     * merezca la pena. Necesita los tiempos por tramo de la ruta (p.segS).
     */
    static SpeedAdvice best(RoutePlanner.Plan p, ChargePlanner.Result now, List<RoutePlanner.Charger> chargers,
                            ChargePlanner.Settings s) {
        if (p == null || now == null || p.segS == null || p.segS.length != p.n || now.outcome == ChargePlanner.Outcome.NO_STOPS) {
            return null;
        }
        boolean stuck = now.outcome == ChargePlanner.Outcome.NO_CHARGER;
        SpeedAdvice best = null;
        for (int cap : CAPS) {
            double[] extra = new double[1];
            double[] cum = cappedKwhCum(p, cap, now.fromKm, extra);
            if (extra[0] < MIN_EXTRA_S) continue;
            ChargePlanner.Result r = ChargePlanner.plan(p.km, ChargePlanner.scaled(p.km, cum, now.fromKm, now.trend), now.fromKm,
                    now.socNow, chargers, s);
            if (r.outcome == ChargePlanner.Outcome.NO_CHARGER) continue;
            SpeedAdvice a = new SpeedAdvice(cap, extra[0] / 60, now.chargeMinutes - r.chargeMinutes, now.stops.size(),
                    r.stops.size(), r.arrivalPct, stuck);
            // Sin llegar con el plan: la más rápida con la que se llega.
            if (stuck) return a;
            boolean fewerStops = a.stopsAfter < a.stopsBefore;
            if (a.gainMin() < MIN_GAIN_MIN && !(fewerStops && a.gainMin() >= -STOP_SAVED_SLACK_MIN)) continue;
            if (best == null || better(a, best)) best = a;
        }
        return best;
    }

    /** Puro: a mejor que b: menos paradas; con las mismas, más tiempo ahorrado. */
    static boolean better(SpeedAdvice a, SpeedAdvice b) {
        if (a.stopsAfter != b.stopsAfter) return a.stopsAfter < b.stopsAfter;
        return a.gainMin() > b.gainMin() + 0.5;
    }

    /**
     * Puro: la energía acumulada de la ruta sin pasar de capKmh desde fromKm (lo de antes no cambia), con el mismo modelo
     * con el que se hizo (EnergyModel, el ajuste a la conducción real y el gasto extra por hora). extraS[0]: los segundos
     * de más conduciendo.
     */
    static double[] cappedKwhCum(RoutePlanner.Plan p, double capKmh, double fromKm, double[] extraS) {
        double[] out = p.kwhCum.clone();
        EnergyModel m = new EnergyModel();
        double add = 0;
        double extra = 0;
        for (int i = 1; i < p.n; i++) {
            if (p.km[i - 1] >= fromKm) {
                double dkm = p.km[i] - p.km[i - 1];
                double sec = Math.max(1, p.segS[i]);
                double v = Math.max(5, Math.min(140, dkm / sec * 3600));
                if (dkm > 0 && v > capKmh + 0.5) {
                    double h = sec / 3600;
                    double hc = dkm / capKmh;
                    double grade = Math.max(-15, Math.min(15, (p.elev[i] - p.elev[i - 1]) / (dkm * 1000) * 100));
                    double wind = p.headwind[i];
                    double temp = p.temp[i];
                    double flatV = m.compute(v, 0, wind, temp, 0);
                    double flatC = m.compute(capKmh, 0, wind, temp, 0);
                    double hillsV = m.compute(v, grade, wind, temp, 0) - flatV;
                    double hillsC = m.compute(capKmh, grade, wind, temp, 0) - flatC;
                    add += (flatC * hc - flatV * h) * p.factor + (hillsC * hc - hillsV * h) + p.overheadKw * (hc - h);
                    extra += (hc - h) * 3600;
                }
            }
            out[i] = p.kwhCum[i] + add;
        }
        if (extraS != null && extraS.length > 0) extraS[0] = extra;
        return out;
    }
}
