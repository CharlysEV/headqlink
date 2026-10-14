package com.headqlink.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Destinos guardados de la pestaña Ruta: «Casa», «Trabajo» y los que se guardan con nombre (un destino o un viaje con
 * paradas intermedias e ida y vuelta). Solo en el móvil (Config.savedPlaces, fuera de la copia de ajustes: son
 * ubicaciones personales) y nunca en el registro. Puro (sin Android salvo org.json): lo prueban los tests.
 *
 * JSON: [{"n": nombre, "d": dirección, "la": lat, "lo": lon, "t": "home" | "work" | "", "v": [paradas], "r": ida y vuelta}].
 */
final class SavedPlaces {
    static final String HOME = "home";
    static final String WORK = "work";
    /** Como mucho, guardados (Casa y Trabajo incluidos). */
    static final int MAX = 30;
    /** Paradas intermedias de un viaje, como mucho. */
    static final int MAX_VIA = 3;
    /** Dos guardados a menos de esto (con las mismas paradas) son el mismo (m). */
    static final double SAME_M = 120;

    /** Un destino guardado. */
    static final class Saved {
        String name = "";
        String detail = "";
        double lat;
        double lon;
        /** HOME, WORK o "" (uno con nombre). */
        String tag = "";
        /** Paradas intermedias (en orden), vacía si es solo un destino. */
        List<RoutePlanner.Place> via = new ArrayList<>();
        /** Vuelve al origen. */
        boolean roundTrip;

        /** El destino como lugar de la ruta. */
        RoutePlanner.Place place() {
            RoutePlanner.Place p = new RoutePlanner.Place();
            p.name = name;
            p.detail = detail;
            p.lat = lat;
            p.lon = lon;
            return p;
        }

        boolean isTrip() {
            return !via.isEmpty() || roundTrip;
        }
    }

    private SavedPlaces() {
    }

    /** Puro: lee el JSON guardado (roto o vacío: lista vacía; las entradas sin coordenadas, fuera). */
    static List<Saved> parse(String json) {
        List<Saved> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Saved s = new Saved();
                s.name = o.optString("n", "");
                s.detail = o.optString("d", "");
                s.lat = o.optDouble("la", Double.NaN);
                s.lon = o.optDouble("lo", Double.NaN);
                s.tag = tagOf(o.optString("t", ""));
                s.roundTrip = o.optBoolean("r", false);
                JSONArray v = o.optJSONArray("v");
                for (int k = 0; v != null && k < v.length() && s.via.size() < MAX_VIA; k++) {
                    RoutePlanner.Place p = placeFrom(v.optJSONObject(k));
                    if (p != null) s.via.add(p);
                }
                if (Double.isNaN(s.lat) || Double.isNaN(s.lon)) continue;
                out.add(s);
            }
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return out;
    }

    /** Puro: la lista como JSON. */
    static String toJson(List<Saved> list) {
        JSONArray a = new JSONArray();
        try {
            for (Saved s : list) {
                JSONObject o = new JSONObject().put("n", s.name == null ? "" : s.name).put("d", s.detail == null ? "" : s.detail)
                        .put("la", s.lat).put("lo", s.lon);
                if (!s.tag.isEmpty()) o.put("t", s.tag);
                if (s.roundTrip) o.put("r", true);
                if (!s.via.isEmpty()) {
                    JSONArray v = new JSONArray();
                    for (RoutePlanner.Place p : s.via) v.put(placeJson(p));
                    o.put("v", v);
                }
                a.put(o);
            }
        } catch (JSONException ignored) {
            // Números finitos: no pasa.
        }
        return a.toString();
    }

    /** Puro: el que lleva esa etiqueta (HOME, WORK), o null. */
    static Saved tagged(List<Saved> list, String tag) {
        for (Saved s : list) if (s.tag.equals(tag)) return s;
        return null;
    }

    /** Puro: el lugar pasa a ser «Casa» (o «Trabajo»): sustituye al que lo era (solo hay uno de cada). */
    static String setTagged(String json, RoutePlanner.Place pl, String tag) {
        List<Saved> list = parse(json);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).tag.equals(tag)) list.remove(i);
        Saved s = fromPlace(pl);
        s.tag = tagOf(tag);
        // Casa y Trabajo, los primeros.
        list.add(tag.equals(HOME) ? 0 : Math.min(list.size(), tagged(list, HOME) != null ? 1 : 0), s);
        return toJson(trim(list));
    }

    /**
     * Puro: guarda un destino (o un viaje: via y roundTrip) con nombre. Si ya estaba (el mismo sitio con las mismas
     * paradas), se queda uno con el nombre nuevo.
     */
    static String add(String json, RoutePlanner.Place dest, List<RoutePlanner.Place> via, boolean roundTrip, String name) {
        List<Saved> list = parse(json);
        Saved s = fromPlace(dest);
        if (name != null && !name.trim().isEmpty()) s.name = name.trim();
        if (via != null) for (RoutePlanner.Place p : via) if (s.via.size() < MAX_VIA) s.via.add(copy(p));
        s.roundTrip = roundTrip;
        for (int i = list.size() - 1; i >= 0; i--) {
            Saved o = list.get(i);
            if (o.tag.isEmpty() && same(o, s)) list.remove(i);
        }
        list.add(s);
        return toJson(trim(list));
    }

    /** Puro: quita el de la posición index (fuera de rango: igual). */
    static String remove(String json, int index) {
        List<Saved> list = parse(json);
        if (index >= 0 && index < list.size()) list.remove(index);
        return toJson(list);
    }

    /** Puro: cambia el nombre del de la posición index (vacío: igual). */
    static String rename(String json, int index, String name) {
        List<Saved> list = parse(json);
        if (index < 0 || index >= list.size() || name == null || name.trim().isEmpty()) return toJson(list);
        list.get(index).name = name.trim();
        return toJson(list);
    }

    /** Puro: ¿el mismo destino con las mismas paradas y la misma vuelta? */
    static boolean same(Saved a, Saved b) {
        if (a.roundTrip != b.roundTrip || a.via.size() != b.via.size()) return false;
        if (RoadInfo.dist(a.lat, a.lon, b.lat, b.lon) > SAME_M) return false;
        for (int i = 0; i < a.via.size(); i++) {
            RoutePlanner.Place x = a.via.get(i);
            RoutePlanner.Place y = b.via.get(i);
            if (RoadInfo.dist(x.lat, x.lon, y.lat, y.lon) > SAME_M) return false;
        }
        return true;
    }

    private static List<Saved> trim(List<Saved> list) {
        // Sobran: fuera los más antiguos con nombre (Casa y Trabajo se quedan).
        for (int i = 0; list.size() > MAX && i < list.size(); ) {
            if (list.get(i).tag.isEmpty()) list.remove(i);
            else i++;
        }
        return list;
    }

    private static String tagOf(String t) {
        return HOME.equals(t) ? HOME : WORK.equals(t) ? WORK : "";
    }

    private static Saved fromPlace(RoutePlanner.Place pl) {
        Saved s = new Saved();
        s.name = pl.name == null ? "" : pl.name;
        s.detail = pl.detail == null ? "" : pl.detail;
        s.lat = pl.lat;
        s.lon = pl.lon;
        return s;
    }

    private static RoutePlanner.Place copy(RoutePlanner.Place p) {
        RoutePlanner.Place c = new RoutePlanner.Place();
        c.name = p.name == null ? "" : p.name;
        c.detail = p.detail == null ? "" : p.detail;
        c.lat = p.lat;
        c.lon = p.lon;
        return c;
    }

    private static JSONObject placeJson(RoutePlanner.Place p) throws JSONException {
        return new JSONObject().put("n", p.name == null ? "" : p.name).put("d", p.detail == null ? "" : p.detail).put("la", p.lat)
                .put("lo", p.lon);
    }

    private static RoutePlanner.Place placeFrom(JSONObject o) {
        if (o == null) return null;
        double la = o.optDouble("la", Double.NaN);
        double lo = o.optDouble("lo", Double.NaN);
        if (Double.isNaN(la) || Double.isNaN(lo)) return null;
        RoutePlanner.Place p = new RoutePlanner.Place();
        p.name = o.optString("n", "");
        p.detail = o.optString("d", "");
        p.lat = la;
        p.lon = lo;
        return p;
    }
}
