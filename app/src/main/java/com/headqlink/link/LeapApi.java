/*
 * HeadQLink: datos reales del coche (cuenta Leapmotor), solo lectura.
 *
 * Portado de LMB10 (lib/leapmotor_engine.dart: login, restoreSession, exportSession, tokenRefresh, withTokenRetry,
 * _baseHeaders, _signedHeaders, _parseBody, getVehicleList, getVehicleStatus y Vehicle.statusPath), de txurtxil:
 * https://github.com/txurtxil/LPB10 (GPL-3.0). HeadQLink (AGPL-3.0) lo incorpora según la sección 13 de ambas licencias.
 *
 * SOLO LECTURA: de LMB10 se ha portado únicamente el login, la sesión, la lista de coches y el estado. Ninguna orden al
 * coche (cerrar, abrir, clima, ventanillas, carga, centinela, PIN…): esas funciones no existen aquí y, además, la capa
 * de transporte se niega a pedir cualquier ruta que no sea de las de lectura (ALLOWED_PATHS).
 *
 * El historial de viajes (mileage/daily/detail/page, con los kWh y los litros de cada viaje según el coche) y el consumo
 * semanal (getLastNweeks100kmECAndRank) siguen lo documentado por leapmotor-mate (ProtossBlaster) y leapmotor-api
 * (markoceri), los dos AGPL-3.0: rutas, campos y la firma (pageNum y pageSize se firman como texto y van como número).
 */
package com.headqlink.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Cliente de solo lectura de la nube internacional de Leapmotor. No es seguro entre hilos: lo serializa quien lo use. */
final class LeapApi {
    static final String HOST = "appgateway.leapmotor-international.de";
    static final String BASE_URL = "https://" + HOST;

    static final String PATH_LOGIN = "/carownerservice/oversea/acct/v1/login";
    static final String PATH_REFRESH = "/carownerservice/oversea/acct/v1/token/refresh";
    static final String PATH_VEHICLES = "/carownerservice/oversea/vehicle/v1/list";
    static final String PATH_STATUS = "/carownerservice/oversea/vehicle/v1/status/get/";
    /** Viajes del coche por días (los kWh y, en un REEV, los litros de cada uno). Va en JSON, sin «oversea». */
    static final String PATH_TRIPS = "/carownerservice/mileage/daily/detail/page";
    /** Consumo medio de las últimas semanas según el coche (kWh/100 km). */
    static final String PATH_WEEKLY_EC = "/carownerservice/oversea/drivingRecord/v1/getLastNweeks100kmECAndRank";

    /** Las únicas rutas que se piden. Todas leen; ninguna manda nada al coche. */
    static final List<String> ALLOWED_PATHS = Collections.unmodifiableList(java.util.Arrays.asList(
            PATH_LOGIN, PATH_REFRESH, PATH_VEHICLES, PATH_STATUS, PATH_TRIPS, PATH_WEEKLY_EC));

    /** true si la ruta es una de las de lectura (el estado, con el modelo detrás: /status/get/c10). */
    static boolean allowed(String path) {
        if (path == null) return false;
        if (path.equals(PATH_LOGIN) || path.equals(PATH_REFRESH) || path.equals(PATH_VEHICLES) || path.equals(PATH_TRIPS)
                || path.equals(PATH_WEEKLY_EC)) {
            return true;
        }
        return path.startsWith(PATH_STATUS) && path.substring(PATH_STATUS.length()).matches("[a-z0-9_-]{1,24}");
    }

    // ------------------------------------------------------------------ transporte

    /** Respuesta HTTP: código y cuerpo. */
    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    /**
     * POST de formulario a BASE_URL + path con TLS mutuo: identity es el certificado con el que se presenta el móvil
     * (el del usuario para el login; el de cuenta para lo demás). Las pruebas ponen uno de mentira.
     */
    interface Transport {
        Response post(LeapTls.Identity identity, String path, Map<String, String> headers, String body) throws IOException;
    }

    // ------------------------------------------------------------------ errores

    /** La nube contestó con un error (code != 0 o HTTP != 200). */
    static class ApiException extends IOException {
        final int httpCode;
        final int apiCode;
        final String serverMessage;

        ApiException(int httpCode, int apiCode, String serverMessage) {
            super("HTTP " + httpCode + ", código " + apiCode + (serverMessage.isEmpty() ? "" : ": " + serverMessage));
            this.httpCode = httpCode;
            this.apiCode = apiCode;
            this.serverMessage = serverMessage;
        }

        /** Como withTokenRetry de LMB10: el mensaje habla del token (o la nube contesta 401). */
        boolean tokenProblem() {
            return httpCode == 401 || serverMessage.toLowerCase(Locale.ROOT).contains("token");
        }
    }

    /** La sesión ya no vale (el refresco falló): hay que volver a entrar con el correo y la contraseña. */
    static final class SessionExpiredException extends IOException {
        SessionExpiredException(String why) {
            super(why);
        }
    }

    // ------------------------------------------------------------------ sesión y coches

    /** Lo que hace falta para seguir sin volver a entrar (SessionData de LMB10). Se guarda cifrado. */
    static final class Session {
        String userId = "";
        String token = "";
        String refreshToken = "";
        String deviceId = "";
        String signIkm = "";
        String signSalt = "";
        String signInfo = "";
        String accountId = "";
        String uid = "";
        String base64Cert = "";

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("userId", userId);
            o.put("token", token);
            o.put("refreshToken", refreshToken);
            o.put("deviceId", deviceId);
            o.put("signIkm", signIkm);
            o.put("signSalt", signSalt);
            o.put("signInfo", signInfo);
            o.put("accountId", accountId);
            o.put("uid", uid);
            o.put("base64Cert", base64Cert);
            return o;
        }

        static Session fromJson(JSONObject o) {
            Session s = new Session();
            s.userId = o.optString("userId");
            s.token = o.optString("token");
            s.refreshToken = o.optString("refreshToken");
            s.deviceId = o.optString("deviceId");
            s.signIkm = o.optString("signIkm");
            s.signSalt = o.optString("signSalt");
            s.signInfo = o.optString("signInfo");
            s.accountId = o.optString("accountId");
            s.uid = o.optString("uid");
            s.base64Cert = o.optString("base64Cert");
            return s;
        }

        boolean usable() {
            return !userId.isEmpty() && !token.isEmpty() && !base64Cert.isEmpty() && !signIkm.isEmpty();
        }
    }

    /** Un coche de la cuenta (propio o compartido). */
    static final class Vehicle {
        final String vin;
        final String carType;
        final String nickName;
        final boolean shared;

        Vehicle(String vin, String carType, String nickName, boolean shared) {
            this.vin = vin;
            this.carType = carType;
            this.nickName = nickName;
            this.shared = shared;
        }

        /** Ruta del estado: el B10 y el B11 usan la del C10; los demás, su modelo en minúsculas. */
        String statusPath() {
            return statusPath(carType);
        }

        static String statusPath(String carType) {
            String t = carType == null ? "" : carType.toLowerCase(Locale.ROOT);
            return t.equals("b10") || t.equals("b11") ? "c10" : t;
        }
    }

    // ------------------------------------------------------------------ cliente

    /** Reloj de pared (ms): el del sistema o, en las pruebas, uno fijo. */
    interface Clock {
        long nowMs();
    }

    private final Transport http;
    private final LeapTls.Identity clientIdentity;
    private final Clock clockMs;
    private final Random rnd;
    private Session session;
    private byte[] signKey;
    private LeapTls.Identity accountIdentity;
    private String deviceId;
    /** Sube cada vez que cambia la sesión (login o refresco): quien la guarda sabe si tiene que volver a hacerlo. */
    private int sessionVersion;

    /**
     * clientIdentity: el certificado de cliente del usuario. deviceId: el guardado o, la primera vez, null (se crea uno
     * al azar como en LMB10; tras el login pasa a ser el del token).
     */
    LeapApi(Transport http, LeapTls.Identity clientIdentity, Clock clockMs, Random rnd, String deviceId) {
        this.http = http;
        this.clientIdentity = clientIdentity;
        this.clockMs = clockMs;
        this.rnd = rnd;
        this.deviceId = deviceId != null && !deviceId.isEmpty() ? deviceId : LeapCrypto.newDeviceId(new SecureRandom());
    }

    Session session() {
        return session;
    }

    int sessionVersion() {
        return sessionVersion;
    }

    boolean loggedIn() {
        return session != null && accountIdentity != null;
    }

    /** Entra con el correo y la contraseña de la cuenta de Leapmotor. La contraseña no se guarda en ningún sitio. */
    Session login(String email, String password) throws IOException, GeneralSecurityException {
        String nonce = LeapCrypto.nonce(rnd);
        String ts = String.valueOf(clockMs.nowMs());
        String sign = LeapCrypto.loginSign(deviceId, email, nonce, password, ts);
        String body = "isRecoverAcct=0&password=" + LeapCrypto.encodeComponent(password) + "&policyId=" + LeapCrypto.POLICY_ID
                + "&loginMethod=1&email=" + LeapCrypto.encodeComponent(email);
        JSONObject data = parse(call(clientIdentity, PATH_LOGIN, baseHeaders(nonce, ts, sign), body), "login");
        JSONObject d = data.optJSONObject("data");
        if (d == null) throw new ApiException(200, 0, "login sin datos");
        Session s = new Session();
        s.userId = str(d, "id");
        s.token = str(d, "token");
        s.refreshToken = str(d, "refreshToken");
        s.signIkm = str(d, "signIkm");
        s.signSalt = str(d, "signSalt");
        s.signInfo = str(d, "signInfo");
        s.accountId = str(d, "id");
        s.uid = str(d, "uid");
        s.base64Cert = str(d, "base64Cert");
        s.deviceId = LeapCrypto.sessionDeviceId(s.token, deviceId);
        if (s.base64Cert.isEmpty()) throw new ApiException(200, 0, "login sin certificado de cuenta");
        restore(s);
        return s;
    }

    /** Sigue con una sesión guardada sin volver a entrar (carga el PKCS#12 de cuenta con su contraseña derivada). */
    void restore(Session s) throws GeneralSecurityException, IOException {
        byte[] p12 = android.util.Base64.decode(s.base64Cert, android.util.Base64.DEFAULT);
        char[] pw = LeapCrypto.accountP12Password(s.accountId, s.uid).toCharArray();
        LeapTls.Identity acct = LeapTls.loadPkcs12(p12, pw);
        signKey = LeapCrypto.signKey(s.signIkm, s.signSalt, s.signInfo);
        accountIdentity = acct;
        session = s;
        deviceId = s.deviceId;
        sessionVersion++;
    }

    /** Refresca el token con el refreshToken (sin pedir la contraseña). Si la nube lo rechaza, la sesión caducó. */
    void refresh() throws IOException {
        Session s = requireSession();
        if (s.refreshToken.isEmpty()) throw new SessionExpiredException("sin refreshToken");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("refreshToken", s.refreshToken);
        Map<String, String> h = signedHeaders(null, params);
        h.putAll(authHeaders(s));
        JSONObject res;
        try {
            res = parse(call(accountIdentity, PATH_REFRESH, h, "refreshToken=" + LeapCrypto.encodeComponent(s.refreshToken)),
                    "token refresh");
        } catch (ApiException e) {
            throw new SessionExpiredException("refresco rechazado (" + e.apiCode + ")");
        }
        JSONObject d = res.optJSONObject("data");
        String token = d == null ? "" : str(d, "token");
        if (token.isEmpty()) throw new SessionExpiredException("refresco sin token");
        s.token = token;
        // LMB10 lo deja vacío si no llega otro; aquí se conserva el anterior.
        String rt = str(d, "refreshToken");
        if (!rt.isEmpty()) s.refreshToken = rt;
        sessionVersion++;
    }

    /** Coches de la cuenta: los propios (bindcars) y los compartidos (sharedcars). */
    List<Vehicle> vehicles() throws IOException {
        return withTokenRetry(() -> {
            Session s = requireSession();
            Map<String, String> h = signedHeaders(null, null);
            h.putAll(authHeaders(s));
            JSONObject res = parse(call(accountIdentity, PATH_VEHICLES, h, ""), "vehicle list");
            JSONObject d = res.optJSONObject("data");
            List<Vehicle> out = new ArrayList<>();
            if (d == null) return out;
            for (String bucket : new String[]{"bindcars", "sharedcars"}) {
                JSONArray a = d.optJSONArray(bucket);
                if (a == null) continue;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject v = a.optJSONObject(i);
                    if (v == null) continue;
                    String nick = v.isNull("nickName") ? "" : v.optString("nickName");
                    out.add(new Vehicle(v.optString("vin"), v.optString("carType"), nick, bucket.equals("sharedcars")));
                }
            }
            return out;
        });
    }

    /** Estado del coche: el objeto «data» de la respuesta (lo interpreta LeapStatus). */
    JSONObject status(String vin, String carType) throws IOException {
        return withTokenRetry(() -> {
            Session s = requireSession();
            Map<String, String> h = signedHeaders(vin, null);
            h.putAll(authHeaders(s));
            JSONObject res = parse(call(accountIdentity, PATH_STATUS + Vehicle.statusPath(carType), h,
                    "vin=" + LeapCrypto.encodeComponent(vin)), "vehicle status");
            JSONObject d = res.optJSONObject("data");
            return d != null ? d : new JSONObject();
        });
    }

    /** Filas por página del historial de viajes (la nube solo admite 20). */
    static final int TRIPS_PAGE_SIZE = 20;

    /**
     * Una página del historial de viajes entre fromS y toS (segundos de época): el objeto «data» (pageNum, totalPage,
     * total y list). Va en JSON; pageNum y pageSize se firman como texto y se envían como número (si no, la firma no
     * cuadra).
     */
    JSONObject tripsPage(String vin, long fromS, long toS, int page) throws IOException {
        return withTokenRetry(() -> {
            Session s = requireSession();
            Map<String, String> params = new LinkedHashMap<>();
            params.put("startTime", String.valueOf(fromS));
            params.put("endTime", String.valueOf(toS));
            params.put("pageNum", String.valueOf(page));
            params.put("pageSize", String.valueOf(TRIPS_PAGE_SIZE));
            Map<String, String> h = signedHeaders(vin, params);
            h.putAll(authHeaders(s));
            h.put("carvin", vin);
            h.put("Content-Type", "application/json");
            JSONObject body = new JSONObject();
            try {
                body.put("vin", vin);
                body.put("startTime", String.valueOf(fromS));
                body.put("endTime", String.valueOf(toS));
                body.put("pageNum", page);
                body.put("pageSize", TRIPS_PAGE_SIZE);
            } catch (JSONException e) {
                throw new IOException(e);
            }
            JSONObject res = parse(call(accountIdentity, PATH_TRIPS, h, body.toString()), "trip history", true);
            JSONObject d = res.optJSONObject("data");
            return d != null ? d : new JSONObject();
        });
    }

    /** Consumo de las últimas semanas según el coche: el objeto «data» (rankResult y weeklyEC). */
    JSONObject weeklyConsumption(String vin) throws IOException {
        return withTokenRetry(() -> {
            Session s = requireSession();
            Map<String, String> params = new LinkedHashMap<>();
            params.put("carvin", vin);
            Map<String, String> h = signedHeaders(null, params);
            h.putAll(authHeaders(s));
            JSONObject res = parse(call(accountIdentity, PATH_WEEKLY_EC, h, "carvin=" + LeapCrypto.encodeComponent(vin)),
                    "weekly consumption");
            JSONObject d = res.optJSONObject("data");
            return d != null ? d : new JSONObject();
        });
    }

    // ------------------------------------------------------------------ piezas

    private interface Call<T> {
        T run() throws IOException;
    }

    /** Si la nube dice que el token no vale, se refresca y se repite una vez (withTokenRetry de LMB10). */
    private <T> T withTokenRetry(Call<T> c) throws IOException {
        try {
            return c.run();
        } catch (ApiException e) {
            if (!e.tokenProblem()) throw e;
            refresh();
            return c.run();
        }
    }

    private Session requireSession() throws IOException {
        if (session == null || accountIdentity == null) throw new SessionExpiredException("sin sesión");
        return session;
    }

    private Response call(LeapTls.Identity id, String path, Map<String, String> headers, String body) throws IOException {
        if (!allowed(path)) throw new IOException("ruta no permitida (solo lectura)");
        return http.post(id, path, headers, body);
    }

    /** Cabeceras comunes (_baseHeaders). */
    Map<String, String> baseHeaders(String nonce, String ts, String sign) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("acceptLanguage", LeapCrypto.LANGUAGE);
        h.put("channel", LeapCrypto.CHANNEL);
        h.put("deviceType", LeapCrypto.DEVICE_TYPE);
        h.put("source", LeapCrypto.SOURCE);
        h.put("version", LeapCrypto.APP_VERSION);
        h.put("nonce", nonce);
        h.put("deviceId", deviceId);
        h.put("timestamp", ts);
        h.put("sign", sign);
        h.put("X-P12_ENC_ALG", LeapCrypto.P12_ENC_ALG);
        h.put("Content-Type", "application/x-www-form-urlencoded");
        return h;
    }

    /** Cabeceras firmadas (_signedHeaders): HMAC de los campos comunes, el VIN y los del cuerpo. */
    Map<String, String> signedHeaders(String vin, Map<String, String> bodyParams) {
        String nonce = LeapCrypto.nonce(rnd);
        String ts = String.valueOf(clockMs.nowMs());
        Map<String, String> f = new LinkedHashMap<>();
        f.put("acceptLanguage", LeapCrypto.LANGUAGE);
        f.put("channel", LeapCrypto.CHANNEL);
        f.put("deviceId", deviceId);
        f.put("deviceType", LeapCrypto.DEVICE_TYPE);
        f.put("nonce", nonce);
        f.put("source", LeapCrypto.SOURCE);
        f.put("timestamp", ts);
        f.put("version", LeapCrypto.APP_VERSION);
        if (vin != null) f.put("vin", vin);
        if (bodyParams != null) f.putAll(bodyParams);
        return baseHeaders(nonce, ts, LeapCrypto.sign(signKey, f));
    }

    private static Map<String, String> authHeaders(Session s) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("userId", s.userId);
        h.put("token", s.token);
        return h;
    }

    /** _parseBody: JSON con code 0 y HTTP 200; si no, ApiException (sin copiar el cuerpo: puede llevar datos). */
    static JSONObject parse(Response r, String label) throws ApiException {
        return parse(r, label, false);
    }

    static JSONObject parse(Response r, String label, boolean textCodeOk) throws ApiException {
        JSONObject o;
        try {
            o = new JSONObject(r.body);
        } catch (JSONException e) {
            throw new ApiException(r.code, -1, label + ": la respuesta no es JSON");
        }
        Object code = o.opt("code");
        // El historial de viajes puede mandar el código como texto («0»): solo ahí se admite (leapmotor-mate).
        if (textCodeOk && code instanceof String && ((String) code).trim().matches("-?[0-9]{1,12}")) {
            code = Long.parseLong(((String) code).trim());
        }
        boolean ok = code instanceof Number && ((Number) code).doubleValue() == 0;
        if (r.code != 200 || !ok) {
            int c = code instanceof Number ? ((Number) code).intValue() : -1;
            String msg = o.isNull("message") ? "" : o.optString("message");
            if (msg.length() > 120) msg = msg.substring(0, 120);
            throw new ApiException(r.code, c, msg.isEmpty() ? label + " failed" : msg);
        }
        return o;
    }

    /** Un campo como texto, como el toString() de Dart (null y lo que falte: vacío). */
    static String str(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return "";
        return String.valueOf(o.opt(k));
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
