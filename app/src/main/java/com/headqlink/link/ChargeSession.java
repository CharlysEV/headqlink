package com.headqlink.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Una carga en directo: las lecturas de la nube (%, kW) desde que se enchufa hasta que se desenchufa, el % con el que
 * se puede seguir según el plan («ya puedes seguir»), el que ahorra la siguiente parada, la hora a la que estará lista y
 * si carga más lento de lo esperado. Sin Android: se prueba en el PC. Se guarda en el historial (ChargeLog) para
 * aprender la curva real del C10.
 */
final class ChargeSession {
    /** Una lectura de la nube (hora del dato del coche, %, kW que entran en la batería). */
    static final class Sample {
        final long atMs;
        final double soc;
        final double kw;

        Sample(long atMs, double soc, double kw) {
            this.atMs = atMs;
            this.soc = soc;
            this.kw = kw;
        }
    }

    enum Event {
        /** Llega al % con el que se puede seguir. */
        READY,
        /** Carga mucho más lento de lo esperado. */
        SLOW,
        /** Se acabó (desenchufado, completa, sin datos o demasiado larga). */
        ENDED
    }

    /** Lento: por debajo de esta parte de lo esperado en SLOW_SAMPLES lecturas seguidas. */
    static final double SLOW_RATIO = 0.6;
    static final int SLOW_SAMPLES = 2;
    /** Lo lento solo cuenta pasado esto (el arranque de la carga es lento) y por debajo de este % (arriba baja sola). */
    static final long SLOW_AFTER_MS = 3 * 60_000L;
    static final double SLOW_BELOW_PCT = 80;
    /** Sin datos nuevos de la nube en esto, o más larga que esto: se da por terminada. */
    static final long STALE_MS = 30 * 60_000L;
    static final long MAX_MS = 3 * 3600_000L;
    /** El ritmo real se mide en esta ventana, con al menos RATE_MIN_MS entre la primera y la última lectura. */
    static final long RATE_WINDOW_MS = 10 * 60_000L;
    static final long RATE_MIN_MS = 3 * 60_000L;
    /** Margen sobre el % mínimo para seguir. */
    static final double READY_MARGIN_PCT = 2;
    static final int MAX_SAMPLES = 400;

    final long startMs;
    final double startSoc;
    final boolean dc;
    /** El cargador, si se sabe (parada del plan o el más cercano de la ruta); null si no. */
    final RoutePlanner.Charger charger;
    final double capacityKwh;
    final double carPeakKw;
    final double carVolts;

    // Lo que dice el plan (NaN o vacío sin ruta).
    volatile double targetPct = Double.NaN;
    /** Con el objetivo: % al llegar a la siguiente parada (o al destino) y su nombre. */
    volatile double nextArrivePct = Double.NaN;
    volatile String nextName = "";
    /** Hasta este % se ahorra la siguiente parada (nombre, minutos de más aquí, minutos que se ahorran allí). */
    volatile double skipPct = Double.NaN;
    volatile String skipName = "";
    volatile double skipExtraMin = Double.NaN;
    volatile double skipSavedMin = Double.NaN;

    /** De la demostración (DemoMode): no se mezcla con datos reales ni se guarda en el historial. */
    volatile boolean demo;

    final List<Sample> samples = new ArrayList<>();
    /** Lo que dice el coche que le falta (a su tope), min; NaN sin dato. */
    double carRemainMin = Double.NaN;
    long endMs;
    double endSoc = Double.NaN;
    boolean readyFired;
    boolean slowFired;
    boolean ended;
    private int lowStreak;
    /** La potencia esperada y la vista cuando saltó SLOW (para el aviso). */
    double slowKw = Double.NaN;
    double slowExpectedKw = Double.NaN;

    ChargeSession(long startMs, double startSoc, boolean dc, RoutePlanner.Charger charger, double capacityKwh, double carPeakKw,
                  double carVolts) {
        this.startMs = startMs;
        this.startSoc = startSoc;
        this.dc = dc;
        this.charger = charger;
        this.capacityKwh = capacityKwh;
        this.carPeakKw = carPeakKw;
        this.carVolts = carVolts;
    }

    /** El objetivo del plan: «ya puedes seguir» con readyPct (más el margen), y lo que hay después. */
    synchronized void setTarget(double readyPct, double nextArrivePct, String nextName) {
        this.targetPct = Double.isNaN(readyPct) ? Double.NaN : Math.min(100, readyPct + READY_MARGIN_PCT);
        this.nextArrivePct = nextArrivePct;
        this.nextName = nextName == null ? "" : nextName;
    }

    /** ¿Compensa ahorrarse la siguiente parada? (se carga aquí menos tiempo del que se ahorra allí). */
    boolean skipWorth() {
        return !Double.isNaN(skipPct) && skipExtraMin < skipSavedMin;
    }

    synchronized void setSkip(double pct, String name, double extraMin, double savedMin) {
        this.skipPct = pct;
        this.skipName = name == null ? "" : name;
        this.skipExtraMin = extraMin;
        this.skipSavedMin = savedMin;
    }

    /**
     * Una lectura de la nube (atMs: hora del dato del coche; nowMs: ahora). Devuelve lo que ha pasado, cada evento una
     * sola vez. Las lecturas repetidas (el mismo dato) no cuentan para la potencia ni el ritmo.
     */
    synchronized List<Event> observe(long atMs, double soc, double kw, boolean plugged, boolean completed, double remainMin,
                                     long nowMs) {
        List<Event> out = new ArrayList<>();
        if (ended) return out;
        Sample last = last();
        // Solo lecturas con el coche enchufado (la de después de desenchufar ya no es de la carga).
        boolean fresh = plugged && !Double.isNaN(soc) && (last == null || atMs > last.atMs);
        if (fresh) {
            if (samples.size() >= MAX_SAMPLES) samples.remove(1);
            samples.add(new Sample(atMs, soc, Double.isNaN(kw) ? Double.NaN : Math.abs(kw)));
            carRemainMin = remainMin;
            last = last();
        }
        if (last != null && !readyFired && !Double.isNaN(targetPct) && last.soc >= targetPct - 0.25) {
            readyFired = true;
            out.add(Event.READY);
        }
        if (fresh && plugged && !completed && dc && !slowFired && !Double.isNaN(last.kw) && atMs - startMs >= SLOW_AFTER_MS && last.soc < SLOW_BELOW_PCT) {
            double expected = expectedKw(last.soc);
            if (last.kw < SLOW_RATIO * expected) lowStreak++;
            else lowStreak = 0;
            if (lowStreak >= SLOW_SAMPLES) {
                slowFired = true;
                slowKw = last.kw;
                slowExpectedKw = expected;
                out.add(Event.SLOW);
            }
        }
        long lastData = last == null ? startMs : last.atMs;
        if (!plugged || completed || nowMs - lastData > STALE_MS || nowMs - startMs > MAX_MS) {
            ended = true;
            endMs = last == null ? nowMs : last.atMs;
            endSoc = last == null ? startSoc : last.soc;
            out.add(Event.ENDED);
        }
        return out;
    }

    synchronized Sample last() {
        return samples.isEmpty() ? null : samples.get(samples.size() - 1);
    }

    /** % de ahora (la última lectura), o el de inicio. */
    synchronized double soc() {
        Sample l = last();
        return l == null ? startSoc : l.soc;
    }

    /** ¿Es un cargador de 400–500 V para este coche de 800 V? (carga a la mitad). */
    boolean lowVolts() {
        return charger != null && ChargePlanner.lowVolts(charger, carVolts);
    }

    /** Pico que admite el coche en este cargador (kW): a la mitad en uno de 400–500 V. */
    double peakKw() {
        return carPeakKw * (lowVolts() ? ChargePlanner.LOW_VOLTS_FACTOR : 1);
    }

    /** Potencia que debería entrar en la batería a este % (kW): lo menos del cargador y la curva del coche. */
    double expectedKw(double pct) {
        double car = ChargePlanner.carPowerKw(pct, peakKw());
        if (charger == null || !(charger.maxKw > 0)) return car;
        return Math.min(charger.maxKw * ChargePlanner.CHARGE_EFF, car);
    }

    /** kW que entran de verdad, por el ritmo de subida del % en los últimos minutos; NaN si aún no se puede saber. */
    synchronized double rateKw() {
        Sample l = last();
        if (l == null) return Double.NaN;
        Sample ref = null;
        for (Sample s : samples) {
            if (s.atMs >= l.atMs - RATE_WINDOW_MS && l.atMs - s.atMs >= RATE_MIN_MS) {
                ref = s;
                break;
            }
        }
        if (ref == null) return Double.NaN;
        double minutes = (l.atMs - ref.atMs) / 60_000.0;
        double pctPerMin = (l.soc - ref.soc) / minutes;
        if (pctPerMin <= 0) return Double.NaN;
        return pctPerMin * capacityKwh / 100 * 60;
    }

    /**
     * Minutos desde la última lectura hasta llegar a pct: la curva del coche, escalada por lo que entra de verdad ahora
     * (el ritmo real o, si aún no se sabe, la potencia que da el coche) frente a lo que daría la curva a este %. NaN sin
     * lecturas.
     */
    synchronized double minutesTo(double pct) {
        Sample l = last();
        if (l == null || Double.isNaN(pct)) return Double.NaN;
        if (l.soc >= pct) return 0;
        double chargerKw = charger != null && charger.maxKw > 0 ? charger.maxKw : 0;
        double model = ChargePlanner.minutes(l.soc, pct, chargerKw, peakKw(), capacityKwh);
        double seen = rateKw();
        if (Double.isNaN(seen)) seen = l.kw;
        double expected = expectedKw(l.soc);
        if (Double.isNaN(seen) || seen <= 0.5 || expected <= 0) return model;
        double scale = Math.max(0.5, Math.min(3, expected / seen));
        return model * scale;
    }

    /** Hora (ms) a la que estará en el objetivo; sin objetivo, la que da el coche para su tope. NaN si no se sabe. */
    synchronized double readyAtMs() {
        Sample l = last();
        if (l == null) return Double.NaN;
        double m = !Double.isNaN(targetPct) ? minutesTo(targetPct) : carRemainMin;
        if (Double.isNaN(m)) return Double.NaN;
        return l.atMs + m * 60_000;
    }

    /** kWh cargados hasta ahora (por el %). */
    synchronized double kwhAdded() {
        return Math.max(0, (soc() - startSoc) / 100 * capacityKwh);
    }

    // ------------------------------------------------------------------ JSON (historial)

    synchronized JSONObject toJson() {
        try {
            JSONObject o = new JSONObject().put("start", startMs).put("startSoc", startSoc).put("dc", dc).put("cap", capacityKwh)
                    .put("peak", carPeakKw).put("volts", carVolts).put("end", endMs).put("endSoc", nan(endSoc))
                    .put("target", nan(targetPct)).put("ready", readyFired).put("slow", slowFired);
            if (charger != null) {
                o.put("charger", new JSONObject().put("name", charger.name == null ? "" : charger.name).put("net", charger.network)
                        .put("kw", charger.maxKw).put("v", charger.maxVolts).put("src", charger.source).put("lat", nan(charger.lat))
                        .put("lon", nan(charger.lon)));
            }
            JSONArray a = new JSONArray();
            for (Sample s : samples) a.put(new JSONArray().put(s.atMs).put(s.soc).put(nan(s.kw)));
            o.put("samples", a);
            return o;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static ChargeSession fromJson(JSONObject o) throws JSONException {
        RoutePlanner.Charger c = null;
        JSONObject j = o.optJSONObject("charger");
        if (j != null) {
            c = new RoutePlanner.Charger();
            c.name = j.optString("name");
            c.detail = "";
            c.network = j.optString("net", ChargerFilter.OTHER);
            c.maxKw = j.optDouble("kw", 0);
            c.maxVolts = j.optDouble("v", 0);
            c.source = j.optInt("src", RoutePlanner.Charger.SOURCE_OSM);
            c.lat = j.optDouble("lat", Double.NaN);
            c.lon = j.optDouble("lon", Double.NaN);
        }
        ChargeSession s = new ChargeSession(o.getLong("start"), o.getDouble("startSoc"), o.optBoolean("dc"), c, o.optDouble("cap", 0),
                o.optDouble("peak", 0), o.optDouble("volts", 400));
        s.endMs = o.optLong("end");
        s.endSoc = o.optDouble("endSoc", Double.NaN);
        s.targetPct = o.optDouble("target", Double.NaN);
        s.readyFired = o.optBoolean("ready");
        s.slowFired = o.optBoolean("slow");
        s.ended = true;
        JSONArray a = o.optJSONArray("samples");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONArray x = a.getJSONArray(i);
                s.samples.add(new Sample(x.getLong(0), x.getDouble(1), x.optDouble(2, Double.NaN)));
            }
        }
        return s;
    }

    /** JSON no admite NaN: se guarda como null (y optDouble lo devuelve como NaN). */
    private static Object nan(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? JSONObject.NULL : v;
    }
}
