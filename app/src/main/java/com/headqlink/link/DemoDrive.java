package com.headqlink.link;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;

/**
 * Datos del «modo demostración» (vista previa del modo extendido, sin coche): un trayecto guionizado de unos 13 min
 * (semáforo, giros, rotonda, autovía con subida y bajada, frenazo, salida y ciudad), una ruta larga con el perfil de
 * un Madrid → Sevilla (relieve, viento por tramos y cargadores) y siete viajes guardados en dos semanas.
 *
 * Todo sale de una semilla: con la misma, los mismos números, así que las capturas se pueden comparar. Sin Android
 * (se prueba en el PC); DemoMode lo lleva a los sensores, la ruta, la navegación y los viajes.
 */
final class DemoDrive {
    /** Paso de la simulación (s). */
    static final double DT = 0.1;
    static final long SEED = 20261006L;
    /** «Ahora» de la demostración al empezar el trayecto: 6 oct 2026, 16:40 en España (14:40 UTC). */
    static final long BASE_MS = 1791297600000L;
    static final TimeZone TZ = TimeZone.getTimeZone("Europe/Madrid");
    /** Kilómetro de la ruta larga en el que empieza el trayecto (pasado el puerto: por delante, bajada). */
    static final double ROUTE_START_KM = 300;
    static final String DESTINATION = "Sevilla";
    static final double WIND_KMH = 11;
    static final double WIND_FROM_DEG = 205;
    static final double TEMP_C = 24;

    private static final double G = 9.81;
    /** Tirón máximo (m/s³): las aceleraciones cambian de forma realista, no a saltos. */
    private static final double JERK = 7.5;

    // Tramos del guion: {segundos, km/h objetivo, giro total (° , + a la izquierda), pendiente %, límite km/h,
    // vía, maniobra (tipo NavTap), ángulo de la maniobra (NavTap: − izquierda), vía tras la maniobra, salida,
    // aceleración máx (g), frenada máx (g)}.
    private static final int TURN = 1;
    private static final int ROUNDABOUT = 3;
    private static final double[][] LEGS = {
            {14, 0, 0, 0, 50, 0, 0, 0, 0, 0, .20, .30},
            {26, 50, 0, .5, 50, 0, 0, 0, 0, 0, .27, .30},
            {12, 25, 0, 0, 50, 0, 0, 0, 0, 0, .20, .30},
            {8, 25, -90, 0, 50, 1, TURN, 90, 1, 0, .20, .30},
            {30, 50, 0, 1, 50, 1, 0, 0, 0, 0, .20, .30},
            {10, 25, 0, 0, 50, 1, 0, 0, 0, 0, .20, .30},
            {3, 25, -30, 0, 40, 2, ROUNDABOUT, 0, 3, 2, .20, .30},
            {8, 25, 150, 0, 40, 2, 0, 0, 0, 0, .20, .30},
            {3, 30, -30, 0, 40, 2, 0, 0, 0, 0, .20, .30},
            {22, 120, -15, 0, 120, 3, 0, 0, 0, 0, .26, .30},
            {50, 120, 12, 0, 120, 3, 0, 0, 0, 0, .20, .30},
            {100, 120, 0, 4.5, 120, 3, 0, 0, 0, 0, .20, .30},
            {30, 118, -10, 1, 120, 3, 0, 0, 0, 0, .20, .30},
            {90, 115, -25, -5, 120, 3, 0, 0, 0, 0, .20, .30},
            {6, 60, 0, -2, 120, 3, 0, 0, 0, 0, .20, .48},
            {16, 60, 0, 0, 120, 3, 0, 0, 0, 0, .20, .30},
            {25, 110, 0, 0, 120, 3, 0, 0, 0, 0, .22, .30},
            {120, 118, 8, .5, 120, 3, 0, 0, 0, 0, .20, .30},
            {20, 70, 0, 0, 120, 3, 0, 0, 0, 0, .20, .30},
            {6, 70, -20, 0, 80, 4, TURN, 45, 4, 0, .20, .30},
            {12, 55, -60, 0, 60, 4, 0, 0, 0, 0, .20, .30},
            {50, 50, 0, -1, 50, 5, 0, 0, 0, 0, .20, .30},
            {12, 0, 0, 0, 50, 5, 0, 0, 0, 0, .20, .25},
            {14, 0, 0, 0, 50, 5, 0, 0, 0, 0, .20, .30},
            {20, 45, 0, 0, 50, 5, 0, 0, 0, 0, .20, .30},
            {8, 20, 0, 0, 50, 5, 0, 0, 0, 0, .20, .30},
            {7, 20, 90, 0, 50, 6, TURN, -90, 6, 0, .20, .30},
            {40, 40, 0, 0, 50, 6, 0, 0, 0, 0, .20, .30},
            {14, 0, 0, 0, 50, 6, 0, 0, 0, 0, .20, .30},
            {20, 0, 0, 0, 50, 6, 0, 0, 0, 0, .20, .30},
    };
    static final String[] ROADS = {"Av. de la Estación", "Calle Mayor", "Glorieta del Molino", "A-4 · Autovía del Sur",
            "Salida 243", "Calle Real", "Plaza de España"};
    static final int CAMERA_LIMIT = 120;

    /** Una maniobra del trayecto, en el km en que empieza. */
    static final class Maneuver {
        double km;
        int kind;
        int angle;
        int exit;
        String road;
        boolean[] lanes;
    }

    /** Navegación en un instante (lo que mandaría Android Auto). */
    static final class Nav {
        int kind;
        int angle;
        int exit = -1;
        int stepMeters;
        String road;
        String nextRoad;
        boolean[] lanes;
        int thenKind = -1;
        int thenAngle;
        String thenRoad;
    }

    final int n;
    final double loopSec;
    final float[] speedKmh;
    final float[] longG;
    final float[] latG;
    final float[] yawRad;
    final float[] headingDeg;
    final double[] lat;
    final double[] lon;
    final float[] altM;
    final float[] gradePct;
    final double[] distKm;
    final byte[] leg;
    final List<Maneuver> maneuvers = new ArrayList<>();
    /** Radar fijo de la demostración (km del trayecto), hacia el final del tramo largo de autovía. */
    final double cameraKm;

    DemoDrive() {
        this(SEED);
    }

    DemoDrive(long seed) {
        double total = 0;
        for (double[] l : LEGS) total += l[0];
        loopSec = total;
        n = (int) Math.round(total / DT) + 1;
        speedKmh = new float[n];
        longG = new float[n];
        latG = new float[n];
        yawRad = new float[n];
        headingDeg = new float[n];
        lat = new double[n];
        lon = new double[n];
        altM = new float[n];
        gradePct = new float[n];
        distKm = new double[n];
        leg = new byte[n];
        simulate(new Random(seed));
        double t = 0;
        for (int i = 0; i < 17; i++) t += LEGS[i][0];
        cameraKm = distKm[index(t + 137)];
    }

    private void simulate(Random rnd) {
        double v = 0;
        double a = 0;
        double hdg = 180;
        double la = 38.7650;
        double lo = -3.3850;
        double alt = 705;
        double grade = 0;
        double dist = 0;
        int li = 0;
        double legStart = 0;
        for (int k = 0; k < n; k++) {
            double t = k * DT;
            while (li < LEGS.length - 1 && t >= legStart + LEGS[li][0]) {
                legStart += LEGS[li][0];
                li++;
                if (LEGS[li][6] != 0) addManeuver(li, dist);
            }
            if (k == 0 && LEGS[0][6] != 0) addManeuver(0, 0);
            double[] l = LEGS[li];
            double vt = l[1] / 3.6;
            double target = clamp((vt - v) / 2.0, -l[11] * G, l[10] * G);
            a += clamp(target - a, -JERK * DT, JERK * DT);
            v += a * DT;
            if (vt == 0 && v < 0.5) {
                v = 0;
                a = 0;
            }
            if (v < 0) {
                v = 0;
                a = 0;
            }
            // Giro: velocidad angular en forma de seno (entra y sale suave) que suma el giro del tramo.
            double yawDeg = 0;
            if (l[2] != 0) {
                double u = (t - legStart) / l[0];
                yawDeg = l[2] * Math.PI / (2 * l[0]) * Math.sin(Math.PI * Math.min(1, Math.max(0, u)));
            }
            double yaw = Math.toRadians(yawDeg);
            hdg = ((hdg - yawDeg * DT) % 360 + 360) % 360; // + izquierda = rumbo menor
            grade += (l[3] - grade) * Math.min(1, DT / 6);
            double step = v * DT;
            alt += grade / 100 * step;
            dist += step / 1000;
            la += step * Math.cos(Math.toRadians(hdg)) / 111320;
            lo += step * Math.sin(Math.toRadians(hdg)) / (111320 * Math.cos(Math.toRadians(la)));

            speedKmh[k] = (float) (v * 3.6 + (v > 1 ? rnd.nextGaussian() * 0.15 : 0));
            longG[k] = (float) (a / G + (v > 1 ? rnd.nextGaussian() * 0.004 : 0));
            latG[k] = (float) (v * yaw / G + (v > 1 ? rnd.nextGaussian() * 0.006 : 0));
            yawRad[k] = (float) yaw;
            headingDeg[k] = (float) hdg;
            lat[k] = la;
            lon[k] = lo;
            altM[k] = (float) alt;
            gradePct[k] = (float) grade;
            distKm[k] = dist;
            leg[k] = (byte) li;
        }
    }

    private void addManeuver(int li, double km) {
        double[] l = LEGS[li];
        Maneuver m = new Maneuver();
        m.km = km;
        m.kind = (int) l[6];
        m.angle = (int) l[7];
        m.exit = l[9] > 0 ? (int) l[9] : -1;
        m.road = ROADS[(int) l[8]];
        // Salida de autovía: tres carriles, el bueno es el de la derecha.
        if ((int) l[8] == 4) m.lanes = new boolean[]{false, false, true};
        maneuvers.add(m);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Muestra del instante t (s), dentro del trayecto. */
    int index(double t) {
        return (int) Math.max(0, Math.min(n - 1, Math.round(t / DT)));
    }

    int limitKmh(int k) {
        return (int) LEGS[leg[k]][4];
    }

    String road(int k) {
        return ROADS[(int) LEGS[leg[k]][5]];
    }

    /** Navegación en la muestra k: la próxima maniobra, la de después y los carriles. */
    Nav nav(int k) {
        Nav out = new Nav();
        double km = distKm[k];
        out.road = road(k);
        int next = -1;
        for (int i = 0; i < maneuvers.size(); i++) {
            if (maneuvers.get(i).km > km + 0.005) {
                next = i;
                break;
            }
        }
        if (next < 0) {
            // Pasada la última: seguir recto hasta el final del trayecto.
            out.kind = TURN;
            out.angle = 0;
            out.nextRoad = out.road;
            out.stepMeters = (int) Math.max(50, Math.round((distKm[n - 1] - km) * 1000 + 1200));
            return out;
        }
        Maneuver m = maneuvers.get(next);
        out.kind = m.kind;
        out.angle = m.angle;
        out.exit = m.exit;
        out.nextRoad = m.road;
        out.lanes = m.lanes;
        out.stepMeters = (int) Math.round((m.km - km) * 1000);
        if (next + 1 < maneuvers.size()) {
            Maneuver t = maneuvers.get(next + 1);
            out.thenKind = t.kind;
            out.thenAngle = t.angle;
            out.thenRoad = t.road;
        }
        return out;
    }

    // ------------------------------------------------------------------ ruta larga

    /** Ruta de la pestaña Ruta: 531 km con el relieve, el viento y los cargadores de un Madrid → Sevilla. */
    static RoutePlanner.Plan plan(long seed) {
        Random rnd = new Random(seed ^ 0x5EB1L);
        double p1 = rnd.nextDouble() * 6.3;
        double p2 = rnd.nextDouble() * 6.3;
        double p3 = rnd.nextDouble() * 6.3;
        RoutePlanner.Plan p = new RoutePlanner.Plan();
        int n = 400;
        double total = 531;
        p.destination = DESTINATION;
        p.n = n;
        p.lat = new double[n];
        p.lon = new double[n];
        p.km = new double[n];
        p.elev = new double[n];
        p.headwind = new double[n];
        p.temp = new double[n];
        p.kwhCum = new double[n];
        p.gravCum = new double[n];
        p.totalKm = total;
        double[][] relief = {{0, 655}, {25, 610}, {60, 700}, {100, 690}, {140, 760}, {180, 650}, {210, 690}, {240, 720},
                {252, 790}, {262, 640}, {275, 470}, {300, 340}, {330, 260}, {360, 200}, {400, 110}, {430, 160}, {455, 130},
                {490, 60}, {531, 12}};
        double[][] wind = {{0, 10}, {120, -7}, {250, 16}, {330, 22}, {450, 8}, {531, 6}};
        EnergyModel m = new EnergyModel();
        double secs = 0;
        for (int i = 0; i < n; i++) {
            double km = total * i / (n - 1);
            p.km[i] = km;
            double f = km / total;
            // Recta Madrid → Sevilla con una curva suave.
            p.lat[i] = 40.4168 + (37.3891 - 40.4168) * f + 0.18 * Math.sin(f * Math.PI);
            p.lon[i] = -3.7038 + (-5.9845 + 3.7038) * f - 0.25 * Math.sin(f * Math.PI * 1.3);
            p.elev[i] = interp(relief, km) + 9 * Math.sin(km / 3.1 + p1) + 5 * Math.sin(km / 1.3 + p2) + 14 * Math.sin(km / 11 + p3);
            p.headwind[i] = step(wind, km) + 2.5 * Math.sin(km / 7 + p2);
            p.temp[i] = 17 + 10 * f;
            if (i > 0) {
                double dkm = km - p.km[i - 1];
                double v = km > 520 ? 60 : 110;
                double grade = Math.max(-15, Math.min(15, (p.elev[i] - p.elev[i - 1]) / (dkm * 1000) * 100));
                double s = dkm / v * 3600;
                secs += s;
                double kw = m.compute(v, grade, p.headwind[i], p.temp[i], 0);
                double flat = m.compute(v, 0, p.headwind[i], p.temp[i], 0);
                p.kwhCum[i] = p.kwhCum[i - 1] + kw * s / 3600;
                p.gravCum[i] = p.gravCum[i - 1] + (kw - flat) * s / 3600;
            }
        }
        p.totalSeconds = Math.round(secs);
        p.destLat = p.lat[n - 1];
        p.destLon = p.lon[n - 1];
        p.destTemp = 27;
        p.destWind = 12;
        p.destRain = 0;
        p.destCode = 1;
        Object[][] ch = {
                {62.0, "Zunder La Mancha", 150.0, "CCS · Tipo 2", "zunder"},
                {118.0, "Iberdrola Puerto Lápice", 100.0, "CCS · CHAdeMO", "iberdrola"},
                {196.0, "Ionity Valdepeñas", 300.0, "CCS", "ionity"},
                {243.0, "Repsol Despeñaperros", 50.0, "CCS · CHAdeMO · Tipo 2", "repsol"},
                {318.0, "Tesla Supercharger Bailén", 250.0, "CCS · Tipo 2", "tesla"},
                {352.0, "Endesa X Andújar", 120.0, "CCS", "endesa"},
                {402.0, "Centro comercial Córdoba Sur", 22.0, "Tipo 2", "other"},
                {470.0, "Zunder Écija", 150.0, "CCS · Tipo 2", "zunder"},
        };
        for (Object[] o : ch) {
            RoutePlanner.Charger c = new RoutePlanner.Charger();
            c.kmAlong = (Double) o[0];
            c.name = (String) o[1];
            c.maxKw = (Double) o[2];
            c.detail = (String) o[3];
            c.network = (String) o[4];
            int idx = (int) Math.round(c.kmAlong / total * (n - 1));
            c.lat = p.lat[idx] + 0.004;
            c.lon = p.lon[idx] - 0.003;
            p.chargers.add(c);
        }
        return p;
    }

    private static double interp(double[][] pts, double x) {
        for (int i = 1; i < pts.length; i++) {
            if (x <= pts[i][0]) {
                double f = (x - pts[i - 1][0]) / (pts[i][0] - pts[i - 1][0]);
                // Coseno: sin esquinas en el relieve.
                f = (1 - Math.cos(f * Math.PI)) / 2;
                return pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * f;
            }
        }
        return pts[pts.length - 1][1];
    }

    private static double step(double[][] pts, double x) {
        double v = pts[0][1];
        for (double[] q : pts) if (x >= q[0]) v = q[1];
        return v;
    }

    // ------------------------------------------------------------------ viajes guardados

    /** Siete viajes en las dos semanas anteriores a BASE_MS, del más reciente al más antiguo. */
    static List<TripLog.Trip> trips(long seed) {
        // {días antes, hora, minuto, km, minutos, kWh/100 km, subida m, máx km/h}
        double[][] d = {
                {1, 21, 15, 61.3, 47, 19.6, 240, 121},
                {3, 8, 10, 18.5, 29, 15.2, 61, 88},
                {5, 17, 48, 36.9, 41, 17.4, 145, 112},
                {8, 8, 5, 18.2, 25, 14.6, 58, 90},
                {9, 10, 30, 142.6, 88, 18.9, 610, 124},
                {11, 19, 5, 23.1, 31, 16.8, 88, 101},
                {13, 8, 12, 18.4, 27, 15.9, 64, 92},
        };
        List<TripLog.Trip> out = new ArrayList<>();
        Random rnd = new Random(seed ^ 0x7121L);
        for (double[] q : d) {
            Calendar c = Calendar.getInstance(TZ);
            c.setTimeInMillis(BASE_MS);
            c.add(Calendar.DAY_OF_YEAR, -(int) q[0]);
            c.set(Calendar.HOUR_OF_DAY, (int) q[1]);
            c.set(Calendar.MINUTE, (int) q[2]);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            TripLog.Trip t = new TripLog.Trip();
            t.startMs = c.getTimeInMillis();
            t.km = q[3];
            t.minutes = (long) q[4];
            t.kwh = q[3] * q[5] / 100;
            t.climb = q[6];
            t.descent = q[6] * (0.85 + rnd.nextDouble() * 0.3);
            t.maxKmh = q[7];
            // Una parada de 14 min en el viaje largo y otra de 6 en el de la tarde (para ver las paradas en el mapa).
            double stopMin = q[3] > 100 ? 14 : q[0] == 1 ? 6 : 0;
            t.track = track(rnd, q[3], q[4], stopMin);
            out.add(t);
        }
        return out;
    }

    /**
     * Recorrido verosímil: tramos rectos con giros, más largos cuanto más largo el viaje, con el segundo de cada punto
     * y, si stopMin > 0, una parada de ese rato a un 40 % del camino.
     */
    private static double[][] track(Random rnd, double km, double minutes, double stopMin) {
        int pts = 70;
        double moveSec = Math.max(60, (minutes - stopMin) * 60);
        int stopAt = stopMin > 0 ? (int) (pts * 0.4) : -1;
        double la = 40.43 + rnd.nextDouble() * 0.05;
        double lo = -3.70 + rnd.nextDouble() * 0.05;
        double hdg = rnd.nextDouble() * 360;
        double stepM = km * 1000 / pts;
        java.util.List<double[]> list = new java.util.ArrayList<>();
        for (int i = 0; i < pts; i++) {
            if (rnd.nextDouble() < 0.18) hdg += (rnd.nextBoolean() ? 1 : -1) * (35 + rnd.nextDouble() * 60);
            else hdg += rnd.nextGaussian() * 6;
            la += stepM * Math.cos(Math.toRadians(hdg)) / 111320;
            lo += stepM * Math.sin(Math.toRadians(hdg)) / (111320 * Math.cos(Math.toRadians(la)));
            double sec = moveSec * i / (pts - 1) + (stopAt >= 0 && i > stopAt ? stopMin * 60 : 0);
            list.add(new double[]{Math.round(la * 1e5) / 1e5, Math.round(lo * 1e5) / 1e5, Math.round(sec)});
            // Tras la parada, el primer punto llega a unos 40 m (como el registro real, que apunta cada 30 m movidos).
            if (i == stopAt) list.add(new double[]{Math.round((la + 0.00036) * 1e5) / 1e5, Math.round(lo * 1e5) / 1e5,
                    Math.round(sec + stopMin * 60)});
        }
        return list.toArray(new double[0][]);
    }
}
