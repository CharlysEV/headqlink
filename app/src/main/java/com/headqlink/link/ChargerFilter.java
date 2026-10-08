package com.headqlink.link;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Filtro de los cargadores de la pestaña Ruta, como en los planificadores de rutas: potencia mínima y redes (Tesla,
 * Zunder, Ionity…). La red de cada cargador sale de sus etiquetas de OpenStreetMap (brand, operator, network, name).
 * Sin Android: se prueba en el PC.
 */
final class ChargerFilter {
    /** Potencias mínimas que se pueden elegir (kW; 0 = cualquiera). */
    static final int[] MIN_KW_CHOICES = {0, 22, 50, 100, 150};
    /** Clave de los cargadores de una red que no está en la lista. */
    static final String OTHER = "other";

    /** Redes conocidas: clave → {nombre que se enseña, trozos que la delatan en las etiquetas (normalizados)}. */
    static final Map<String, String[]> NETWORKS = new LinkedHashMap<>();

    static {
        NETWORKS.put("tesla", new String[]{"Tesla", "tesla", "supercharger"});
        NETWORKS.put("zunder", new String[]{"Zunder", "zunder"});
        NETWORKS.put("ionity", new String[]{"Ionity", "ionity"});
        NETWORKS.put("iberdrola", new String[]{"Iberdrola", "iberdrola"});
        NETWORKS.put("endesa", new String[]{"Endesa X", "endesa"});
        NETWORKS.put("repsol", new String[]{"Repsol", "repsol"});
        NETWORKS.put("wenea", new String[]{"Wenea", "wenea"});
        NETWORKS.put("moeve", new String[]{"Moeve / Cepsa", "moeve", "cepsa"});
        NETWORKS.put("galp", new String[]{"Galp", "galp"});
        NETWORKS.put("fastned", new String[]{"Fastned", "fastned"});
        NETWORKS.put("electra", new String[]{"Electra", "electra"});
        NETWORKS.put("allego", new String[]{"Allego", "allego"});
        NETWORKS.put("powerdot", new String[]{"Powerdot", "powerdot"});
        NETWORKS.put("atlante", new String[]{"Atlante", "atlante"});
        NETWORKS.put("easycharger", new String[]{"Easycharger", "easycharger"});
        NETWORKS.put("plenitude", new String[]{"Plenitude / Be Charge", "plenitude", "be charge", "becharge"});
        NETWORKS.put("lidl", new String[]{"Lidl", "lidl"});
        NETWORKS.put("ewiva", new String[]{"Ewiva", "ewiva"});
        NETWORKS.put("enel", new String[]{"Enel X", "enel"});
        NETWORKS.put("totalenergies", new String[]{"TotalEnergies", "totalenergies", "total energies"});
        NETWORKS.put("shell", new String[]{"Shell Recharge", "shell"});
        NETWORKS.put("bp", new String[]{"bp pulse", "bp pulse", "aral pulse"});
    }

    private ChargerFilter() {
    }

    /** Minúsculas, sin tildes ni signos. */
    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** La red de un cargador por sus etiquetas (la primera que encaje, mirando primero brand, network y operator), u OTHER. */
    static String classify(String brand, String network, String operator, String name) {
        for (String field : new String[]{brand, network, operator, name}) {
            String n = " " + norm(field) + " ";
            if (n.trim().isEmpty()) continue;
            for (Map.Entry<String, String[]> e : NETWORKS.entrySet()) {
                String[] v = e.getValue();
                for (int i = 1; i < v.length; i++) {
                    // Palabra entera (o varias): «bp pulse» sí; «bp» dentro de otra palabra, no.
                    if (n.contains(" " + v[i] + " ")) return e.getKey();
                }
            }
        }
        return OTHER;
    }

    /** Nombre que se enseña de una red (clave), o la propia clave. */
    static String label(String key) {
        String[] v = NETWORKS.get(key);
        return v != null ? v[0] : key;
    }

    /** ¿Pasa el filtro? minKw 0: cualquiera (un cargador sin potencia conocida solo pasa así); networks vacío: todas. */
    // De dónde sale la potencia de un cargador: OpenStreetMap la dice; se estima por la red (las que solo tienen carga
    // rápida: un Supercharger, un hub de Zunder o de Ionity); por los enchufes (CCS o CHAdeMO: continua, pero no se
    // sabe cuánta; solo Tipo 2: alterna); o nada.
    static final int KW_TAGGED = 0;
    static final int KW_NETWORK = 1;
    static final int KW_SOCKETS = 2;
    static final int KW_UNKNOWN = 3;
    static final int SOCKET_DC = 1;
    static final int SOCKET_AC = 2;

    /** Tipo de enchufe de una clave socket:* de OpenStreetMap (sin «socket:»): continua, alterna o 0 si no se sabe. */
    static int socketKind(String socket) {
        String s = socket.toLowerCase(Locale.ROOT);
        if (s.contains(":")) s = s.substring(0, s.indexOf(':'));
        if (s.equals("type2_combo") || s.equals("chademo") || s.equals("type1_combo") || s.equals("tesla_supercharger")
                || s.equals("tesla_supercharger_ccs") || s.equals("gb_dc") || s.equals("nacs")) {
            return SOCKET_DC;
        }
        if (s.equals("type2") || s.equals("type2_cable") || s.equals("type1") || s.equals("schuko") || s.equals("cee_blue")
                || s.equals("cee_red_16a") || s.equals("cee_red_32a") || s.equals("tesla_destination") || s.equals("type3c")) {
            return SOCKET_AC;
        }
        return 0;
    }

    /**
     * Potencia probable de un cargador sin potencia en OpenStreetMap: {kW, origen (KW_*)}. Las redes que solo montan carga
     * rápida tienen su potencia típica (por lo bajo); si no, los enchufes dicen si es continua (≈50 kW, sin confirmar) o
     * alterna (22 kW); si no se sabe nada, 0.
     */
    static double[] estimateKw(String network, String name, int sockets) {
        boolean dc = (sockets & SOCKET_DC) != 0;
        boolean ac = (sockets & SOCKET_AC) != 0;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if ("tesla".equals(network)) {
            // Los «Destination» (hoteles, restaurantes) son de alterna.
            if (n.contains("destination") || (ac && !dc)) return new double[]{11, KW_NETWORK};
            return new double[]{150, KW_NETWORK};
        }
        Double typical = FAST_ONLY.get(network);
        if (typical != null) return new double[]{typical, KW_NETWORK};
        if (dc) return new double[]{50, KW_SOCKETS};
        if (ac) return new double[]{22, KW_SOCKETS};
        return new double[]{0, KW_UNKNOWN};
    }

    /** Redes que solo montan carga rápida, con su potencia típica por lo bajo (kW). */
    private static final Map<String, Double> FAST_ONLY = new LinkedHashMap<>();

    static {
        FAST_ONLY.put("ionity", 350.0);
        FAST_ONLY.put("zunder", 180.0);
        FAST_ONLY.put("fastned", 300.0);
        FAST_ONLY.put("electra", 300.0);
        FAST_ONLY.put("atlante", 150.0);
    }

    /**
     * El cargador pasa el filtro. Con potencia mínima: la de OpenStreetMap o la típica de su red; los demás (potencia sin
     * confirmar) solo si includeUnknown, y nunca los de solo alterna si piden más de lo que dan.
     */
    static boolean accepts(RoutePlanner.Charger c, int minKw, Set<String> networks, boolean includeUnknown) {
        boolean netOk = networks == null || networks.isEmpty()
                || networks.contains(c.network == null || c.network.isEmpty() ? OTHER : c.network);
        if (!netOk) return false;
        if (minKw <= 0) return true;
        if (c.kwSource == KW_TAGGED || c.kwSource == KW_NETWORK || c.acOnly) return c.maxKw >= minKw;
        return includeUnknown;
    }

    static boolean accepts(double maxKw, String network, int minKw, Set<String> networks) {
        if (minKw > 0 && !(maxKw >= minKw)) return false;
        return networks == null || networks.isEmpty() || networks.contains(network == null || network.isEmpty() ? OTHER : network);
    }

    /** Redes presentes en una lista de claves, con cuántos cargadores tiene cada una, de más a menos (OTHER al final). */
    static List<Map.Entry<String, Integer>> counts(List<String> networks) {
        Map<String, Integer> m = new LinkedHashMap<>();
        // Sin getOrDefault ni List.sort: la versión de GitHub llega a Android antiguos sin esas API.
        for (String k : networks) {
            Integer c = m.get(k);
            m.put(k, c == null ? 1 : c + 1);
        }
        List<Map.Entry<String, Integer>> out = new ArrayList<>(m.entrySet());
        Collections.sort(out, (a, b) -> {
            if (a.getKey().equals(OTHER) != b.getKey().equals(OTHER)) return a.getKey().equals(OTHER) ? 1 : -1;
            return Integer.compare(b.getValue(), a.getValue());
        });
        return out;
    }

    // ------------------------------------------------------------------ guardar

    static String formatNetworks(Set<String> s) {
        if (s == null || s.isEmpty()) return "";
        List<String> l = new ArrayList<>(s);
        Collections.sort(l);
        StringBuilder b = new StringBuilder();
        for (String k : l) b.append(b.length() > 0 ? "," : "").append(k);
        return b.toString();
    }

    static Set<String> parseNetworks(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) return out;
        for (String p : s.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    /** Potencia guardada, a la elección más cercana de MIN_KW_CHOICES. */
    static int snapMinKw(int kw) {
        int best = 0;
        for (int c : MIN_KW_CHOICES) if (Math.abs(c - kw) < Math.abs(best - kw)) best = c;
        return best;
    }
}
