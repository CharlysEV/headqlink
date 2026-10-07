package com.headqlink.link;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.List;

/**
 * La cuenta de Leapmotor en el proceso: un solo cliente (LeapApi) para el sondeo del coche y para la pantalla del
 * móvil, con todo serializado (dos refrescos a la vez gastarían el refreshToken). Guarda la sesión cifrada cada vez
 * que cambia (login o token refrescado).
 */
final class CarCloudSession {
    /** Falta algo por configurar (certificado, sesión o coche). */
    static final class NotConfiguredException extends IOException {
        NotConfiguredException(String what) {
            super(what);
        }
    }

    /** Antes de pedir cada página: false para no pedir más (el tope diario de lecturas). */
    interface PageGate {
        boolean take();
    }

    private static LeapApi api;
    private static CarCloudStore.Saved saved;
    /** Pedido de olvidar el cliente (sin esperar al candado: lo pide la interfaz mientras el sondeo puede estar leyendo). */
    private static volatile boolean stale;
    /** Transporte de las pruebas (null: el real). */
    private static LeapApi.Transport testTransport;

    private CarCloudSession() {
    }

    /** Solo pruebas: otro transporte (y se olvida el cliente actual). */
    static synchronized void setTransportForTest(LeapApi.Transport t) {
        testTransport = t;
        api = null;
        saved = null;
        stale = false;
    }

    /**
     * Se olvida el cliente (certificado nuevo, clave del servidor aceptada, sesión cerrada): se rehace al usarlo. No
     * espera al candado (se llama desde la interfaz): lo aplica el siguiente uso.
     */
    static void invalidate() {
        stale = true;
    }

    private static LeapApi.Transport transport(CarCloudStore st) {
        if (testTransport != null) return testTransport;
        return new LeapHttps(new LeapTls.PinTrust(st.acceptedPins()));
    }

    private static LeapApi newApi(CarCloudStore st, String deviceId) throws IOException, GeneralSecurityException {
        LeapTls.Identity id;
        try {
            id = st.identity();
        } catch (GeneralSecurityException e) {
            // La clave del Keystore ya no abre lo guardado (se borró, o el móvil se restauró): hay que volver a configurarlo.
            throw new NotConfiguredException("certificado guardado ilegible (" + e.getClass().getSimpleName() + ")");
        }
        if (id == null) throw new NotConfiguredException("sin certificado de cliente");
        return new LeapApi(transport(st), id, System::currentTimeMillis, new SecureRandom(), deviceId);
    }

    /** Cliente con la sesión guardada, o NotConfigured si falta algo. */
    private static void ensure(CarCloudStore st) throws IOException, GeneralSecurityException {
        if (stale) {
            stale = false;
            api = null;
            saved = null;
        }
        if (api != null && api.loggedIn() && saved != null) return;
        CarCloudStore.Saved s;
        try {
            s = st.session();
        } catch (GeneralSecurityException e) {
            throw new NotConfiguredException("sesión guardada ilegible (" + e.getClass().getSimpleName() + ")");
        }
        if (s == null || s.session == null || !s.session.usable()) throw new NotConfiguredException("sin sesión");
        LeapApi a = newApi(st, s.session.deviceId);
        a.restore(s.session);
        api = a;
        saved = s;
    }

    private static void persist(CarCloudStore st) throws IOException, GeneralSecurityException {
        if (api == null || saved == null) return;
        saved.session = api.session();
        st.saveSession(saved);
    }

    /** Estado del coche elegido (lo usan el sondeo y «Leer estado ahora»). */
    static synchronized LeapStatus readStatus(Context ctx) throws IOException, GeneralSecurityException {
        CarCloudStore st = new CarCloudStore(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        int v0 = api.sessionVersion();
        JSONObject data;
        try {
            data = api.status(saved.vin, saved.carType);
        } catch (LeapApi.SessionExpiredException e) {
            api = null;
            throw e;
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
        return LeapStatus.parse(data);
    }

    /**
     * Viajes del coche entre fromS y toS (segundos de época) del historial de la nube, como mucho maxPages páginas de 20.
     * pageRead se llama antes de cada página (el sondeo cuenta ahí su tope diario; si devuelve false, se para).
     */
    static synchronized List<CloudHistory.Trip> readTrips(Context ctx, long fromS, long toS, int maxPages,
                                                        PageGate pageRead)
            throws IOException, GeneralSecurityException {
        CarCloudStore st = new CarCloudStore(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        int v0 = api.sessionVersion();
        List<CloudHistory.Trip> out = new java.util.ArrayList<>();
        try {
            int pages = 1;
            for (int page = 1; page <= pages; page++) {
                if (!pageRead.take()) break;
                JSONObject d = api.tripsPage(saved.vin, fromS, toS, page);
                out.addAll(CloudHistory.parsePage(d));
                if (page == 1) pages = CloudHistory.pages(d, maxPages);
            }
        } catch (LeapApi.SessionExpiredException e) {
            api = null;
            throw e;
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
        return out;
    }

    /**
     * Modo centinela encendido o apagado (la única orden; con el PIN guardado). true si el coche lo confirma; false si no
     * contesta a tiempo.
     */
    static synchronized boolean setSentry(Context ctx, boolean on) throws IOException, GeneralSecurityException {
        CarCloudStore st = new CarCloudStore(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        String pin = st.pin();
        if (pin == null || pin.isEmpty()) throw new NotConfiguredException("sin PIN del coche");
        int v0 = api.sessionVersion();
        try {
            boolean ok = api.remote(saved.vin, LeapApi.CMD_SENTRY, LeapApi.sentryContent(on), pin);
            L.i("nube Leapmotor: modo centinela " + (on ? "encendido" : "apagado") + (ok ? " (confirmado por el coche)" : " (sin confirmar)"));
            return ok;
        } catch (LeapApi.SessionExpiredException e) {
            api = null;
            throw e;
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
    }

    /** Consumo medio de las últimas semanas según el coche, o null si la nube no da cifras. */
    static synchronized CloudHistory.Weekly readWeekly(Context ctx) throws IOException, GeneralSecurityException {
        CarCloudStore st = new CarCloudStore(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        int v0 = api.sessionVersion();
        try {
            return CloudHistory.parseWeekly(api.weeklyConsumption(saved.vin));
        } catch (LeapApi.SessionExpiredException e) {
            api = null;
            throw e;
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
    }

    /**
     * Entra con el correo y la contraseña (la contraseña no se guarda) y lista los coches. Si solo hay uno, queda
     * elegido; con varios, hay que elegirlo (chooseVehicle).
     */
    static synchronized List<LeapApi.Vehicle> login(Context ctx, String email, String password)
            throws IOException, GeneralSecurityException {
        CarCloudStore st = new CarCloudStore(ctx);
        String deviceId = null;
        try {
            CarCloudStore.Saved old = st.session();
            if (old != null && old.session != null) deviceId = old.session.deviceId;
        } catch (IOException | GeneralSecurityException ignored) {
            // Sesión vieja ilegible: se empieza de cero.
        }
        api = null;
        saved = null;
        LeapApi a = newApi(st, deviceId);
        a.login(email, password);
        List<LeapApi.Vehicle> cars = a.vehicles();
        CarCloudStore.Saved s = new CarCloudStore.Saved();
        s.session = a.session();
        s.email = email.trim();
        if (cars.size() == 1) {
            s.vin = cars.get(0).vin;
            s.carType = cars.get(0).carType;
        }
        api = a;
        saved = s;
        persist(st);
        L.i("nube Leapmotor: sesión iniciada (" + CarCloudStore.maskEmail(email) + "), " + cars.size() + " coche(s)"
                + (cars.size() == 1 ? ", modelo " + cars.get(0).carType : ""));
        CarCloud.settingsChanged();
        return cars;
    }

    /** Coches de la cuenta (para elegir otro). */
    static synchronized List<LeapApi.Vehicle> vehicles(Context ctx) throws IOException, GeneralSecurityException {
        CarCloudStore st = new CarCloudStore(ctx);
        ensure(st);
        int v0 = api.sessionVersion();
        try {
            return api.vehicles();
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
    }

    static synchronized void chooseVehicle(Context ctx, LeapApi.Vehicle v) throws IOException, GeneralSecurityException {
        CarCloudStore st = new CarCloudStore(ctx);
        ensure(st);
        saved.vin = v.vin;
        saved.carType = v.carType;
        persist(st);
        L.i("nube Leapmotor: coche elegido, modelo " + v.carType);
        CarCloud.settingsChanged();
    }

    /** El VIN elegido termina en… (para enseñarlo: «…1234»), o "". Lee lo guardado: no espera al candado. */
    static String vinTail(Context ctx) {
        try {
            CarCloudStore.Saved s = new CarCloudStore(ctx).session();
            if (s == null || s.vin.length() < 4) return "";
            return "…" + s.vin.substring(s.vin.length() - 4);
        } catch (IOException | GeneralSecurityException e) {
            return "";
        }
    }

    /** El correo de la sesión (para rellenar el campo al volver a entrar), o "". Lee lo guardado: no espera al candado. */
    static String email(Context ctx) {
        try {
            CarCloudStore.Saved s = new CarCloudStore(ctx).session();
            return s == null ? "" : s.email;
        } catch (IOException | GeneralSecurityException e) {
            return "";
        }
    }

    /** Cierra la sesión: fuera tokens, coche y correo. El certificado se queda. */
    static synchronized void logout(Context ctx) {
        new CarCloudStore(ctx).deleteSession();
        api = null;
        saved = null;
        L.i("nube Leapmotor: sesión cerrada");
        CarCloud.settingsChanged();
    }

    /** «Cerrar sesión y borrar datos»: todo, también el certificado y la clave del Keystore. */
    static synchronized void wipe(Context ctx) {
        new CarCloudStore(ctx).wipeAll();
        api = null;
        saved = null;
        L.i("nube Leapmotor: sesión cerrada y datos borrados (certificado, sesión y ajustes)");
        CarCloud.settingsChanged();
    }
}
