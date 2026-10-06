package com.headqlink.link

import com.headqlink.link.Requirements.Hint
import com.headqlink.link.Requirements.Hotspot
import com.headqlink.link.Requirements.Id
import com.headqlink.link.Requirements.Importance
import com.headqlink.link.Requirements.Oem
import com.headqlink.link.Requirements.Perm
import com.headqlink.link.Requirements.Port
import com.headqlink.link.Requirements.Snapshot
import com.headqlink.link.Requirements.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comprobación de requisitos: qué sale según los ajustes, con qué importancia y qué cuenta como «falta». */
class RequirementsTest {
    private fun eval(block: Snapshot.() -> Unit = {}) = Requirements.evaluate(Snapshot().apply(block))

    private fun ids(items: List<Requirements.Item>) = items.map { it.id }

    private fun item(items: List<Requirements.Item>, id: Id): Requirements.Item {
        val i = Requirements.find(items, id)
        assertNotNull("falta $id en $items", i)
        return i!!
    }

    @Test
    fun allGoodIsReady() {
        val items = eval()
        assertEquals(0, Requirements.missingCount(items))
        assertTrue(Requirements.blocking(items).isEmpty())
        // Auto ampliado + Wi-Fi Direct con AA 17.6: todo lo suyo, en este orden.
        assertEquals(
            listOf(
                Id.ANDROID_AUTO, Id.ACCESSIBILITY, Id.AA_DEVMODE, Id.NEARBY_WIFI, Id.WIFI_ON, Id.HOTSPOT_OFF,
                Id.NOTIFICATIONS, Id.BATTERY, Id.OVERLAY, Id.MEDIA,
            ),
            ids(items),
        )
        assertTrue(items.all { it.status == Status.OK })
    }

    @Test
    fun importances() {
        val items = eval { btAuto = true; qdlinkInstalled = true; oem = Oem.SAMSUNG }
        assertEquals(Importance.REQUIRED, item(items, Id.ANDROID_AUTO).importance)
        assertEquals(Importance.REQUIRED, item(items, Id.ACCESSIBILITY).importance)
        assertEquals(Importance.REQUIRED, item(items, Id.AA_DEVMODE).importance)
        assertEquals(Importance.REQUIRED, item(items, Id.NEARBY_WIFI).importance)
        assertEquals(Importance.REQUIRED, item(items, Id.BLUETOOTH).importance)
        assertEquals(Importance.RECOMMENDED, item(items, Id.NOTIFICATIONS).importance)
        assertEquals(Importance.RECOMMENDED, item(items, Id.BATTERY).importance)
        assertEquals(Importance.RECOMMENDED, item(items, Id.QDLINK).importance)
        assertEquals(Importance.OPTIONAL, item(items, Id.OVERLAY).importance)
        assertEquals(Importance.OPTIONAL, item(items, Id.MEDIA).importance)
    }

    @Test
    fun hotspotModeSwapsTheNetworkItems() {
        val items = eval { linkMode = Config.LINK_HOTSPOT; hotspot = Hotspot.ON }
        val got = ids(items)
        assertTrue(Id.HOTSPOT_ON in got)
        assertTrue(Id.HOTSPOT_BAND in got)
        assertFalse(Id.NEARBY_WIFI in got)
        assertFalse(Id.WIFI_ON in got)
        assertFalse(Id.HOTSPOT_OFF in got)
        assertEquals(Status.TIP, item(items, Id.HOTSPOT_BAND).status)
        assertEquals(0, Requirements.missingCount(items))
    }

    @Test
    fun hotspotModeWithHotspotOffBlocks() {
        val items = eval { linkMode = Config.LINK_HOTSPOT; hotspot = Hotspot.OFF }
        assertEquals(Status.MISSING, item(items, Id.HOTSPOT_ON).status)
        assertEquals(listOf(Id.HOTSPOT_ON), ids(Requirements.blocking(items)))
        // Wi-Fi apagado o sin permiso de Wi-Fi cercano no importan con la zona Wi-Fi.
        assertEquals(1, Requirements.missingCount(eval {
            linkMode = Config.LINK_HOTSPOT; hotspot = Hotspot.OFF; wifiOn = false; nearby = Perm.ASK
        }))
    }

    @Test
    fun wifiDirectNeedsHotspotOffWifiOnAndNearby() {
        val items = eval { hotspot = Hotspot.ON; wifiOn = false; nearby = Perm.ASK }
        assertEquals(listOf(Id.NEARBY_WIFI, Id.WIFI_ON, Id.HOTSPOT_OFF), ids(Requirements.blocking(items)))
        assertEquals(3, Requirements.missingCount(items))
    }

    @Test
    fun unknownOrCheckingHotspotDoesNotCount() {
        for (h in listOf(Hotspot.UNKNOWN, Hotspot.CHECKING)) {
            val p2p = eval { hotspot = h }
            val hs = eval { linkMode = Config.LINK_HOTSPOT; hotspot = h }
            assertEquals(0, Requirements.missingCount(p2p))
            assertEquals(0, Requirements.missingCount(hs))
            val want = if (h == Hotspot.UNKNOWN) Status.UNKNOWN else Status.CHECKING
            assertEquals(want, item(p2p, Id.HOTSPOT_OFF).status)
            assertEquals(want, item(hs, Id.HOTSPOT_ON).status)
        }
    }

    @Test
    fun oldAndroidAutoNeedsNoAccessibilityNorDevMode() {
        val items = eval { aaVersion = "17.3.651"; accessibilityRunning = false; accessibilityEnabled = false; devMode = 0 }
        assertFalse(Id.ACCESSIBILITY in ids(items))
        assertFalse(Id.AA_DEVMODE in ids(items))
        assertEquals(0, Requirements.missingCount(items))
        // force_legacy_launch: tampoco con un AA nuevo.
        assertFalse(Id.ACCESSIBILITY in ids(eval { forceLegacyLaunch = true; accessibilityRunning = false }))
    }

    @Test
    fun accessibilityHints() {
        val off = item(eval { accessibilityRunning = false; accessibilityEnabled = false }, Id.ACCESSIBILITY)
        assertEquals(Status.MISSING, off.status)
        assertEquals(Hint.NONE, off.hint)
        assertTrue(off.blocks())

        val restricted = item(eval { accessibilityRunning = false; accessibilityEnabled = false; restrictedSettings = true }, Id.ACCESSIBILITY)
        assertEquals(Hint.RESTRICTED, restricted.hint)

        // Activada en Ajustes pero sin el servicio: Android la paró (lo restringido ya no importa).
        val stuck = item(eval { accessibilityRunning = false; accessibilityEnabled = true; restrictedSettings = true }, Id.ACCESSIBILITY)
        assertEquals(Status.MISSING, stuck.status)
        assertEquals(Hint.NOT_RUNNING, stuck.hint)

        assertEquals(Status.OK, item(eval { accessibilityEnabled = false; accessibilityRunning = true }, Id.ACCESSIBILITY).status)
    }

    @Test
    fun androidAutoMissingOrDisabled() {
        val missing = eval { aaVersion = null }
        assertEquals(Hint.NOT_INSTALLED, item(missing, Id.ANDROID_AUTO).hint)
        assertTrue(item(missing, Id.ANDROID_AUTO).blocks())
        // Sin AA no hay versión: ni accesibilidad ni modo desarrollador hasta instalarlo.
        assertFalse(Id.AA_DEVMODE in ids(missing))
        assertFalse(Id.ACCESSIBILITY in ids(missing))

        val disabled = item(eval { aaEnabled = false }, Id.ANDROID_AUTO)
        assertEquals(Status.MISSING, disabled.status)
        assertEquals(Hint.DISABLED, disabled.hint)
    }

    @Test
    fun devModeStates() {
        assertEquals(Hint.NONE, item(eval { devMode = 0 }, Id.AA_DEVMODE).hint)
        assertTrue(item(eval { devMode = 0 }, Id.AA_DEVMODE).blocks())
        val unchecked = item(eval { devMode = -1 }, Id.AA_DEVMODE)
        assertEquals(Status.MISSING, unchecked.status)
        assertEquals(Hint.NOT_CHECKED, unchecked.hint)
        val checking = item(eval { devMode = -1; devModeChecking = true }, Id.AA_DEVMODE)
        assertEquals(Status.CHECKING, checking.status)
        assertFalse(checking.missing())
    }

    @Test
    fun appModeNeedsAppAccessibilityAndOverlay() {
        val items = eval { mode = Config.MODE_APP; targetAppChosen = false; overlay = false; accessibilityRunning = false }
        val got = ids(items)
        assertFalse(Id.ANDROID_AUTO in got)
        assertFalse(Id.AA_DEVMODE in got)
        assertFalse(Id.MEDIA in got)
        assertEquals(Importance.REQUIRED, item(items, Id.OVERLAY).importance)
        assertEquals(setOf(Id.ACCESSIBILITY, Id.TARGET_APP, Id.OVERLAY), ids(Requirements.blocking(items)).toSet())
    }

    @Test
    fun overlayOptionalInAutoAndAbsentInDiagnostics() {
        val aa = eval { overlay = false }
        assertEquals(Status.MISSING, item(aa, Id.OVERLAY).status)
        assertFalse(item(aa, Id.OVERLAY).counts())
        assertEquals(0, Requirements.missingCount(aa))
        val pattern = eval { mode = Config.MODE_PATTERN; overlay = false }
        assertFalse(Id.OVERLAY in ids(pattern))
        assertFalse(Id.ANDROID_AUTO in ids(pattern))
        assertFalse(Id.ACCESSIBILITY in ids(pattern))
        // Auto sin ampliar: sin fotos ni vídeos.
        assertFalse(Id.MEDIA in ids(eval { mode = Config.MODE_AA }))
    }

    @Test
    fun bluetoothOnlyWithAutoConnect() {
        assertFalse(Id.BLUETOOTH in ids(eval { bluetooth = Perm.BLOCKED }))
        val items = eval { btAuto = true; bluetooth = Perm.BLOCKED; batteryUnrestricted = false }
        val bt = item(items, Id.BLUETOOTH)
        assertEquals(Hint.BLOCKED, bt.hint)
        assertTrue(bt.blocks())
        // La batería la necesita la conexión automática, pero sigue siendo recomendada.
        val battery = item(items, Id.BATTERY)
        assertEquals(Hint.BT_AUTO, battery.hint)
        assertTrue(battery.counts())
        assertFalse(battery.blocks())
        assertEquals(Hint.NONE, item(eval { batteryUnrestricted = false }, Id.BATTERY).hint)
    }

    @Test
    fun notificationsAreRecommended() {
        val ask = item(eval { notifications = Perm.ASK }, Id.NOTIFICATIONS)
        assertEquals(Status.MISSING, ask.status)
        assertEquals(Hint.NONE, ask.hint)
        assertTrue(ask.counts())
        assertFalse(ask.blocks())
        assertEquals(Hint.BLOCKED, item(eval { notifications = Perm.BLOCKED }, Id.NOTIFICATIONS).hint)
    }

    @Test
    fun qdlinkWarnsAndBusyPortIsAnError() {
        assertNull(Requirements.find(eval(), Id.QDLINK))
        val installed = item(eval { qdlinkInstalled = true }, Id.QDLINK)
        assertEquals(Status.WARN, installed.status)
        assertEquals(Hint.INSTALLED, installed.hint)
        assertFalse(installed.counts())

        val busy = eval { qdlinkInstalled = true; port = Port.BUSY }
        assertEquals(Status.ERROR, item(busy, Id.QDLINK).status)
        assertEquals(Hint.PORT_BUSY, item(busy, Id.QDLINK).hint)
        assertEquals(listOf(Id.QDLINK), ids(Requirements.blocking(busy)))
        // Ocupado también sin QDLink instalado (otra app).
        assertEquals(Status.ERROR, item(eval { port = Port.BUSY }, Id.QDLINK).status)
        // Puerto sin comprobar: nada.
        assertNull(Requirements.find(eval { port = Port.UNKNOWN }, Id.QDLINK))
    }

    @Test
    fun oemTips() {
        assertNull(Requirements.find(eval(), Id.BATTERY_OEM))
        val samsung = item(eval { oem = Oem.SAMSUNG }, Id.BATTERY_OEM)
        assertEquals(Status.TIP, samsung.status)
        assertEquals(Hint.SAMSUNG, samsung.hint)
        assertFalse(samsung.counts())
        assertEquals(Hint.OEM, item(eval { oem = Oem.OTHER }, Id.BATTERY_OEM).hint)
    }

    @Test
    fun countsRequiredAndRecommendedButNotOptional() {
        val items = eval {
            accessibilityRunning = false; accessibilityEnabled = false // obligatorio
            notifications = Perm.ASK // recomendado
            media = Perm.ASK // opcional
            overlay = false // opcional en Auto
        }
        assertEquals(2, Requirements.missingCount(items))
        assertEquals(listOf(Id.ACCESSIBILITY), ids(Requirements.blocking(items)))
    }

    @Test
    fun headUnitServerVersion() {
        assertTrue(Requirements.usesHeadUnitServer("17.4.0"))
        assertTrue(Requirements.usesHeadUnitServer("17.4.651024-release"))
        assertTrue(Requirements.usesHeadUnitServer("17.10.1"))
        assertTrue(Requirements.usesHeadUnitServer("18.0"))
        assertFalse(Requirements.usesHeadUnitServer("17.3.9"))
        assertFalse(Requirements.usesHeadUnitServer("16.9.6"))
        assertFalse(Requirements.usesHeadUnitServer("17"))
        // Como SelfLauncherManager: lo que no se entiende va por el camino viejo.
        assertFalse(Requirements.usesHeadUnitServer("beta"))
        assertFalse(Requirements.usesHeadUnitServer(""))
        assertFalse(Requirements.usesHeadUnitServer(null))
    }

    @Test
    fun accessibilitySettingParsing() {
        val pkg = "com.headqlink.app"
        val cls = "com.headqlink.link.TouchService"
        assertTrue(Requirements.serviceEnabled("$pkg/$cls", pkg, cls))
        assertTrue(Requirements.serviceEnabled("com.a/com.a.Svc:$pkg/$cls:com.b/.X", pkg, cls))
        assertFalse(Requirements.serviceEnabled("com.a/com.a.Svc", pkg, cls))
        // Otra app con el mismo nombre de clase no cuenta.
        assertFalse(Requirements.serviceEnabled("com.other/$cls", pkg, cls))
        // Forma corta con el punto.
        assertTrue(Requirements.serviceEnabled("com.x/.Svc", "com.x", "com.x.Svc"))
        assertFalse(Requirements.serviceEnabled(null, pkg, cls))
        assertFalse(Requirements.serviceEnabled("", pkg, cls))
        assertFalse(Requirements.serviceEnabled("basura", pkg, cls))
    }

    @Test
    fun permissionStates() {
        assertEquals(Perm.GRANTED, Requirements.permState(true, true, false))
        assertEquals(Perm.ASK, Requirements.permState(false, false, false)) // nunca pedido
        assertEquals(Perm.ASK, Requirements.permState(false, true, true)) // denegado una vez
        assertEquals(Perm.BLOCKED, Requirements.permState(false, true, false)) // denegado para siempre
    }

    @Test
    fun usbCableNeedsNoHotspotNorWifi() {
        // Cable USB: ni zona Wi-Fi, ni Wi-Fi, ni «Dispositivos Wi-Fi cercanos», ni el puerto UDP 18463.
        val items = eval { linkMode = Config.LINK_USB; hotspot = Hotspot.OFF; wifiOn = false; nearby = Perm.ASK; port = Port.BUSY }
        val got = ids(items)
        assertFalse(Id.HOTSPOT_ON in got)
        assertFalse(Id.HOTSPOT_BAND in got)
        assertFalse(Id.HOTSPOT_OFF in got)
        assertFalse(Id.NEARBY_WIFI in got)
        assertFalse(Id.WIFI_ON in got)
        assertFalse(Id.QDLINK in got)
        assertEquals(Status.TIP, item(items, Id.USB_CABLE).status)
        assertEquals(0, Requirements.missingCount(items))
        assertTrue(Requirements.blocking(items).isEmpty())
        // Con QDLink instalada: aviso (no cuenta) de que Android puede preguntar qué app abre «QDriveLink».
        val qd = eval { linkMode = Config.LINK_USB; qdlinkInstalled = true }
        assertEquals(Hint.USB_CHOOSER, item(qd, Id.QDLINK).hint)
        assertEquals(Status.WARN, item(qd, Id.QDLINK).status)
        assertEquals(0, Requirements.missingCount(qd))
    }

    // ---------------------------------------------------------------- arranque manual del servidor de Android Auto

    @Test
    fun manualServerStartMakesAccessibilityOptionalAndAddsTheServerRow() {
        val items = eval { manualServer = true; aaServer = Requirements.AaServer.IN_USE; accessibilityRunning = false; accessibilityEnabled = false; restrictedSettings = true; devMode = -1 }
        assertEquals(
            listOf(
                Id.ANDROID_AUTO, Id.AA_SERVER, Id.ACCESSIBILITY, Id.AA_DEVMODE, Id.NEARBY_WIFI, Id.WIFI_ON, Id.HOTSPOT_OFF,
                Id.NOTIFICATIONS, Id.BATTERY, Id.OVERLAY, Id.MEDIA,
            ),
            ids(items),
        )
        val acc = item(items, Id.ACCESSIBILITY)
        assertEquals(Importance.OPTIONAL, acc.importance)
        assertEquals(Status.MISSING, acc.status)
        assertEquals(Hint.MANUAL, acc.hint)
        assertFalse(acc.counts())
        assertFalse(acc.blocks())
        val server = item(items, Id.AA_SERVER)
        assertEquals(Importance.INFO, server.importance)
        assertEquals(Status.OK, server.status)
        // AA atiende a HeadQLink: el modo desarrollador está activo aunque no se haya podido comprobar.
        assertEquals(Status.OK, item(items, Id.AA_DEVMODE).status)
        assertEquals(0, Requirements.missingCount(items))
        assertTrue("Conectar no se bloquea", Requirements.blocking(items).isEmpty())
        assertNull("sin oferta del manual: ya lo es", Requirements.find(items, Id.SERVER_MANUAL_OFFER))
    }

    @Test
    fun manualServerRowNeverProbesNeverBlocksAndSaysOnlyWhatIsKnown() {
        // Sin conectarse al servidor (una sonda lo bloquearía): en uso por HeadQLink, intentos fallando, o cómo arrancarlo.
        for (state in Requirements.AaServer.values()) {
            val items = eval { manualServer = true; aaServer = state; accessibilityRunning = false; devMode = -1 }
            val server = item(items, Id.AA_SERVER)
            val expected = when (state) {
                Requirements.AaServer.IN_USE -> Status.OK
                Requirements.AaServer.WAITING -> Status.WARN
                Requirements.AaServer.UNKNOWN -> Status.TIP
            }
            assertEquals(state.toString(), expected, server.status)
            assertFalse(server.counts())
            assertFalse(server.blocks())
            assertTrue(state.toString(), Requirements.blocking(items).isEmpty())
            assertEquals(state.toString(), 0, Requirements.missingCount(items))
        }
        // Sin saber y sin el modo desarrollador comprobado: consejo (no se puede comprobar sin la accesibilidad).
        for (state in listOf(Requirements.AaServer.UNKNOWN, Requirements.AaServer.WAITING)) {
            val dev = item(eval { manualServer = true; aaServer = state; devMode = -1 }, Id.AA_DEVMODE)
            assertEquals(Status.TIP, dev.status)
            assertEquals(Hint.MANUAL, dev.hint)
            assertFalse(dev.counts())
        }
        // Ya comprobado antes (con el automático, o porque AA atendió un intento): activo.
        assertEquals(Status.OK, item(eval { manualServer = true; aaServer = Requirements.AaServer.UNKNOWN; devMode = 1 }, Id.AA_DEVMODE).status)
    }

    @Test
    fun manualServerStartDoesNotApplyToTheAppModeNorToOldAndroidAuto() {
        // Modo App: los toques siguen necesitando la accesibilidad.
        val app = eval { mode = Config.MODE_APP; manualServer = true; accessibilityRunning = false }
        assertEquals(Importance.REQUIRED, item(app, Id.ACCESSIBILITY).importance)
        assertTrue(item(app, Id.ACCESSIBILITY).blocks())
        assertNull(Requirements.find(app, Id.AA_SERVER))
        assertNull(Requirements.find(app, Id.SERVER_MANUAL_OFFER))
        // AA anterior a 17.4: no hay servidor que arrancar.
        val old = eval { aaVersion = "17.3.1"; manualServer = true }
        assertNull(Requirements.find(old, Id.AA_SERVER))
        assertNull(Requirements.find(old, Id.ACCESSIBILITY))
        assertFalse(Requirements.usesHeadUnitServer("17.3.1"))
        assertTrue(Requirements.usesHeadUnitServer("17.7.0"))
    }

    @Test
    fun automaticWithoutAccessibilityOffersTheManualStart() {
        val items = eval { accessibilityRunning = false; accessibilityEnabled = false; restrictedSettings = true }
        val got = ids(items)
        assertEquals(got.indexOf(Id.ACCESSIBILITY) + 1, got.indexOf(Id.SERVER_MANUAL_OFFER))
        val offer = item(items, Id.SERVER_MANUAL_OFFER)
        assertEquals(Status.TIP, offer.status)
        assertFalse(offer.counts())
        // La accesibilidad sigue siendo obligatoria en el automático.
        assertEquals(listOf(Id.ACCESSIBILITY), ids(Requirements.blocking(items)))
        assertNull(Requirements.find(eval(), Id.SERVER_MANUAL_OFFER))
        assertNull(Requirements.find(eval { mode = Config.MODE_APP; accessibilityRunning = false }, Id.SERVER_MANUAL_OFFER))
    }
}
