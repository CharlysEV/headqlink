package com.headqlink.link;

/**
 * Reglas del destino de Google Maps leído de su tarjeta (MapsReader): Maps no dice a dónde va, pero su notificación sí
 * cuánto falta por carretera. La dirección leída se busca y se acepta si cuadra con esa distancia (fitsDistance), y el
 * plan calculado se compara con los km de Maps (planMatches). Solo si el conductor activa el planificador.
 */
final class DestGuess {
    /** Por debajo de esto (km por carretera) no se lee nada: no merece la pena planificar.  */
    static final double MIN_ROAD_KM = 0.8;
    /** Rodeo máximo de la carretera sobre la línea recta (calles de ciudad y autovías con curvas). */
    static final double MAX_DETOUR = 2.2;
    /** Margen en km para la posición del GPS y el punto exacto del sitio. */
    static final double SLACK_KM = 0.4;

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

    /** Puro: ¿cuadra un sitio a straightKm en línea recta con roadKm por carretera? */
    static boolean fitsDistance(double straightKm, double roadKm) {
        return straightKm <= roadKm + SLACK_KM && straightKm * MAX_DETOUR + SLACK_KM >= roadKm;
    }

    /**
     * Puro: ¿cuadra el plan calculado (km por carretera desde aquí) con lo que falta según Maps? Margen: 2 km o el
     * 20 %, lo que sea más (OSRM y Google no siempre eligen la misma carretera).
     */
    static boolean planMatches(double planKm, double mapsKm) {
        if (!(planKm > 0) || !(mapsKm > 0)) return true;
        return Math.abs(planKm - mapsKm) <= Math.max(2.0, 0.2 * mapsKm);
    }
}
