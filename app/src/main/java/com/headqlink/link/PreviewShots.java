package com.headqlink.link;

import java.util.ArrayList;
import java.util.List;

/**
 * Las capturas de la vista previa del modo extendido: nombre del PNG, pantalla del panel y el instante del trayecto de
 * demostración que mejor la enseña (Conducción, llegando a una salida con radar; Instrumentos, en la curva de la
 * salida tras el frenazo; Eficiencia, al final de la bajada recuperando energía…). Las usan PreviewActivity (en el
 * móvil, con adb) y la prueba que dibuja en el PC.
 */
final class PreviewShots {
    static final class Shot {
        final String name;
        final String screen;
        final double seekSec;
        final long waitMs;

        Shot(String name, String screen, double seekSec, long waitMs) {
            this.name = name;
            this.screen = screen;
            this.seekSec = seekSec;
            this.waitMs = waitMs;
        }
    }

    static final Shot[] ALL = {
            new Shot("coche_ruta", "car-0", 300, 1800),
            new Shot("coche_conduccion", "car-1", 572, 1800),
            new Shot("coche_viajes", "car-2", 600, 1800),
            new Shot("coche_instrumentos", "car-3", 606, 1800),
            new Shot("coche_eficiencia", "car-4", 396, 1800),
            new Shot("auto", "aa", 300, 1500),
            new Shot("fotos", "photos", 300, 2500),
            new Shot("videos", "videos", 300, 2500),
            new Shot("web", "web", 300, 3500),
            new Shot("tv", "tv", 300, 3500),
            new Shot("radio", "radio", 300, 3500),
            new Shot("juegos", "games", 300, 1500),
            new Shot("ajustes", "settings", 300, 1500),
    };

    private PreviewShots() {
    }

    /** "all" (o vacío): todas; "coche": las cinco pestañas de Coche; si no, nombres separados por comas. */
    static List<Shot> select(String which) {
        List<Shot> out = new ArrayList<>();
        String w = which == null ? "" : which.trim();
        for (Shot s : ALL) {
            if (w.isEmpty() || w.equals("all") || (w.equals("coche") && s.name.startsWith("coche_"))) {
                out.add(s);
                continue;
            }
            for (String part : w.split(",")) {
                if (part.trim().equals(s.name)) out.add(s);
            }
        }
        return out;
    }
}
