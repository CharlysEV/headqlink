package com.headqlink.link;

/**
 * Suavidad de la conducción (nota de 0 a 100) a partir del tirón (jerk): cuánto cambian por segundo la aceleración
 * longitudinal y la fuerza lateral. Acelerar o frenar de golpe y los volantazos lo disparan; anticiparse lo mantiene
 * bajo. La aceleración se filtra antes de derivar (el GPS y el giroscopio tienen ruido) y solo cuenta en marcha.
 * La nota es 100·e^(−tirón eficaz / ESCALA): con 0,1 g/s (muy suave) sale ~75; con 0,35 g/s, ~37.
 * Sin Android: se prueba en el PC.
 */
final class Smoothness {
    /** Constante de tiempo del filtro de la aceleración (s). */
    static final double FILTER_S = 0.5;
    /** Tirón eficaz (g/s) con el que la nota baja a 100/e ≈ 37. */
    static final double SCALE_G_PER_S = 0.35;
    /** Ventana aproximada de la nota «ahora» (s). */
    static final double RECENT_S = 30;
    /** Segundos en marcha antes de dar nota. */
    static final double MIN_S = 20;

    private double fLong;
    private double fLat;
    private boolean primed;
    private double sumJ2;
    private double secs;
    private double recentJ2 = Double.NaN;

    /** Una muestra: aceleraciones (g), segundos desde la anterior y si el coche se mueve. */
    void add(double longG, double latG, double dt, boolean moving) {
        if (!(dt > 0) || dt > 2 || Double.isNaN(longG) || Double.isNaN(latG)) {
            primed = false;
            return;
        }
        if (!primed) {
            fLong = longG;
            fLat = latG;
            primed = true;
            return;
        }
        double a = Math.min(1, dt / FILTER_S);
        double nl = fLong + (longG - fLong) * a;
        double nt = fLat + (latG - fLat) * a;
        double jl = (nl - fLong) / dt;
        double jt = (nt - fLat) / dt;
        fLong = nl;
        fLat = nt;
        if (!moving) return;
        double j2 = jl * jl + jt * jt;
        sumJ2 += j2 * dt;
        secs += dt;
        double r = Math.min(1, dt / RECENT_S);
        recentJ2 = Double.isNaN(recentJ2) ? j2 : recentJ2 + (j2 - recentJ2) * r;
    }

    /** Nota del viaje (0-100), o -1 si aún no hay bastante. */
    int score() {
        return secs < MIN_S ? -1 : scoreFor(jerkRms());
    }

    /** Nota de los últimos ~30 s, o -1. */
    int recentScore() {
        return secs < MIN_S || Double.isNaN(recentJ2) ? -1 : scoreFor(Math.sqrt(recentJ2));
    }

    /** Tirón eficaz del viaje (g/s). */
    double jerkRms() {
        return secs > 0 ? Math.sqrt(sumJ2 / secs) : 0;
    }

    double seconds() {
        return secs;
    }

    static int scoreFor(double jerkRms) {
        return (int) Math.round(100 * Math.exp(-Math.max(0, jerkRms) / SCALE_G_PER_S));
    }

    void reset() {
        primed = false;
        sumJ2 = 0;
        secs = 0;
        recentJ2 = Double.NaN;
    }
}
