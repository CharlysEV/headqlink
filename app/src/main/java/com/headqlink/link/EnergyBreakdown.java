package com.headqlink.link;

/**
 * Reparto de la energía estimada de un viaje (EnergyModel): en qué se ha ido la batería (aire, rodadura, subidas,
 * aceleraciones, climatización y electrónica) y cuánto se ha recuperado al bajar y frenar.
 *
 * En cada paso, si las ruedas piden energía, lo que sale de la batería (rueda / rendimiento) se reparte entre las
 * partes que empujan en contra, en proporción a su potencia (una bajada suave que «ayuda» reduce lo que se reparte,
 * no resta de una parte). Si las ruedas devuelven energía, lo que entra en la batería es lo recuperado. Climatización
 * y electrónica salen siempre de la batería. Así, consumido − recuperado = energía neta del viaje (la que integra
 * CarSensors). Sin Android: se prueba en el PC.
 */
final class EnergyBreakdown {
    static final int AIR = 0;
    static final int ROLLING = 1;
    static final int SLOPE = 2;
    static final int ACCEL = 3;
    static final int CLIMATE = 4;
    static final int AUX = 5;
    static final int PARTS = 6;

    private final double[] kwh = new double[PARTS];
    private double recovered;

    /** Suma un paso de dtH horas con el último reparto calculado por el modelo. */
    void add(EnergyModel m, double dtH) {
        if (!(dtH > 0)) return;
        kwh[CLIMATE] += m.hvacKw * dtH;
        kwh[AUX] += EnergyModel.AUX_KW * dtH;
        double air = m.aeroKw;
        double roll = m.rollKw;
        double slope = m.gradeKw;
        double accel = m.accelKw;
        double wheel = air + roll + slope + accel;
        if (wheel >= 0) {
            double pos = Math.max(0, air) + Math.max(0, roll) + Math.max(0, slope) + Math.max(0, accel);
            if (pos <= 0) return;
            double battery = wheel / EnergyModel.DRIVE_EFF * dtH;
            kwh[AIR] += Math.max(0, air) / pos * battery;
            kwh[ROLLING] += Math.max(0, roll) / pos * battery;
            kwh[SLOPE] += Math.max(0, slope) / pos * battery;
            kwh[ACCEL] += Math.max(0, accel) / pos * battery;
        } else {
            recovered += -wheel * EnergyModel.REGEN_EFF * dtH;
        }
    }

    double part(int i) {
        return kwh[i];
    }

    /** Energía que ha salido de la batería (kWh). */
    double consumed() {
        double t = 0;
        for (double v : kwh) t += v;
        return t;
    }

    /** Energía recuperada al bajar y frenar (kWh). */
    double recovered() {
        return recovered;
    }

    double net() {
        return consumed() - recovered;
    }

    /** Fracción del consumo que se lleva la parte i (0 si aún no hay consumo). */
    double share(int i) {
        double c = consumed();
        return c > 0 ? kwh[i] / c : 0;
    }

    void copyFrom(EnergyBreakdown o) {
        System.arraycopy(o.kwh, 0, kwh, 0, PARTS);
        recovered = o.recovered;
    }

    void reset() {
        java.util.Arrays.fill(kwh, 0);
        recovered = 0;
    }
}
