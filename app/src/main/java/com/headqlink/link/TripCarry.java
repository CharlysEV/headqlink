package com.headqlink.link;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Un viaje cortado por un reinicio de la conexión (la app o Android Auto se reinician, el cable se suelta…): si la
 * conexión vuelve en menos de CONTINUE_MS, el viaje sigue (km, energía, tiempo, desnivel, máximos y reparto de la
 * energía de CarSensors; TripLog sigue en el mismo fichero), y la pantalla del coche vuelve a como estaba (la pantalla
 * partida y la sección y pestaña abiertas, CarUi). Pasado ese tiempo, viaje nuevo y Android Auto, como siempre.
 *
 * Se guarda en las preferencias de la app (sobrevive a que Android cierre la app), sin sitios ni nada personal.
 */
final class TripCarry {
    static final long CONTINUE_MS = 10 * 60_000L;
    private static final String PREFS = "hql_trip_carry";

    private TripCarry() {
    }

    /** Puro: ¿se retoma algo guardado a savedMs (pared) ahora, en nowMs? */
    static boolean fresh(long savedMs, long nowMs) {
        return savedMs > 0 && nowMs >= savedMs - 60_000 && nowMs - savedMs <= CONTINUE_MS;
    }

    private static android.content.SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Lo guardado con esa clave si sigue a tiempo; si no, null (y se borra). */
    static JSONObject take(Context c, String key) {
        String s = sp(c).getString(key, "");
        if (s.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(s);
            if (fresh(o.optLong("at"), System.currentTimeMillis())) return o;
        } catch (Exception ignored) {
        }
        sp(c).edit().remove(key).apply();
        return null;
    }

    static void put(Context c, String key, JSONObject o) {
        try {
            o.put("at", System.currentTimeMillis());
            sp(c).edit().putString(key, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static void clear(Context c, String key) {
        sp(c).edit().remove(key).apply();
    }

    static JSONArray arr(double[] v) {
        JSONArray a = new JSONArray();
        try {
            for (double d : v) a.put(d);
        } catch (Exception ignored) {
        }
        return a;
    }
}
