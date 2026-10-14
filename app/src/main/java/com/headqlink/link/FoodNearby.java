package com.headqlink.link;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ¿Hay dónde comer junto a un cargador? Para las paradas largas del plan (LONG_STOP_MIN o más): restaurantes, cafeterías
 * y comida rápida de OpenStreetMap (amenity=restaurant|cafe|fast_food) a RADIUS_M o menos. Una sola consulta a Overpass
 * para todas las paradas que falten (por la puerta de Http.overpass: si Overpass está en pausa, se espera) y lo sabido
 * se guarda en el móvil un mes por cargador (ChargerMarks.key): no se pregunta en cada repintado ni dos veces lo mismo.
 * Solo se pregunta por las paradas del plan y, si se prefiere comer, por unas pocas alternativas a las que no tienen.
 */
final class FoodNearby {
    /** Desde esta parada (min), se mira si hay dónde comer. */
    static final double LONG_STOP_MIN = 15;
    static final double RADIUS_M = 300;
    /** Lo sabido vale un mes. */
    static final long MAX_AGE_MS = 30L * 86_400_000L;
    /** Tras un fallo, no se vuelve a pedir lo mismo hasta pasado esto. */
    static final long RETRY_MS = 180_000;
    /** Cargadores por consulta, como mucho. */
    static final int MAX_PER_QUERY = 12;
    private static final String FILE = "food-near-chargers-v1.json";

    /** Lo que hay cerca de un cargador. */
    static final class Info {
        int count;
        /** El nombre del más cercano con nombre ("" si ninguno lo tiene). */
        String name = "";
        long atMs;
    }

    private static Map<String, Info> cache;
    private static final Set<String> busy = new HashSet<>();
    private static final Map<String, Long> failedAt = new HashMap<>();
    /** Sube cuando llega algo nuevo (el plan con «preferir comida» se rehace). */
    static volatile int version;

    private FoodNearby() {
    }

    /** Lo sabido de este cargador (al día), o null si no se sabe. */
    static synchronized Info get(Context ctx, RoutePlanner.Charger c) {
        load(ctx);
        Info i = cache.get(ChargerMarks.key(c));
        return i == null || System.currentTimeMillis() - i.atMs > MAX_AGE_MS ? null : i;
    }

    /** Lo que ya se sabe (sin cargar el archivo: para el planificador, que no debe esperar al disco). */
    static synchronized boolean hasFood(RoutePlanner.Charger c) {
        if (cache == null) return false;
        Info i = cache.get(ChargerMarks.key(c));
        return i != null && i.count > 0 && System.currentTimeMillis() - i.atMs <= MAX_AGE_MS;
    }

    /**
     * Pide en otro hilo lo que falte de estos cargadores (los que ya se saben, se están pidiendo o fallaron hace poco, no);
     * done (en ese hilo) si llegó algo.
     */
    static void request(Context ctx, List<RoutePlanner.Charger> chargers, Runnable done) {
        List<RoutePlanner.Charger> want = new ArrayList<>();
        long now = SystemClock.elapsedRealtime();
        synchronized (FoodNearby.class) {
            load(ctx);
            for (RoutePlanner.Charger c : chargers) {
                if (want.size() >= MAX_PER_QUERY) break;
                String k = ChargerMarks.key(c);
                Info i = cache.get(k);
                if (i != null && System.currentTimeMillis() - i.atMs <= MAX_AGE_MS) continue;
                Long f = failedAt.get(k);
                if (busy.contains(k) || (f != null && now - f < RETRY_MS)) continue;
                busy.add(k);
                want.add(c);
            }
        }
        if (want.isEmpty() || DemoMode.active()) {
            synchronized (FoodNearby.class) {
                for (RoutePlanner.Charger c : want) busy.remove(ChargerMarks.key(c));
            }
            return;
        }
        Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            boolean ok = false;
            try {
                JSONArray els = new JSONObject(Http.overpass(query(want))).getJSONArray("elements");
                Map<String, Info> got = assign(els, want, RADIUS_M, System.currentTimeMillis());
                synchronized (FoodNearby.class) {
                    cache.putAll(got);
                    save(app);
                }
                version++;
                ok = true;
                int with = 0;
                for (Info i : got.values()) if (i.count > 0) with++;
                L.i("ruta: sitios para comer junto a " + want.size() + " cargadores: " + with + " con alguno");
            } catch (Exception e) {
                L.w("ruta: sitios para comer: " + Http.safeError(e));
                long at = SystemClock.elapsedRealtime();
                synchronized (FoodNearby.class) {
                    for (RoutePlanner.Charger c : want) failedAt.put(ChargerMarks.key(c), at);
                }
            } finally {
                synchronized (FoodNearby.class) {
                    for (RoutePlanner.Charger c : want) busy.remove(ChargerMarks.key(c));
                }
            }
            if (ok && done != null) done.run();
        }, "hql-food");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Puro: la consulta a Overpass (un «around» pequeño por cargador). */
    static String query(List<RoutePlanner.Charger> chargers) {
        StringBuilder q = new StringBuilder("[out:json][timeout:25];(");
        for (RoutePlanner.Charger c : chargers) {
            q.append(String.format(Locale.US, "nwr[\"amenity\"~\"^(restaurant|cafe|fast_food)$\"](around:%.0f,%.5f,%.5f);", RADIUS_M,
                    c.lat, c.lon));
        }
        return q.append(");out center tags;").toString();
    }

    /**
     * Puro: de los elementos de Overpass, cuántos sitios para comer tiene cada cargador a radiusM o menos y el nombre del
     * más cercano con nombre. Todos los cargadores pedidos salen (con 0 si no tienen ninguno).
     */
    static Map<String, Info> assign(JSONArray elements, List<RoutePlanner.Charger> chargers, double radiusM, long nowMs) {
        Map<String, Info> out = new HashMap<>();
        double[] best = new double[chargers.size()];
        for (int k = 0; k < chargers.size(); k++) {
            Info i = new Info();
            i.atMs = nowMs;
            out.put(ChargerMarks.key(chargers.get(k)), i);
            best[k] = Double.MAX_VALUE;
        }
        for (int e = 0; elements != null && e < elements.length(); e++) {
            JSONObject o = elements.optJSONObject(e);
            if (o == null) continue;
            JSONObject center = o.optJSONObject("center");
            double la = center != null ? center.optDouble("lat") : o.optDouble("lat");
            double lo = center != null ? center.optDouble("lon") : o.optDouble("lon");
            if (Double.isNaN(la) || Double.isNaN(lo)) continue;
            JSONObject tags = o.optJSONObject("tags");
            String name = tags == null ? "" : tags.optString("name", "");
            for (int k = 0; k < chargers.size(); k++) {
                RoutePlanner.Charger c = chargers.get(k);
                double m = RoadInfo.dist(c.lat, c.lon, la, lo);
                if (m > radiusM) continue;
                Info i = out.get(ChargerMarks.key(c));
                i.count++;
                if (!name.isEmpty() && m < best[k]) {
                    best[k] = m;
                    i.name = name;
                }
            }
        }
        return out;
    }

    private static void load(Context ctx) {
        if (cache != null) return;
        cache = new HashMap<>();
        if (ctx == null) return;
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.exists()) return;
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONArray a = o.optJSONArray(k);
                if (a == null || a.length() < 3) continue;
                Info i = new Info();
                i.count = a.optInt(0);
                i.name = a.optString(1, "");
                i.atMs = a.optLong(2);
                if (now - i.atMs <= MAX_AGE_MS) cache.put(k, i);
            }
        } catch (Exception e) {
            L.w("ruta: no se pudo leer lo guardado de sitios para comer: " + e.getClass().getSimpleName());
        }
    }

    private static void save(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Info> e : cache.entrySet()) {
                o.put(e.getKey(), new JSONArray().put(e.getValue().count).put(e.getValue().name).put(e.getValue().atMs));
            }
            File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!tmp.renameTo(new File(ctx.getFilesDir(), FILE))) L.w("ruta: no se pudo guardar lo de sitios para comer");
        } catch (JSONException | java.io.IOException e) {
            L.w("ruta: no se pudo guardar lo de sitios para comer: " + e.getClass().getSimpleName());
        }
    }
}
