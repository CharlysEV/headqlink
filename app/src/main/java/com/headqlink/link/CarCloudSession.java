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

    private static LeapApi api;
    private static CarCloudStore.Saved saved;
    /** Transporte de las pruebas (null: el real). */
    private static LeapApi.Transport testTransport;

    private CarCloudSession() {
    }

    /** Solo pruebas: otro transporte (y se olvida el cliente actual). */
    static synchronized void setTransportForTest(LeapApi.Transport t) {
        testTransport = t;
        api = null;
        saved = null;
    }

    /** Se olvida el cliente (certificado nuevo, clave del servidor aceptada, sesión cerrada): se rehace al usarlo. */
    static synchronized void invalidate() {
        api = null;
        saved = null;
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

    /** El VIN elegido termina en… (para enseñarlo: «…1234»), o "". */
    static synchronized String vinTail(Context ctx) {
        try {
            CarCloudStore.Saved s = saved != null ? saved : new CarCloudStore(ctx).session();
            if (s == null || s.vin.length() < 4) return "";
            return "…" + s.vin.substring(s.vin.length() - 4);
        } catch (IOException | GeneralSecurityException e) {
            return "";
        }
    }

    /** El correo de la sesión (para rellenar el campo al volver a entrar), o "". */
    static synchronized String email(Context ctx) {
        try {
            CarCloudStore.Saved s = saved != null ? saved : new CarCloudStore(ctx).session();
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
