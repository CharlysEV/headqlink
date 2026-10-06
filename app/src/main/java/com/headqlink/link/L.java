package com.headqlink.link;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Log a logcat (tag HeadQLink) y a fichero en getExternalFilesDir(), con listener para la UI. Cada línea se copia
 * además al log unificado (QdTrace). Un fichero por proceso, con partes nuevas (headqlink-…-pN.log) cada
 * MAX_FILE_CHARS caracteres; al abrir cada una se aplica la retención: ≤ 30 ficheros y ≤ 100 MiB de headqlink-*.log.
 */
final class L {
    interface Listener {
        void onLine(String line);
    }

    private static final String TAG = "HeadQLink";
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static final int MAX_FILES = 30;
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    /** Tope de cada parte: el proceso puede vivir días con el servicio en marcha. */
    private static final long MAX_FILE_CHARS = 16L * 1024 * 1024;

    // Con L.class.
    private static Writer file;
    private static volatile File path;
    private static File dir;
    private static String baseName;
    private static int part;
    private static long written;
    private static volatile Listener listener;

    private L() {
    }

    static synchronized void init(Context ctx) {
        if (file != null) return;
        dir = ctx.getExternalFilesDir(null);
        baseName = "headqlink-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        part = 1;
        if (open()) i("log file: " + path.getAbsolutePath());
    }

    /** Con L.class: abre la parte actual tras aplicar la retención (contando la nueva y su tope). */
    private static boolean open() {
        LogRetention.prune(dir, "headqlink-", MAX_FILES - 1, MAX_BYTES - MAX_FILE_CHARS);
        File f = new File(dir, part == 1 ? baseName + ".log" : baseName + "-p" + part + ".log");
        try {
            file = new FileWriter(f, true);
            path = f;
            written = f.length();
            return true;
        } catch (IOException e) {
            file = null;
            Log.e(TAG, "cannot open log file", e);
            return false;
        }
    }

    /** Con L.class: la parte llegó a su tope; se cierra y se sigue en la siguiente. */
    private static void rotate() {
        try {
            file.write("--- continúa en la parte " + (part + 1) + " ---\n");
            file.close();
        } catch (IOException ignored) {
        }
        part++;
        open();
    }

    static File currentFile() {
        return path;
    }

    static void setListener(Listener l) {
        listener = l;
    }

    static void i(String msg) {
        Log.i(TAG, msg);
        write("I", msg, true);
    }

    static void w(String msg) {
        Log.w(TAG, msg);
        write("W", msg, true);
        CarTrace.problem("AVISO", msg);
    }

    /**
     * Decisión del ciclo de vida del enlace y de Android Auto (esperas, pausa, reanudar, apagados, capa, avisos): con el
     * prefijo «ciclo:» para encontrarlas juntas en el log unificado.
     */
    static void life(String msg) {
        i("ciclo: " + msg);
    }

    static void lifeWarn(String msg) {
        w("ciclo: " + msg);
    }

    static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        write("E", msg + (t != null ? " :: " + t : ""), true);
        CarTrace.problem("ERROR", msg + (t != null ? " :: " + Log.getStackTraceString(t) : ""));
    }

    /**
     * Avisos y errores del núcleo (QdTrace.qdLog): a logcat, a este fichero y al diario del coche, pero no otra vez al
     * log unificado (ya están en él con su etiqueta e hilo). Se llama en el hilo del log unificado.
     */
    static void fromCore(boolean error, String msg, Throwable t) {
        if (error) Log.e(TAG, msg, t);
        else Log.w(TAG, msg, t);
        write(error ? "E" : "W", msg + (t != null ? " :: " + t : ""), false);
        CarTrace.problem(error ? "ERROR" : "AVISO", msg + (t != null ? " :: " + t : ""));
    }

    /**
     * Línea que ya está en el log unificado con su propia etiqueta (cortes, resúmenes): a logcat y a este fichero, sin
     * copiarla otra vez al unificado ni al diario del coche.
     */
    static void quiet(String level, String msg) {
        if ("W".equals(level)) Log.w(TAG, msg);
        else Log.i(TAG, msg);
        write(level, msg, false);
    }

    /**
     * Línea que ya está en logcat y en el log unificado con su propia etiqueta (el cable USB, HQL/USB): solo a este
     * fichero, para que también se vea en Diagnóstico.
     */
    static void fileOnly(String level, String msg) {
        write(level, msg, false);
    }

    private static void write(String level, String msg, boolean toUnified) {
        if (toUnified) QdTrace.forkLine(level, msg);
        String line;
        synchronized (TS) {
            line = TS.format(new Date()) + " " + level + " " + msg;
        }
        LowLatency.log(() -> {
            synchronized (L.class) {
                if (file != null) {
                    try {
                        file.write(line);
                        file.write('\n');
                        if (!LowLatency.enabled) file.flush();
                        written += line.length() + 1;
                        if (written > MAX_FILE_CHARS) rotate();
                    } catch (IOException ignored) {
                    }
                }
            }
        });
        Listener l = listener;
        if (l != null) l.onLine(line);
    }

    /** Vuelca a disco lo escrito (hilo escritor de LowLatency). */
    static void flush() {
        synchronized (L.class) {
            if (file != null) {
                try {
                    file.flush();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 9 / 4 + 4);
        for (int i = 0; i < len && off + i < b.length; i++) {
            if (i > 0 && i % 4 == 0) sb.append(' ');
            int v = b[off + i] & 0xff;
            sb.append(HEX[v >> 4]).append(HEX[v & 15]);
        }
        return sb.toString();
    }
}
