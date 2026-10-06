package com.headqlink.link

import com.headqlink.link.LinkGlance.Action
import com.headqlink.link.LinkGlance.Detail
import com.headqlink.link.LinkGlance.Look
import com.headqlink.link.LinkGlance.Size
import com.headqlink.link.LinkGlance.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Widget y ajustes rápidos: del estado del enlace a lo que se enseña (color, botón, estado y detalle). */
class LinkGlanceTest {
    private fun glance(block: LinkGlance.Input.() -> Unit = {}) = LinkGlance.of(LinkGlance.Input().apply(block))

    /** En marcha por la zona Wi-Fi, con lo que cambie block. */
    private fun running(block: LinkGlance.Input.() -> Unit = {}) = glance {
        running = true
        activeLinkMode = Config.LINK_HOTSPOT
        car = LinkState.Car.SEARCHING
        block()
    }

    @Test
    fun offIsGreyAndConnects() {
        val g = glance()
        assertEquals(Look.OFF, g.look)
        assertEquals(Action.CONNECT, g.action)
        assertEquals(Status.OFF, g.status)
        assertEquals(Detail.TAP_TO_CONNECT, g.detail)
        assertFalse(g.active())
        assertEquals("", g.videoText())
    }

    @Test
    fun preparingIsAmberAndStillConnect() {
        // Conectar arrancando el servidor de Android Auto antes del servicio.
        val g = glance { preparing = true }
        assertEquals(Look.BUSY, g.look)
        assertEquals(Action.CONNECT, g.action)
        assertEquals(Status.PREPARING, g.status)
        assertEquals(Detail.STARTING_AA, g.detail)
        assertFalse(g.active())
    }

    @Test
    fun searchingIsAmberWithTheNetworkRow() {
        val g = running { networkLevel = LinkState.Level.OK; network = "Zona Wi-Fi activa (swlan0 10.42.0.1)" }
        assertEquals(Look.BUSY, g.look)
        assertEquals(Action.DISCONNECT, g.action)
        assertTrue(g.active())
        assertEquals(Status.SEARCHING, g.status)
        assertEquals(Detail.NETWORK, g.detail)
        assertEquals("Zona Wi-Fi activa (swlan0 10.42.0.1)", g.text)
        // Sin texto de red, sin detalle.
        assertEquals(Detail.NONE, running().detail)
        // Arranque manual esperando al servidor de Android Auto.
        assertEquals(Status.AA_SERVER, running { aaServerWaiting = true }.status)
    }

    @Test
    fun foundByWifiOrByTheCable() {
        val g = running {
            car = LinkState.Car.SEEN
            activeLinkMode = Config.LINK_USB
            networkLevel = LinkState.Level.OK
            network = "Cable USB: Neusoft QDriveLink"
        }
        assertEquals(Look.BUSY, g.look)
        assertEquals(Status.FOUND, g.status)
        assertEquals(Detail.NETWORK, g.detail)
        assertEquals("Cable USB: Neusoft QDriveLink", g.text)
        assertEquals(Config.LINK_USB, g.transport)
    }

    @Test
    fun connectedWithPictureIsGreenWithFpsAndMbps() {
        // LinkState.video llega con el formato del móvil: coma decimal en español.
        val g = running { car = LinkState.Car.CONNECTED; video = "30 fps · 4,8 Mbps"; locale = "es-ES" }
        assertEquals(Look.LIVE, g.look)
        assertEquals(Status.LIVE, g.status)
        assertEquals(Detail.VIDEO, g.detail)
        assertEquals(30f, g.fps, 0.001f)
        assertEquals(4.8f, g.mbps, 0.001f)
        assertEquals("30 fps · 4,8 Mbit/s", g.videoText())
        assertEquals("30 fps · 4.8 Mbit/s", LinkGlance.videoText(30f, 4.8f, Locale.ENGLISH))
        assertEquals("28 fps · 12,3 Mbit/s", LinkGlance.videoText(27.6f, 12.34f, Locale.forLanguageTag("pt-BR")))
    }

    @Test
    fun connectedWithoutPictureYetIsAmber() {
        val g = running { car = LinkState.Car.CONNECTED }
        assertEquals(Look.BUSY, g.look)
        assertEquals(Status.CONNECTED, g.status)
        assertEquals(Detail.WAITING_VIDEO, g.detail)
        // Con lo que dice la fila «Auto» (arrancando), eso.
        val starting = running { car = LinkState.Car.CONNECTED; sourceLevel = LinkState.Level.BUSY; source = "Arrancando Auto" }
        assertEquals(Detail.SOURCE, starting.detail)
        assertEquals("Arrancando Auto", starting.text)
    }

    @Test
    fun carGoneWaitsForItToComeBack() {
        val paused = running { car = LinkState.Car.RECONNECTING; aaParked = true }
        assertEquals(Look.BUSY, paused.look)
        assertEquals(Status.WAITING_RETURN, paused.status)
        assertEquals(Detail.AA_PAUSED, paused.detail)
        assertEquals(Detail.NONE, running { car = LinkState.Car.RECONNECTING }.detail)
    }

    @Test
    fun problemsAreRed() {
        val port = running { udpBusy = true; networkLevel = LinkState.Level.ERROR; network = "El puerto 18463 está ocupado" }
        assertEquals(Look.ERROR, port.look)
        assertEquals(Action.DISCONNECT, port.action)
        assertEquals(Status.PORT_BUSY, port.status)
        assertEquals(Detail.PORT_BUSY, port.detail)

        val hotspot = running { networkLevel = LinkState.Level.ERROR; network = "Zona Wi-Fi apagada: actívala" }
        assertEquals(Look.ERROR, hotspot.look)
        assertEquals(Status.NETWORK_PROBLEM, hotspot.status)
        assertEquals("Zona Wi-Fi apagada: actívala", hotspot.text)

        // Android Auto que no responde: rojo aunque la sesión con el coche siga.
        val aa = running { car = LinkState.Car.CONNECTED; video = "30 fps · 4,8 Mbps"; sourceLevel = LinkState.Level.ERROR; source = "Auto no responde" }
        assertEquals(Look.ERROR, aa.look)
        assertEquals(Status.SOURCE_PROBLEM, aa.status)
        assertEquals(Detail.SOURCE, aa.detail)
    }

    @Test
    fun aNetworkErrorDoesNotHideALiveSession() {
        // Con la sesión y la imagen en marcha, un aviso viejo de la fila «Red» no tapa el verde.
        val g = running { car = LinkState.Car.CONNECTED; video = "30 fps · 4,8 Mbps"; networkLevel = LinkState.Level.ERROR; udpBusy = true }
        assertEquals(Look.LIVE, g.look)
        assertEquals(Status.LIVE, g.status)
    }

    @Test
    fun selectorMarksTheChosenConnectionAndTracksTheRealOne() {
        // Parado: la elegida es la que irá.
        val off = glance { linkMode = Config.LINK_P2P }
        assertEquals(Config.LINK_P2P, off.linkMode)
        assertEquals(Config.LINK_P2P, off.transport)
        // En marcha con el cable por delante de la zona Wi-Fi elegida.
        val cable = running { linkMode = Config.LINK_HOTSPOT; activeLinkMode = Config.LINK_USB; usbOverride = true }
        assertEquals(Config.LINK_HOTSPOT, cable.linkMode)
        assertEquals(Config.LINK_USB, cable.transport)
        // Algo desconocido guardado: Wi-Fi Direct (como Config).
        assertEquals(Config.LINK_P2P, glance { linkMode = "otra" }.linkMode)
    }

    @Test
    fun theCompactIconCyclesThroughTheThreeConnections() {
        assertEquals(Config.LINK_P2P, LinkGlance.nextLink(Config.LINK_HOTSPOT))
        assertEquals(Config.LINK_USB, LinkGlance.nextLink(Config.LINK_P2P))
        assertEquals(Config.LINK_HOTSPOT, LinkGlance.nextLink(Config.LINK_USB))
        assertEquals(Config.LINK_USB, LinkGlance.nextLink("otra"))
    }

    @Test
    fun sizeClasses() {
        // 2x2 (o 3 columnas estrechas): el botón, el estado y el icono de la conexión.
        assertEquals(Size.COMPACT, LinkGlance.sizeFor(130, 180))
        assertEquals(Size.COMPACT, LinkGlance.sizeFor(165, 175))
        assertEquals(Size.COMPACT, LinkGlance.sizeFor(259, 300))
        assertEquals(Size.COMPACT_SHORT, LinkGlance.sizeFor(165, 174))
        assertEquals(Size.COMPACT_SHORT, LinkGlance.sizeFor(115, 115))
        assertEquals(Size.COMPACT_SHORT, LinkGlance.sizeFor(250, 110))
        // 4x2: con la cabecera desde 175 dp de alto.
        assertEquals(Size.WIDE, LinkGlance.sizeFor(340, 180))
        assertEquals(Size.WIDE, LinkGlance.sizeFor(260, 175))
        assertEquals(Size.WIDE_SHORT, LinkGlance.sizeFor(260, 150))
        assertEquals(Size.WIDE_SHORT, LinkGlance.sizeFor(300, 110))
        assertEquals(Size.WIDE_SHORT, LinkGlance.sizeFor(400, 174))
        assertTrue(Size.COMPACT_SHORT.compact() && Size.COMPACT.compact())
        assertFalse(Size.WIDE_SHORT.compact() || Size.WIDE.compact())
    }

    @Test
    fun repaintOnlyWhatChangesAndTheFiguresAtMostEveryFiveSeconds() {
        val live30 = running { car = LinkState.Car.CONNECTED; video = "30 fps · 4,8 Mbps" }
        val live29 = running { car = LinkState.Car.CONNECTED; video = "29 fps · 4,6 Mbps" }
        val searching = running()
        // La primera vez, ya.
        assertEquals(0L, LinkGlance.pushDelay(null, 0, live30, 1_000))
        // Igual: nada.
        assertEquals(-1L, LinkGlance.pushDelay(live30, 1_000, running { car = LinkState.Car.CONNECTED; video = "30 fps · 4,8 Mbps" }, 1_200))
        // Solo las cifras: como mucho cada 4,5 s.
        assertTrue(live29.sameButVideo(live30))
        assertNotEquals(live29.signature(), live30.signature())
        assertEquals(4_000L, LinkGlance.pushDelay(live30, 1_000, live29, 1_500))
        assertEquals(0L, LinkGlance.pushDelay(live30, 1_000, live29, 5_500))
        // Un cambio de estado, al momento.
        assertFalse(searching.sameButVideo(live30))
        assertEquals(0L, LinkGlance.pushDelay(live30, 1_000, searching, 1_100))
        // Otra conexión, otro modo u otro idioma también son cambios.
        assertEquals(0L, LinkGlance.pushDelay(searching, 1_000, running { linkMode = Config.LINK_USB }, 1_100))
        assertEquals(0L, LinkGlance.pushDelay(searching, 1_000, running { mode = Config.MODE_AA_EXT }, 1_100))
        assertEquals(0L, LinkGlance.pushDelay(searching, 1_000, running { locale = "pt-PT" }, 1_100))
    }
}
