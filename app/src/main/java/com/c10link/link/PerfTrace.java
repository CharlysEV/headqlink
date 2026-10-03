package com.c10link.link;

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
 *   sta_wifi (0/1 móvil conectado a una red), wifi_scan / wifi_scan_cb (escaneo terminado),
 *   bluetooth, bt_a2dp, bt_hfp (0/1), power_save, doze (0/1), thermal (0..6),
 *   screen_on / screen_off / unlocked
 *   idr_req, flush (frames descartados), wifi_scan, note (texto en a)
 * Fichero: getExternalFilesDir()/perf/perf-AAAAMMDD-HHMMSS.csv
 */
final class PerfTrace {
    private static File dir;
    private static BufferedWriter out;
    private static long t0;
    private static long lastFlush;

    private PerfTrace() {
    }

    static synchronized void init(Context ctx) {
        if (dir != null) return;
        dir = new File(ctx.getExternalFilesDir(null), "perf");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    static synchronized void beginSession() {
        endSession();
        if (dir == null) return;
        File f = new File(dir, "perf-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".csv");
        try {
            out = new BufferedWriter(new FileWriter(f), 64 * 1024);
            out.write("t_ms,evento,a,b,c,d,e\n");
            t0 = SystemClock.elapsedRealtime();
            L.i("traza de rendimiento: " + f.getAbsolutePath());
        } catch (IOException e) {
            L.e("no se pudo abrir la traza de rendimiento", e);
            out = null;
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
