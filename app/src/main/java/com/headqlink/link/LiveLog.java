package com.headqlink.link;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Log en directo para el desarrollador (diagnóstico, solo en el móvil donde se active desde adb: LiveLogReceiver). Las
 * líneas del log de la app (ya sin datos personales: Redact) se guardan en memoria y un servidor HTTP mínimo las sirve
 * en el puerto PORT, solo a conexiones de la red privada Tailscale (100.64.0.0/10) y con la clave. Así se sigue el log
 * en el coche, con datos móviles, sin cable ni adb. Se apaga solo en la hora fijada al activarlo.
 *
 * GET /log?k=CLAVE&since=N → las líneas con número &gt; N, una por línea; la cabecera X-Next dice el último número.
 */
public final class LiveLog {
    static final int PORT = 47900;
    private static final int MAX_LINES = 20_000;
    private static final String PREFS = "hql_livelog";

    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static long lastSeq;
    private static volatile boolean collecting;
    private static volatile ServerSocket server;
    private static volatile String key = "";
    private static volatile long untilMs;

    private LiveLog() {
    }

    /** Desde L.write: guarda la línea si está activo (barato si no). */
    static void add(String line) {
        if (!collecting) return;
        if (System.currentTimeMillis() > untilMs) {
            stop("caducado");
            return;
        }
        synchronized (lines) {
            lines.addLast(line);
            lastSeq++;
            while (lines.size() > MAX_LINES) lines.removeFirst();
        }
    }

    /** Puro: ¿una dirección de Tailscale (100.64.0.0/10, o IPv6 fd7a:115c:a1e0::/48) o del propio móvil? */
    static boolean allowed(byte[] a) {
        if (a == null) return false;
        if (a.length == 4) {
            if ((a[0] & 0xFF) == 127) return true;
            return (a[0] & 0xFF) == 100 && (a[1] & 0xC0) == 64;
        }
        if (a.length == 16) {
            return (a[0] & 0xFF) == 0xfd && (a[1] & 0xFF) == 0x7a && (a[2] & 0xFF) == 0x11 && (a[3] & 0xFF) == 0x5c
                    && (a[4] & 0xFF) == 0xa1 && (a[5] & 0xFF) == 0xe0;
        }
        return false;
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Al arrancar la app: si estaba activo y no ha caducado, sigue. */
    public static void resume(Context c) {
        SharedPreferences p = sp(c);
        long until = p.getLong("until", 0);
        String k = p.getString("key", "");
        if (until > System.currentTimeMillis() && k.length() >= 16) start(k, until);
    }

    /** Lo activa hasta untilMs con esa clave (LiveLogReceiver) y lo guarda para los arranques siguientes. */
    static void enable(Context c, String k, long until) {
        sp(c).edit().putString("key", k).putLong("until", until).apply();
        start(k, until);
    }

    static void disable(Context c) {
        sp(c).edit().putLong("until", 0).apply();
        stop("apagado");
    }

    private static synchronized void start(String k, long until) {
        key = k;
        untilMs = until;
        collecting = true;
        if (server != null) return;
        try {
            ServerSocket s = new ServerSocket(PORT, 8, InetAddress.getByName("0.0.0.0"));
            server = s;
            Thread t = new Thread(() -> serve(s), "livelog");
            t.setDaemon(true);
            t.start();
            L.i(String.format(java.util.Locale.US, "log en directo: activo (puerto %d) hasta dentro de %d min", PORT,
                    (until - System.currentTimeMillis()) / 60_000));
        } catch (Exception e) {
            L.w("log en directo: no se pudo abrir el puerto: " + e.getClass().getSimpleName());
        }
    }

    private static synchronized void stop(String why) {
        collecting = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
            L.i("log en directo: " + why);
        }
        synchronized (lines) {
            lines.clear();
        }
    }

    private static void serve(ServerSocket s) {
        while (!s.isClosed()) {
            try (Socket c = s.accept()) {
                c.setSoTimeout(5000);
                if (!allowed(c.getInetAddress().getAddress())) continue;
                BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                String req = in.readLine();
                if (req == null) continue;
                String path = req.split(" ").length > 1 ? req.split(" ")[1] : "";
                String k = param(path, "k");
                long since = parseLong(param(path, "since"));
                OutputStream out = c.getOutputStream();
                if (!key.equals(k) || !path.startsWith("/log")) {
                    out.write("HTTP/1.0 403 Forbidden\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    continue;
                }
                StringBuilder body = new StringBuilder();
                long next;
                synchronized (lines) {
                    next = lastSeq;
                    long first = lastSeq - lines.size() + 1;
                    long i = first;
                    for (String l : lines) {
                        if (i > since) body.append(l).append('\n');
                        i++;
                    }
                }
                byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nX-Next: " + next
                        + "\r\nContent-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(b);
                out.flush();
            } catch (Exception ignored) {
                // Una petición rota no tumba el servidor; al cerrarlo (stop), accept falla y el bucle acaba.
            }
        }
    }

    private static String param(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        for (String kv : path.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(name)) return kv.substring(e + 1);
        }
        return "";
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
