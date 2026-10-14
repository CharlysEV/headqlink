package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El destino de Google Maps deducido por los km que faltan (sitios y posiciones de ejemplo, inventados). */
class DestGuessTest {
    private fun cand(label: String, kind: String, lat: Double, lon: Double) =
        DestGuess.Candidate(label, kind, RoutePlanner.Place().apply { name = label; this.lat = lat; this.lon = lon })

    // Salida en (40.0, -3.0); 0,09° de latitud ≈ 10 km.
    private val home = cand("Casa", "casa", 40.09, -3.0)          // ~10 km al norte
    private val work = cand("Trabajo", "trabajo", 40.27, -3.0)    // ~30 km
    private val shop = cand("Tienda", "reciente", 40.018, -3.0)   // ~2 km

    @Test
    fun theOnlyPlaceThatFitsTheRemainingRoadIsTheDestination() {
        // Maps: 13 km por carretera. Casa a 10 en línea recta (rodeo 1,3) encaja; Trabajo (30) y la tienda (2), no.
        val fit = DestGuess.fits(listOf(home, work, shop), 40.0, -3.0, 13.0)
        assertEquals(listOf("Casa"), fit.map { it.label })
        assertEquals("Casa", DestGuess.unique(fit)!!.label)
    }

    @Test
    fun twoPlacesThatFitAreAskedNotGuessed() {
        // Otro sitio también a ~10 km (al este): los dos encajan con 13 km; se pregunta.
        val east = cand("Playa", "guardado", 40.0, -2.8826)
        val fit = DestGuess.fits(listOf(home, east, work), 40.0, -3.0, 13.0)
        assertEquals(setOf("Casa", "Playa"), fit.map { it.label }.toSet())
        assertNull(DestGuess.unique(fit))
        // Ninguno encaja: también se pregunta (con los de siempre).
        assertTrue(DestGuess.fits(listOf(home, work), 40.0, -3.0, 100.0).isEmpty())
    }

    @Test
    fun theMostTypicalDetourGoesFirst() {
        // Con 13 km: Casa a 10 (rodeo 1,3, el típico) antes que uno a 6,5 (rodeo 2,0).
        val near = cand("Cerca", "reciente", 40.0585, -3.0)
        val fit = DestGuess.fits(listOf(near, home), 40.0, -3.0, 13.0)
        assertEquals(listOf("Casa", "Cerca"), fit.map { it.label })
    }

    @Test
    fun noGuessWithoutPositionOrForVeryShortRoutes() {
        assertTrue(DestGuess.fits(listOf(home), Double.NaN, -3.0, 13.0).isEmpty())
        assertTrue(DestGuess.fits(listOf(shop), 40.0, -3.0, 0.5).isEmpty())
        assertTrue(DestGuess.fits(null, 40.0, -3.0, 13.0).isEmpty())
    }

    @Test
    fun distanceRule() {
        assertTrue(DestGuess.fitsDistance(10.0, 13.0))
        assertTrue(DestGuess.fitsDistance(13.2, 13.0))      // GPS y punto exacto: margen
        assertFalse(DestGuess.fitsDistance(15.0, 13.0))     // más lejos en recta que por carretera: imposible
        assertFalse(DestGuess.fitsDistance(4.0, 13.0))      // rodeo de más de 2,2
    }

    @Test
    fun thePlanMustAgreeWithMaps() {
        assertTrue(DestGuess.planMatches(14.0, 13.0))
        assertTrue(DestGuess.planMatches(110.0, 100.0))     // 20 % de 100
        assertFalse(DestGuess.planMatches(20.0, 13.0))
        assertTrue(DestGuess.planMatches(Double.NaN, 13.0)) // sin dato, no se deshace nada
    }

    @Test
    fun withoutAFitTheNearestSensibleOnesAreOffered() {
        // Ruta de 2 km sin ninguno que encaje: la tienda (2 km) y Casa (10) se ofrecen; Barcelona (≈480), no.
        val far = cand("Barcelona", "reciente", 41.39, 2.17)
        val offer = DestGuess.closest(listOf(far, home, shop), 40.0, -3.0, 2.0, 3)
        assertEquals(listOf("Tienda", "Casa"), offer.map { it.label })
        assertEquals(1, DestGuess.closest(listOf(home, shop), 40.0, -3.0, 2.0, 1).size)
    }

    @Test
    fun aSavedPlaceAndARecentInTheSameSpotCountOnce() {
        val recentHome = cand("Calle Mayor 1", "reciente", 40.0903, -3.0)  // ~35 m de Casa
        val all = DestGuess.merge(listOf(home, work), listOf(recentHome, shop))
        assertEquals(listOf("Casa", "Trabajo", "Tienda"), all.map { it.label })
    }
}
