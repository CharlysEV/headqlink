package com.headqlink.link;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cargadores de OpenStreetMap guardados en el móvil por cuadrículas de TILE_DEG grados (~20 km), TTL_MS: una zona ya
 * consultada no se vuelve a pedir a Overpass (cuyos servidores públicos se saturan a ratos). Una cuadrícula sin
 * cargadores también se guarda (es un dato). Son datos públicos: sin cifrar.
 */
final class ChargerCache {
    static final double TILE_DEG = 0.2;
    static final long TTL_MS = 30L * 86_400_000L;
    static final int MAX_TILES = 600;
    /** v2: con los enchufes de cada cargador (para estimar la potencia); la v1 no los tenía y se vuelve a pedir. */
    private static final String FILE = "chargers-cache-v2.json";

    /** Un cargador tal como se guarda. */
    static final class Item {
        String id = "";
        double lat;
        double lon;
        String name = "";
        String detail = "";
        String network = ChargerFilter.OTHER;
        double kw;
        /** Enchufes (ChargerFilter.SOCKET_DC | SOCKET_AC), 0 si OpenStreetMap no los dice. */
        int sockets;

        /** Con la potencia de OpenStreetMap o, si falta, la estimada por la red o los enchufes. */
        RoutePlanner.Charger toCharger() {
            RoutePlanner.Charger c = new RoutePlanner.Charger();
            c.name = name;
            c.detail = detail;
            c.lat = lat;
            c.lon = lon;
            c.network = network;
            c.acOnly = sockets == ChargerFilter.SOCKET_AC;
            if (kw > 0) {
                c.maxKw = kw;
                c.kwSource = ChargerFilter.KW_TAGGED;
            } else {
                double[] e = ChargerFilter.estimateKw(network, name, sockets);
                c.maxKw = e[0];
                c.kwSource = (int) e[1];
            }
            return c;
        }
    }

    private static final class Tile {
        final long atMs;
        final List<Item> items;

        Tile(long atMs, List<Item> items) {
            this.atMs = atMs;
            this.items = items;
        }
    }

    private static Map<String, Tile> tiles;

    private ChargerCache() {
    }

    // ------------------------------------------------------------------ cuadrículas (sin Android)

    static String tileOf(double lat, double lon) {
        return (int) Math.floor(lat / TILE_DEG) + "_" + (int) Math.floor(lon / TILE_DEG);
    }

    /** [sur, oeste, norte, este] de una cuadrícula. */
    static double[] tileBox(String key) {
        String[] p = key.split("_");
        int a = Integer.parseInt(p[0]);
        int b = Integer.parseInt(p[1]);
        return new double[]{a * TILE_DEG, b * TILE_DEG, (a + 1) * TILE_DEG, (b + 1) * TILE_DEG};
    }

    /** Las cuadrículas que tocan los recuadros [s, w, n, e, …], en el orden de la ruta y sin repetir. */
    static List<String> tilesFor(List<double[]> boxes) {
        Set<String> out = new LinkedHashSet<>();
        for (double[] b : boxes) {
            int a0 = (int) Math.floor(b[0] / TILE_DEG);
            int a1 = (int) Math.floor(b[2] / TILE_DEG);
            int b0 = (int) Math.floor(b[1] / TILE_DEG);
            int b1 = (int) Math.floor(b[3] / TILE_DEG);
            for (int a = a0; a <= a1; a++) for (int c = b0; c <= b1; c++) out.add(a + "_" + c);
        }
        return new ArrayList<>(out);
    }

    /** Tramos [km desde, km hasta] de la ruta que pasan por cuadrículas sin datos. */
    static List<double[]> gaps(double[] lat, double[] lon, double[] km, Set<String> missing) {
        List<double[]> out = new ArrayList<>();
        if (missing.isEmpty()) return out;
        double[] cur = null;
        for (int i = 0; i < lat.length; i++) {
            boolean miss = missing.contains(tileOf(lat[i], lon[i]));
            if (miss && cur == null) {
                cur = new double[]{km[Math.max(0, i - 1)], km[i]};
            } else if (miss) {
                cur[1] = km[i];
            } else if (cur != null) {
                cur[1] = km[i];
                out.add(cur);
                cur = null;
            }
        }
        if (cur != null) out.add(cur);
        return out;
    }

    // ------------------------------------------------------------------ JSON (sin Android)

    static String toJson(Map<String, Long> at, Map<String, List<Item>> items) {
        try {
            JSONObject t = new JSONObject();
            for (Map.Entry<String, List<Item>> e : items.entrySet()) {
                JSONArray a = new JSONArray();
                for (Item it : e.getValue()) {
                    a.put(new JSONObject().put("i", it.id).put("la", it.lat).put("lo", it.lon).put("n", it.name).put("d", it.detail)
                            .put("w", it.network).put("k", it.kw).put("s", it.sockets));
                }
                Long when = at.get(e.getKey());
                t.put(e.getKey(), new JSONObject().put("at", when == null ? 0 : when).put("items", a));
            }
            return new JSONObject().put("v", 2).put("tiles", t).toString();
        } catch (org.json.JSONException e) {
            return "{}";
        }
    }

    /** Lee el JSON en at (hora de cada cuadrícula) e items. */
    static void fromJson(String json, Map<String, Long> at, Map<String, List<Item>> items) {
        try {
            JSONObject t = new JSONObject(json).optJSONObject("tiles");
            if (t == null) return;
            java.util.Iterator<String> keys = t.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject o = t.getJSONObject(k);
                JSONArray a = o.optJSONArray("items");
                List<Item> list = new ArrayList<>();
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject x = a.getJSONObject(i);
                        Item it = new Item();
                        it.id = x.optString("i");
                        it.lat = x.getDouble("la");
                        it.lon = x.getDouble("lo");
                        it.name = x.optString("n");
                        it.detail = x.optString("d");
                        it.network = x.optString("w", ChargerFilter.OTHER);
                        it.kw = x.optDouble("k", 0);
                        it.sockets = x.optInt("s", 0);
                        list.add(it);
                    }
                }
                at.put(k, o.optLong("at"));
                items.put(k, list);
            }
        } catch (Exception e) {
            L.w("cargadores: caché ilegible; empiezo de cero (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ------------------------------------------------------------------ en el móvil

    private static synchronized Map<String, Tile> load(Context ctx) {
        if (tiles != null) return tiles;
        tiles = new HashMap<>();
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return tiles;
        try {
            Map<String, Long> at = new HashMap<>();
            Map<String, List<Item>> items = new HashMap<>();
            fromJson(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), at, items);
            for (Map.Entry<String, List<Item>> e : items.entrySet()) {
                Long when = at.get(e.getKey());
                tiles.put(e.getKey(), new Tile(when == null ? 0 : when, e.getValue()));
            }
        } catch (Exception e) {
            L.w("cargadores: no se pudo leer la caché (" + e.getClass().getSimpleName() + ")");
        }
        return tiles;
    }

    /** Los cargadores de una cuadrícula, o null si no está (o caducó). */
    static synchronized List<Item> get(Context ctx, String key, long nowMs) {
        Tile t = load(ctx).get(key);
        if (t == null || nowMs - t.atMs > TTL_MS) return null;
        return t.items;
    }

    /** Guarda cuadrículas recién pedidas (las vacías también) y quita las caducadas o las más viejas si sobran. */
    static synchronized void put(Context ctx, Map<String, List<Item>> fetched, long nowMs) {
        Map<String, Tile> m = load(ctx);
        for (Map.Entry<String, List<Item>> e : fetched.entrySet()) m.put(e.getKey(), new Tile(nowMs, e.getValue()));
        List<String> keys = new ArrayList<>(m.keySet());
        for (String k : keys) if (nowMs - m.get(k).atMs > TTL_MS) m.remove(k);
        if (m.size() > MAX_TILES) {
            keys = new ArrayList<>(m.keySet());
            java.util.Collections.sort(keys, (a, b) -> Long.compare(m.get(a).atMs, m.get(b).atMs));
            for (int i = 0; i < keys.size() - MAX_TILES; i++) m.remove(keys.get(i));
        }
        Map<String, Long> at = new HashMap<>();
        Map<String, List<Item>> items = new HashMap<>();
        for (Map.Entry<String, Tile> e : m.entrySet()) {
            at.put(e.getKey(), e.getValue().atMs);
            items.put(e.getKey(), e.getValue().items);
        }
        File f = new File(ctx.getFilesDir(), FILE);
        File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
        try {
            java.nio.file.Files.write(tmp.toPath(), toJson(at, items).getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) L.w("cargadores: no se pudo guardar la caché");
        } catch (Exception e) {
            L.w("cargadores: no se pudo guardar la caché (" + e.getClass().getSimpleName() + ")");
        }
    }
}
