package com.c10link.link;

import java.util.Locale;

/** Estadística de intervalos entre eventos (frames), para localizar tirones. */
final class Jitter {
    private final String name;
    private final long expectedNs;
    private long last;
    private int n;
    private long sum;
    private long max;
    private long min = Long.MAX_VALUE;
    private int late;      // > 1.5 x esperado
    private int veryLate;  // > 2.5 x esperado (frame "perdido" a ojo)

    Jitter(String name, int fps) {
        this.name = name;
        this.expectedNs = 1_000_000_000L / fps;
    }

    synchronized void tick() {
        long now = System.nanoTime();
        if (last != 0) {
            long d = now - last;
            n++;
            sum += d;
            if (d > max) max = d;
            if (d < min) min = d;
            if (d > expectedNs * 3 / 2) late++;
            if (d > expectedNs * 5 / 2) veryLate++;
        }
        last = now;
    }

    /** Devuelve el resumen y reinicia los contadores (manteniendo el último timestamp). */
    synchronized String takeSummary() {
        if (n == 0) return name + " sin datos";
        String s = String.format(Locale.US, "%s avg %.1f min %.1f max %.1f ms, >1.5x %d, >2.5x %d (n=%d)",
                name, sum / 1e6 / n, min / 1e6, max / 1e6, late, veryLate, n);
        n = 0;
        sum = 0;
        max = 0;
        min = Long.MAX_VALUE;
        late = 0;
        veryLate = 0;
        return s;
    }
}
