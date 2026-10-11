package com.headqlink.link;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Home Assistant (la casa del usuario) desde la pantalla del coche: su API REST oficial con la dirección de la casa
 * (la de fuera: Nabu Casa o un dominio propio, porque el móvil va con datos) y un token de acceso de larga duración.
 * - La dirección y la lista de elementos elegidos van en ajustes; el token, cifrado con el Keystore (su propia clave)
 *   y fuera de las copias de seguridad.
 * - Nunca se registran la dirección, el token ni los nombres de los elementos (el log se exporta): solo cuántos hay.
 * - Qué hace un toque en cada tipo de elemento (luz, enchufe, garaje, cerradura, escena…) lo decide action(), puro.
 */
final class HomeAssistant {
    private static final String PREFS = "hql_home_assistant";
    private static final String K_URL = "url";
    private static final String K_ENTITIES = "entities";
    private static final String F_TOKEN = "ha_token.bin";
    private static final String ALIAS = "hql_ha_v1";

    /** Tipos que se pueden poner en el coche (los demás no tienen sentido allí). */
    static final String[] DOMAINS = {"light", "switch", "cover", "lock", "scene", "script", "button", "input_button", "input_boolean",
            "fan", "climate", "sensor", "binary_sensor", "automation", "alarm_control_panel", "media_player"};

    private final Context ctx;
    private final SharedPreferences sp;

    HomeAssistant(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sp = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ ajustes

    String url() {
        return sp.getString(K_URL, "");
    }

    /** ¿Hay dirección y token? */
    boolean configured() {
        return !url().isEmpty() && tokenFile().isFile();
    }

    void save(String url, String token) throws Exception {
        sp.edit().putString(K_URL, normalizeUrl(url)).apply();
        if (token != null && !token.isEmpty()) {
            File f = tokenFile();
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), new CarCloudStore.KeystoreBox(ALIAS).seal(token.trim().getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Borra todo (dirección, elementos, token y su clave). */
    void forget() {
        sp.edit().clear().apply();
        //noinspection ResultOfMethodCallIgnored
        tokenFile().delete();
        new CarCloudStore.KeystoreBox(ALIAS).destroy();
    }

    private String token() throws Exception {
        byte[] sealed = Files.readAllBytes(tokenFile().toPath());
        return new String(new CarCloudStore.KeystoreBox(ALIAS).open(sealed), StandardCharsets.UTF_8);
    }

    private File tokenFile() {
        return new File(ctx.getNoBackupFilesDir(), F_TOKEN);
    }

    /** Los elementos elegidos para el coche, en su orden. */
    List<String> entities() {
        String v = sp.getString(K_ENTITIES, "");
        List<String> out = new ArrayList<>();
        for (String s : v.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    void setEntities(List<String> ids) {
        sp.edit().putString(K_ENTITIES, String.join(",", new LinkedHashSet<>(ids))).apply();
    }

    /** Puro: «casa.duckdns.org:8123/» → «https://casa.duckdns.org:8123» (sin la barra final; https si no se dice). */
    static String normalizeUrl(String u) {
        String s = u == null ? "" : u.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return s;
        if (!s.matches("(?i)^https?://.*")) s = "https://" + s;
        return s;
    }

    // ------------------------------------------------------------------ API

    /** Un elemento de la casa: su id, nombre, estado y lo que hace falta para pintarlo. */
    static final class Entity {
        String id;
        String domain;
        String name;
        String state;
        String unit;
        String deviceClass;
        double temperature = Double.NaN;
        double currentTemperature = Double.NaN;

        /** Puro: el estado para leerlo («Encendida», «21,5 °C»…). on/off se traducen fuera (HomeScreen). */
        String value() {
            if (unit != null && !unit.isEmpty() && state != null && state.matches("-?[0-9.]+")) {
                try {
                    double v = Double.parseDouble(state);
                    return (Math.abs(v - Math.rint(v)) < 1e-9 ? String.format(Locale.getDefault(), "%.0f", v)
                            : String.format(Locale.getDefault(), "%.1f", v)) + " " + unit;
                } catch (NumberFormatException ignored) {
                }
            }
            if ("climate".equals(domain) && !Double.isNaN(currentTemperature)) {
                return String.format(Locale.getDefault(), "%.1f °", currentTemperature)
                        + (Double.isNaN(temperature) ? "" : String.format(Locale.getDefault(), " → %.1f °", temperature));
            }
            return state;
        }
    }

    /** Puro: un estado de la API (/api/states) a Entity. */
    static Entity parse(JSONObject o) {
        Entity e = new Entity();
        e.id = o.optString("entity_id");
        int dot = e.id.indexOf('.');
        e.domain = dot > 0 ? e.id.substring(0, dot) : "";
        e.state = o.optString("state");
        JSONObject a = o.optJSONObject("attributes");
        e.name = a != null && !a.optString("friendly_name").isEmpty() ? a.optString("friendly_name") : e.id;
        if (a != null) {
            e.unit = a.optString("unit_of_measurement", "");
            e.deviceClass = a.optString("device_class", "");
            e.temperature = a.optDouble("temperature", Double.NaN);
            e.currentTemperature = a.optDouble("current_temperature", Double.NaN);
        }
        return e;
    }

    /**
     * Puro: qué servicio llamar al tocar un elemento ({dominio, servicio}), o null si solo se mira (sensores, clima,
     * alarma). Luces, enchufes y demás: conmutar; garaje y persianas: abrir o cerrar según estén; cerradura: abrir si
     * está cerrada y cerrar si no; escenas y scripts: lanzar; botones: pulsar.
     */
    static String[] action(Entity e) {
        String d = e.domain;
        switch (d) {
            case "light":
            case "switch":
            case "input_boolean":
            case "fan":
            case "automation":
            case "media_player":
                return new String[]{d, "toggle"};
            case "cover":
                return new String[]{d, "open".equals(e.state) || "opening".equals(e.state) ? "close_cover" : "open_cover"};
            case "lock":
                return new String[]{d, "locked".equals(e.state) ? "unlock" : "lock"};
            case "scene":
            case "script":
                return new String[]{d, "turn_on"};
            case "button":
            case "input_button":
                return new String[]{d, "press"};
            default:
                return null;
        }
    }

    /** Puro: ¿está «encendido» (para el color del botón)? */
    static boolean active(Entity e) {
        String s = e.state == null ? "" : e.state;
        switch (e.domain) {
            case "cover":
                return s.equals("open") || s.equals("opening");
            case "lock":
                return s.equals("unlocked") || s.equals("unlocking") || s.equals("open");
            case "climate":
                return !s.equals("off");
            case "alarm_control_panel":
                return s.startsWith("armed") || s.equals("triggered");
            case "media_player":
                return s.equals("playing");
            default:
                return s.equals("on") || s.equals("home");
        }
    }

    /** Puro: ¿pide confirmación? (cerraduras: no se abre la puerta de casa con un roce). */
    static boolean needsConfirm(Entity e) {
        return "lock".equals(e.domain) || ("cover".equals(e.domain) && "door".equals(e.deviceClass));
    }

    /** Comprueba la conexión: null si va bien; si no, el motivo corto. */
    String test() {
        try {
            JSONObject o = new JSONObject(request("GET", "/api/", null));
            return o.has("message") ? null : "respuesta inesperada";
        } catch (Exception e) {
            return reason(e);
        }
    }

    /** Todos los elementos de la casa de los tipos que valen para el coche, por tipo y nombre. */
    List<Entity> all() throws Exception {
        JSONArray a = new JSONArray(request("GET", "/api/states", null));
        List<Entity> out = new ArrayList<>();
        Set<String> ok = new java.util.HashSet<>(Arrays.asList(DOMAINS));
        for (int i = 0; i < a.length(); i++) {
            Entity e = parse(a.getJSONObject(i));
            if (ok.contains(e.domain)) out.add(e);
        }
        out.sort((x, y) -> x.domain.equals(y.domain) ? x.name.compareToIgnoreCase(y.name) : x.domain.compareTo(y.domain));
        return out;
    }

    /** Los elegidos, con su estado de ahora (los que ya no existen se saltan). */
    List<Entity> chosen() throws Exception {
        List<String> ids = entities();
        List<Entity> out = new ArrayList<>();
        if (ids.isEmpty()) return out;
        JSONArray a = new JSONArray(request("GET", "/api/states", null));
        java.util.Map<String, Entity> byId = new java.util.HashMap<>();
        for (int i = 0; i < a.length(); i++) {
            Entity e = parse(a.getJSONObject(i));
            byId.put(e.id, e);
        }
        for (String id : ids) if (byId.containsKey(id)) out.add(byId.get(id));
        return out;
    }

    void call(String domain, String service, String entityId) throws Exception {
        JSONObject body = new JSONObject().put("entity_id", entityId);
        request("POST", "/api/services/" + domain + "/" + service, body.toString());
    }

    private String request(String method, String path, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url() + path).openConnection();
        con.setConnectTimeout(8_000);
        con.setReadTimeout(12_000);
        con.setRequestMethod(method);
        con.setRequestProperty("Authorization", "Bearer " + token());
        con.setRequestProperty("Content-Type", "application/json");
        if (body != null) {
            con.setDoOutput(true);
            try (OutputStream o = con.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = con.getResponseCode();
        if (code == 401) throw new SecurityException("token no válido");
        if (code >= 400) throw new IllegalStateException("HTTP " + code);
        try (InputStream in = con.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Un error sin la dirección de la casa (va al log y a la pantalla). */
    static String reason(Throwable e) {
        if (e instanceof SecurityException) return "token no válido";
        if (e instanceof java.net.UnknownHostException) return "no se encuentra la dirección";
        if (e instanceof java.net.SocketTimeoutException) return "no responde";
        if (e instanceof java.net.ConnectException) return "no se puede conectar";
        if (e instanceof javax.net.ssl.SSLException) return "certificado no válido";
        if (e instanceof java.security.GeneralSecurityException) return "token ilegible";
        String m = e.getMessage();
        return m != null && m.startsWith("HTTP ") ? m : e.getClass().getSimpleName();
    }
}
