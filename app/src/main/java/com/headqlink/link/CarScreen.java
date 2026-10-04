package com.headqlink.link;

import android.content.Context;
import android.view.View;

/** Una pantalla propia en la zona de contenido del coche (fotos, web, TV, juegos…). */
interface CarScreen {
    /** Navegación interna de las pantallas: volver al panel de inicio o a otra pantalla. */
    interface Host {
        Context context();

        /** Sustituye el contenido por otra vista dentro de la misma pantalla (detalle, reproductor…). */
        void setContent(View v);

        /** Ancho y alto de la zona de contenido. */
        int width();

        int height();

        void post(Runnable r);

        /** Pantalla grande (vídeo, TV): el panel se reduce a una barra de iconos. */
        void setFullscreen(boolean on);
    }

    View create(Host host);

    /** Al salir de la pantalla: parar vídeo, liberar el navegador… */
    default void destroy() {
    }
}
