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
