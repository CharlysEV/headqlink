package com.headqlink.link;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Buscador de destinos de la pestaña Ruta, mientras se escribe: dos buscadores a la vez y sus resultados juntos, sin
 * repetidos y con la distancia desde donde está el coche.
 * <ul>
 * <li>El de direcciones de Android (Geocoder, el de Google en el móvil): entiende direcciones con número y nombres de
 * sitios («Mercadona Torrejón») como Google Maps.</li>
 * <li>Photon (photon.komoot.io, sobre OpenStreetMap): hecho para buscar mientras se escribe (palabras a medias), con
 * preferencia por lo cercano.</li>
 * </ul>
 * Nominatim (el de antes) queda solo para cuando se pulsa Buscar y los otros dos no encuentran nada: sus normas no
 * permiten buscar mientras se escribe. Los últimos destinos elegidos se guardan en el móvil (Config) y salen con la caja
 * vacía. Las partes sin Android (leer Photon, juntar, recientes) se prueban en el PC.
 */
final class PlaceSearch {
    /** Cuánto se espera a cada buscador. */
    static final long TIMEOUT_MS = 4500;
    static final int MAX_RESULTS = 8;
    /** Dos resultados a menos de esto son el mismo sitio. */
    static final double SAME_PLACE_M = 120;
    static final int MAX_RECENT = 8;

    private PlaceSearch() {
    }

    // ------------------------------------------------------------------ buscar

    /** Busca en los dos a la vez (llamar fuera del hilo principal). Sin resultados, lista vacía. */
    static List<RoutePlanner.Place> search(Context ctx, String q, double lat, double lon) {
        final List<RoutePlanner.Place>[] got = newLists(2);
        CountDownLatch done = new CountDownLatch(2);
        new Thread(() -> {
            try {
                got[0] = geocoder(ctx, q, lat, lon);
            } catch (Exception e) {
                L.i("buscar: el de Android falló: " + Http.safeError(e));
            } finally {
                done.countDown();
            }
        }, "search-android").start();
        new Thread(() -> {
            try {
                got[1] = photon(q, lat, lon);
            } catch (Exception e) {
                L.i("buscar: Photon falló: " + Http.safeError(e));
            } finally {
                done.countDown();
            }
        }, "search-photon").start();
        try {
            done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return merge(got[0], got[1], lat, lon, MAX_RESULTS);
    }

    @SuppressWarnings("unchecked")
    private static List<RoutePlanner.Place>[] newLists(int n) {
        List<RoutePlanner.Place>[] a = new List[n];
        for (int i = 0; i < n; i++) a[i] = new ArrayList<>();
        return a;
    }

    /** El de direcciones de Android, primero en unos 110 km alrededor y, si no hay nada, en cualquier sitio. */
    @SuppressWarnings("deprecation")
    static List<RoutePlanner.Place> geocoder(Context ctx, String q, double lat, double lon) throws Exception {
        List<RoutePlanner.Place> out = new ArrayList<>();
        if (!Geocoder.isPresent()) return out;
        Geocoder g = new Geocoder(ctx, Locale.getDefault());
        List<Address> a = null;
        if (!Double.isNaN(lat)) a = g.getFromLocationName(q, MAX_RESULTS, lat - 1, lon - 1, lat + 1, lon + 1);
        if (a == null || a.isEmpty()) a = g.getFromLocationName(q, MAX_RESULTS);
        if (a == null) return out;
        for (Address ad : a) {
            if (!ad.hasLatitude() || !ad.hasLongitude()) continue;
            RoutePlanner.Place p = new RoutePlanner.Place();
            p.name = addressName(ad.getFeatureName(), ad.getThoroughfare(), ad.getSubThoroughfare(), ad.getLocality());
            p.detail = ad.getMaxAddressLineIndex() >= 0 ? ad.getAddressLine(0) : join(ad.getLocality(), ad.getAdminArea());
            p.lat = ad.getLatitude();
            p.lon = ad.getLongitude();
            if (p.name.isEmpty()) p.name = p.detail;
            out.add(p);
        }
        return out;
    }

    /**
     * Nombre corto de un resultado de Android: el del sitio si lo tiene (un comercio); si no, la calle con el número; si
     * no, el pueblo. getFeatureName de una dirección suele ser solo el número.
     */
    static String addressName(String feature, String street, String number, String locality) {
        boolean featureIsNumber = feature != null && feature.matches("\\s*\\d+[\\p{L}]?\\s*");
        if (feature != null && !feature.trim().isEmpty() && !featureIsNumber && !feature.equals(street)) return feature.trim();
        if (street != null && !street.trim().isEmpty()) {
            String n = number != null && !number.trim().isEmpty() ? number.trim() : featureIsNumber ? feature.trim() : "";
            return n.isEmpty() ? street.trim() : street.trim() + " " + n;
        }
        return locality != null ? locality.trim() : "";
    }

    /** Photon: los más cercanos primero entre los que encajan. */
    static List<RoutePlanner.Place> photon(String q, double lat, double lon) throws Exception {
        String url = "https://photon.komoot.io/api/?limit=" + MAX_RESULTS + "&q=" + Uri.encode(q);
        if (!Double.isNaN(lat)) url += String.format(Locale.US, "&lat=%.4f&lon=%.4f", lat, lon);
        return parsePhoton(Http.get(url));
    }

    /** Lee la respuesta de Photon (GeoJSON). */
    static List<RoutePlanner.Place> parsePhoton(String json) throws JSONException {
        List<RoutePlanner.Place> out = new ArrayList<>();
        JSONArray f = new JSONObject(json).optJSONArray("features");
        if (f == null) return out;
        for (int i = 0; i < f.length(); i++) {
            JSONObject o = f.optJSONObject(i);
            if (o == null) continue;
            JSONObject g = o.optJSONObject("geometry");
            JSONArray c = g == null ? null : g.optJSONArray("coordinates");
            JSONObject pr = o.optJSONObject("properties");
            if (c == null || c.length() < 2 || pr == null) continue;
            String name = pr.optString("name", "");
            String street = pr.optString("street", "");
            String number = pr.optString("housenumber", "");
            String city = firstNonEmpty(pr.optString("city", ""), pr.optString("town", ""), pr.optString("village", ""),
                    pr.optString("district", ""));
            String streetLine = street.isEmpty() ? "" : number.isEmpty() ? street : street + " " + number;
            RoutePlanner.Place p = new RoutePlanner.Place();
            p.name = !name.isEmpty() ? name : !streetLine.isEmpty() ? streetLine : city;
            if (p.name.isEmpty()) continue;
            p.detail = join(name.isEmpty() ? "" : streetLine, join(pr.optString("postcode", ""), city).replace(", ", " "),
                    pr.optString("state", ""));
            if (p.detail.isEmpty()) p.detail = pr.optString("country", "");
            p.lat = c.optDouble(1);
            p.lon = c.optDouble(0);
            out.add(p);
        }
        return out;
    }

    /**
     * Junta los dos: alternando (el de Android primero: suele acertar a la primera con lo escrito entero), sin los que
     * están a menos de SAME_PLACE_M de uno anterior con el mismo nombre (o casi encima), como mucho max.
     */
    static List<RoutePlanner.Place> merge(List<RoutePlanner.Place> android, List<RoutePlanner.Place> photon, double lat, double lon,
                                          int max) {
        List<RoutePlanner.Place> out = new ArrayList<>();
        int n = Math.max(android == null ? 0 : android.size(), photon == null ? 0 : photon.size());
        for (int i = 0; i < n && out.size() < max; i++) {
            if (android != null && i < android.size()) addUnique(out, android.get(i), max);
            if (photon != null && i < photon.size()) addUnique(out, photon.get(i), max);
        }
        return out;
    }

    private static void addUnique(List<RoutePlanner.Place> out, RoutePlanner.Place p, int max) {
        if (out.size() >= max || Double.isNaN(p.lat) || Double.isNaN(p.lon)) return;
        for (RoutePlanner.Place o : out) {
            double d = RoadInfo.dist(o.lat, o.lon, p.lat, p.lon);
            if (d < 25 || (d < SAME_PLACE_M && sameName(o.name, p.name))) return;
        }
        out.add(p);
    }

    static boolean sameName(String a, String b) {
        return norm(a).equals(norm(b));
    }

    /** Sin tildes, mayúsculas ni signos: «C. de Alcalá, 27» y «c de alcala 27» son lo mismo. */
    static String norm(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").replaceAll("[^a-z0-9ñ]+", " ").trim();
    }

    /** Si el texto merece buscar mientras se escribe (3 letras o más que no sean solo espacios). */
    static boolean worthTyping(String q) {
        return q != null && q.trim().length() >= 3;
    }

    // ------------------------------------------------------------------ recientes

    /** Los últimos destinos elegidos (del más reciente al más antiguo). */
    static List<RoutePlanner.Place> recents(String json) {
        List<RoutePlanner.Place> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                RoutePlanner.Place p = new RoutePlanner.Place();
                p.name = o.getString("n");
                p.detail = o.optString("d");
                p.lat = o.getDouble("la");
                p.lon = o.getDouble("lo");
                out.add(p);
            }
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return out;
    }

    /** Pone pl el primero de los recientes (sin repetirlo) y se queda con MAX_RECENT. */
    static String remember(String json, RoutePlanner.Place pl) {
        List<RoutePlanner.Place> all = new ArrayList<>();
        all.add(pl);
        for (RoutePlanner.Place o : recents(json)) {
            if (all.size() >= MAX_RECENT) break;
            if (RoadInfo.dist(o.lat, o.lon, pl.lat, pl.lon) < SAME_PLACE_M) continue;
            all.add(o);
        }
        JSONArray a = new JSONArray();
        try {
            for (RoutePlanner.Place p : all) {
                a.put(new JSONObject().put("n", p.name).put("d", p.detail == null ? "" : p.detail).put("la", p.lat).put("lo", p.lon));
            }
        } catch (JSONException ignored) {
            // Números finitos: no pasa.
        }
        return a.toString();
    }

    // ------------------------------------------------------------------ piezas

    private static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String s : parts) {
            if (s == null || s.trim().isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(s.trim());
        }
        return b.toString();
    }

    private static String firstNonEmpty(String... s) {
        for (String x : s) if (x != null && !x.isEmpty()) return x;
        return "";
    }
}
