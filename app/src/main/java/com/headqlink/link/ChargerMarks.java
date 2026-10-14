package com.headqlink.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cargadores favoritos y vetados de la pestaña Ruta: un favorito se prefiere al elegir las paradas del plan
 * (ChargePlanner.FAV_BONUS_KW); uno vetado no se usa nunca (ni sale entre los que pasan el filtro). Se guardan en el
 * móvil (Config.chargerMarks) por el id estable del cargador («dgt:…» del registro oficial, «osm:node…» de
 * OpenStreetMap) y su posición: el mismo sitio puede llegar de la DGT un día y de OpenStreetMap otro (se juntan en
 * RoutePlanner.mergeSources), y así la marca le sigue valiendo. Puro (sin Android salvo org.json).
 *
 * JSON: [{"i": id, "n": nombre, "la": lat, "lo": lon, "k": 1 favorito | -1 vetado}].
 */
final class ChargerMarks {
    static final int NONE = 0;
    static final int FAV = 1;
    static final int BAN = -1;
    /** Un cargador a menos de esto de uno marcado es el mismo sitio (km): RoutePlanner.SAME_SITE_KM. */
    static final double SAME_KM = RoutePlanner.SAME_SITE_KM;
    static final int MAX = 100;

    /** Una marca. */
    static final class Mark {
        String id = "";
        String name = "";
        double lat;
        double lon;
        int kind;
    }

    private ChargerMarks() {
    }

    /** Puro: el id estable de un cargador: el de su fuente o, sin él, su posición redondeada (unos 10 m). */
    static String key(RoutePlanner.Charger c) {
        if (c.id != null && !c.id.isEmpty()) return c.id;
        return String.format(Locale.US, "@%.4f,%.4f", c.lat, c.lon);
    }

    /** Puro: lee las marcas (roto o vacío: ninguna). */
    static List<Mark> parse(String json) {
        List<Mark> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Mark m = new Mark();
                m.id = o.optString("i", "");
                m.name = o.optString("n", "");
                m.lat = o.optDouble("la", Double.NaN);
                m.lon = o.optDouble("lo", Double.NaN);
                m.kind = o.optInt("k", NONE) > 0 ? FAV : o.optInt("k", NONE) < 0 ? BAN : NONE;
                if (m.kind == NONE || (m.id.isEmpty() && Double.isNaN(m.lat))) continue;
                out.add(m);
            }
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return out;
    }

    static String toJson(List<Mark> list) {
        JSONArray a = new JSONArray();
        try {
            for (Mark m : list) {
                JSONObject o = new JSONObject().put("i", m.id).put("n", m.name).put("k", m.kind);
                if (!Double.isNaN(m.lat) && !Double.isNaN(m.lon)) o.put("la", m.lat).put("lo", m.lon);
                a.put(o);
            }
        } catch (JSONException ignored) {
            // Números finitos: no pasa.
        }
        return a.toString();
    }

    /** Puro: la marca de este cargador (por id o, si no, por estar en el mismo sitio), o null. */
    static Mark find(List<Mark> marks, RoutePlanner.Charger c) {
        String k = key(c);
        for (Mark m : marks) if (!m.id.isEmpty() && m.id.equals(k)) return m;
        for (Mark m : marks) {
            if (Double.isNaN(m.lat) || Math.abs(m.lat - c.lat) > 0.01 || Math.abs(m.lon - c.lon) > 0.015) continue;
            if (RoutePlanner.segmentKm(m.lat, m.lon, m.lat, m.lon, c.lat, c.lon) < SAME_KM) return m;
        }
        return null;
    }

    /** Puro: NONE, FAV o BAN. */
    static int kindOf(List<Mark> marks, RoutePlanner.Charger c) {
        Mark m = find(marks, c);
        return m == null ? NONE : m.kind;
    }

    /** Puro: la siguiente al tocar el botón: ninguna → favorito → vetado → ninguna. */
    static int next(int kind) {
        return kind == NONE ? FAV : kind == FAV ? BAN : NONE;
    }

    /** Puro: marca el cargador (NONE: le quita la marca). */
    static String set(String json, RoutePlanner.Charger c, int kind) {
        List<Mark> list = parse(json);
        Mark old = find(list, c);
        if (old != null) list.remove(old);
        if (kind != NONE) {
            Mark m = new Mark();
            m.id = key(c);
            m.name = c.name == null ? "" : c.name;
            m.lat = c.lat;
            m.lon = c.lon;
            m.kind = kind > 0 ? FAV : BAN;
            list.add(m);
            while (list.size() > MAX) list.remove(0);
        }
        return toJson(list);
    }

    /** Puro: quita la marca de la posición index. */
    static String remove(String json, int index) {
        List<Mark> list = parse(json);
        if (index >= 0 && index < list.size()) list.remove(index);
        return toJson(list);
    }

    /** Puro: los cargadores sin los vetados (la misma lista si no hay ninguno). */
    static List<RoutePlanner.Charger> withoutBanned(List<RoutePlanner.Charger> chargers, List<Mark> marks) {
        boolean any = false;
        for (Mark m : marks) any |= m.kind == BAN;
        if (!any) return chargers;
        List<RoutePlanner.Charger> out = new ArrayList<>();
        for (RoutePlanner.Charger c : chargers) if (kindOf(marks, c) != BAN) out.add(c);
        return out;
    }

    /** Puro: los favoritos de la lista (para ChargePlanner.Settings.favorites). */
    static java.util.Set<RoutePlanner.Charger> favorites(List<RoutePlanner.Charger> chargers, List<Mark> marks) {
        java.util.Set<RoutePlanner.Charger> out = new java.util.HashSet<>();
        boolean any = false;
        for (Mark m : marks) any |= m.kind == FAV;
        if (!any) return out;
        for (RoutePlanner.Charger c : chargers) if (kindOf(marks, c) == FAV) out.add(c);
        return out;
    }
}
