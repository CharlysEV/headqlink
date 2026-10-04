package com.headqlink.link;

import android.os.Process;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Ajuste "Optimizaciones de latencia" (Ajustes de imagen; apagado = comportamiento anterior).
 * Encendido:
 * - El registro (log y diario del coche) se escribe en un hilo propio y se vuelca a disco cuando no
 *   queda nada pendiente, en vez de escribir y vaciar a disco en el hilo que lee del coche.
 * - Los toques no se registran uno a uno en el log (sí en el diario y en la traza).
 * - Decodificador de AA en modo baja latencia, encoder con la extensión de baja latencia de
 *   Qualcomm y velocidad de operación alta, e hilos de vídeo y lectura con prioridad de pantalla.
 * - La captura de logcat guarda solo información y superiores.
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

    /** Prioridad de pantalla para el hilo actual, si el ajuste está encendido. */
    static void boostCurrentThread() {
        if (!enabled) return;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (RuntimeException ignored) {
        }
    }

    /** Escritura de registro: en el hilo escritor si el ajuste está encendido; si no, en el acto. */
    static void log(Runnable write) {
        if (!enabled) {
            write.run();
            return;
        }
        ensureWriter();
        QUEUE.offer(write);
    }

    private static synchronized void ensureWriter() {
        if (writer != null) return;
        writer = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            while (true) {
                try {
                    QUEUE.take().run();
                    if (QUEUE.isEmpty()) {
                        L.flush();
                        CarTrace.flush();
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
