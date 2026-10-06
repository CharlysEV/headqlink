package com.headqlink.link

import android.app.Application
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/**
 * Lo que hacen los toques del widget y su puente: la conexión y el modo se guardan siempre y, con el enlace en
 * marcha, se aplican (LinkService); el botón grande desconecta en marcha y, sin configurar, abre la app.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = Application::class, qualifiers = "es-rES")
class LinkWidgetActionsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val cfg: Config get() = Config(ctx)

    @Before
    fun setUp() {
        Str.init(ctx)
        LinkState.running = false
        LinkState.activeLinkMode = ""
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        LinkState.running = false
        LinkState.activeLinkMode = ""
        LinkState.car = LinkState.Car.OFF
        LinkState.video = ""
    }

    private fun send(action: String, value: String? = null) {
        val i = Intent(ctx, LinkWidget::class.java).setAction(action)
        if (value != null) i.putExtra(LinkWidget.EXTRA_VALUE, value)
        LinkWidget().onReceive(ctx, i)
    }

    @Test
    fun stoppedTheConnectionIsOnlySaved() {
        cfg.setLinkMode(Config.LINK_HOTSPOT)
        send(LinkWidget.ACTION_LINK, Config.LINK_USB)
        assertEquals(Config.LINK_USB, cfg.linkMode())
        assertNull(shadowOf(ctx).nextStartedService)
        // El icono del 2x2: la siguiente.
        send(LinkWidget.ACTION_CYCLE)
        assertEquals(Config.LINK_HOTSPOT, cfg.linkMode())
        send(LinkWidget.ACTION_CYCLE)
        assertEquals(Config.LINK_P2P, cfg.linkMode())
    }

    @Test
    fun runningTheConnectionIsAppliedNow() {
        cfg.setLinkMode(Config.LINK_HOTSPOT)
        LinkState.running = true
        LinkState.activeLinkMode = Config.LINK_HOTSPOT
        send(LinkWidget.ACTION_LINK, Config.LINK_P2P)
        assertEquals(Config.LINK_P2P, cfg.linkMode())
        val started = shadowOf(ctx).nextStartedService
        assertEquals(LinkService.ACTION_SET_LINK, started.action)
        assertEquals(LinkService::class.java.name, started.component!!.className)
        assertEquals("widget", started.getStringExtra(LinkService.EXTRA_FROM))
        // La misma que ya va: nada que aplicar.
        LinkState.activeLinkMode = Config.LINK_P2P
        send(LinkWidget.ACTION_LINK, Config.LINK_P2P)
        assertNull(shadowOf(ctx).nextStartedService)
    }

    @Test
    fun theModeIsSavedAndAppliedLikeThePictureSettings() {
        cfg.setMode(Config.MODE_AA)
        send(LinkWidget.ACTION_MODE, Config.MODE_AA_EXT)
        assertEquals(Config.MODE_AA_EXT, cfg.mode())
        assertNull(shadowOf(ctx).nextStartedService)
        LinkState.running = true
        send(LinkWidget.ACTION_MODE, Config.MODE_AA)
        assertEquals(Config.MODE_AA, cfg.mode())
        val started = shadowOf(ctx).nextStartedService
        assertEquals(LinkService.ACTION_APPLY, started.action)
        assertTrue(started.getBooleanExtra(LinkService.EXTRA_AA_RENEGOTIATE, false))
        // Solo los dos modos de Android Auto.
        send(LinkWidget.ACTION_MODE, "pattern")
        assertEquals(Config.MODE_AA, cfg.mode())
    }

    @Test
    fun bigButtonWithoutSetupOpensTheApp() {
        val act = Robolectric.buildActivity(QuickToggleActivity::class.java, toggleIntent()).create().get()
        assertEquals(HomeActivity::class.java.name, shadowOf(act).nextStartedActivity.component!!.className)
        assertTrue(act.isFinishing)
    }

    @Test
    fun bigButtonRunningDisconnects() {
        cfg.setSetupDone(true)
        LinkState.running = true
        val act = Robolectric.buildActivity(QuickToggleActivity::class.java, toggleIntent()).create().get()
        val stop = shadowOf(ctx).nextStartedService
        assertEquals(LinkService.ACTION_STOP, stop.action)
        assertTrue(act.isFinishing)
        assertFalse(LinkControl.busy())
    }

    @Test
    fun quickSettingsTileShowsTheStateAndDisconnects() {
        cfg.setSetupDone(true)
        LinkState.running = true
        LinkState.car = LinkState.Car.CONNECTED
        LinkState.video = "30 fps · 4,8 Mbps"
        val svc = Robolectric.setupService(LinkTileService::class.java)
        svc.onStartListening()
        val tile = svc.qsTile
        assertEquals(android.service.quicksettings.Tile.STATE_ACTIVE, tile.state)
        assertEquals("HeadQLink", tile.label)
        assertEquals("30 fps · 4,8 Mbit/s", tile.subtitle)
        assertEquals("Coche conectado · 30 fps · 4,8 Mbit/s", tile.stateDescription)
        // En marcha: tocarlo desconecta (el servicio está en primer plano: startService vale).
        svc.onClick()
        assertEquals(LinkService.ACTION_STOP, shadowOf(ctx).nextStartedService.action)
        svc.onStopListening()
        // Parado: inactivo y «Apagado».
        LinkState.running = false
        LinkState.car = LinkState.Car.OFF
        LinkState.video = ""
        svc.onStartListening()
        assertEquals(android.service.quicksettings.Tile.STATE_INACTIVE, svc.qsTile.state)
        assertEquals("Apagado", svc.qsTile.subtitle)
        svc.onStopListening()
    }

    private fun toggleIntent() = Intent(ctx, QuickToggleActivity::class.java)
        .setAction(QuickToggleActivity.ACTION_TOGGLE)
        .putExtra(QuickToggleActivity.EXTRA_FROM, "widget")
}
