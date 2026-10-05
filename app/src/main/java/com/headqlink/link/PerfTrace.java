package com.headqlink.link;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Traza de rendimiento por sesión (CSV) para localizar retardos: cada frame enviado, cada toque,
 * peticiones de IDR, atascos y escaneos WiFi del sistema. Columnas:
 *   t_ms (desde el inicio de la sesión), evento, a, b, c, d, e
 *   frame:  bytes, clave(0/1), espera_en_cola_ms, escritura_ms, frames_en_cola
 *   touch:  acción, x, y                 (toque recibido del coche)
 *   aa_touch: acción, x, y               (toque enviado a Android Auto)
 *   net:    cola_kernel_bytes, rtt_ms, rttvar_ms, sin_confirmar, retrans_total, cwnd, caudal_kbps,
 *           ocupado_ms, limitado_receptor_ms, limitado_buffer_ms, ventana_coche_bytes   (cada 50 ms)
 *   stall:  hueco_ms                     (el proceso no corrió: congelado o frenado por el sistema)
 *   rx:     tipo_mensaje, bytes          (cada paquete recibido del coche)
 *   p2p0:   tx_paquetes/s, tx_errores/s, tx_descartes/s, rx_paquetes/s, rx_descartes/s (cada 1 s)
 *   ifstat: interfaz, tx_paquetes/s, tx_errores/s, tx_descartes/s, rx_paquetes/s, rx_descartes/s (interfaz de la
 *           sesión, si no es p2p0; cada 1 s)
 *   sta_wifi (0/1 móvil conectado a una red), wifi_scan / wifi_scan_cb (escaneo terminado),
 *   bluetooth, bt_a2dp, bt_hfp (0/1), power_save, doze (0/1), thermal (0..6),
 *   screen_on / screen_off / unlocked
 *   idr_req, flush (frames descartados), wifi_scan, note (texto en a)
 * Fichero: getExternalFilesDir()/perf/perf-AAAAMMDD-HHMMSS-mmm[-S<sesión>].csv
 */
final class PerfTrace {
    /** Retención (qdauto §7.1): ≤ 60 trazas y ≤ 200 MiB, también con el proceso en marcha (una por sesión). */
    private static final int MAX_FILES = 60;
    private static final long MAX_BYTES = 200L * 1024 * 1024;

    private static File dir;
    private static BufferedWriter out;
    private static long t0;
    private static long lastFlush;
    /** Sesión en curso: endSession(int) solo cierra la suya (con relevos, la nueva puede abrir antes de que cierre la vieja). */
    private static int generation;

    private PerfTrace() {
    }

    static synchronized void init(Context ctx) {
        if (dir != null) return;
        dir = new File(ctx.getExternalFilesDir(null), "perf");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        LogRetention.prune(dir, "perf-", MAX_FILES, MAX_BYTES);
    }

    /** Como beginSession(String), sin etiqueta (motor original). */
    static synchronized int beginSession() {
        return beginSession(null);
    }

    /**
     * Abre la traza de una sesión nueva (cierra la anterior); label (p. ej. "S3") va en el nombre. Devuelve su testigo
     * para endSession(int).
     */
    static synchronized int beginSession(String label) {
        endSession();
        generation++;
        if (dir == null) return generation;
        // La retención también aquí (no solo al arrancar el proceso), contando la que se abre.
        LogRetention.prune(dir, "perf-", MAX_FILES - 1, MAX_BYTES);
        // Con milisegundos y la sesión: dos sesiones en el mismo segundo (relevo, reconexión rápida) no se pisan.
        File f = new File(dir, "perf-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date())
                + (label != null && !label.isEmpty() ? "-" + label : "") + ".csv");
        try {
            out = new BufferedWriter(new FileWriter(f), 64 * 1024);
            out.write("t_ms,evento,a,b,c,d,e\n");
            t0 = SystemClock.elapsedRealtime();
            L.i("traza de rendimiento: " + f.getAbsolutePath());
        } catch (IOException e) {
            L.e("no se pudo abrir la traza de rendimiento", e);
            out = null;
        }
        return generation;
    }

    /** Cierra la traza solo si sigue siendo la de esa sesión. */
    static synchronized void endSession(int token) {
        if (token == generation) endSession();
    }

    /** Vuelca a disco lo escrito (exportar log). */
    static synchronized void flush() {
        if (out == null) return;
        try {
            out.flush();
        } catch (IOException ignored) {
        }
    }

    static synchronized void endSession() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    static void frame(int bytes, boolean key, long lagMs, long writeMs, int queued) {
        row("frame", bytes, key ? 1 : 0, lagMs, writeMs + "," + queued);
    }

    static void touch(String what, int action, float x, float y) {
        row(what, action, Math.round(x), Math.round(y), "");
    }

    static void net(int[] v) {
        row("net", v[0], v[1] / 1000, v[2] / 1000,
                v[3] + "," + v[4] + "," + v[5] + "," + v[7] + "," + v[8] + "," + v[9] + "," + v[10] + "," + v[11]);
    }

    static void p2p(long tx, long txErr, long txDrop, long rx, long rxDrop) {
        row("p2p0", tx, txErr, txDrop, rx + "," + rxDrop);
    }

    /** Contadores por segundo de la interfaz de la sesión (zona Wi-Fi u otra): ifstat, interfaz, tx, txErr, txDrop, rx,rxDrop. */
    static void ifstat(String iface, long tx, long txErr, long txDrop, long rx, long rxDrop) {
        row("ifstat", iface, tx, txErr, txDrop + "," + rx + "," + rxDrop);
    }

    static void rx(int type, int len) {
        row("rx", type, len, "", "");
    }

    static void event(String what, long a) {
        row(what, a, "", "", "");
    }

    static void note(String text) {
        row("note", "\"" + text.replace('"', '\'') + "\"", "", "", "");
    }

    private static synchronized void row(String ev, Object a, Object b, Object c, String d) {
        if (out == null) return;
        long now = SystemClock.elapsedRealtime();
        try {
            out.write(Long.toString(now - t0));
            out.write(',');
            out.write(ev);
            out.write(',');
            out.write(String.valueOf(a));
            out.write(',');
            out.write(String.valueOf(b));
            out.write(',');
            out.write(String.valueOf(c));
            out.write(',');
            out.write(d);
            out.write('\n');
            if (now - lastFlush > 1000) {
                out.flush();
                lastFlush = now;
            }
        } catch (IOException ignored) {
        }
    }
}
