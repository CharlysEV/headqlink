package com.headqlink.link;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Captura continua del logcat de este proceso (HeadQLink + Open Headunit) en
 * getExternalFilesDir()/logcat/, en ficheros de 10 MB (se conservan los 10 últimos), y registro
 * de fallos en getExternalFilesDir()/crash/. Una app puede leer el log de su propio proceso sin
 * permisos especiales.
 */
public final class LogcatCapture {
    private static final long MAX_FILE = 10L * 1024 * 1024;
    private static final int KEEP = 10;
    private static volatile boolean started;

    private static volatile boolean infoOnly;

    private LogcatCapture() {
    }

    public static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        Context app = ctx.getApplicationContext();
        installCrashHandler(app);
        File dir = new File(app.getExternalFilesDir(null), "logcat");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // Con "Optimizaciones de latencia", solo información y superiores (sin el detalle de depuración).
        infoOnly = new Config(app).lowLatency();
        Thread t = new Thread(() -> capture(dir), "logcat-capture");
        t.setDaemon(true);
        t.start();
    }

    private static void capture(File dir) {
        String pid = String.valueOf(Process.myPid());
        while (true) {
            java.lang.Process p = null;
            Writer out = null;
            try {
                p = (infoOnly
                        ? new ProcessBuilder("logcat", "-v", "threadtime", "--pid=" + pid, "*:I")
                        : new ProcessBuilder("logcat", "-v", "threadtime", "--pid=" + pid)).redirectErrorStream(true).start();
                BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
                File f = newFile(dir);
                out = new FileWriter(f, true);
                long written = 0;
                String line;
                while ((line = in.readLine()) != null) {
                    out.write(line);
                    out.write('\n');
                    written += line.length() + 1;
                    if (written > MAX_FILE) {
                        out.close();
                        prune(dir);
                        f = newFile(dir);
                        out = new FileWriter(f, true);
                        written = 0;
                    } else if (!in.ready()) {
                        out.flush();
                    }
                }
            } catch (IOException e) {
                Log.w("HeadQLink", "captura de logcat interrumpida: " + e);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
                if (p != null) p.destroy();
            }
            try {
                Thread.sleep(5000); // logcat terminó (raro): reintentar
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static File newFile(File dir) {
        prune(dir);
        return new File(dir, "logcat-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".log");
    }

    /** Conserva los KEEP ficheros más recientes. */
    private static void prune(File dir) {
        File[] files = dir.listFiles((d, n) -> n.startsWith("logcat-"));
        if (files == null || files.length < KEEP) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i <= files.length - KEEP; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    /** Guarda la traza completa de cualquier fallo no capturado y deja seguir al manejador original. */
    private static void installCrashHandler(Context app) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                QdTrace.w("HQL/Fallo", thread.getName() + " :: " + Log.getStackTraceString(ex));
                QdTrace.flush(500);
            } catch (Throwable ignored) {
            }
            try {
                File dir = new File(app.getExternalFilesDir(null), "crash");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                LogRetention.prune(dir, "crash-", 49, Long.MAX_VALUE);
                File f = new File(dir, "crash-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
                try (PrintWriter w = new PrintWriter(new FileWriter(f))) {
                    w.println("hilo: " + thread.getName());
                    ex.printStackTrace(w);
                }
                CarTrace.problem("FALLO", thread.getName() + " :: " + Log.getStackTraceString(ex));
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, ex);
        });
    }
}
