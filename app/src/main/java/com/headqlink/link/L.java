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

/** Log a logcat (tag HeadQLink) y a fichero en getExternalFilesDir(), con listener para la UI. */
final class L {
    interface Listener {
        void onLine(String line);
    }

    private static final String TAG = "HeadQLink";
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static Writer file;
    private static File path;
    private static volatile Listener listener;

    private L() {
    }

    static synchronized void init(Context ctx) {
        if (file != null) return;
        File dir = ctx.getExternalFilesDir(null);
        String name = "headqlink-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".log";
        try {
            path = new File(dir, name);
            file = new FileWriter(path, true);
            i("log file: " + new File(dir, name).getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "cannot open log file", e);
        }
    }

    static File currentFile() {
        return path;
    }

    static void setListener(Listener l) {
        listener = l;
    }

    static void i(String msg) {
        Log.i(TAG, msg);
        write("I", msg);
    }

    static void w(String msg) {
        Log.w(TAG, msg);
        write("W", msg);
        CarTrace.problem("AVISO", msg);
    }

    static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        write("E", msg + (t != null ? " :: " + t : ""));
        CarTrace.problem("ERROR", msg + (t != null ? " :: " + Log.getStackTraceString(t) : ""));
    }

    private static void write(String level, String msg) {
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
