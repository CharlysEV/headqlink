package com.headqlink.link;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Historial de cargas (files/charge-sessions.json): cada carga vista en directo con sus lecturas (%, kW), el cargador y
 * si llegó al objetivo o fue lenta. Solo en el móvil. Sirve para aprender la curva real del C10 y comprobar cuánto
 * cargan de verdad los cargadores de 400–500 V y los de 150 kW o más.
 */
final class ChargeLog {
    static final int MAX = 200;
    private static final String FILE = "charge-sessions.json";
    /** Cargas tan cortas no se guardan (un enchufado y desenchufado). */
    static final long MIN_MS = 60_000L;

    private ChargeLog() {
    }

    /** Añade una carga al JSON del historial (las más viejas fuera si pasan de max). Sin Android. */
    static String append(String json, ChargeSession s, int max) {
        JSONArray a = parse(json);
        a.put(s.toJson());
        JSONArray out = new JSONArray();
        for (int i = Math.max(0, a.length() - max); i < a.length(); i++) out.put(a.opt(i));
        return out.toString();
    }

    /** Las cargas del JSON (las ilegibles, fuera). Sin Android. */
    static List<ChargeSession> read(String json) {
        List<ChargeSession> out = new ArrayList<>();
        JSONArray a = parse(json);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            try {
                out.add(ChargeSession.fromJson(o));
            } catch (Exception e) {
                L.w("cargas: una carga ilegible en el historial (" + e.getClass().getSimpleName() + ")");
            }
        }
        return out;
    }

    private static JSONArray parse(String json) {
        if (json == null || json.trim().isEmpty()) return new JSONArray();
        try {
            return new JSONArray(json);
        } catch (Exception e) {
            L.w("cargas: historial ilegible; empiezo de cero (" + e.getClass().getSimpleName() + ")");
            return new JSONArray();
        }
    }

    /** Guarda una carga terminada (si duró algo). */
    static synchronized void add(Context ctx, ChargeSession s) {
        if (s.demo || s.endMs - s.startMs < MIN_MS || s.samples.isEmpty()) return;
        File f = new File(ctx.getFilesDir(), FILE);
        File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
        try {
            String old = f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
            Files.write(tmp.toPath(), append(old, s, MAX).getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) L.w("cargas: no se pudo guardar el historial");
        } catch (Exception e) {
            L.w("cargas: no se pudo guardar el historial (" + e.getClass().getSimpleName() + ")");
        }
    }

    static synchronized List<ChargeSession> load(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return new ArrayList<>();
        try {
            return read(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            L.w("cargas: no se pudo leer el historial (" + e.getClass().getSimpleName() + ")");
            return new ArrayList<>();
        }
    }
}
