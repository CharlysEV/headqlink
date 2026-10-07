package com.headqlink.link;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reconoce, por su texto, lo que pulsa {@link AaServerStarter} en los ajustes de Android Auto: el botón «⋮ Más opciones»
 * y las opciones «Iniciar servidor de la unidad principal» / «Parar servidor unidad principal» de su menú. Puro (sin
 * Android): lo prueban los tests.
 *
 * Android Auto cambia sus textos entre versiones y según el idioma del móvil, así que no se compara el texto entero: se
 * normaliza (minúsculas, sin tildes, sin signos) y se buscan trozos en todos los idiomas de HeadQLink (es, en, pt-PT,
 * pt-BR) y las variantes conocidas («Parar servidor unidad principal», con o sin «de la», «Start head unit server»,
 * «Iniciar servidor da unidade principal»…). Para el botón ⋮ se mira antes lo estable (su resource-id, el botón
 * estándar de desbordamiento) y después su descripción.
 */
final class AaMenuMatch {
    /** Qué hace una opción del menú: el texto es la acción disponible («Parar…» ⇒ el servidor está encendido). */
    enum Kind { NONE, START, STOP }

    /** Los textos conocidos (para los tests y para decir en el log qué se buscaba). */
    static final String[] KNOWN_START = {
            "Iniciar servidor de la unidad principal",
            "Iniciar servidor unidad principal",
            "Start head unit server",
            "Iniciar servidor da unidade principal",
            "Iniciar o servidor da unidade principal",
            "Avvia server unità principale",
            "Démarrer le serveur de l'unité principale",
            "Haupteinheit-Server starten",
            "Hoofdunit-server starten",
    };
    static final String[] KNOWN_STOP = {
            "Parar servidor unidad principal",
            "Parar servidor de la unidad principal",
            "Detener servidor de la unidad principal",
            "Stop head unit server",
            "Parar servidor da unidade principal",
            "Parar o servidor da unidade principal",
            "Interromper servidor da unidade principal",
            "Arresta server unità principale",
            "Arrêter le serveur de l'unité principale",
            "Haupteinheit-Server stoppen",
            "Hoofdunit-server stoppen",
    };

    /** «Unidad principal» en cada idioma (normalizado): sin esto no es la opción del servidor. */
    private static final String[] HEAD_UNIT = {
            "unidad principal", "unidade principal", "head unit", "headunit", "unidad central", "unidade central",
            "unita principale", "unite principale", "haupteinheit", "hoofdunit",
    };
    /** Verbos de parar (palabras enteras, normalizadas). */
    private static final String[] STOP_WORDS = {
            "parar", "detener", "stop", "interromper", "deter", "desactivar", "desativar", "apagar", "terminar",
            "finalizar", "encerrar",
            "arresta", "arrestare", "ferma", "arreter", "stoppen", "anhalten", "beenden",
    };
    /** Descripción del botón ⋮ (normalizada; trozos). */
    private static final String[] OVERFLOW_DESC = {
            "mas opciones", "more options", "mais opcoes", "outras opcoes", "otras opciones", "opciones adicionales",
            "altre opzioni", "plus d options", "weitere optionen", "meer opties",
    };
    /** Descripción del botón ⋮ (normalizada; entera). */
    private static final String[] OVERFLOW_DESC_EXACT = {
            "opciones", "options", "opcoes", "more", "mas", "mais", "opzioni", "altro", "mehr", "meer",
    };
    /** Las otras opciones del menú de desarrollador de AA: si salen y la del servidor no, AA ha cambiado el texto. */
    private static final String[] DEVELOPER = {
            "desarrollador", "developer", "programador", "desenvolvedor",
            "sviluppatore", "developpeur", "entwickler", "ontwikkelaar",
    };

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{Nd}]+");
    private static final Pattern EMAIL = Pattern.compile("\\S+@\\S+");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{5,}");

    private AaMenuMatch() {
    }

    /** Minúsculas, sin tildes y sin signos (los separadores, un espacio): «Más opciones…» → «mas opciones». */
    static String normalize(CharSequence s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toString(), Normalizer.Form.NFD);
        n = MARKS.matcher(n).replaceAll("");
        n = NON_WORD.matcher(n.toLowerCase(Locale.ROOT)).replaceAll(" ");
        return n.trim();
    }

    /** La opción del servidor (por su texto o su descripción) y qué hace. */
    static Kind serverItem(CharSequence text, CharSequence desc) {
        Kind k = serverItem(text);
        return k != Kind.NONE ? k : serverItem(desc);
    }

    static Kind serverItem(CharSequence text) {
        String n = normalize(text);
        if (n.isEmpty() || !containsAny(n, HEAD_UNIT)) return Kind.NONE;
        // El texto es la acción disponible: «Parar…» ⇒ encendido. Sin verbo de parar, la de iniciar (como siempre).
        return hasWord(n, STOP_WORDS) ? Kind.STOP : Kind.START;
    }

    /**
     * El botón ⋮: el estándar de desbordamiento (por su resource-id o su clase) o, si no, su descripción en cualquiera de
     * los idiomas («Más opciones», «More options», «Mais opções»).
     */
    static boolean isOverflow(CharSequence desc, String viewId, CharSequence className) {
        if (viewId != null) {
            String id = viewId.toLowerCase(Locale.ROOT);
            int slash = id.indexOf(":id/");
            String name = slash >= 0 ? id.substring(slash + 4) : id;
            if (name.contains("overflow") || name.equals("more_options") || name.equals("action_more")) return true;
        }
        if (className != null && className.toString().contains("OverflowMenuButton")) return true;
        String d = normalize(desc);
        if (d.isEmpty()) return false;
        if (containsAny(d, OVERFLOW_DESC)) return true;
        for (String e : OVERFLOW_DESC_EXACT) if (d.equals(e)) return true;
        return false;
    }

    /** Otra opción del modo desarrollador de AA («Configuración de desarrollador», «Salir del modo de desarrollador»). */
    static boolean isDeveloperItem(CharSequence text) {
        String n = normalize(text);
        return !n.isEmpty() && containsAny(n, DEVELOPER);
    }

    /**
     * Un nodo para el volcado del log: clase corta, texto (solo si withText: las opciones de un menú, nunca la pantalla
     * de ajustes), descripción y resource-id. Sin datos personales: correos y números largos tapados, textos recortados.
     */
    static String describe(CharSequence className, CharSequence text, CharSequence desc, String viewId, boolean withText) {
        StringBuilder b = new StringBuilder();
        String cls = className == null ? "?" : className.toString();
        int dot = cls.lastIndexOf('.');
        b.append(dot >= 0 ? cls.substring(dot + 1) : cls);
        if (withText && text != null && text.length() > 0) b.append(" «").append(scrub(text)).append('»');
        if (desc != null && desc.length() > 0) b.append(" desc=«").append(scrub(desc)).append('»');
        if (viewId != null && !viewId.isEmpty()) {
            int slash = viewId.indexOf(":id/");
            b.append(" id=").append(slash >= 0 ? viewId.substring(slash + 4) : viewId);
        }
        return b.toString();
    }

    /** Recorta a 40 caracteres y tapa correos y números largos. */
    static String scrub(CharSequence s) {
        String v = s.toString().replace('\n', ' ').trim();
        v = EMAIL.matcher(v).replaceAll("…@…");
        v = LONG_NUMBER.matcher(v).replaceAll("#");
        return v.length() > 40 ? v.substring(0, 39) + "…" : v;
    }

    private static boolean containsAny(String n, String[] keys) {
        for (String k : keys) if (n.contains(k)) return true;
        return false;
    }

    private static boolean hasWord(String n, String[] words) {
        for (String w : n.split(" ")) {
            for (String k : words) if (w.equals(k)) return true;
        }
        return false;
    }
}
