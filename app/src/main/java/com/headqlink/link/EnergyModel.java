package com.headqlink.link;

/**
 * Modelo físico aproximado del coche (SUV eléctrico; valores públicos y estimados) para estimar la
 * potencia que pide a la batería: aerodinámica con el viento, rodadura, pendiente, aceleración,
 * electrónica y climatización. Sin datos del coche: es una estimación.
 */
final class EnergyModel {
    static final double MASS_KG = 1985 + 90;   // en orden de marcha + ocupantes
    static final double CDA = 0.27 * 2.65;     // Cd estimado × área frontal (m²)
    static final double CRR = 0.009;           // neumáticos de baja resistencia
    static final double DRIVE_EFF = 0.88;      // batería → rueda
    static final double REGEN_EFF = 0.65;      // rueda → batería
    static final double AUX_KW = 0.4;          // electrónica
    /** Capacidad útil de la batería (LFP, 69,9 kWh brutos). */
    static final double USABLE_KWH = 67.0;

    // Último reparto calculado (kW).
    double aeroKw;
    double windKw;
    double rollKw;
    double gradeKw;
    double accelKw;
    double hvacKw;
    double batteryKw;

    /**
     * speedKmh, gradePct, headwindKmh (positivo de cara), tempC, accelG (longitudinal).
     * Devuelve la potencia en batería (kW, negativa si recupera).
     */
    double compute(double speedKmh, double gradePct, double headwindKmh, double tempC, double accelG) {
        double v = speedKmh / 3.6;
        double vw = Double.isNaN(headwindKmh) ? 0 : headwindKmh / 3.6;
        double t = Double.isNaN(tempC) ? 15 : tempC;
        double rho = 101325 / (287.05 * (273.15 + t));
        double theta = Math.atan(gradePct / 100);
        double air = v + vw;
        aeroKw = 0.5 * rho * CDA * air * Math.abs(air) * v / 1000;
        windKw = aeroKw - 0.5 * rho * CDA * v * v * v / 1000;
        rollKw = v > 0.3 ? CRR * MASS_KG * 9.81 * Math.cos(theta) * v / 1000 : 0;
        gradeKw = MASS_KG * 9.81 * Math.sin(theta) * v / 1000;
        accelKw = MASS_KG * accelG * 9.81 * v / 1000;
        hvacKw = hvac(t);
        double wheel = aeroKw + rollKw + gradeKw + accelKw;
        batteryKw = (wheel >= 0 ? wheel / DRIVE_EFF : wheel * REGEN_EFF) + AUX_KW + hvacKw;
        return batteryKw;
    }

    /** Climatización estimada por temperatura exterior (bomba de calor). */
    static double hvac(double tempC) {
        if (tempC < 18) return Math.min(2.5, (18 - tempC) * 0.09);
        if (tempC > 24) return Math.min(2.0, (tempC - 24) * 0.12);
        return 0.1;
    }
}
