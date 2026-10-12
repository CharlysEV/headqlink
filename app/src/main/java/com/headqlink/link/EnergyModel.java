package com.headqlink.link;

/**
 * Modelo físico aproximado del coche (el de CarSpec.current(): el C10 o el T03; valores públicos y estimados) para
 * estimar la potencia que pide a la batería: aerodinámica con el viento, rodadura, pendiente, aceleración, electrónica y
 * climatización. Sin datos del coche: es una estimación.
 */
final class EnergyModel {
    static final double CRR = 0.009;           // neumáticos de baja resistencia
    static final double DRIVE_EFF = 0.88;      // batería → rueda
    static final double REGEN_EFF = 0.65;      // rueda → batería
    static final double AUX_KW = 0.4;          // electrónica

    /** Masa en orden de marcha + ocupantes (kg). */
    static double massKg() {
        return CarSpec.current().massKg + 90;
    }

    /** Cd estimado × área frontal (m²). */
    static double cda() {
        return CarSpec.current().cda;
    }

    /** Capacidad útil de la batería (kWh; la del C10, 67 de 69,9 brutos). */
    static double usableKwh() {
        return CarSpec.current().usableKwh;
    }

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
        double mass = massKg();
        double cda = cda();
        aeroKw = 0.5 * rho * cda * air * Math.abs(air) * v / 1000;
        windKw = aeroKw - 0.5 * rho * cda * v * v * v / 1000;
        rollKw = v > 0.3 ? CRR * mass * 9.81 * Math.cos(theta) * v / 1000 : 0;
        gradeKw = mass * 9.81 * Math.sin(theta) * v / 1000;
        accelKw = mass * accelG * 9.81 * v / 1000;
        hvacKw = hvac(t);
        double wheel = aeroKw + rollKw + gradeKw + accelKw;
        batteryKw = (wheel >= 0 ? wheel / DRIVE_EFF : wheel * REGEN_EFF) + AUX_KW + hvacKw;
        return batteryKw;
    }

    /** Climatización estimada por temperatura exterior (bomba de calor; sin ella, la calefacción gasta ~1,6 veces más). */
    static double hvac(double tempC) {
        if (tempC < 18 && !CarSpec.current().heatPump) return Math.min(3.5, (18 - tempC) * 0.15);
        if (tempC < 18) return Math.min(2.5, (18 - tempC) * 0.09);
        if (tempC > 24) return Math.min(2.0, (tempC - 24) * 0.12);
        return 0.1;
    }
}
