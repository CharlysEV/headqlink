package com.headqlink.link;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTP mínimo para los servicios abiertos (OSM, OSRM, Open-Meteo). Llamar fuera del hilo principal. */
final class Http {
    /** Nominatim y Overpass piden identificarse. */
    static final String USER_AGENT = "Qonecta+/" + com.andrerinas.openheadunit.BuildConfig.VERSION_NAME
            + " (proyecto abierto; Android Auto en los coches Leapmotor)";

    private Http() {
    }

    private static final java.util.regex.Pattern URL_PATTERN = java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://([^/\\s?#:]+)\\S*");

    /**
     * Un error para el registro sin la ubicación (el log se exporta y no debe llevar GPS, qdauto §7.5): las URLs de
     * Open-Meteo, OSRM o Nominatim llevan la posición o el destino en la ruta o en la consulta, así que de cada URL
     * queda solo el host; de un JSON inesperado, solo el tipo (su texto puede traer direcciones).
     */
    static String safeError(Throwable e) {
        if (e == null) return "";
        if (e instanceof org.json.JSONException) return "respuesta inesperada (" + e.getClass().getSimpleName() + ")";
        String m = e.getMessage();
        String s = e.getClass().getSimpleName() + (m != null ? ": " + m : "");
        return URL_PATTERN.matcher(s).replaceAll("$1");
    }

    static String get(String url) throws Exception {
        return request(url, null);
    }

    /**
     * Servidores públicos de Overpass (OpenStreetMap): el principal y dos espejos. El principal se satura a ratos (504,
     * 429) y algún espejo cae (500): entonces se repite la consulta en el siguiente.
     */
    static final String[] OVERPASS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    };

    /**
     * Pausa de Overpass: cuando los tres servidores fallan (saturados o sin red), no se vuelve a preguntar hasta pasado
     * un rato (30 s, 60 s… hasta 5 min), en vez de que cada consulta espere y reintente minutos. Así nada que dependa de
     * OpenStreetMap (radares, límites, cargadores) se queda colgado esperando.
     */
    static final class OverpassGate {
        static final long FIRST_MS = 30_000;
        static final long MAX_MS = 300_000;
        private long until;
        private int fails;

        synchronized boolean open(long now) {
            return now >= until;
        }

        synchronized long until() {
            return until;
        }

        synchronized void failed(long now) {
            fails++;
            until = now + pause(fails);
        }

        synchronized void ok() {
            fails = 0;
            until = 0;
        }

        synchronized int fails() {
            return fails;
        }

        /** Puro: la pausa tras n fallos seguidos (30 s, 60 s, 120 s, 240 s, 300 s…). */
        static long pause(int n) {
            long p = FIRST_MS << Math.min(Math.max(0, n - 1), 8);
            return Math.min(MAX_MS, p);
        }
    }

    static final OverpassGate OVERPASS_GATE = new OverpassGate();

    /** En pausa (servidores saturados o sin red): se lanza en vez de consultar. */
    static final class OverpassPausedException extends Exception {
        OverpassPausedException(long ms) {
            super("Overpass en pausa " + ms / 1000 + " s");
        }
    }

    /** Consulta a Overpass (para lo de fondo: cargadores), con esperas largas. */
    static String overpass(String query) throws Exception {
        return overpass(query, false);
    }

    /**
     * Consulta a Overpass, probando los espejos si uno falla. quick: lo que va con la conducción (límite de la vía,
     * radares): tiempos cortos y sin repetir en el mismo servidor. Si los tres fallan, Overpass queda en pausa un rato.
     */
    static String overpass(String query, boolean quick) throws Exception {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!OVERPASS_GATE.open(now)) throw new OverpassPausedException(OVERPASS_GATE.until() - now);
        Exception last = null;
        String data = "data=" + android.net.Uri.encode(query);
        int connectMs = quick ? 8_000 : 15_000;
        int readMs = quick ? 15_000 : 60_000; // Overpass puede tardar ~20 s en rutas largas
        for (int s = 0; s < OVERPASS.length; s++) {
            // El principal, si está ocupado (429: cupo por conexión; 504: cola llena), se repite una vez (no en las rápidas).
            int tries = s == 0 && !quick ? 2 : 1;
            for (int t = 0; t < tries; t++) {
                try {
                    String r = request(OVERPASS[s], data, connectMs, readMs);
                    OVERPASS_GATE.ok();
                    return r;
                } catch (Exception e) {
                    last = e;
                    String m = String.valueOf(e.getMessage());
                    boolean busy = m.contains("HTTP 429") || m.contains("HTTP 504");
                    if (e instanceof java.net.UnknownHostException) {
                        // Sin red: los espejos tampoco van a responder.
                        s = OVERPASS.length;
                        break;
                    }
                    if (busy && t < tries - 1) {
                        android.os.SystemClock.sleep(3000);
                        continue;
                    }
                    break;
                }
            }
        }
        OVERPASS_GATE.failed(android.os.SystemClock.elapsedRealtime());
        L.w("Overpass: sin respuesta (" + safeError(last) + "); en pausa " + OverpassGate.pause(OVERPASS_GATE.fails()) / 1000 + " s");
        throw last;
    }

    /**
     * Todo lo que queda en el flujo. InputStream.readAllBytes es de Android 13: en Android 11 y 12 la app se cerraba al
     * comprobar las versiones nada más abrirse (Mi9T Pro con Android 11, 2026-10-12).
     */
    static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** POST con cuerpo de formulario (Overpass: data=…). */
    static String post(String url, String formBody) throws Exception {
        return request(url, formBody, 15_000, 60_000);
    }

    private static String request(String url, String body) throws Exception {
        return request(url, body, 15_000, 60_000);
    }

    private static String request(String url, String body, int connectMs, int readMs) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(connectMs);
        con.setReadTimeout(readMs);
        con.setRequestProperty("User-Agent", USER_AGENT);
        con.setRequestProperty("Accept-Language", "es");
        if (body != null) {
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            try (OutputStream o = con.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = con.getResponseCode();
        if (code >= 400) throw new IllegalStateException("HTTP " + code + " en " + new URL(url).getHost());
        try (InputStream in = con.getInputStream()) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }
}
