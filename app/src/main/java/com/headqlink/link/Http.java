package com.headqlink.link;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTP mínimo para los servicios abiertos (OSM, OSRM, Open-Meteo). Llamar fuera del hilo principal. */
final class Http {
    /** Nominatim y Overpass piden identificarse. */
    static final String USER_AGENT = "HeadQLink/0.1 (proyecto abierto; Android Auto en el Leapmotor C10)";

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

    /** Consulta a Overpass, probando los espejos si uno falla. */
    static String overpass(String query) throws Exception {
        Exception last = null;
        String data = "data=" + android.net.Uri.encode(query);
        for (int s = 0; s < OVERPASS.length; s++) {
            // El principal, si está ocupado (429: cupo por conexión; 504: cola llena), se espera y se repite.
            int tries = s == 0 ? 3 : 1;
            for (int t = 0; t < tries; t++) {
                try {
                    return post(OVERPASS[s], data);
                } catch (Exception e) {
                    last = e;
                    String m = String.valueOf(e.getMessage());
                    boolean busy = m.contains("HTTP 429") || m.contains("HTTP 504");
                    if (busy && t < tries - 1) {
                        L.w("Overpass: " + safeError(e) + "; espero y repito");
                        android.os.SystemClock.sleep(4000L * (t + 1));
                        continue;
                    }
                    L.w("Overpass: " + safeError(e) + "; pruebo otro servidor");
                    break;
                }
            }
        }
        throw last;
    }

    /** POST con cuerpo de formulario (Overpass: data=…). */
    static String post(String url, String formBody) throws Exception {
        return request(url, formBody);
    }

    private static String request(String url, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15_000);
        con.setReadTimeout(60_000); // Overpass puede tardar ~20 s en rutas largas
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
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
