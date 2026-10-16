package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lectura de la pantalla de Google Maps en Android Auto, con líneas inventadas que imitan las capturas de la zona de
 * AA (1824 × 882): la navegación con el recuadro abajo a la izquierda, y la tarjeta del viaje abierta.
 */
class MapsCardTest {
    /**
     * Tema claro (16/10): recuadro blanco sobre un mapa beis claro, con la ruta morada y el coche en el mapa. Con mucha
     * tolerancia al fondo, el recuadro «no acababa» y se tomaba el coche del mapa por el icono de la ruta.
     */
    @Test
    fun routeIconInTheLightTheme() {
        val w = 990
        val h = 770
        val beige = 0xFFF2EFE9.toInt()
        val white = 0xFFFFFFFF.toInt()
        val dark = 0xFF202124.toInt()
        val argb = IntArray(w * h) { beige }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in y0 until y1) for (x in x0 until x1) argb[y * w + x] = c
        }
        rect(105, 563, 530, 748, white) // el recuadro de la navegación
        for (cx in intArrayOf(162, 263, 369, 485)) rect(cx - 14, 686, cx + 14, 714, dark) // X, alternativas, +, ruta
        rect(580, 560, 600, 770, 0xFF4A1FE0.toInt()) // la ruta en el mapa
        rect(700, 640, 740, 740, 0xFF2A9D9F.toInt()) // el coche en el mapa
        val eta = MapsCard.Line("5 h y 29 min", 135, 578, 372, 612)
        val km = MapsCard.Line("541 km", 135, 624, 326, 652)
        assertEquals(4, MapsCard.rowIcons(argb, w, h, eta, km).size)
        val route = MapsCard.routeIcon(argb, w, h, eta, km)!!
        assertTrue("el icono de la ruta, no el mapa: " + route[0], route[0] in 470..500)
    }

    private fun l(text: String, left: Int, top: Int, right: Int, bottom: Int) = MapsCard.Line(text, left, top, right, bottom)

    /** Navegando: la indicación arriba, una burbuja «8 min más lento» en el mapa y el recuadro abajo. */
    private val navigating = listOf(
        l("40 m", 440, 50, 520, 95),
        l("Calle Ancha", 440, 105, 690, 150),
        l("8 min más lento", 760, 280, 980, 305),
        l("Calle Ancha", 940, 685, 1100, 715),
        l("30 min", 335, 700, 470, 755),
        l("36 km · 21:09", 335, 765, 560, 805),
    )

    /** La tarjeta del viaje abierta, con nombres del mapa a la derecha y la indicación arriba a la derecha. */
    private val card = listOf(
        l("40 m · Calle Ancha", 800, 60, 1130, 100),
        l("Calle Mayor, 1", 330, 160, 600, 205),
        l("Centro", 330, 210, 470, 250),
        l("28001 Madrid", 330, 255, 530, 300),
        l("Fácil", 400, 330, 470, 365),
        l("30 min · 36 km", 440, 420, 640, 460),
        l("Añadir parada", 460, 500, 680, 540),
        l("Madrid", 800, 380, 880, 410),
        l("Getafe", 780, 600, 850, 625),
        l("Mostoles", 560, 580, 650, 605),
    )

    @Test
    fun theEtaBoxIsTheLowTimeWithKmUnderIt() {
        val eta = MapsCard.etaBox(navigating, 882)!!
        assertEquals("30 min", eta.text)
        // La burbuja «8 min más lento» (arriba, sin km debajo) no cuenta.
        assertNull(MapsCard.etaBox(navigating.filter { it.text != "36 km · 21:09" }, 882))
        // Con «1 h 5 min» y los km en la misma línea también vale.
        assertEquals("1 h 5 min · 120 km", MapsCard.etaBox(listOf(l("1 h 5 min · 120 km", 335, 720, 700, 760)), 882)!!.text)
    }

    @Test
    fun theAddressIsReadFromTheOpenCard() {
        assertEquals("Calle Mayor, 1, Centro, 28001 Madrid", MapsCard.address(card))
        assertTrue(MapsCard.cardOpen(card))
        // Navegando (la tarjeta cerrada) no hay dirección.
        assertNull(MapsCard.address(navigating))
        assertFalse(MapsCard.cardOpen(navigating))
    }

    @Test
    fun aPlaceNameOnTopIsKeptAndTheMapLabelsAreNot() {
        val withName = listOf(
            l("Hotel Sol", 330, 120, 500, 160),
            l("Calle Mayor, 1", 330, 165, 600, 205),
            l("28001 Madrid", 330, 210, 530, 250),
            l("Moderado", 400, 330, 520, 365),
            l("30 min · 36 km", 440, 420, 640, 460),
            l("Alcobendas", 930, 150, 1050, 175),
        )
        assertEquals("Hotel Sol, Calle Mayor, 1, 28001 Madrid", MapsCard.address(withName))
    }

    @Test
    fun englishCardWorksToo() {
        val en = listOf(
            l("1 Main Street", 330, 160, 600, 205),
            l("London", 330, 210, 470, 250),
            l("Easy", 400, 330, 470, 365),
            l("1 h 10 min · 95 km", 420, 420, 700, 460),
        )
        assertEquals("1 Main Street, London", MapsCard.address(en))
    }

    /** Recuadro de navegación sintético: fondo del panel, cuatro iconos en fila y, a la derecha, el mapa. */
    private fun panel(w: Int, h: Int, panelBg: Int, iconColor: Int, mapColor: Int): IntArray {
        val px = IntArray(w * h) { mapColor }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = c
        }
        rect(330, 690, 800, 880, panelBg)                  // el recuadro
        for (cx in listOf(397, 507, 621, 737)) rect(cx - 18, 815, cx + 18, 851, iconColor)  // X, rutas, +, ruta
        return px
    }

    private val navEta = l("30 min", 360, 700, 470, 745)
    private val navKm = l("36 km · 21:09", 360, 752, 560, 785)

    @Test
    fun theRouteIconIsTheLastOfTheRowAtNight() {
        val px = panel(1824, 882, 0xFF202124.toInt(), 0xFFE8EAED.toInt(), 0xFF35466A.toInt())
        val p = MapsCard.routeIcon(px, 1824, 882, navEta, navKm)!!
        assertEquals(737.0, p[0].toDouble(), 6.0)
        assertEquals(833.0, p[1].toDouble(), 6.0)
    }

    @Test
    fun theCancelIconIsTheFirstOfTheRow() {
        val px = panel(1824, 882, 0xFF202124.toInt(), 0xFFE8EAED.toInt(), 0xFF35466A.toInt())
        assertEquals(4, MapsCard.rowIcons(px, 1824, 882, navEta, navKm).size)
        val x = MapsCard.cancelIcon(px, 1824, 882, navEta, navKm)!!
        assertEquals(397.0, x[0].toDouble(), 6.0)
    }

    @Test
    fun theRouteIconIsFoundInDaylightToo() {
        val px = panel(1824, 882, 0xFFFFFFFF.toInt(), 0xFF202124.toInt(), 0xFFF3EEE6.toInt())
        val p = MapsCard.routeIcon(px, 1824, 882, navEta, navKm)!!
        assertEquals(737.0, p[0].toDouble(), 6.0)
        // Sin iconos: nada que tocar.
        val empty = panel(1824, 882, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFF3EEE6.toInt())
        assertNull(MapsCard.routeIcon(empty, 1824, 882, navEta, navKm))
    }

    @Test
    fun theKmLineIsTheOneUnderTheTime() {
        assertEquals("36 km · 21:09", MapsCard.kmLine(navigating, MapsCard.etaBox(navigating, 882))!!.text)
        val one = l("1 h 5 min · 120 km", 335, 720, 700, 760)
        assertEquals(one, MapsCard.kmLine(listOf(one), one))
    }

    @Test
    fun theParkingLineWithItsIconIsNotPartOfTheAddress() {
        val c = listOf(
            l("Calle Mayor, 1", 330, 160, 600, 205),
            l("28001 Madrid", 330, 210, 530, 250),
            l("(P) Fácil", 360, 330, 470, 365),
            l("30 min · 36 km", 440, 420, 640, 460),
        )
        assertEquals("Calle Mayor, 1, 28001 Madrid", MapsCard.address(c))
    }

    @Test
    fun timesWithoutSpacesAsTheReaderSometimesGivesThem() {
        val glued = listOf(l("6hy2min", 250, 671, 470, 714), l("601km · 6:41", 250, 722, 430, 755))
        assertEquals("6hy2min", MapsCard.etaBox(glued, 882)!!.text)
        assertEquals("6 h y 8 min · 601 km", MapsCard.cardEta(listOf(l("6 h y 8 min · 601 km", 400, 420, 700, 460)))!!.text)
        // Una calle con «h» detrás de un número no es un tiempo.
        assertNull(MapsCard.etaBox(listOf(l("C/ 12 Hornos", 250, 671, 470, 714), l("601 km", 250, 722, 430, 755)), 882))
    }

    @Test
    fun aChargerCardIsSearchedByStreetAndPostcodeThenByName() {
        // Tarjeta de un punto de carga (inventado): nombre en dos líneas, valoración, horario, calle, código postal,
        // teléfono, redes y potencia; y abajo «min · km».
        val charger = listOf(
            l("Punto de carga", 575, 160, 860, 205),
            l("Iberdrola", 575, 210, 760, 255),
            l("2,4 ★(5)", 575, 260, 700, 295),
            l("Abierto 24 horas", 575, 300, 810, 335),
            l("Calle Inventada, 2", 575, 342, 900, 377),
            l("12345 Pueblo", 575, 384, 830, 419),
            l("900 000 000", 640, 460, 850, 500),
            l("Red X y 7 más", 640, 555, 900, 630),
            l("Muy rápida", 640, 680, 820, 710),
            l("4 h y 43 min · 492 km", 620, 730, 915, 765),
            l("Añadir parada", 615, 805, 820, 845),
        )
        assertEquals(
            listOf("Calle Inventada, 2, 12345 Pueblo", "Punto de carga Iberdrola, Pueblo", "Punto de carga, Iberdrola, 2,4 ★(5)"),
            MapsCard.addressQueries(charger))
    }

    @Test
    fun aRestaurantCardSkipsRatingHoursAndNeighbourhood() {
        val shop = listOf(
            l("Burger Sol |", 330, 160, 560, 205),
            l("Hamburguesería en …", 330, 210, 740, 255),
            l("4,4 ★(4k+) · 10-20 €", 330, 260, 630, 295),
            l("Hamburguesería", 330, 300, 560, 335),
            l("Abre a las 13:00", 330, 340, 560, 375),
            l("Calle Ancha, 148", 330, 382, 730, 417),
            l("Centro", 330, 424, 460, 459),
            l("28001 Madrid", 330, 466, 570, 501),
            l("Características", 330, 555, 580, 590),
            l("6 h y 2 min · 601 km", 395, 640, 675, 675),
        )
        val q = MapsCard.addressQueries(shop)
        assertEquals("Calle Ancha, 148, 28001 Madrid", q[0])
        assertEquals("Burger Sol Hamburguesería en, Madrid", q[1])
        // La tarjeta de una dirección sin más: la calle con su código postal primero.
        assertEquals("Calle Mayor, 1, 28001 Madrid", MapsCard.addressQueries(card)[0])
    }

    @Test
    fun theDestinationIsCalledByThePlaceNameNotTheHouseNumber() {
        // Unos grandes almacenes (inventado): nombre, valoración, horario, calle, código postal.
        val store = listOf(
            l("Almacenes Sol Gran Vía", 330, 160, 760, 205),
            l("4,3 ★(12k)", 330, 210, 520, 245),
            l("Abierto · Cierra a las 22:00", 330, 250, 700, 285),
            l("Gran Vía, 7-9", 330, 292, 560, 327),
            l("48001 Villanueva", 330, 334, 540, 369),
            l("4 h y 34 min · 411 km", 400, 430, 700, 465),
        )
        assertEquals("Almacenes Sol Gran Vía", MapsCard.placeLabel(store))
        // Dos líneas de nombre, sí; la segunda cortada («Hamburguesería en …», su tipo), no.
        assertEquals("Burger Sol", MapsCard.placeLabel(listOf(
            l("Burger Sol |", 330, 160, 560, 205),
            l("Hamburguesería en …", 330, 210, 740, 255),
            l("Calle Ancha, 148", 330, 382, 730, 417),
            l("28001 Madrid", 330, 466, 570, 501),
            l("6 h y 2 min · 601 km", 395, 640, 675, 675))))
        // Una dirección sin más: la calle con su código postal.
        assertEquals("Calle Mayor, 1, 28001 Madrid", MapsCard.placeLabel(card))
        assertNull(MapsCard.placeLabel(navigating))
    }

    @Test
    fun theStartButtonOfTheRoutePreview() {
        val preview = listOf(
            l("Detalles de la ruta", 30, 40, 360, 80),
            l("Tu ubicación", 70, 180, 300, 215),
            l("1 h y 44 min · 175 km", 70, 270, 400, 305),
            l("Iniciar a las 13:24", 70, 520, 400, 555),
            l("A Iniciar", 140, 830, 360, 880),
            l("4 h y 41 min · 434 km", 120, 930, 500, 965),
        )
        assertEquals("A Iniciar", MapsCard.startButton(preview)!!.text)
        assertEquals("Start", MapsCard.startButton(listOf(l("Start", 140, 830, 360, 880)))!!.text)
        assertEquals("开始", MapsCard.startButton(listOf(l("开始", 140, 830, 360, 880)))!!.text)
        assertNull(MapsCard.startButton(listOf(l("Iniciar sesión en tu cuenta", 0, 0, 10, 10))))
    }

    @Test
    fun theExitButtonOfTheMenuWhenTheRouteHasStops() {
        // La X de Maps con paradas en la ruta: un menú con la próxima parada, «Quitar parada» y «Salir de la navegación».
        val menu = listOf(
            l("Próxima parada", 410, 190, 760, 230),
            l("Almacenes Sol Gran Vía", 410, 260, 880, 300),
            l("Quitar parada", 560, 420, 820, 460),
            l("Salir de la navegación", 470, 540, 900, 580),
        )
        assertEquals("Salir de la navegación", MapsCard.exitNavButton(menu)!!.text)
        assertEquals("Exit navigation", MapsCard.exitNavButton(listOf(l("Exit navigation", 0, 0, 10, 10)))!!.text)
        assertNull(MapsCard.exitNavButton(listOf(l("Quitar parada", 0, 0, 10, 10))))
    }

    /**
     * Zona de AA sintética (1824 × 882): barra de apps a la izquierda con el icono de Maps (cuatro colores de Google),
     * Spotify (verde) y Teléfono (verde); la brújula roja y el aviso amarillo de Maps solos; el mapa con ruido; y, si
     * split, la franja del fondo de AA y la tarjeta de música a la derecha.
     */
    private fun aa(split: Boolean, bg: Int, map: Int): IntArray {
        val w = 1824
        val h = 882
        val px = IntArray(w * h) { i -> if ((i * 7919) % 13 == 0) 0xFF5F6B7A.toInt() else map }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = c
        }
        rect(0, 0, 110, h, bg)                                                  // barra de AA
        rect(20, 250, 46, 276, 0xFFEA4335.toInt()); rect(46, 250, 72, 276, 0xFFFBBC04.toInt())   // Maps
        rect(20, 276, 46, 302, 0xFF34A853.toInt()); rect(46, 276, 72, 302, 0xFF4285F4.toInt())
        rect(20, 340, 72, 392, 0xFF1ED760.toInt())                              // Spotify
        rect(20, 420, 72, 472, 0xFF34A853.toInt())                              // Teléfono
        rect(150, 100, 190, 140, 0xFFEA4335.toInt())                            // brújula
        rect(150, 160, 190, 200, 0xFFFBBC04.toInt())                            // aviso
        if (split) {
            rect(1240, 0, 1256, h, bg)                                          // franja entre las dos
            rect(1256, 0, w, h, 0xFF6B4E3A.toInt())                             // música
        }
        return px
    }

    @Test
    fun splitScreenIsSeenByTheGapBetweenTheTwoApps() {
        val night = 0xFF000000.toInt()
        val day = 0xFFF1F3F4.toInt()
        assertTrue(MapsCard.splitScreen(aa(true, night, 0xFF35466A.toInt()), 1824, 882))
        assertFalse(MapsCard.splitScreen(aa(false, night, 0xFF35466A.toInt()), 1824, 882))
        assertTrue(MapsCard.splitScreen(aa(true, day, 0xFFE8EAED.toInt()), 1824, 882))
        assertFalse(MapsCard.splitScreen(aa(false, day, 0xFFE8EAED.toInt()), 1824, 882))
        // La franja, en medio de las dos apps.
        assertEquals(1248.0, MapsCard.splitGapX(aa(true, night, 0xFF35466A.toInt()), 1824, 882).toDouble(), 2.0)
    }

    /**
     * Coche virtual (16-10): la imagen de AA es más estrecha que su zona y queda una banda negra hasta el borde derecho.
     * No es la pantalla partida: antes se pulsaba el icono de Maps y así se partía.
     */
    /**
     * Coche virtual (16-10, «franja al 41 %»): mapa de día a pantalla completa, con columnas beis lisas de arriba abajo.
     * No son el hueco de la pantalla partida, que es del color de la barra de AA (negra).
     */
    @Test
    fun aPlainDayMapIsNotASplitScreen() {
        val black = 0xFF000000.toInt()
        val beige = 0xFFF5F0E8.toInt()
        val px = aa(false, black, beige)
        for (y in 0 until 882) for (x in 760 until 800) px[y * 1824 + x] = beige
        assertFalse(MapsCard.splitScreen(px, 1824, 882))
        // La pantalla partida de verdad (hueco negro entre Maps y la música), con el mapa de día: sí.
        assertTrue(MapsCard.splitScreen(aa(true, black, beige), 1824, 882))
    }

    /**
     * Un panel oscuro de Maps a la izquierda que llega hasta pasado el 40 % del ancho: su borde no es el hueco de la
     * pantalla partida.
     */
    @Test
    fun aMapsPanelOnTheLeftIsNotASplitScreen() {
        val night = 0xFF000000.toInt()
        for (end in intArrayOf(760, 790, 820)) {
            val px = aa(false, night, 0xFF35466A.toInt())
            for (y in 0 until 882) for (x in 300 until end) px[y * 1824 + x] = 0xFF000000.toInt()
            assertFalse("panel hasta x=$end", MapsCard.splitScreen(px, 1824, 882))
        }
    }

    @Test
    fun aBandUpToTheEdgeIsNotASplitScreen() {
        val night = 0xFF000000.toInt()
        for (from in intArrayOf(1600, 1650, 1700)) {
            val px = aa(false, night, 0xFF35466A.toInt())
            for (y in 0 until 882) for (x in from until 1824) px[y * 1824 + x] = night
            assertFalse("banda desde x=$from", MapsCard.splitScreen(px, 1824, 882))
        }
    }

    @Test
    fun theMapsIconIsTheOneWithTheFourGoogleColours() {
        val p = MapsCard.mapsIcon(aa(true, 0xFF000000.toInt(), 0xFF35466A.toInt()), 1824, 882)!!
        assertEquals(46.0, p[0].toDouble(), 8.0)
        assertEquals(276.0, p[1].toDouble(), 8.0)
        // Sin el icono de Maps (solo la brújula, el aviso y los verdes), nada.
        val none = aa(true, 0xFF000000.toInt(), 0xFF35466A.toInt())
        for (y in 250 until 302) for (x in 20 until 72) none[y * 1824 + x] = 0xFF000000.toInt()
        assertNull(MapsCard.mapsIcon(none, 1824, 882))
    }

    /**
     * «Detalles de la ruta» con AA a media pantalla (16-10): «Iniciar» es un círculo verde azulado sin texto a la
     * izquierda de «5 h y 27 min / 461 km». Se pulsa su centro; sin él, nada.
     */
    @Test
    fun theRoundStartButtonWithoutText() {
        val w = 988
        val h = 769
        val px = IntArray(w * h) { 0xFF8FC98A.toInt() } // el mapa (verde)
        for (y in 620 until 750) for (x in 105 until 530) px[y * w + x] = 0xFFFFFFFF.toInt() // la tarjeta
        val teal = 0xFF006874.toInt()
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - 162
            val dy = y - 685
            if (dx * dx + dy * dy <= 38 * 38) px[y * w + x] = teal
        }
        val lines = listOf(MapsCard.Line("5 h y 27 min", 220, 645, 408, 682), MapsCard.Line("461 km", 220, 690, 332, 726))
        val c = MapsCard.startRound(px, w, h, lines)!!
        assertEquals(162.0, c[0].toDouble(), 4.0)
        assertEquals(685.0, c[1].toDouble(), 4.0)
        // Sin el botón (la tarjeta blanca y el mapa verde), nada.
        for (y in 0 until h) for (x in 0 until w) if (px[y * w + x] == teal) px[y * w + x] = 0xFFFFFFFF.toInt()
        assertNull(MapsCard.startRound(px, w, h, lines))
    }

    @Test
    fun nothingToReadGivesNull() {
        assertNull(MapsCard.address(listOf(l("P", 330, 330, 360, 365), l("30 min · 36 km", 440, 420, 640, 460))))
        assertNull(MapsCard.address(emptyList()))
        assertNull(MapsCard.etaBox(emptyList(), 882))
    }
}
