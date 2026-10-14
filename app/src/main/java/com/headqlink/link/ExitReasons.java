package com.headqlink.link;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Por qué terminó la app la vez anterior (Android 11+: ActivityManager.getHistoricalProcessExitReasons). El registro de
 * fallos (LogcatCapture) solo ve los fallos del código Java; los bloqueos («la app no responde»), la falta de memoria,
 * los fallos del código nativo y los cierres forzados (por el usuario o por el ahorro de batería del fabricante, que
 * además quitan la accesibilidad) no dejaban rastro. Al arrancar se leen las salidas nuevas y se apuntan en el log, en
 * logs/exits.csv y, los bloqueos y fallos nativos, con su traza en crash/.
 */
public final class ExitReasons {
    static final String CSV_NAME = "exits.csv";
    static final String CSV_HEADER = "fecha,motivo,codigo,estado,pss_mb,rss_mb,descripcion";
    private static final String PREFS = "hql_exits";
    private static final String K_LAST = "last_ts";
    private static final int MAX_EXITS = 16;
    private static final int MAX_ROWS = 300;
    private static final int MAX_TRACE_BYTES = 512 * 1024;

    private ExitReasons() {
    }

    /** En App.onCreate: en un hilo aparte (lee del sistema y escribe ficheros). */
    public static void start(Context ctx) {
        if (Build.VERSION.SDK_INT < 30) return;
        Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                record(app);
            } catch (RuntimeException e) {
                L.w("salidas anteriores: no se pudieron leer: " + e);
            }
        }, "exit-reasons");
        t.setDaemon(true);
        t.start();
    }

    @android.annotation.TargetApi(30)
    private static void record(Context app) {
        L.init(app);
        ActivityManager am = app.getSystemService(ActivityManager.class);
        if (am == null) return;
        List<ApplicationExitInfo> all = am.getHistoricalProcessExitReasons(app.getPackageName(), 0, MAX_EXITS);
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = sp.getLong(K_LAST, 0);
        List<ApplicationExitInfo> fresh = new ArrayList<>();
        for (ApplicationExitInfo e : all) {
            // Solo el proceso principal (no los de servicios aislados) y solo las que no se habían apuntado.
            if (e.getTimestamp() > last && app.getPackageName().equals(e.getProcessName())) fresh.add(e);
        }
        if (fresh.isEmpty()) return;
        // La lista viene de la más nueva a la más vieja: se apuntan en orden.
        long newest = last;
        File base = app.getExternalFilesDir(null);
        for (int i = fresh.size() - 1; i >= 0; i--) {
            ApplicationExitInfo e = fresh.get(i);
            newest = Math.max(newest, e.getTimestamp());
            String when = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date(e.getTimestamp()));
            String what = describe(e.getReason());
            String state = importance(e.getImportance());
            long pssMb = e.getPss() / 1024;
            long rssMb = e.getRss() / 1024;
            String desc = e.getDescription() == null ? "" : e.getDescription();
            String line = "salida anterior: " + when + " · " + what + " · " + state
                    + (pssMb > 0 ? " · " + pssMb + " MB" : "") + (desc.isEmpty() ? "" : " · " + desc)
                    + (forced(e.getReason()) ? " (Android suele quitar la accesibilidad de la app tras un cierre forzado)" : "");
            if (abnormal(e.getReason())) L.w(line);
            else L.i(line);
            if (base != null) {
                appendCsv(new File(base, "logs"), new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(e.getTimestamp())),
                        what, e.getReason(), state, pssMb, rssMb, desc);
                if (e.getReason() == ApplicationExitInfo.REASON_ANR || e.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    saveTrace(new File(base, "crash"), e, line);
                }
            }
        }
        sp.edit().putLong(K_LAST, newest).apply();
    }

    /** Puro: el motivo en palabras. */
    static String describe(int reason) {
        switch (reason) {
            case 6: // REASON_ANR
                return "bloqueo (la app no respondía)";
            case 4: // REASON_CRASH
                return "fallo del código Java";
            case 5: // REASON_CRASH_NATIVE
                return "fallo del código nativo";
            case 3: // REASON_LOW_MEMORY
                return "falta de memoria (Android la cerró para liberar RAM)";
            case 10: // REASON_USER_REQUESTED
                return "forzar detención (el usuario o el ahorro de batería del fabricante)";
            case 11: // REASON_USER_STOPPED
                return "se detuvo el usuario de Android (multiusuario)";
            case 2: // REASON_SIGNALED
                return "terminada por el sistema (señal)";
            case 9: // REASON_EXCESSIVE_RESOURCE_USAGE
                return "uso excesivo de recursos";
            case 7: // REASON_INITIALIZATION_FAILURE
                return "fallo al arrancar";
            case 8: // REASON_PERMISSION_CHANGE
                return "cambio de un permiso";
            case 12: // REASON_DEPENDENCY_DIED
                return "murió un proceso del que dependía";
            case 1: // REASON_EXIT_SELF
                return "se cerró ella misma";
            case 13: // REASON_OTHER
                return "otro motivo del sistema";
            case 14: // REASON_FREEZER (Android 14)
                return "congelada por el sistema";
            case 15: // REASON_PACKAGE_STATE_CHANGE (Android 14)
                return "cambio de estado del paquete";
            case 16: // REASON_PACKAGE_UPDATED (Android 14)
                return "actualización de la app";
            default:
                return "motivo desconocido (" + reason + ")";
        }
    }

    /** Puro: ¿una salida que no debería pasar (para avisar en el log)? */
    static boolean abnormal(int reason) {
        switch (reason) {
            case 1: // salir ella misma
            case 8: // cambio de permiso (lo hace el usuario)
            case 15:
            case 16: // actualización
                return false;
            default:
                return true;
        }
    }

    /** Puro: cierre forzado (el que suele desactivar el servicio de accesibilidad). */
    static boolean forced(int reason) {
        return reason == 10;
    }

    /** Puro: lo que hacía la app cuando terminó. */
    static String importance(int imp) {
        if (imp <= 100) return "a la vista";
        if (imp <= 125) return "servicio en primer plano";
        if (imp <= 200) return "visible";
        if (imp <= 230) return "perceptible";
        if (imp <= 300) return "servicio en segundo plano";
        if (imp < 400) return "en segundo plano";
        return "en caché";
    }

    /** Puro: una fila del CSV (comillas si hace falta). */
    static String csvRow(String date, String what, int code, String state, long pss, long rss, String desc) {
        return date + "," + quote(what) + "," + code + "," + quote(state) + "," + pss + "," + rss + "," + quote(desc);
    }

    private static String quote(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return '"' + s.replace("\"", "\"\"").replace('\n', ' ') + '"';
    }

    private static void appendCsv(File dir, String date, String what, int code, String state, long pss, long rss, String desc) {
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, CSV_NAME);
            List<String> lines = new ArrayList<>();
            if (f.exists()) lines.addAll(java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
            if (lines.isEmpty() || !CSV_HEADER.equals(lines.get(0))) lines.add(0, CSV_HEADER);
            lines.add(csvRow(date, what, code, state, pss, rss, desc));
            while (lines.size() > MAX_ROWS + 1) lines.remove(1);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                for (String l : lines) w.write(l + "\n");
            }
        } catch (Exception e) {
            L.w("salidas anteriores: no se pudo escribir " + CSV_NAME + ": " + e);
        }
    }

    /** La traza que guarda Android de un bloqueo o de un fallo nativo, en crash/ (como mucho 512 KB). */
    @android.annotation.TargetApi(30)
    private static void saveTrace(File dir, ApplicationExitInfo e, String header) {
        try (InputStream in = e.getTraceInputStream()) {
            if (in == null) return;
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            LogRetention.prune(dir, "crash-", 49, Long.MAX_VALUE, null);
            String kind = e.getReason() == ApplicationExitInfo.REASON_ANR ? "bloqueo" : "nativo";
            File f = new File(dir, "crash-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(e.getTimestamp()))
                    + "-" + kind + ".txt");
            try (OutputStream out = new FileOutputStream(f)) {
                out.write((header + "\n\n").getBytes(StandardCharsets.UTF_8));
                byte[] buf = new byte[16 * 1024];
                int total = 0;
                for (int r; (r = in.read(buf)) > 0 && total < MAX_TRACE_BYTES; ) {
                    int n = Math.min(r, MAX_TRACE_BYTES - total);
                    out.write(buf, 0, n);
                    total += n;
                }
            }
            L.i("salidas anteriores: traza del " + kind + " en crash/" + f.getName());
        } catch (Exception ex) {
            L.w("salidas anteriores: sin traza: " + ex);
        }
    }
}
