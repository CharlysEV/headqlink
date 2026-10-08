package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.io.FileInputStream

/** Puntos de recarga de la DGT (DATEX II v3): potencia y voltaje por conector, guardado compacto y cuadrículas. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class DgtChargersTest {
    @Before
    fun texts() {
        Str.init(RuntimeEnvironment.getApplication())
    }

    private fun connector(type: String, mode: String, watts: Double, volts: Double?): String {
        val v = if (volts != null) "<egi:voltage>$volts</egi:voltage>" else ""
        return "<egi:connector><egi:connectorType>$type</egi:connectorType><egi:chargingMode>$mode</egi:chargingMode>" +
            "<egi:connectorFormat>cableMode3</egi:connectorFormat><egi:maxPowerAtSocket>$watts</egi:maxPowerAtSocket>$v</egi:connector>"
    }

    private fun point(connectors: String) =
        "<egi:refillPoint xsi:type=\"egi:ElectricChargingPoint\" id=\"p\" version=\"\">" +
            "<fac:name><com:values><com:value lang=\"es\">ES*1*PUNTO</com:value></com:values></fac:name>$connectors</egi:refillPoint>"

    private fun site(id: String, name: String, op: String, lat: Double, lon: Double, vararg points: String) =
        "<egi:energyInfrastructureSite id=\"$id\" version=\"\">" +
            "<fac:name><com:values><com:value lang=\"es\">$name</com:value></com:values></fac:name>" +
            "<fac:locationReference xsi:type=\"loc:PointLocation\"><loc:_locationReferenceExtension><loc:facilityLocation>" +
            "<locx:address><locx:addressLine order=\"1\"><locx:text><com:values><com:value lang=\"es\">Dirección: Calle 1</com:value>" +
            "</com:values></locx:text></locx:addressLine></locx:address></loc:facilityLocation></loc:_locationReferenceExtension>" +
            "<loc:coordinatesForDisplay><loc:latitude>$lat</loc:latitude><loc:longitude>$lon</loc:longitude></loc:coordinatesForDisplay>" +
            "</fac:locationReference>" +
            "<fac:operator xsi:type=\"fac:OrganisationSpecification\" id=\"ES*1\" version=\"\">" +
            "<fac:name><com:values><com:value lang=\"es\">$op</com:value></com:values></fac:name></fac:operator>" +
            "<egi:energyInfrastructureStation id=\"${id}_1\" version=\"\">" + points.joinToString("") { point(it) } +
            "</egi:energyInfrastructureStation></egi:energyInfrastructureSite>"

    private val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<d2:payload xmlns:d2=\"http://datex2.eu/schema/3/d2Payload\" xmlns:com=\"http://datex2.eu/schema/3/common\" " +
        "xmlns:loc=\"http://datex2.eu/schema/3/locationReferencing\" xmlns:egi=\"http://datex2.eu/schema/3/energyInfrastructure\" " +
        "xmlns:fac=\"http://datex2.eu/schema/3/facilities\" xmlns:locx=\"http://datex2.eu/schema/3/locationExtension\" " +
        "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"egi:EnergyInfrastructureTablePublication\">" +
        "<egi:energyInfrastructureTable id=\"ELECTROLINERAS\" version=\"1\">" +
        site(
            "1", "Hub Torija", "(ZUNDER) Grupo Easychargar SA", 40.74, -3.03,
            connector("iec62196T2COMBO", "mode4DC", 350000.0, 920.0) + connector("iec62196T2", "mode3AC3p", 22000.0, 400.0),
            connector("iec62196T2COMBO", "mode4DC", 350000.0, 920.0),
        ) +
        site("2", "Supercharger Torija", "Tesla Spain SLU", 40.75, -3.04, connector("iec62196T2COMBO", "mode4DC", 250000.0, 500.0)) +
        site("3", "Hotel", "Hotel SL", 40.76, -3.05, connector("iec62196T2", "mode3AC3p", 22000.0, null)) +
        "</egi:energyInfrastructureTable></d2:payload>"

    private fun parsed() = DgtChargers.parse(xml.byteInputStream())

    @Test
    fun eachSiteKeepsItsBestDirectCurrentPowerAndVoltage() {
        val s = parsed()
        assertEquals(3, s.size)
        val z = s[0]
        assertEquals("Hub Torija", z.name)
        assertEquals("(ZUNDER) Grupo Easychargar SA", z.operator)
        assertEquals(40.74, z.lat, 1e-9)
        assertEquals(-3.03, z.lon, 1e-9)
        assertEquals(350.0, z.dcKw, 1e-9)
        assertEquals(22.0, z.acKw, 1e-9)
        assertEquals(920.0, z.dcVolts, 1e-9)
        assertEquals(2, z.points)
        assertTrue(z.ccs && z.type2)
        val c = z.toCharger()
        assertEquals("zunder", c.network)
        assertEquals(350.0, c.maxKw, 1e-9)
        assertEquals(920.0, c.maxVolts, 1e-9)
        assertEquals(ChargerFilter.KW_TAGGED, c.kwSource)
        assertEquals(RoutePlanner.Charger.SOURCE_DGT, c.source)
        assertFalse(c.acOnly)
        // Un Supercharger: 500 V.
        val t = s[1].toCharger()
        assertEquals("tesla", t.network)
        assertEquals(500.0, t.maxVolts, 1e-9)
        // Solo alterna: lento y sin voltaje de continua.
        val h = s[2].toCharger()
        assertTrue(h.acOnly)
        assertEquals(22.0, h.maxKw, 1e-9)
        assertEquals(0.0, h.maxVolts, 1e-9)
    }

    @Test
    fun theCompactLinesKeepEverything() {
        for (s in parsed()) {
            val back = DgtChargers.fromLine(DgtChargers.toLine(s))!!
            assertEquals(DgtChargers.toLine(s), DgtChargers.toLine(back))
        }
        assertNull(DgtChargers.fromLine("roto"))
    }

    @Test
    fun theSitesAreFoundByTileAndForeignTilesAreLeftToOpenStreetMap() {
        DgtChargers.setForTest(parsed())
        val tile = ChargerCache.tileOf(40.74, -3.03)
        assertEquals(3, DgtChargers.inTile(tile)!!.size)
        assertNull(DgtChargers.inTile(ChargerCache.tileOf(48.85, 2.35)))
        assertTrue(DgtChargers.nearSpain(doubleArrayOf(48.85, 40.4), doubleArrayOf(2.35, -3.7)))
        assertFalse(DgtChargers.nearSpain(doubleArrayOf(48.85), doubleArrayOf(2.35)))
    }

    /** Con la publicación entera (HQL_DGT_XML = el XML descargado): se lee toda y casi todos los puntos tienen potencia. */
    @Test
    fun theWholePublication() {
        val path = System.getenv("HQL_DGT_XML")
        assumeTrue(path != null && File(path).exists())
        val t0 = System.currentTimeMillis()
        val all = FileInputStream(path!!).use { DgtChargers.parse(it) }
        val dc = all.filter { it.dcKw > 0 }
        val volts = dc.filter { it.dcVolts > 0 }
        println(
            "DGT: ${all.size} puntos en ${System.currentTimeMillis() - t0} ms; ${dc.size} con continua, ${volts.size} con voltaje; " +
                "≥700 V: ${volts.count { it.dcVolts >= 700 }}"
        )
        assertTrue(all.size > 10000)
        assertTrue(volts.size > dc.size / 2)
        val tesla = dc.map { it.toCharger() }.filter { it.network == "tesla" }
        // Los Supercharger, todos de 400–500 V (los que no lo dicen, con el de su red); para el C10 de 800 V, a la mitad.
        assertTrue(tesla.isNotEmpty() && tesla.all { it.maxVolts in 1.0..699.0 })
        assertTrue(tesla.filter { it.maxKw >= 40 }.all { ChargePlanner.lowVolts(it, 800.0) })
        // De 150 kW o más (salvo Tesla), casi todos cargan bien un coche de 800 V.
        val big = dc.map { it.toCharger() }.filter { it.maxKw >= 150 && it.network != "tesla" }
        assertTrue(big.count { ChargePlanner.highVolts(it) } == big.size)
    }

    private fun ch(net: String, lat: Double, lon: Double, kw: Double, src: Int) = RoutePlanner.Charger().apply {
        name = net
        network = net
        this.lat = lat
        this.lon = lon
        maxKw = kw
        kwSource = src
    }

    @Test
    fun openStreetMapAddsWhatTheRegistryLacksWithoutDuplicatesOrNoise() {
        val dgtZunder = ch("zunder", 40.74, -3.03, 360.0, ChargerFilter.KW_TAGGED)
        val dgtIber = ch("iberdrola", 40.80, -3.10, 50.0, ChargerFilter.KW_TAGGED)
        // El mismo Zunder, 250 m más allá en OpenStreetMap: repetido.
        val osmSame = ch("zunder", 40.7422, -3.03, 180.0, ChargerFilter.KW_NETWORK)
        // Uno sin red a 50 m del de Iberdrola: el mismo.
        val osmNear = ch(ChargerFilter.OTHER, 40.8004, -3.10, 50.0, ChargerFilter.KW_TAGGED)
        // Un Zunder que no está en el registro (Épila): se añade.
        val osmNew = ch("zunder", 41.5389, -1.2413, 250.0, ChargerFilter.KW_TAGGED)
        // Uno sin red ni potencia: ruido, fuera.
        val osmNoise = ch(ChargerFilter.OTHER, 41.0, -2.0, 0.0, ChargerFilter.KW_UNKNOWN)
        // El de Épila repetido en OpenStreetMap (nodo y área), a 80 m: una vez.
        val osmNewTwice = ch("zunder", 41.5396, -1.2413, 250.0, ChargerFilter.KW_TAGGED)
        val out = RoutePlanner.mergeSources(listOf(dgtZunder, dgtIber), listOf(osmSame, osmNear, osmNew, osmNoise, osmNewTwice))
        assertEquals(listOf(dgtZunder, dgtIber, osmNew), out)
    }

    @Test
    fun theDistanceAndTheTilesAroundAPointAreRight() {
        // Madrid (Sol) - Torija: ~67,6 km en línea recta; Madrid - Barcelona, ~505 km.
        assertEquals(67.6, RoutePlanner.distanceKm(40.4168, -3.7038, 40.7436, -3.0300), 0.5)
        assertEquals(505.0, RoutePlanner.distanceKm(40.4168, -3.7038, 41.3874, 2.1686), 3.0)
        assertEquals(0.0, RoutePlanner.distanceKm(40.0, -3.0, 40.0, -3.0), 1e-9)
        // 10 km alrededor: la cuadrícula del punto y las vecinas que toca el círculo.
        val tiles = RoutePlanner.tilesAround(40.4168, -3.7038, 10.0)
        assertTrue(tiles.contains(ChargerCache.tileOf(40.4168, -3.7038)))
        assertTrue(tiles.size in 4..9)
    }
}
