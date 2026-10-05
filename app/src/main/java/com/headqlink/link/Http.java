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
