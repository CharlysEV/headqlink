package com.headqlink.link;

import android.os.Process;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Latencia: lo que no cuesta calor va siempre; lo que sí, con el ajuste "Optimizaciones de latencia".
 * Siempre (desde la 0.2.48; antes solo con el ajuste, y el perfil Coche lo traía apagado):
 * - El registro (log y diario del coche) se escribe en un hilo propio y se vuelca a disco cuando no
 *   queda nada pendiente, en vez de escribir y vaciar a disco en el hilo que lee del coche (un toque
 *   del coche cada ~16 ms acababa en una escritura a disco en ese hilo).
 * - Los toques no se registran uno a uno en el log, solo el principio y el fin de cada gesto (todos
 *   siguen en el diario del coche y en la traza).
 * - Decodificador de AA en modo baja latencia, e hilos de vídeo y lectura con prioridad de pantalla.
 * Con el ajuste ({@link #enabled}): encoder con la extensión de baja latencia de Qualcomm y velocidad
 * de operación alta (más calor), y la captura de logcat solo con información y superiores.
 */
final class LowLatency {
    static volatile boolean enabled;

    private static final LinkedBlockingQueue<Runnable> QUEUE = new LinkedBlockingQueue<>();
    private static Thread writer;

    private LowLatency() {
    }

    static void apply(Config cfg) {
        boolean on = cfg.lowLatency();
        if (on != enabled) L.i("optimizaciones de latencia: " + (on ? "SÍ" : "no"));
        enabled = on;
        PerfTrace.event("low_latency", on ? 1 : 0);
    }

    /** Prioridad de pantalla para el hilo actual (siempre: no cuesta calor). */
    static void boostCurrentThread() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (RuntimeException ignored) {
        }
    }

    /** Escritura de registro: en el hilo escritor si el ajuste está encendido; si no, en el acto. */
    static void log(Runnable write) {
        ensureWriter();
        QUEUE.offer(write);
    }

    private static synchronized void ensureWriter() {
        if (writer != null) return;
        writer = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            // Vaciado al disco como mucho una vez por segundo (exportar el log vacía aparte).
            long lastFlush = 0;
            // Con algo escrito sin vaciar, se espera como mucho un segundo: si no llega nada, se vacía igual (las
            // últimas líneas antes de un rato sin actividad no se quedan en memoria).
            boolean dirty = false;
            while (true) {
                try {
                    Runnable r = dirty ? QUEUE.poll(1, java.util.concurrent.TimeUnit.SECONDS) : QUEUE.take();
                    long t = android.os.SystemClock.elapsedRealtime();
                    if (r == null) {
                        L.flush();
                        CarTrace.flush();
                        lastFlush = t;
                        dirty = false;
                        continue;
                    }
                    r.run();
                    dirty = true;
                    if (QUEUE.isEmpty() && t - lastFlush >= 1000) {
                        L.flush();
                        CarTrace.flush();
                        lastFlush = t;
                        dirty = false;
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException ignored) {
                }
            }
        }, "log-writer");
        writer.setDaemon(true);
        writer.start();
    }
}
