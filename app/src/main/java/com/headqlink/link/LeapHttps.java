package com.headqlink.link;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.IdentityHashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Transporte real de LeapApi: HTTPS con TLS mutuo (el certificado que toque) contra la nube de Leapmotor, la
 * comprobación de nombre de Android y la clave del servidor fijada (LeapTls.PinTrust). Solo las rutas de lectura.
 */
final class LeapHttps implements LeapApi.Transport {
    private static final int CONNECT_MS = 15_000;
    private static final int READ_MS = 20_000;
    private static final int MAX_BODY = 1 << 20;

    /** La clave del servidor no es ninguna de las de confianza: el usuario decide en el móvil. */
    static final class ServerKeyChangedException extends IOException {
        final LeapTls.UnknownServerKeyException detail;

        ServerKeyChangedException(LeapTls.UnknownServerKeyException d) {
            super("clave del servidor desconocida");
            detail = d;
        }
    }

    private final X509TrustManager trust;
    private final Map<LeapTls.Identity, SSLSocketFactory> factories = new IdentityHashMap<>();

    LeapHttps(X509TrustManager trust) {
        this.trust = trust;
    }

    private synchronized SSLSocketFactory factory(LeapTls.Identity id) throws IOException {
        SSLSocketFactory f = factories.get(id);
        if (f != null) return f;
        try {
            f = LeapTls.socketFactory(id, trust);
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS: " + e.getClass().getSimpleName());
        }
        // Dos identidades como mucho (la del usuario y la de cuenta); al cambiar de sesión se crea otro transporte.
        if (factories.size() > 4) factories.clear();
        factories.put(id, f);
        return f;
    }

    @Override
    public LeapApi.Response post(LeapTls.Identity id, String path, Map<String, String> headers, String body) throws IOException {
        // Lectura; o una orden (el centinela) pedida ahora mismo desde LeapApi.remote() en este hilo.
        if (!LeapApi.allowed(path) && !LeapApi.remoteAllowedNow(path)) throw new IOException("ruta no permitida (solo lectura)");
        HttpsURLConnection c = (HttpsURLConnection) new URL(LeapApi.BASE_URL + path).openConnection();
        try {
            c.setSSLSocketFactory(factory(id));
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(b);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new LeapApi.Response(code, in == null ? "" : read(in));
        } catch (IOException e) {
            LeapTls.UnknownServerKeyException k = unknownKey(e);
            if (k != null) throw new ServerKeyChangedException(k);
            throw e;
        } finally {
            c.disconnect();
        }
    }

    /** La excepción de la clave desconocida, si es eso lo que hay debajo del fallo del handshake. */
    static LeapTls.UnknownServerKeyException unknownKey(Throwable t) {
        for (int i = 0; t != null && i < 8; i++, t = t.getCause()) {
            if (t instanceof LeapTls.UnknownServerKeyException) return (LeapTls.UnknownServerKeyException) t;
            for (Throwable s : t.getSuppressed()) {
                if (s instanceof LeapTls.UnknownServerKeyException) return (LeapTls.UnknownServerKeyException) s;
            }
        }
        return null;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) {
                o.write(buf, 0, n);
                if (o.size() > MAX_BODY) throw new IOException("respuesta demasiado grande");
            }
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
