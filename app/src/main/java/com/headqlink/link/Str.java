package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;

/**
 * Textos de la interfaz (res/values/hql_strings.xml en inglés, values-es en español), con el idioma
 * de la app (Ajustes → Idioma, o el del sistema). Para clases sin Context a mano (perfiles, pantallas
 * del coche, estados). Se inicia en App.onCreate.
 */
public final class Str {
    private static volatile Context app;

    private Str() {
    }

    public static void init(Context ctx) {
        app = ctx.getApplicationContext();
    }

    static String get(int id) {
        return car(id, app.getString(id));
    }

    static String get(int id, Object... args) {
        return car(id, app.getString(id, args));
    }

    /**
     * Textos donde «C10» es el coche del usuario (juegos, bienvenida, estimaciones): con otro modelo (CarSpec, el T03)
     * dicen el suyo, y la autonomía WLTP la suya. Los que hablan del C10 como referencia («probado en el C10») no cambian.
     */
    private static final int[] CAR_NAMED = {R.string.hql_fly_10, R.string.hql_game_fly, R.string.hql_game_range_sub,
            R.string.hql_game_ttt_sub, R.string.hql_ttt_lose, R.string.hql_ttt_start, R.string.hql_welcome_title,
            R.string.hql_trips_note, R.string.hql_estimated_model, R.string.hql_ins_curve_help, R.string.hql_range_wltp};

    private static String car(int id, String text) {
        CarSpec spec = CarSpec.current();
        if (spec.name.equals("C10") || text == null) return text;
        for (int k : CAR_NAMED) {
            if (k != id) continue;
            String out = carText(text, spec);
            return out;
        }
        return text;
    }

    /** Puro: el texto con el nombre del coche (y su autonomía WLTP en vez de los 420 km del C10). */
    static String carText(String text, CarSpec spec) {
        return text.replace("C10", spec.name).replace("420", String.valueOf(Math.round(spec.wltpKm)));
    }

    /** Idioma de los textos (para la voz). */
    static java.util.Locale locale() {
        Context c = app;
        if (c == null) return java.util.Locale.getDefault();
        if (android.os.Build.VERSION.SDK_INT >= 24) return c.getResources().getConfiguration().getLocales().get(0);
        return c.getResources().getConfiguration().locale;
    }
}
