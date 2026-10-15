package com.headqlink.link;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dónde se quedó cada vídeo, para «¿Seguir desde 12:34?» y la marca de visto de la cuadrícula. Cada marca dura TTL_MS
 * (48 h) y se borra sola: no se acumula nada. Solo en el móvil (preferencias), por la dirección del vídeo.
 */
final class MediaResume {
    static final long TTL_MS = 48 * 3600_000L;
    /** Por debajo de esto no se ofrece seguir (se acaba de empezar). */
    static final long MIN_POS_MS = 3000;
    /** Como mucho tantas marcas. */
    static final int MAX = 300;
    private static final String PREFS = "hql_media_resume";
    private static final String KEY = "marks";

    private MediaResume() {
    }

    /** Una marca: posición y duración (ms) y cuándo se guardó (ms de pared). */
    static final class Mark {
        final long posMs;
        final long durMs;
        final long savedMs;

        Mark(long posMs, long durMs, long savedMs) {
            this.posMs = posMs;
            this.durMs = durMs;
            this.savedMs = savedMs;
        }

        /** Visto (hasta el final o casi). */
        boolean done() {
            return durMs > 0 && posMs >= durMs - Math.max(2000, durMs / 20);
        }

        /** Se puede seguir: empezado y sin terminar. */
        boolean resumable() {
            return posMs >= MIN_POS_MS && !done();
        }

        /** 0-1: lo visto. */
        float progress() {
            return durMs > 0 ? Math.max(0f, Math.min(1f, posMs / (float) durMs)) : 0f;
        }
    }

    /** Puro: las marcas del texto guardado, sin las caducadas. */
    static Map<String, Mark> decode(String json, long nowMs) {
        Map<String, Mark> out = new HashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONObject o = new JSONObject(json);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject m = o.optJSONObject(k);
                if (m == null) continue;
                Mark mk = new Mark(m.optLong("p"), m.optLong("d"), m.optLong("t"));
                if (nowMs - mk.savedMs <= TTL_MS && mk.savedMs <= nowMs + 60_000) out.put(k, mk);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Puro: el texto que se guarda (las más recientes, como mucho MAX). */
    static String encode(Map<String, Mark> marks) {
        List<Map.Entry<String, Mark>> l = new ArrayList<>(marks.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue().savedMs, a.getValue().savedMs));
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < l.size() && i < MAX; i++) {
                Mark m = l.get(i).getValue();
                o.put(l.get(i).getKey(), new JSONObject().put("p", m.posMs).put("d", m.durMs).put("t", m.savedMs));
            }
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    /** Puro: «12:34» o «1:02:03». */
    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Todas las vigentes (las caducadas se van al leer). */
    static synchronized Map<String, Mark> all(Context c) {
        return decode(sp(c).getString(KEY, ""), System.currentTimeMillis());
    }

    static Mark get(Context c, String key) {
        return all(c).get(key);
    }

    static synchronized void put(Context c, String key, long posMs, long durMs) {
        Map<String, Mark> m = all(c);
        m.put(key, new Mark(posMs, durMs, System.currentTimeMillis()));
        sp(c).edit().putString(KEY, encode(m)).apply();
    }
}
