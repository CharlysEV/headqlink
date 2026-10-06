package com.headqlink.link;

/**
 * Un consejo de eficiencia según el momento, con su cifra calculada con el modelo del coche (EnergyModel):
 * en autovía, cuánto se ahorraría yendo 10 km/h más despacio; si no, lo que más pese en el viaje (aceleraciones o
 * climatización) o lo bien que va la recuperación. Sin Android: se prueba en el PC.
 */
final class EcoTip {
    static final int STEADY = 0;
    static final int SLOWER = 1;
    static final int GENTLER = 2;
    static final int CLIMATE = 3;
    static final int REGEN = 4;

    /** Velocidad desde la que el consejo es ir más despacio. */
    static final double CRUISE_KMH = 70;

    final int kind;
    /** % de ahorro (SLOWER), % del consumo (GENTLER, CLIMATE) o kWh recuperados (REGEN). */
    final double value;

    private EcoTip(int kind, double value) {
        this.kind = kind;
        this.value = value;
    }

    /** % de energía por km que se ahorra a kmh − 10 con el mismo viento y temperatura, en llano y sin acelerar. */
    static double slowerSavingPct(double kmh, double headwindKmh, double tempC) {
        if (kmh <= 20) return 0;
        EnergyModel m = new EnergyModel();
        double now = m.compute(kmh, 0, headwindKmh, tempC, 0) / kmh;
        double slower = m.compute(kmh - 10, 0, headwindKmh, tempC, 0) / (kmh - 10);
        return now > 0 ? Math.max(0, (1 - slower / now) * 100) : 0;
    }

    static EcoTip pick(double speedKmh, double headwindKmh, double tempC, EnergyBreakdown trip) {
        if (speedKmh >= CRUISE_KMH) {
            double s = slowerSavingPct(speedKmh, headwindKmh, tempC);
            if (s >= 3) return new EcoTip(SLOWER, s);
        }
        if (trip != null && trip.consumed() > 0.3) {
            double accel = trip.share(EnergyBreakdown.ACCEL);
            double climate = trip.share(EnergyBreakdown.CLIMATE);
            if (accel >= 0.15 && accel >= climate) return new EcoTip(GENTLER, accel * 100);
            if (climate >= 0.15) return new EcoTip(CLIMATE, climate * 100);
            if (trip.recovered() / trip.consumed() >= 0.08) return new EcoTip(REGEN, trip.recovered());
        }
        return new EcoTip(STEADY, 0);
    }
}
