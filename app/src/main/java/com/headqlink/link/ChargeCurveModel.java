package com.headqlink.link;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * La curva de carga real del coche, aprendida de las cargas rápidas guardadas (ChargeLog): kW que admite frente al %, en
 * tramos de 5 %. Solo cuentan las lecturas en las que mandaba el coche (la potencia, claramente por debajo de la del
 * cargador); en cada tramo, un percentil alto de lo visto si hay bastantes cargas distintas, y si no, la de fábrica
 * (ChargePlanner.carPowerKw). Se mezcla con la de fábrica según la confianza (cuantas más cargas, más pesa la aprendida).
 *
 * Además comprueba las reglas del plan con los datos: si en los cargadores de 400–500 V un coche de 800 V carga de verdad
 * a la mitad (ChargePlanner.LOW_VOLTS_FACTOR) y cuánto dan los de 150 kW o más frente a lo esperado, por red. Cada coche
 * (por su pico de carga: T03, C10, C10 de 800 V, REEV) tiene lo suyo. Lo puro se prueba en el PC.
 */
final class ChargeCurveModel {
    static final double BIN_PCT = 5;
    static final int BINS = 20;
    /** Lectura en la que manda el coche: por debajo de esta parte de la potencia anunciada del cargador. */
    static final double CAR_LIMITED = 0.85;
    /** Un tramo se aprende con al menos estas cargas distintas y lecturas. */
    static final int MIN_SESSIONS = 3;
    static final int MIN_SAMPLES = 8;
    /** Percentil de lo visto en el tramo (alto: el arranque, el final o un cargador compartido dan menos). */
    static final double PERCENTILE = 0.8;
    /** Lo aprendido no pasa de esto frente a la de fábrica (una lectura rara no dispara la curva). */
    static final double MAX_RATIO = 1.25;
    /** Peso de la de fábrica (en cargas) y lo máximo que pesa la aprendida. */
    static final double PRIOR_SESSIONS = 3;
    static final double MAX_WEIGHT = 0.85;
    /** Por debajo de esto (kW) la lectura es el arranque o el final de la carga: no cuenta. */
    static final double MIN_KW = 3;
    /** Misma carga del mismo coche: el pico guardado en la carga, con esta tolerancia (kW). */
    static final double PEAK_TOL_KW = 2;
    /** La regla de los 400–500 V solo se cambia con estas cargas y si lo visto se aleja al menos esto de la mitad. */
    static final int LOW_VOLTS_MIN_SESSIONS = 3;
    static final double LOW_VOLTS_MIN_DIFF = 0.1;
    /** Desde aquí un cargador es de los de «150 kW o más» (kW), y con cuántas cargas se enseña su resumen. */
    static final double HPC_KW = 150;
    static final int HPC_MIN_SESSIONS = 3;
    static final int NETWORK_MIN_SESSIONS = 2;
    /** Lecturas mínimas de una carga para medir su pico o su factor. */
    static final int SESSION_MIN_SAMPLES = 3;

    private ChargeCurveModel() {
    }

    // ------------------------------------------------------------------ la curva

    /** La curva aprendida de un coche (pico de fábrica peakKw): por tramo de 5 %, lo aprendido (NaN sin bastantes datos). */
    static final class Curve {
        final double peakKw;
        final double[] kw = new double[BINS];
        final int[] sessions = new int[BINS];
        final int[] samples = new int[BINS];
        /** Cargas distintas que forman los tramos aprendidos. */
        int totalSessions;

        Curve(double peakKw) {
            this.peakKw = peakKw;
            Arrays.fill(kw, Double.NaN);
        }

        /** ¿Hay algún tramo aprendido? */
        boolean learned() {
            for (double v : kw) if (!Double.isNaN(v)) return true;
            return false;
        }

        int learnedBins() {
            int n = 0;
            for (double v : kw) if (!Double.isNaN(v)) n++;
            return n;
        }

        /** Cuánto pesa lo aprendido en el tramo (0 sin datos; crece con las cargas, hasta MAX_WEIGHT). */
        double weight(int bin) {
            if (Double.isNaN(kw[bin])) return 0;
            return Math.min(MAX_WEIGHT, sessions[bin] / (sessions[bin] + PRIOR_SESSIONS));
        }

        /** Lo aprendido frente a la de fábrica en el tramo, ya mezclado (1: como la de fábrica). */
        double ratio(int bin) {
            double w = weight(bin);
            if (w <= 0) return 1;
            double factory = ChargePlanner.carPowerKw(center(bin), peakKw);
            if (!(factory > 0)) return 1;
            return 1 + w * (kw[bin] / factory - 1);
        }

        /** Razón mezclada a ese % (entre los centros de los tramos, en línea recta). */
        double ratioAt(double pct) {
            double x = (Math.max(0, Math.min(100, pct)) - BIN_PCT / 2) / BIN_PCT;
            if (x <= 0) return ratio(0);
            if (x >= BINS - 1) return ratio(BINS - 1);
            int i = (int) Math.floor(x);
            double f = x - i;
            return ratio(i) * (1 - f) + ratio(i + 1) * f;
        }

        /**
         * Potencia que admite el coche a ese % (kW), mezclando la aprendida con la de fábrica; factor: la parte del pico
         * que admite en ese cargador (1, o la de los 400–500 V). La forma es siempre la del coche (no la de uno pequeño).
         */
        double powerKw(double pct, double factor) {
            return ChargePlanner.carPowerKw(pct, peakKw) * factor * ratioAt(pct);
        }

        /** Lo aprendido a ese % sin mezclar (NaN fuera de los tramos aprendidos), para la gráfica. */
        double learnedKw(int bin) {
            return kw[bin];
        }
    }

    /** Centro del tramo (%). */
    static double center(int bin) {
        return bin * BIN_PCT + BIN_PCT / 2;
    }

    static int binOf(double pct) {
        return Math.max(0, Math.min(BINS - 1, (int) Math.floor(pct / BIN_PCT)));
    }

    /** ¿Es una carga rápida de este coche (por su pico), con cargador conocido y potencia anunciada? */
    private static boolean usable(ChargeSession s, double peakKw) {
        return s.dc && s.charger != null && !s.charger.acOnly && s.charger.maxKw > 0 && Math.abs(s.carPeakKw - peakKw) <= PEAK_TOL_KW;
    }

    /** ¿Manda el coche en esta lectura? (la potencia, claramente por debajo de la anunciada del cargador). */
    static boolean carLimited(ChargeSession.Sample q, RoutePlanner.Charger c) {
        return !Double.isNaN(q.kw) && q.kw >= MIN_KW && q.soc >= 0 && q.soc <= 100 && q.kw < CAR_LIMITED * c.maxKw;
    }

    /**
     * Puro: la curva del coche de pico peakKw con sus cargas rápidas (las de 400–500 V en un coche de 800 V no cuentan: ahí
     * carga a otra parte del pico, y se mira aparte en lowVolts).
     */
    static Curve learn(List<ChargeSession> list, double peakKw) {
        Curve out = new Curve(peakKw);
        List<List<Double>> values = new ArrayList<>();
        List<Set<Integer>> who = new ArrayList<>();
        for (int b = 0; b < BINS; b++) {
            values.add(new ArrayList<>());
            who.add(new HashSet<>());
        }
        for (int i = 0; i < list.size(); i++) {
            ChargeSession s = list.get(i);
            if (!usable(s, peakKw) || ChargePlanner.lowVolts(s.charger, s.carVolts)) continue;
            for (ChargeSession.Sample q : s.samples) {
                if (!carLimited(q, s.charger)) continue;
                double factory = ChargePlanner.carPowerKw(q.soc, peakKw);
                if (!(factory > 0)) continue;
                int b = binOf(q.soc);
                // Frente a la de fábrica a ese mismo %: así la bajada dentro del tramo no sesga el percentil.
                values.get(b).add(q.kw / factory);
                who.get(b).add(i);
            }
        }
        // Por tramo, lo visto frente a la de fábrica (razón; NaN sin bastantes datos).
        double[] raw = new double[BINS];
        for (int b = 0; b < BINS; b++) {
            raw[b] = Double.NaN;
            out.sessions[b] = who.get(b).size();
            out.samples[b] = values.get(b).size();
            if (out.sessions[b] < MIN_SESSIONS || out.samples[b] < MIN_SAMPLES) continue;
            raw[b] = Math.min(MAX_RATIO, percentile(values.get(b), PERCENTILE));
        }
        // Suavizado con los tramos vecinos aprendidos (1-2-1), y en kW en el centro del tramo.
        Set<Integer> all = new HashSet<>();
        for (int b = 0; b < BINS; b++) {
            if (Double.isNaN(raw[b])) continue;
            double sum = 2 * raw[b];
            double wsum = 2;
            if (b > 0 && !Double.isNaN(raw[b - 1])) {
                sum += raw[b - 1];
                wsum += 1;
            }
            if (b < BINS - 1 && !Double.isNaN(raw[b + 1])) {
                sum += raw[b + 1];
                wsum += 1;
            }
            out.kw[b] = sum / wsum * ChargePlanner.carPowerKw(center(b), peakKw);
            all.addAll(who.get(b));
        }
        out.totalSessions = all.size();
        return out;
    }

    /** Puro: el percentil p (0–1) de los valores, interpolando. NaN si no hay. */
    static double percentile(List<Double> v, double p) {
        if (v.isEmpty()) return Double.NaN;
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        double x = Math.max(0, Math.min(1, p)) * (a.length - 1);
        int i = (int) Math.floor(x);
        if (i >= a.length - 1) return a[a.length - 1];
        return a[i] + (a[i + 1] - a[i]) * (x - i);
    }

    // ------------------------------------------------------------------ las reglas, con los datos

    /** Cargas en cargadores de 400–500 V de un coche de 800 V: a qué parte de lo que admite cargó de verdad. */
    static final class LowVolts {
        int sessions;
        /** Lo visto frente a lo que el coche admitiría en un cargador bueno (0,5: la mitad). NaN sin cargas. */
        double factor = Double.NaN;

        /** Lo visto frente a lo que supone la regla (1: se cumple; 1,3: carga un 30 % más). */
        double ratio() {
            return factor / ChargePlanner.LOW_VOLTS_FACTOR;
        }

        /** ¿Hay bastantes cargas para decir algo? */
        boolean enough() {
            return sessions >= LOW_VOLTS_MIN_SESSIONS && !Double.isNaN(factor);
        }

        /** El factor para el plan: el aprendido si los datos contradicen claramente la mitad; si no, la mitad. */
        double planFactor() {
            if (!enough() || Math.abs(factor - ChargePlanner.LOW_VOLTS_FACTOR) < LOW_VOLTS_MIN_DIFF) return ChargePlanner.LOW_VOLTS_FACTOR;
            return Math.max(0.25, Math.min(1, factor));
        }
    }

    /**
     * Puro: en los cargadores de 400–500 V (un coche de 800 V), lo visto frente a lo que el coche admitiría a ese % (curve:
     * la aprendida, o null para la de fábrica), solo en las lecturas en las que no mandaba el cargador. Por carga, el
     * percentil alto; luego la media de las cargas.
     */
    static LowVolts lowVolts(List<ChargeSession> list, double peakKw, Curve curve) {
        LowVolts out = new LowVolts();
        double sum = 0;
        for (ChargeSession s : list) {
            if (!usable(s, peakKw) || !ChargePlanner.lowVolts(s.charger, s.carVolts)) continue;
            List<Double> f = new ArrayList<>();
            for (ChargeSession.Sample q : s.samples) {
                if (!carLimited(q, s.charger) || q.soc < 5 || q.soc > 80) continue;
                double full = curve != null ? curve.powerKw(q.soc, 1) : ChargePlanner.carPowerKw(q.soc, peakKw);
                if (full > 0) f.add(q.kw / full);
            }
            if (f.size() < SESSION_MIN_SAMPLES) continue;
            sum += percentile(f, PERCENTILE);
            out.sessions++;
        }
        if (out.sessions > 0) out.factor = sum / out.sessions;
        return out;
    }

    /** Lo que dan los cargadores de 150 kW o más (todos o los de una red): pico medio y frente a lo esperado. */
    static final class Fast {
        final String network;
        int sessions;
        double peakKw = Double.NaN;
        /** Pico visto frente a lo esperado (lo menos de lo anunciado y lo que admite el coche): 1 = todo. */
        double ratio = Double.NaN;
        double nominalKw = Double.NaN;

        Fast(String network) {
            this.network = network;
        }
    }

    /** Puro: un Fast con las cargas en cargadores de HPC_KW o más (los picos y lo esperado de cada una, ya calculados). */
    private static Fast fast(String network, List<double[]> rows) {
        Fast f = new Fast(network);
        if (rows.isEmpty()) return f;
        double p = 0, r = 0, n = 0;
        for (double[] x : rows) {
            p += x[0];
            r += x[0] / x[1];
            n += x[2];
        }
        f.sessions = rows.size();
        f.peakKw = p / rows.size();
        f.ratio = r / rows.size();
        f.nominalKw = n / rows.size();
        return f;
    }

    /**
     * Puro: las cargas en cargadores de 150 kW o más: el pico de cada una frente a lo esperado (lo menos de lo anunciado
     * y de lo que admite el coche en los % de esa carga, a la parte que toque en uno de 400–500 V). Primero el total
     * (network null) y luego por red (de las que tienen NETWORK_MIN_SESSIONS o más; la peor primero).
     */
    static List<Fast> fastChargers(List<ChargeSession> list, double peakKw, Curve curve, double lowVoltsFactor) {
        List<double[]> total = new ArrayList<>();
        Map<String, List<double[]>> byNet = new HashMap<>();
        for (ChargeSession s : list) {
            if (!usable(s, peakKw) || s.charger.maxKw < HPC_KW) continue;
            double peak = 0;
            double carMax = 0;
            int n = 0;
            double factor = ChargePlanner.lowVolts(s.charger, s.carVolts) ? lowVoltsFactor : 1;
            for (ChargeSession.Sample q : s.samples) {
                if (Double.isNaN(q.kw) || q.kw < MIN_KW) continue;
                n++;
                peak = Math.max(peak, q.kw);
                double car = curve != null ? curve.powerKw(q.soc, factor) : ChargePlanner.carPowerKw(q.soc, peakKw) * factor;
                carMax = Math.max(carMax, car);
            }
            if (n < SESSION_MIN_SAMPLES || !(carMax > 0)) continue;
            double[] row = {peak, Math.min(s.charger.maxKw, carMax), s.charger.maxKw};
            total.add(row);
            String net = s.charger.network == null || s.charger.network.isEmpty() ? ChargerFilter.OTHER : s.charger.network;
            List<double[]> l = byNet.get(net);
            if (l == null) byNet.put(net, l = new ArrayList<>());
            l.add(row);
        }
        List<Fast> out = new ArrayList<>();
        out.add(fast(null, total));
        List<Fast> nets = new ArrayList<>();
        for (Map.Entry<String, List<double[]>> e : byNet.entrySet()) {
            if (ChargerFilter.OTHER.equals(e.getKey()) || e.getValue().size() < NETWORK_MIN_SESSIONS) continue;
            nets.add(fast(e.getKey(), e.getValue()));
        }
        nets.sort((a, b) -> Double.compare(a.ratio, b.ratio));
        out.addAll(nets);
        return out;
    }

    // ------------------------------------------------------------------ todo junto

    /** Lo aprendido de un coche: la curva, los 400–500 V y los de 150 kW o más. */
    static final class Summary {
        final Curve curve;
        final LowVolts lowVolts;
        /** El total (network null) y luego por red. */
        final List<Fast> fast;
        /** Las cargas rápidas de este coche que hay en el historial (para el texto de «se irá completando»). */
        int dcSessions;

        Summary(Curve curve, LowVolts lowVolts, List<Fast> fast) {
            this.curve = curve;
            this.lowVolts = lowVolts;
            this.fast = fast;
        }

        /** Los de 150 kW o más en total, si hay bastantes cargas; null si no. */
        Fast fastTotal() {
            Fast f = fast.isEmpty() ? null : fast.get(0);
            return f != null && f.sessions >= HPC_MIN_SESSIONS ? f : null;
        }
    }

    /** Puro: todo lo aprendido de las cargas de un coche de pico peakKw. */
    static Summary analyze(List<ChargeSession> list, double peakKw) {
        Curve curve = learn(list, peakKw);
        Curve use = curve.learned() ? curve : null;
        LowVolts lv = lowVolts(list, peakKw, use);
        Summary s = new Summary(curve, lv, fastChargers(list, peakKw, use, lv.planFactor()));
        for (ChargeSession x : list) if (x.dc && Math.abs(x.carPeakKw - peakKw) <= PEAK_TOL_KW) s.dcSessions++;
        return s;
    }

    /** Puro: el pico del coche con el que se guardó la carga rápida más reciente (fallback si no hay). */
    static double latestPeak(List<ChargeSession> list, double fallback) {
        ChargeSession best = null;
        for (ChargeSession s : list) if (s.dc && s.carPeakKw > 0 && (best == null || s.startMs > best.startMs)) best = s;
        return best != null ? best.carPeakKw : fallback;
    }

    // ------------------------------------------------------------------ con el historial del móvil

    private static final Object LOCK = new Object();
    private static long cachedStamp = Long.MIN_VALUE;
    private static double cachedPeak = Double.NaN;
    private static Summary cached;

    /** Lo aprendido del historial del móvil para el coche de pico peakKw (se rehace solo si cambia el historial). */
    static Summary forCar(Context ctx, double peakKw) {
        long stamp = ChargeLog.stamp(ctx);
        synchronized (LOCK) {
            if (cached != null && stamp == cachedStamp && peakKw == cachedPeak) return cached;
        }
        Summary s = analyze(ChargeLog.load(ctx), peakKw);
        synchronized (LOCK) {
            cached = s;
            cachedStamp = stamp;
            cachedPeak = peakKw;
        }
        L.i(String.format(Locale.US, "cargas: curva aprendida con %d cargas (%d tramos de %d); 400–500 V: %d cargas%s", s.curve.totalSessions,
                s.curve.learnedBins(), BINS, s.lowVolts.sessions, s.lowVolts.enough() ? String.format(Locale.US, " al %.0f %%", s.lowVolts.factor * 100) : ""));
        return s;
    }
}
