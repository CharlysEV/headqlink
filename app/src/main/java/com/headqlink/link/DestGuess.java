package com.headqlink.link;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * El destino de Google Maps sin tocar el móvil. Maps no dice a dónde va (ni su notificación ni Android Auto), pero su
 * notificación sí dice cuánto falta por carretera. Con eso y la posición, de los sitios conocidos (Casa, Trabajo, los
 * guardados y los recientes) quedan los que están a una distancia en línea recta que cuadra con esa distancia por
 * carretera. Si cuadra uno solo, es el destino; si cuadran varios o ninguno, se pregunta en la pantalla del coche.
 * Después, el plan calculado al sitio elegido se compara con los km de Maps: si no cuadra, la suposición se deshace.
 */
final class DestGuess {
    /** Por debajo de esto (km por carretera) no se adivina: cualquier sitio cercano encajaría. */
    static final double MIN_ROAD_KM = 0.8;
    /** Rodeo máximo de la carretera sobre la línea recta (calles de ciudad y autovías con curvas). */
    static final double MAX_DETOUR = 2.2;
    /** El rodeo típico (para ordenar los que encajan). */
    static final double TYPICAL_DETOUR = 1.3;
    /** Margen en km para la posición del GPS y el punto exacto del sitio. */
    static final double SLACK_KM = 0.4;
    /** Dos sitios a menos de esto (m) son el mismo (un guardado que también está en recientes). */
    static final double SAME_M = 150;

    private DestGuess() {
    }

    /** Un sitio conocido: lo que se enseña en el botón, de dónde viene (para el log, sin el sitio) y dónde está. */
    static final class Candidate {
        final String label;
        final String kind;
        final RoutePlanner.Place place;

        Candidate(String label, String kind, RoutePlanner.Place place) {
            this.label = label;
            this.kind = kind;
            this.place = place;
        }
    }

    /**
     * Puro: los sitios que encajan con roadKm por carretera desde (lat, lon), del más probable al menos (el rodeo más
     * cercano al típico primero). Sin posición o con muy poca distancia, ninguno.
     */
    static List<Candidate> fits(List<Candidate> all, double lat, double lon, double roadKm) {
        List<Candidate> out = new ArrayList<>();
        if (all == null || Double.isNaN(lat) || Double.isNaN(lon) || !(roadKm >= MIN_ROAD_KM)) return out;
        List<double[]> score = new ArrayList<>();
        for (Candidate c : all) {
            double straight = RoadInfo.dist(lat, lon, c.place.lat, c.place.lon) / 1000.0;
            if (!fitsDistance(straight, roadKm)) continue;
            out.add(c);
            double detour = roadKm / Math.max(0.05, straight);
            score.add(new double[]{Math.abs(Math.log(detour / TYPICAL_DETOUR)), out.size() - 1});
        }
        Collections.sort(score, (a, b) -> Double.compare(a[0], b[0]));
        List<Candidate> sorted = new ArrayList<>();
        for (double[] s : score) sorted.add(out.get((int) s[1]));
        return sorted;
    }

    /** Puro: ¿cuadra un sitio a straightKm en línea recta con roadKm por carretera? */
    static boolean fitsDistance(double straightKm, double roadKm) {
        return straightKm <= roadKm + SLACK_KM && straightKm * MAX_DETOUR + SLACK_KM >= roadKm;
    }

    /**
     * Puro: si no encaja ninguno, los que más se acercan para ofrecerlos (como mucho max), sin los que no tienen sentido
     * (más de 4 veces lo que falta en línea recta: Barcelona para una ruta de 2 km).
     */
    static List<Candidate> closest(List<Candidate> all, double lat, double lon, double roadKm, int max) {
        List<Candidate> out = new ArrayList<>();
        if (all == null || Double.isNaN(lat) || Double.isNaN(lon) || !(roadKm > 0)) return out;
        List<double[]> score = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Candidate c = all.get(i);
            double straight = RoadInfo.dist(lat, lon, c.place.lat, c.place.lon) / 1000.0;
            if (straight > roadKm * 4 + 3) continue;
            score.add(new double[]{Math.abs(straight * TYPICAL_DETOUR - roadKm), i});
        }
        Collections.sort(score, (a, b) -> Double.compare(a[0], b[0]));
        for (double[] sc : score) {
            if (out.size() >= max) break;
            out.add(all.get((int) sc[1]));
        }
        return out;
    }

    /** Puro: el destino sin preguntar, si encaja uno solo; null si ninguno o varios. */
    static Candidate unique(List<Candidate> fits) {
        return fits != null && fits.size() == 1 ? fits.get(0) : null;
    }

    /**
     * Puro: ¿cuadra el plan calculado (km por carretera desde aquí) con lo que falta según Maps? Margen: 2 km o el
     * 20 %, lo que sea más (OSRM y Google no siempre eligen la misma carretera).
     */
    static boolean planMatches(double planKm, double mapsKm) {
        if (!(planKm > 0) || !(mapsKm > 0)) return true;
        return Math.abs(planKm - mapsKm) <= Math.max(2.0, 0.2 * mapsKm);
    }

    /** Puro: une listas de sitios sin repetir (el primero que aparece manda: Casa antes que un reciente en el mismo sitio). */
    static List<Candidate> merge(List<Candidate> a, List<Candidate> b) {
        List<Candidate> out = new ArrayList<>();
        for (List<Candidate> l : java.util.Arrays.asList(a, b)) {
            if (l == null) continue;
            for (Candidate c : l) {
                boolean dup = false;
                for (Candidate o : out) {
                    if (RoadInfo.dist(o.place.lat, o.place.lon, c.place.lat, c.place.lon) < SAME_M) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) out.add(c);
            }
        }
        return out;
    }
}
