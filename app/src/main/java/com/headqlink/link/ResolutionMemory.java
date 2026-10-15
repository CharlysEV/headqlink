package com.headqlink.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * La resolución con la que empieza cada viaje (perfiles «Coche» y «Automático»), según cómo le fue a este móvil en los
 * anteriores (pura, la prueban los tests). En marcha no se sube nunca: cada cambio reconecta Android Auto (2-4 s en
 * negro y, con el móvil bloqueado, puede pedir desbloquearlo); solo se baja con calor de verdad (ResolutionLadder).
 *
 * - Ya caliente al conectar (estado moderado o peor): 720p.
 * - Sin viajes que cuenten (de MIN_MINUTES o más, o que se calentaron): 720p, la primera vez.
 * - Si el último que cuenta fue a 1080p: 1080p si no pasó de ligero; 720p si se calentó.
 * - Si fue a 720p sin pasar de ligero: se vuelve a probar 1080p, salvo que los dos últimos a 1080p se calentaran (este
 *   móvil no aguanta 1080p): entonces sigue a 720p hasta que salgan de la lista (KEEP viajes).
 * - Si fue a 720p y aun así se calentó: 720p.
 */
final class ResolutionMemory {
    /** Un viaje cuenta si duró esto o se calentó. */
    static final int MIN_MINUTES = 20;
    /** Desde aquí (PowerManager.THERMAL_STATUS_MODERATE) el viaje «se calentó». */
    static final int HOT = ThermalPolicy.STATUS_MODERATE;
    /** Viajes que se recuerdan. */
    static final int KEEP = 6;

    private ResolutionMemory() {
    }

    /** Un viaje: si fue a 1080p, el estado térmico más alto y los minutos. */
    static final class Entry {
        final boolean high;
        final int maxStatus;
        final int minutes;

        Entry(boolean high, int maxStatus, int minutes) {
            this.high = high;
            this.maxStatus = maxStatus;
            this.minutes = minutes;
        }

        boolean counts() {
            return minutes >= MIN_MINUTES || maxStatus >= HOT;
        }

        boolean hot() {
            return maxStatus >= HOT;
        }
    }

    /** La decisión y por qué (para el log). */
    static final class Decision {
        final boolean high;
        final String why;

        Decision(boolean high, String why) {
            this.high = high;
            this.why = why;
        }
    }

    static Decision start(List<Entry> history, int statusNow) {
        if (statusNow >= HOT) return new Decision(false, "el móvil ya está caliente al conectar (estado " + statusNow + ")");
        Entry last = null;
        for (int i = history.size() - 1; i >= 0 && last == null; i--) if (history.get(i).counts()) last = history.get(i);
        if (last == null) return new Decision(false, "aún no hay viajes con este móvil que digan cómo aguanta 1080p");
        if (last.high) {
            return last.hot() ? new Decision(false, "en el último viaje a 1080p se calentó (estado " + last.maxStatus + ")")
                    : new Decision(true, "en el último viaje a 1080p no pasó de ligero (" + last.minutes + " min)");
        }
        if (last.hot()) return new Decision(false, "en el último viaje, aun a 720p, se calentó (estado " + last.maxStatus + ")");
        int hotHigh = 0;
        int seenHigh = 0;
        for (int i = history.size() - 1; i >= 0 && seenHigh < 2; i--) {
            Entry e = history.get(i);
            if (!e.high || !e.counts()) continue;
            seenHigh++;
            if (e.hot()) hotHigh++;
        }
        if (hotHigh >= 2) return new Decision(false, "los dos últimos viajes a 1080p se calentaron");
        return new Decision(true, "el último viaje a 720p no pasó de ligero (" + last.minutes + " min): pruebo 1080p");
    }

    /** La lista con el viaje nuevo al final (como mucho KEEP). */
    static List<Entry> add(List<Entry> history, Entry e) {
        List<Entry> out = new ArrayList<>(history);
        out.add(e);
        while (out.size() > KEEP) out.remove(0);
        return out;
    }

    /** «h,2,35;l,1,40» (1080p o 720p, estado máximo, minutos). */
    static String encode(List<Entry> history) {
        StringBuilder b = new StringBuilder();
        for (Entry e : history) {
            if (b.length() > 0) b.append(';');
            b.append(e.high ? 'h' : 'l').append(',').append(e.maxStatus).append(',').append(e.minutes);
        }
        return b.toString();
    }

    static List<Entry> decode(String s) {
        List<Entry> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            String[] f = part.split(",");
            if (f.length != 3) continue;
            try {
                out.add(new Entry("h".equals(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2])));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static String describe(Entry e) {
        return String.format(Locale.US, "%s, estado máx. %d, %d min", e.high ? "1080p" : "720p", e.maxStatus, e.minutes);
    }
}
