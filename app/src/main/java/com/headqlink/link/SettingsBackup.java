package com.headqlink.link;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Copia de seguridad de los ajustes en un archivo JSON, para cambiar de móvil o pasárselos a otro: listas de TV y radio,
 * favoritos, marcadores de la Web, precios, filtros de cargadores, planificador, imagen, panel y el modelo del coche.
 * Nunca lleva la cuenta de Leapmotor (sesión, certificados, PIN), Home Assistant, el historial de la Web, los sitios
 * recientes ni los guardados (Casa, Trabajo…), la calibración de los viajes ni nada del estado del móvil. Lo mismo se
 * filtra al importar, por si el archivo viene tocado.
 */
final class SettingsBackup {
    static final String MAGIC = "qonecta-settings";
    static final int FORMAT = 1;

    /** Del almacén «cfg», lo que no viaja (personal, de este móvil o del momento). */
    static final Set<String> SKIP = new HashSet<>(Arrays.asList(
            "recent_places", Config.SAVED_PLACES, Config.HOME_ROUTE, "route_calib", "soc_pct", "soc_used_kwh", "uuid", "seen_version_code", "setup_done",
            "update_checked_at", "update_version", "update_url", "update_notes", "update_apk", Config.NES_TREE,
            Config.BT_AUTO_NAME, Config.MODE_BEFORE_PATTERN, Config.QD_PHONE_INFO, "panel_home_seen", "float_x", "float_y",
            // Las condiciones de uso las acepta cada uno en su móvil; la accesibilidad es de este móvil.
            "responsibility_accepted", UpdateReceiver.ACC_WAS_ON));
    /** Del almacén del coche (CarCloudStore), solo el modelo elegido; la cuenta y su sesión, nunca. */
    static final Set<String> CAR_KEYS = new HashSet<>(Arrays.asList("profile", "custom_kwh"));

    static final class Result {
        int settings;
        int tvLists;
        int radioLists;
        int favorites;
        int bookmarks;
    }

    private SettingsBackup() {
    }

    static String export(Context c, String version) throws JSONException {
        return export(c.getSharedPreferences("cfg", Context.MODE_PRIVATE),
                c.getSharedPreferences(CarCloudStore.PREFS, Context.MODE_PRIVATE), version, System.currentTimeMillis());
    }

    /** Puro (salvo las preferencias que lee): el JSON de la copia. */
    static String export(SharedPreferences cfg, SharedPreferences car, String version, long nowMs) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("app", MAGIC);
        root.put("format", FORMAT);
        root.put("version", version);
        root.put("created", nowMs);
        root.put("cfg", dump(cfg, null));
        root.put("car", dump(car, CAR_KEYS));
        return root.toString(1);
    }

    /** {clave: [tipo, valor]} con tipo s (texto), b, i, l, f o ss (conjunto de textos). */
    private static JSONObject dump(SharedPreferences sp, Set<String> only) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (only != null ? !only.contains(k) : !travels(k)) continue;
            if (v instanceof String) {
                String s = (String) v;
                if (k.endsWith("_lists")) s = withoutLocalFiles(s);
                if (isLocal(s)) continue;
                o.put(k, new JSONArray().put("s").put(s));
            } else if (v instanceof Boolean) {
                o.put(k, new JSONArray().put("b").put(v));
            } else if (v instanceof Integer) {
                o.put(k, new JSONArray().put("i").put(v));
            } else if (v instanceof Long) {
                o.put(k, new JSONArray().put("l").put(v));
            } else if (v instanceof Float) {
                o.put(k, new JSONArray().put("f").put((double) (Float) v));
            } else if (v instanceof Set) {
                JSONArray a = new JSONArray();
                for (Object x : (Set<?>) v) a.put(String.valueOf(x));
                o.put(k, new JSONArray().put("ss").put(a));
            }
        }
        return o;
    }

    /** Puro: la clave de «cfg» viaja en la copia. */
    static boolean travels(String key) {
        return key != null && !SKIP.contains(key) && !key.startsWith("update_");
    }

    /** Un archivo elegido en este móvil (content://, file://): en otro no se puede abrir. */
    static boolean isLocal(String s) {
        return s.startsWith("content://") || s.startsWith("file://");
    }

    /** Puro: de una lista de listas ([[nombre, origen], …]), fuera las que son archivos del móvil. */
    static String withoutLocalFiles(String json) {
        try {
            JSONArray a = new JSONArray(json);
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONArray e = a.optJSONArray(i);
                if (e != null && e.length() >= 2 && isLocal(e.optString(1))) continue;
                out.put(a.get(i));
            }
            return out.toString();
        } catch (JSONException e) {
            return json;
        }
    }

    /** Lee y comprueba el archivo; lanza si no es una copia nuestra. */
    static JSONObject parse(String text) throws JSONException {
        JSONObject root = new JSONObject(text.trim());
        if (!MAGIC.equals(root.optString("app"))) throw new JSONException("no es una copia de ajustes");
        if (root.optInt("format", 0) > FORMAT) throw new JSONException("copia de una versión más nueva");
        return root;
    }

    /** Lo que trae (para preguntar antes de importar), sin tocar nada. */
    static Result summary(JSONObject root) {
        Result r = new Result();
        JSONObject cfg = root.optJSONObject("cfg");
        if (cfg == null) return r;
        for (java.util.Iterator<String> it = cfg.keys(); it.hasNext(); ) if (travels(it.next())) r.settings++;
        r.tvLists = arrayLen(cfg, Config.IPTV + "_lists");
        r.radioLists = arrayLen(cfg, Config.RADIO + "_lists");
        r.favorites = arrayLen(cfg, Favorites.TV + "_favs") + arrayLen(cfg, Favorites.RADIO + "_favs");
        r.bookmarks = arrayLen(cfg, Config.WEB_SHORTCUTS);
        return r;
    }

    private static int arrayLen(JSONObject cfg, String key) {
        JSONArray e = cfg.optJSONArray(key);
        if (e == null || !"s".equals(e.optString(0))) return 0;
        try {
            return new JSONArray(e.optString(1)).length();
        } catch (JSONException ex) {
            return 0;
        }
    }

    static Result apply(Context c, JSONObject root) {
        return apply(c.getSharedPreferences("cfg", Context.MODE_PRIVATE),
                c.getSharedPreferences(CarCloudStore.PREFS, Context.MODE_PRIVATE), root);
    }

    /** Escribe lo que trae (encima de lo que hay; lo que no trae se queda como está). */
    static Result apply(SharedPreferences cfg, SharedPreferences car, JSONObject root) {
        Result r = summary(root);
        r.settings = load(cfg, root.optJSONObject("cfg"), null);
        load(car, root.optJSONObject("car"), CAR_KEYS);
        return r;
    }

    private static int load(SharedPreferences sp, JSONObject o, Set<String> only) {
        if (o == null) return 0;
        SharedPreferences.Editor ed = sp.edit();
        int n = 0;
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (only != null ? !only.contains(k) : !travels(k)) continue;
            JSONArray e = o.optJSONArray(k);
            if (e == null || e.length() < 2) continue;
            String t = e.optString(0);
            switch (t) {
                case "s": {
                    String s = e.optString(1);
                    if (k.endsWith("_lists")) s = withoutLocalFiles(s);
                    if (isLocal(s)) continue;
                    ed.putString(k, s);
                    break;
                }
                case "b":
                    ed.putBoolean(k, e.optBoolean(1));
                    break;
                case "i":
                    ed.putInt(k, e.optInt(1));
                    break;
                case "l":
                    ed.putLong(k, e.optLong(1));
                    break;
                case "f":
                    ed.putFloat(k, (float) e.optDouble(1));
                    break;
                case "ss": {
                    JSONArray a = e.optJSONArray(1);
                    if (a == null) continue;
                    Set<String> set = new HashSet<>();
                    for (int i = 0; i < a.length(); i++) set.add(a.optString(i));
                    ed.putStringSet(k, set);
                    break;
                }
                default:
                    continue;
            }
            n++;
        }
        ed.commit();
        return n;
    }

    /** Nombre del archivo: qonecta-ajustes-2026-10-13.json. */
    static String fileName(long nowMs) {
        return "qonecta-ajustes-" + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                .format(new java.util.Date(nowMs)) + ".json";
    }
}
