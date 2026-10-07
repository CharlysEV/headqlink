package com.headqlink.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Historial oficial de la nube de Leapmotor: los viajes con los kWh (y, en un REEV, los litros de gasolina) que mide el
 * coche, los mismos que enseña la app oficial; y el consumo medio de las últimas semanas. Interpretación, cruce con
 * los viajes de HeadQLink y caché; sin Android ni red: se prueba en el PC.
 *
 * Campos de leapmotor-mate (ProtossBlaster, AGPL-3.0): routeStartTs y routeEndTs en ms, totalMileage en km,
 * totalEnergy en kWh, driveReevOil en litros y maxSpeed. La nube deja registros vacíos (0 km y 0 kWh): se descartan,
 * salvo los que llevan gasolina (el generador cargando con el coche parado).
 */
final class CloudHistory {
    /** Un viaje según el coche. NaN: no lo dijo. */
    static final class Trip {
        final long startMs;
        final long endMs;
        final double km;
        final double kwh;
        final double fuelL;
        final double maxKmh;

        Trip(long startMs, long endMs, double km, double kwh, double fuelL, double maxKmh) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.km = km;
            this.kwh = kwh;
            this.fuelL = fuelL;
            this.maxKmh = maxKmh;
        }
    }

    /** Consumo medio según el coche: el de las últimas semanas y el de cada una (kWh/100 km). */
    static final class Weekly {
        final double avgKwhPer100;
        final double[] weeks;

        Weekly(double avgKwhPer100, double[] weeks) {
            this.avgKwhPer100 = avgKwhPer100;
            this.weeks = weeks;
        }
    }

    /** Cuánto se guarda y se pide hacia atrás. */
    static final long KEEP_MS = 35L * 86_400_000L;

    private CloudHistory() {
    }

    // ------------------------------------------------------------------ interpretar

    /** Los viajes de una página del historial («data» con «list»), sin los registros vacíos. */
    static List<Trip> parsePage(JSONObject data) {
        List<Trip> out = new ArrayList<>();
        JSONArray rows = data == null ? null : data.optJSONArray("list");
        if (rows == null) return out;
        for (int i = 0; i < rows.length(); i++) {
            Trip t = parseRow(rows.optJSONObject(i));
            if (t != null) out.add(t);
        }
        return out;
    }

    static Trip parseRow(JSONObject row) {
        if (row == null) return null;
        long start = LeapStatus.toEpochMs(row.opt("routeStartTs"));
        long end = LeapStatus.toEpochMs(row.opt("routeEndTs"));
        if (start <= 0 || end < start) return null;
        double km = LeapStatus.asDouble(row.opt("totalMileage"));
        double kwh = LeapStatus.asDouble(row.opt("totalEnergy"));
        double fuel = LeapStatus.asDouble(row.opt("driveReevOil"));
        double vmax = LeapStatus.asDouble(row.opt("maxSpeed"));
        if (bad(km) || bad(kwh)) return null;
        if (!Double.isNaN(fuel) && fuel < 0) fuel = Double.NaN;
        // Registro vacío: ni distancia ni energía ni gasolina.
        if (km <= 0 && kwh <= 0 && !(fuel > 0)) return null;
        return new Trip(start, end, km, kwh, fuel, vmax);
    }

    private static boolean bad(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) || v < 0;
    }

    /** Sin la gasolina: un eléctrico puro también manda driveReevOil (a 0) y no debe salir «0 L» en cada viaje. */
    static List<Trip> withoutFuel(List<Trip> trips) {
        List<Trip> out = new ArrayList<>(trips.size());
        for (Trip t : trips) out.add(new Trip(t.startMs, t.endMs, t.km, t.kwh, Double.NaN, t.maxKmh));
        return out;
    }

    /** Páginas que faltan según la primera respuesta (totalPage), como mucho maxPages. */
    static int pages(JSONObject first, int maxPages) {
        int total = first == null ? 0 : first.optInt("totalPage", 1);
        return Math.max(1, Math.min(maxPages, total));
    }

    /** El consumo de las últimas semanas («data» de getLastNweeks100kmECAndRank), o null si no hay cifras. */
    static Weekly parseWeekly(JSONObject data) {
        if (data == null) return null;
        JSONObject rank = data.optJSONObject("rankResult");
        double avg = rank == null ? Double.NaN : LeapStatus.asDouble(rank.opt("hundredKmEC"));
        JSONArray w = data.optJSONArray("weeklyEC");
        List<Double> weeks = new ArrayList<>();
        if (w != null) {
            for (int i = 0; i < w.length(); i++) {
                JSONObject o = w.optJSONObject(i);
                double v = o == null ? Double.NaN : LeapStatus.asDouble(o.opt("hundredKmEC"));
                weeks.add(plausible(v) ? v : Double.NaN);
            }
        }
        if (!plausible(avg)) avg = Double.NaN;
        boolean any = !Double.isNaN(avg);
        for (double v : weeks) any |= !Double.isNaN(v);
        if (!any) return null;
        double[] a = new double[weeks.size()];
        for (int i = 0; i < a.length; i++) a[i] = weeks.get(i);
        return new Weekly(avg, a);
    }

    /** Un consumo de coche eléctrico creíble (kWh/100 km). */
    static boolean plausible(double kwhPer100) {
        return !Double.isNaN(kwhPer100) && kwhPer100 >= 5 && kwhPer100 <= 60;
    }

    // ------------------------------------------------------------------ juntar y cruzar

    /**
     * Junta lo guardado con lo nuevo (lo nuevo manda si coincide el inicio), sin lo anterior a keepFromMs, del más
     * reciente al más antiguo.
     */
    static List<Trip> merge(List<Trip> old, List<Trip> fresh, long keepFromMs) {
        java.util.Map<Long, Trip> byStart = new java.util.TreeMap<>(Collections.reverseOrder());
        if (old != null) for (Trip t : old) if (t.startMs >= keepFromMs) byStart.put(t.startMs, t);
        if (fresh != null) for (Trip t : fresh) if (t.startMs >= keepFromMs) byStart.put(t.startMs, t);
        return new ArrayList<>(byStart.values());
    }

    /** Lo que dice el coche de un viaje de HeadQLink: la suma de sus viajes que caen dentro. */
    static final class Match {
        final int trips;
        final double km;
        final double kwh;
        final double fuelL;

        Match(int trips, double km, double kwh, double fuelL) {
            this.trips = trips;
            this.km = km;
            this.kwh = kwh;
            this.fuelL = fuelL;
        }

        /** kWh/100 km según el coche, o NaN si hay poco recorrido o no es creíble. */
        double kwhPer100() {
            if (km < 0.5) return Double.NaN;
            double v = kwh / km * 100;
            return v >= 0 && v <= 80 ? v : Double.NaN;
        }

        /** L/100 km de gasolina según el coche, o NaN. */
        double litersPer100() {
            return km >= 0.5 && !Double.isNaN(fuelL) ? fuelL / km * 100 : Double.NaN;
        }
    }

    /** Margen al cruzar: la nube y el móvil no empiezan ni acaban el viaje en el mismo segundo. */
    static final long MATCH_SLACK_MS = 5 * 60_000L;

    /**
     * Viajes del coche contenidos en [fromMs, toMs] (con margen) que solapan de verdad con él. null si no hay ninguno.
     */
    static Match match(List<Trip> cloud, long fromMs, long toMs) {
        if (cloud == null || toMs <= fromMs) return null;
        int n = 0;
        double km = 0;
        double kwh = 0;
        double fuel = 0;
        boolean anyFuel = false;
        for (Trip t : cloud) {
            if (t.startMs < fromMs - MATCH_SLACK_MS || t.endMs > toMs + MATCH_SLACK_MS) continue;
            // Debe solapar con el viaje (no solo caer en el margen).
            if (t.endMs < fromMs || t.startMs > toMs) continue;
            n++;
            km += t.km;
            kwh += t.kwh;
            if (!Double.isNaN(t.fuelL)) {
                fuel += t.fuelL;
                anyFuel = true;
            }
        }
        return n == 0 ? null : new Match(n, km, kwh, anyFuel ? fuel : Double.NaN);
    }

    /** Totales del coche desde fromMs: km, kWh y litros (NaN si ningún viaje dijo gasolina). */
    static Match totals(List<Trip> cloud, long fromMs) {
        if (cloud == null) return null;
        int n = 0;
        double km = 0;
        double kwh = 0;
        double fuel = 0;
        boolean anyFuel = false;
        for (Trip t : cloud) {
            if (t.startMs < fromMs) continue;
            n++;
            km += t.km;
            kwh += t.kwh;
            if (!Double.isNaN(t.fuelL)) {
                fuel += t.fuelL;
                anyFuel = true;
            }
        }
        return n == 0 ? null : new Match(n, km, kwh, anyFuel ? fuel : Double.NaN);
    }

    // ------------------------------------------------------------------ caché

    static JSONObject toJson(List<Trip> trips, Weekly weekly, long tripsAtMs, long weeklyAtMs) throws JSONException {
        JSONObject o = new JSONObject();
        JSONArray a = new JSONArray();
        if (trips != null) {
            for (Trip t : trips) {
                JSONObject r = new JSONObject();
                r.put("s", t.startMs);
                r.put("e", t.endMs);
                r.put("km", t.km);
                r.put("kwh", t.kwh);
                if (!Double.isNaN(t.fuelL)) r.put("l", t.fuelL);
                if (!Double.isNaN(t.maxKmh)) r.put("v", t.maxKmh);
                a.put(r);
            }
        }
        o.put("trips", a);
        o.put("tripsAt", tripsAtMs);
        if (weekly != null) {
            JSONObject w = new JSONObject();
            if (!Double.isNaN(weekly.avgKwhPer100)) w.put("avg", weekly.avgKwhPer100);
            JSONArray ws = new JSONArray();
            for (double v : weekly.weeks) ws.put(Double.isNaN(v) ? JSONObject.NULL : v);
            w.put("weeks", ws);
            o.put("weekly", w);
        }
        o.put("weeklyAt", weeklyAtMs);
        return o;
    }

    static List<Trip> tripsFromJson(JSONObject o) {
        List<Trip> out = new ArrayList<>();
        JSONArray a = o == null ? null : o.optJSONArray("trips");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null) continue;
            out.add(new Trip(r.optLong("s"), r.optLong("e"), r.optDouble("km", 0), r.optDouble("kwh", 0),
                    r.has("l") ? r.optDouble("l") : Double.NaN, r.has("v") ? r.optDouble("v") : Double.NaN));
        }
        return out;
    }

    static Weekly weeklyFromJson(JSONObject o) {
        JSONObject w = o == null ? null : o.optJSONObject("weekly");
        if (w == null) return null;
        JSONArray ws = w.optJSONArray("weeks");
        double[] a = new double[ws == null ? 0 : ws.length()];
        for (int i = 0; i < a.length; i++) a[i] = ws.isNull(i) ? Double.NaN : ws.optDouble(i);
        return new Weekly(w.has("avg") ? w.optDouble("avg") : Double.NaN, a);
    }

    /** Resumen para el log: sin horas exactas ni nada personal. */
    static String logLine(List<Trip> trips, long sinceMs) {
        Match m = totals(trips, sinceMs);
        if (m == null) return "0 viajes";
        String s = String.format(Locale.US, "%d viajes, %.0f km, %.1f kWh", m.trips, m.km, m.kwh);
        double v = m.kwhPer100();
        if (!Double.isNaN(v)) s += String.format(Locale.US, " (%.1f kWh/100 km)", v);
        if (!Double.isNaN(m.fuelL)) s += String.format(Locale.US, ", %.2f L de gasolina", m.fuelL);
        return s;
    }
}
