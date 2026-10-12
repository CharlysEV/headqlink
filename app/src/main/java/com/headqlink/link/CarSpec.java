package com.headqlink.link;

import android.content.Context;

/**
 * Lo que la app necesita saber del modelo de coche: nombre, masa y aerodinámica (EnergyModel), batería útil, carga
 * rápida y en casa, autonomía WLTP y si climatiza con bomba de calor. Valores públicos y estimados. El modelo sale del
 * perfil de batería (CarCloudStore): «T03» o los del C10 (Life, ProMax, REEV) o «a mano» (C10 con esa capacidad).
 */
public final class CarSpec {
    final String name;
    /** En orden de marcha, sin ocupantes (EnergyModel suma 90 kg). */
    final double massKg;
    /** Cd × área frontal (m²). */
    final double cda;
    final double grossKwh;
    final double usableKwh;
    /** Pico de carga rápida (kW) y voltaje de la batería. */
    final double peakDcKw;
    final double volts;
    /** Cargador de a bordo (kW, corriente alterna). */
    final double acKw;
    final double wltpKm;
    /** Bomba de calor: calienta gastando menos que una resistencia. */
    final boolean heatPump;
    /** Autonomía extendida: generador de gasolina (el plan de ruta y el consumo lo tienen en cuenta). */
    final boolean reev;

    CarSpec(String name, double massKg, double cda, double grossKwh, double usableKwh, double peakDcKw, double volts,
            double acKw, double wltpKm, boolean heatPump) {
        this(name, massKg, cda, grossKwh, usableKwh, peakDcKw, volts, acKw, wltpKm, heatPump, false);
    }

    CarSpec(String name, double massKg, double cda, double grossKwh, double usableKwh, double peakDcKw, double volts,
            double acKw, double wltpKm, boolean heatPump, boolean reev) {
        this.name = name;
        this.massKg = massKg;
        this.cda = cda;
        this.grossKwh = grossKwh;
        this.usableKwh = usableKwh;
        this.peakDcKw = peakDcKw;
        this.volts = volts;
        this.acKw = acKw;
        this.wltpKm = wltpKm;
        this.heatPump = heatPump;
        this.reev = reev;
    }

    /** Leapmotor C10 eléctrico (69,9 kWh LFP, 84 kW, 420 km WLTP): para el que está hecha la app. */
    static final CarSpec C10 = new CarSpec("C10", 1985, 0.27 * 2.65, 69.9, 67.0, 84, 400, 11, 420, true);
    /** C10 de 81,9 kWh (800 V, ~130 kW). Su autonomía WLTP no está confirmada: la del C10 (420, la del juego). */
    static final CarSpec C10_PROMAX = new CarSpec("C10", 2045, 0.27 * 2.65, 81.9, 79.0, 130, 800, 11, 420, true);
    /** C10 de autonomía extendida: 28,4 kWh de batería, ~65 kW en carga rápida y generador de gasolina. */
    static final CarSpec C10_REEV = new CarSpec("C10", 1990, 0.27 * 2.65, 28.4, 26.5, 65, 400, 6.6, 420, true, true);
    /**
     * Leapmotor T03: urbano de 3,62 m, 37,3 kWh, 48 kW en carga rápida (del 30 al 80 % en ~36 min), 6,6 kW en casa, 265 km
     * WLTP. Climatiza con resistencia (PTC), sin bomba de calor.
     */
    static final CarSpec T03 = new CarSpec("T03", 1175, 0.32 * 2.25, 37.3, 36.0, 48, 400, 6.6, 265, false);

    private static volatile CarSpec current = C10;

    /** El coche del perfil de batería elegido. */
    static CarSpec current() {
        return current;
    }

    /** Puro: el coche de un perfil de batería (CarCloudStore.PROFILE_*). */
    static CarSpec forProfile(String profile) {
        if (CarCloudStore.PROFILE_T03.equals(profile)) return T03;
        if (CarCloudStore.PROFILE_C10_PROMAX.equals(profile)) return C10_PROMAX;
        if (CarCloudStore.PROFILE_C10_REEV.equals(profile)) return C10_REEV;
        return C10;
    }

    static void use(CarSpec spec) {
        current = spec == null ? C10 : spec;
    }

    /** Al arrancar la app y al cambiar de perfil: el coche del perfil guardado. */
    public static void load(Context ctx) {
        try {
            use(forProfile(new CarCloudStore(ctx).profile()));
        } catch (Throwable t) {
            use(C10);
        }
    }

    /** ¿Es el T03 (pantalla y batería pequeñas)? */
    boolean isT03() {
        return this == T03;
    }
}
