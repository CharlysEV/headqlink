package com.headqlink.link;

import android.content.Context;
import android.content.pm.PackageInfo;

/**
 * Las versiones de Android Auto con las que se ha probado HeadQLink. Desde AA 17.4 el Self-Mode depende del servidor de
 * head unit de desarrollador de AA (127.0.0.1:5277) y de los textos de su menú ⋮, y cada versión nueva puede cambiar las
 * dos cosas: Open Headunit #985 (AA 17.8: conecta y se desconecta a los 1-2 s; volver a 17.7 lo arregla), #1022 (no
 * responde hasta borrar la caché de AA) y, en AA 17.9, «el helper ya no funciona». Aquí, la tabla de las probadas (lo
 * demás: «sin probar todavía»), la lectura de la versión instalada (versionName y versionCode) y su texto para el log,
 * la «Comprobación» y {@code sessions.csv}. La parte pura la prueban los tests.
 */
final class AaVersions {
    /** Serie (mayor.menor) probada en el móvil y cuándo. */
    static final String[][] VERIFIED = {
            {"17.7", "2026-10"},
    };

    /** Lo que se sabe de fuera (Open Headunit) de las series sin probar aquí; solo para el log. */
    private static final String[][] UPSTREAM = {
            {"17.8", "Open Headunit #985: el Self-Mode conecta y se desconecta a los 1-2 s; volver a 17.7 lo arregla"},
            {"17.9", "Open Headunit: «el helper ya no funciona» (comentario de un usuario)"},
    };

    enum State {
        /** Probada con HeadQLink (está en {@link #VERIFIED}). */
        VERIFIED,
        /** Instalada pero sin probar todavía. */
        UNTESTED,
        /** No instalada (o no se pudo leer). */
        MISSING,
    }

    private static volatile String cachedName;
    private static volatile long cachedCode = -1;

    private AaVersions() {
    }

    /** «17.7.663654-release» → «17.7»; null si no se entiende. */
    static String series(String versionName) {
        if (versionName == null) return null;
        String[] p = versionName.trim().split("[.\\-\\s]");
        if (p.length < 2) return null;
        try {
            int major = Integer.parseInt(p[0]);
            int minor = Integer.parseInt(p[1]);
            return major + "." + minor;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static State state(String versionName) {
        if (versionName == null) return State.MISSING;
        return verifiedDate(versionName) != null ? State.VERIFIED : State.UNTESTED;
    }

    /** Cuándo se probó su serie («2026-10»), o null si no está probada. */
    static String verifiedDate(String versionName) {
        String s = series(versionName);
        if (s == null) return null;
        for (String[] v : VERIFIED) if (v[0].equals(s)) return v[1];
        return null;
    }

    /** Las series probadas, para los textos: «17.7.x». */
    static String verifiedList() {
        StringBuilder b = new StringBuilder();
        for (String[] v : VERIFIED) {
            if (b.length() > 0) b.append(", ");
            b.append(v[0]).append(".x");
        }
        return b.toString();
    }

    /** Lo que se sabe de fuera de esta serie, o null. */
    static String upstreamNote(String versionName) {
        String s = series(versionName);
        if (s == null) return null;
        for (String[] u : UPSTREAM) if (u[0].equals(s)) return u[1];
        return null;
    }

    /**
     * Para el log: «17.7.663654-release (código 177663654): probada con HeadQLink (2026-10)», «… sin probar todavía con
     * HeadQLink (probadas: 17.7.x; Open Headunit #985: …)» o «no instalado».
     */
    static String describe(String versionName, long versionCode) {
        if (versionName == null) return "no instalado";
        StringBuilder b = new StringBuilder(versionName);
        if (versionCode >= 0) b.append(" (código ").append(versionCode).append(')');
        String date = verifiedDate(versionName);
        if (date != null) {
            b.append(": probada con HeadQLink (").append(date).append(')');
        } else {
            b.append(": sin probar todavía con HeadQLink (probadas: ").append(verifiedList());
            String note = upstreamNote(versionName);
            if (note != null) b.append("; ").append(note);
            b.append(')');
        }
        return b.toString();
    }

    /** La celda de {@code sessions.csv}: «17.7.663654-release» o vacía. */
    static String csvValue(String versionName) {
        return versionName == null ? "" : versionName;
    }

    // ---------------------------------------------------------------- con Android

    /** Lee la versión instalada (se guarda para el resumen de cada sesión y los avisos). Devuelve versionName o null. */
    static String read(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(AaServerStarter.AA_PKG, 0);
            cachedName = pi.versionName != null ? pi.versionName : "?";
            cachedCode = android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            cachedName = null;
            cachedCode = -1;
        }
        return cachedName;
    }

    /** La última versión leída ({@link #read}), o null. */
    static String lastName() {
        return cachedName;
    }

    static long lastCode() {
        return cachedCode;
    }

    /** Lee la versión y la describe para el log («androidAuto=…» del arranque del servicio). */
    static String readAndDescribe(Context ctx) {
        String name = read(ctx);
        return describe(name, cachedCode);
    }

    /** Para los avisos: «17.8.123» o, si no se sabe, «17.x». */
    static String shortLabel(Context ctx) {
        String name = cachedName != null ? cachedName : read(ctx);
        if (name == null) return "17.x";
        int dash = name.indexOf('-');
        return dash > 0 ? name.substring(0, dash) : name;
    }
}
