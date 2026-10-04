package com.headqlink.link;

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
        return app.getString(id);
    }

    static String get(int id, Object... args) {
        return app.getString(id, args);
    }
}
