package com.headqlink.link;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puro: lo que hace falta saber de un disco de PlayStation para copiarlo entero a files/psx/«juego»/ (el núcleo lo lee
 * por ruta): las pistas de un .cue, los discos de un .m3u, cuál de los archivos de un .zip se abre, cuáles son BIOS y qué
 * copias viejas se borran para no llenar el móvil.
 */
final class PsxDisc {
    /** Copias de discos que se quedan en el móvil (las más recientes); el resto se borra al copiar uno nuevo. */
    static final int KEEP = 3;
    private static final Pattern CUE_FILE = Pattern.compile("(?im)^\\s*FILE\\s+(?:\"([^\"]+)\"|(\\S+))");
    private static final Pattern BIOS = Pattern.compile("(?i)^(scph\\d+[a-z]?|psxonpsp660|ps1_rom)\\.bin$");

    private PsxDisc() {
    }

    /** Puro: los archivos de las pistas de un .cue (solo el nombre, sin carpetas), en orden y sin repetir. */
    static List<String> cueFiles(String cue) {
        List<String> out = new ArrayList<>();
        Matcher m = CUE_FILE.matcher(cue);
        while (m.find()) {
            String f = baseName(m.group(1) != null ? m.group(1) : m.group(2));
            if (!f.isEmpty() && !out.contains(f)) out.add(f);
        }
        return out;
    }

    /** Puro: los discos de una lista .m3u (sin comentarios ni líneas vacías; solo el nombre). */
    static List<String> m3uFiles(String m3u) {
        List<String> out = new ArrayList<>();
        for (String line : m3u.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String f = baseName(l);
            if (!out.contains(f)) out.add(f);
        }
        return out;
    }

    /**
     * Puro: ¿una lista .m3u de discos? Todas sus líneas (sin comentarios) son discos (.cue, .chd, .pbp, .iso…); una de
     * canales de tele o radio (direcciones http, #EXTINF) no lo es.
     */
    static boolean isDiscList(String m3u) {
        List<String> files = m3uFiles(m3u);
        if (files.isEmpty()) return false;
        for (String f : files) {
            if (!f.toLowerCase(Locale.ROOT).matches(".*\\.(cue|chd|pbp|iso|ccd|mds|img|bin)$")) return false;
        }
        return !m3u.contains("://");
    }

    /** Puro: el archivo que se abre de los de un .zip: la lista .m3u, si no el .cue, si no el primer .chd, .pbp o .iso. */
    static String mainFile(Collection<String> names) {
        for (String ext : Arrays.asList(".m3u", ".cue", ".chd", ".pbp", ".iso")) {
            for (String n : names) if (n.toLowerCase(Locale.ROOT).endsWith(ext)) return n;
        }
        return null;
    }

    /** Puro: ¿una BIOS de PlayStation (scph1001.bin, scph5501.bin, psxonpsp660.bin…)? */
    static boolean isBios(String name) {
        return BIOS.matcher(baseName(name)).matches();
    }

    /** Puro: solo el nombre del archivo (sin carpetas, con / o \). */
    static String baseName(String path) {
        String p = path.trim();
        int i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return i >= 0 ? p.substring(i + 1) : p;
    }

    /** Puro: nombre de carpeta para un juego (sin caracteres que den guerra en el sistema de archivos). */
    static String folder(String title) {
        String f = title.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim();
        return f.isEmpty() ? "disco" : f;
    }

    /**
     * Puro: de las carpetas de discos copiados (con su última vez), las que sobran para quedarse con las {@code keep} más
     * recientes. «saves» y «bios» no son discos y nunca se tocan.
     */
    static List<String> evict(List<String> names, List<Long> lastUsed, int keep) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (!n.equals("saves") && !n.equals("bios")) idx.add(i);
        }
        idx.sort((a, b) -> Long.compare(lastUsed.get(b), lastUsed.get(a)));
        List<String> out = new ArrayList<>();
        for (int i = keep; i < idx.size(); i++) out.add(names.get(idx.get(i)));
        return out;
    }

    /** Borra una carpeta copiada y lo que tiene dentro. */
    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
