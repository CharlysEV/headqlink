package com.headqlink.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Ajuste de la previsión de las rutas a lo que gasta de verdad el coche: al llegar, se compara lo previsto con lo real
 * (la bajada del % de la nube de Leapmotor por la capacidad) y con las últimas rutas se saca un factor para la parte
 * que depende de la conducción (aire, rodadura, paradas, climatización); la de las cuestas es física y no se toca.
 * Sin Android: se prueba en el PC.
 */
final class RouteCalibration {
    /** Una ruta terminada: lo previsto y lo real (kWh) y sus km. */
    static final class Sample {
        final double predictedKwh;
        final double realKwh;
        final double km;
        final long atMs;
        /** Con qué modelo se previó (las de antes de MODEL_VERSION no sirven para corregir el de ahora). */
        final int version;

        Sample(double predictedKwh, double realKwh, double km, long atMs) {
            this(predictedKwh, realKwh, km, atMs, MODEL_VERSION);
        }

        Sample(double predictedKwh, double realKwh, double km, long atMs, int version) {
            this.predictedKwh = predictedKwh;
            this.realKwh = realKwh;
            this.km = km;
            this.atMs = atMs;
            this.version = version;
        }
    }

    /**
     * 2: la previsión lleva el gasto extra del coche (Overhead: por viaje y por hora) en lugar de multiplicar toda la
     * conducción; las llegadas medidas con el modelo de antes (1) no corrigen este.
     */
    static final int MODEL_VERSION = 2;

    /**
     * Lo que el modelo físico (aire, rodadura, cuestas, arranques) no ve y el coche sí gasta: un fijo por viaje (kWh:
     * arrancar, enfriar o calentar al principio) y uno por hora (kW: climatización, electrónica). Sale del historial del
     * coche; en ciudad pesa mucho (viajes cortos y lentos) y en autovía poco, al revés que un factor que lo multiplica
     * todo (con el de 1,5 de los viajes cortos, un viaje de 300 km por autovía salía un 24 % por encima de ABRP).
     */
    static final class Overhead {
        final double perTripKwh;
        final double kw;
        final int trips;

        Overhead(double perTripKwh, double kw, int trips) {
            this.perTripKwh = perTripKwh;
            this.kw = kw;
            this.trips = trips;
        }

        static final Overhead NONE = new Overhead(0, 0, 0);
    }

    static final double MAX_PER_TRIP_KWH = 1.0;
    static final double MAX_OVERHEAD_KW = 2.5;
    /** Corrección que queda con las llegadas del modelo de ahora (acotada: el grueso ya lo explica Overhead). */
    static final double RESIDUAL_MIN = 0.85;
    static final double RESIDUAL_MAX = 1.25;

    /**
     * El gasto extra del coche con su historial: el exceso de cada viaje sobre el modelo (lo que dice el coche menos lo
     * que da el modelo en llano a su velocidad media, con arranques y frenadas) se ajusta a «fijo + kW × horas» por
     * mínimos cuadrados, acotado. NONE con poco recorrido.
     */
    static Overhead overhead(List<CloudHistory.Trip> trips, double tempC) {
        if (trips == null) return Overhead.NONE;
        EnergyModel m = new EnergyModel();
        int n = 0;
        double km = 0;
        double sh = 0;
        double shh = 0;
        double se = 0;
        double she = 0;
        for (CloudHistory.Trip t : trips) {
            double h = (t.endMs - t.startMs) / 3_600_000.0;
            if (t.km < HISTORY_MIN_KM || h < 0.05 || t.kwh <= 0) continue;
            double v = Math.max(5, Math.min(130, t.km / h));
            double model = m.compute(v, 0, 0, tempC, 0) * h + RoutePlanner.stopAndGoKwh(v, t.km);
            double e = t.kwh - model;
            n++;
            km += t.km;
            sh += h;
            shh += h * h;
            se += e;
            she += h * e;
        }
        if (n < 3 || km < HISTORY_MIN_TOTAL_KM || sh <= 0) return Overhead.NONE;
        double det = n * shh - sh * sh;
        double a;
        double b;
        if (det > 1e-9 * n * n) {
            b = (n * she - sh * se) / det;
            a = (se - b * sh) / n;
        } else {
            a = 0;
            b = se / sh;
        }
        // Acotados; si el fijo se sale, el de por hora se rehace con el fijo en su tope.
        if (a < 0 || a > MAX_PER_TRIP_KWH) {
            a = Math.max(0, Math.min(MAX_PER_TRIP_KWH, a));
            b = (se - n * a) / sh;
        }
        b = Math.max(0, Math.min(MAX_OVERHEAD_KW, b));
        return new Overhead(a, b, n);
    }

    /** La corrección que queda: lo real entre lo previsto de las llegadas del modelo de ahora, prudente y acotada. */
    static double residual(List<Sample> samples) {
        double real = 0;
        double pred = 0;
        if (samples != null) {
            for (Sample s : samples) {
                if (s.version < MODEL_VERSION) continue;
                real += s.realKwh;
                pred += s.predictedKwh;
            }
        }
        double f = (real + PRIOR_KWH) / (pred + PRIOR_KWH);
        return Math.max(RESIDUAL_MIN, Math.min(RESIDUAL_MAX, f));
    }

    /** Las últimas rutas que cuentan. */
    static final int KEEP = 12;
    /** Rutas muy cortas o de poco gasto no dicen nada (la resolución del % de la nube es de 0,1). */
    static final double MIN_KM = 2.0;
    static final double MIN_REAL_KWH = 0.3;
    /** Peso de «sin datos» (kWh): con pocas rutas el factor se queda cerca de 1. */
    static final double PRIOR_KWH = 1.0;
    static final double MIN_FACTOR = 0.8;
    static final double MAX_FACTOR = 1.5;

    private RouteCalibration() {
    }

    /** ¿Sirve esta llegada para ajustar? (descarta cargas por el camino y datos absurdos). */
    static boolean usable(double predictedKwh, double realKwh, double km) {
        if (Double.isNaN(predictedKwh) || Double.isNaN(realKwh) || Double.isNaN(km)) return false;
        if (km < MIN_KM || realKwh < MIN_REAL_KWH || predictedKwh <= 0.05) return false;
        double ratio = realKwh / predictedKwh;
        return ratio > 0.4 && ratio < 2.5;
    }

    /** Factor de la parte de conducción: lo real entre lo previsto de las últimas rutas, acotado y prudente. */
    static double factor(List<Sample> samples) {
        return factor(samples, 1.0);
    }

    /**
     * Igual, partiendo de prior (el factor que sale del historial del coche, historyFactor; 1 sin él): con pocas rutas
     * medidas manda prior, y cuantas más, más mandan ellas.
     */
    static double factor(List<Sample> samples, double prior) {
        double p = Double.isNaN(prior) ? 1.0 : Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, prior));
        double real = 0;
        double pred = 0;
        if (samples != null) {
            for (Sample s : samples) {
                real += s.realKwh;
                pred += s.predictedKwh;
            }
        }
        double f = (real + PRIOR_KWH * p) / (pred + PRIOR_KWH);
        return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, f));
    }

    /** Viajes del historial que cuentan para el factor: ni muy cortos ni de un momento. */
    static final double HISTORY_MIN_KM = 3;
    static final double HISTORY_MIN_TOTAL_KM = 20;

    /**
     * Factor de la conducción según el historial del coche (CloudHistory): los kWh que dice el coche de sus viajes entre
     * los que daría el modelo en llano a la velocidad media de cada uno (con sus arranques y frenadas). NaN con poco
     * recorrido. Así la previsión arranca con el consumo real de ese coche y de ese conductor, antes de medir rutas.
     */
    static double historyFactor(List<CloudHistory.Trip> trips, double tempC) {
        if (trips == null) return Double.NaN;
        EnergyModel m = new EnergyModel();
        double real = 0;
        double model = 0;
        double km = 0;
        for (CloudHistory.Trip t : trips) {
            double h = (t.endMs - t.startMs) / 3_600_000.0;
            if (t.km < HISTORY_MIN_KM || h < 0.05 || t.kwh <= 0) continue;
            double v = Math.max(5, Math.min(130, t.km / h));
            model += m.compute(v, 0, 0, tempC, 0) * h + RoutePlanner.stopAndGoKwh(v, t.km);
            real += t.kwh;
            km += t.km;
        }
        if (km < HISTORY_MIN_TOTAL_KM || model <= 0) return Double.NaN;
        return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, real / model));
    }

    /** Añade una llegada (las más recientes primero) y se queda con las KEEP últimas. */
    static List<Sample> add(List<Sample> old, Sample s) {
        List<Sample> out = new ArrayList<>();
        out.add(s);
        if (old != null) for (Sample o : old) if (out.size() < KEEP) out.add(o);
        return out;
    }

    static String toJson(List<Sample> samples) {
        JSONArray a = new JSONArray();
        try {
            for (Sample s : samples) {
                a.put(new JSONObject().put("p", s.predictedKwh).put("r", s.realKwh).put("km", s.km).put("t", s.atMs).put("v", s.version));
            }
        } catch (JSONException ignored) {
            // Números finitos: no pasa.
        }
        return a.toString();
    }

    static List<Sample> fromJson(String json) {
        List<Sample> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Sample(o.getDouble("p"), o.getDouble("r"), o.getDouble("km"), o.optLong("t"), o.optInt("v", 1)));
            }
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return out;
    }
}
