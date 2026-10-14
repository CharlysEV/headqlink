package com.headqlink.link;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puro: quita de una línea del log lo que identifica un coche o un móvil concreto, porque los compañeros comparten sus
 * logs. Como con las MAC (HotspotRadio.maskMac), se deja el final, que basta para distinguir:
 * - el CarUUID y el ProjectID del coche (en el JSON de CAR_INFO y en «carUuid=…»): «…5678»;
 * - las direcciones IPv4 (la del coche y la del móvil en la zona Wi-Fi o en Wi-Fi Direct, las de los datos móviles):
 *   los dos últimos números, «*.*.53.80», para seguir viendo qué es del coche y qué del móvil en la misma red.
 * Se aplica al escribir cada línea (L, el log qd, el diario del coche, la captura de logcat y la tabla de sesiones).
 */
final class Redact {
    private static final Pattern JSON_ID = Pattern.compile(
            "(\"(?:CarUUID|ProjectID|carUuid|projectId|VIN|Vin)\"\\s*:\\s*\")([^\"]{5,})(\")");
    private static final Pattern KV_ID = Pattern.compile("\\b((?:carUuid|projectId|CarUUID|ProjectID)=)([^,\\s)]{5,})");
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![\\d.]*\\d)");

    private Redact() {
    }

    static String line(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s;
        if (out.indexOf('=') >= 0 || out.indexOf('"') >= 0) {
            out = replace(JSON_ID, out, m -> m.group(1) + tail(m.group(2)) + m.group(3));
            out = replace(KV_ID, out, m -> m.group(1) + tail(m.group(2)));
        }
        if (hasDottedDigits(out)) {
            out = replace(IPV4, out, m -> ip(m.group(1), m.group(2), m.group(3), m.group(4)));
        }
        return out;
    }

    /** «ABCD12345678» → «…5678». */
    static String tail(String id) {
        return id.length() <= 4 ? id : "…" + id.substring(id.length() - 4);
    }

    /** Una IPv4: se queda la de bucle local y las especiales; el resto, con los dos primeros números tapados. */
    private static String ip(String a, String b, String c, String d) {
        String full = a + "." + b + "." + c + "." + d;
        for (String g : new String[]{a, b, c, d}) {
            if (Integer.parseInt(g) > 255) return full; // no es una IP (p. ej. una versión «17.7.663654.0»)
        }
        if ("127".equals(a) || "0.0.0.0".equals(full) || "255".equals(a)) return full;
        return "*.*." + c + "." + d;
    }

    private static boolean hasDottedDigits(String s) {
        int dots = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '.') dots++;
        return dots >= 3;
    }

    private interface Fn {
        String apply(Matcher m);
    }

    private static String replace(Pattern p, String s, Fn fn) {
        Matcher m = p.matcher(s);
        if (!m.find()) return s;
        StringBuffer sb = new StringBuffer(s.length());
        do {
            m.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(m)));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }
}
