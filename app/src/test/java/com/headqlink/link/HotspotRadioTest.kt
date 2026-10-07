package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Banda, canal y textos de la radio de la zona Wi-Fi (viaje del 2026-10-07: 127 cortes sin saber la banda). */
class HotspotRadioTest {
    @Test
    fun bandAndChannelFromTheFrequency() {
        assertEquals("2,4 GHz", HotspotRadio.bandOf(2412))
        assertEquals(1, HotspotRadio.channelOf(2412))
        assertEquals(6, HotspotRadio.channelOf(2437))
        assertEquals(13, HotspotRadio.channelOf(2472))
        assertEquals(14, HotspotRadio.channelOf(2484))
        assertEquals("5 GHz", HotspotRadio.bandOf(5180))
        assertEquals(36, HotspotRadio.channelOf(5180))
        assertEquals(149, HotspotRadio.channelOf(5745))
        assertEquals(177, HotspotRadio.channelOf(5885))
        assertEquals("6 GHz", HotspotRadio.bandOf(5955))
        assertEquals(1, HotspotRadio.channelOf(5955))
        assertEquals(2, HotspotRadio.channelOf(5935))
        assertEquals(37, HotspotRadio.channelOf(6135))
        assertEquals("60 GHz", HotspotRadio.bandOf(58320))
        assertEquals(1, HotspotRadio.channelOf(58320))
        assertNull(HotspotRadio.bandOf(0))
        assertEquals(-1, HotspotRadio.channelOf(0))
    }

    @Test
    fun radioTextAndDetail() {
        val r = HotspotRadio.Radio(5180, 4, 6, "wlan1")
        assertEquals("5 GHz canal 36 (5180 MHz) · 80 MHz · Wi-Fi 6 (802.11ax)", HotspotRadio.radioText(r))
        assertEquals("canal 36 · 80 MHz · Wi-Fi 6 (802.11ax)", HotspotRadio.detail(r))
        // Android 11: sin estándar; ancho desconocido.
        val old = HotspotRadio.Radio(2437, -1, -1, null)
        assertEquals("2,4 GHz canal 6 (2437 MHz)", HotspotRadio.radioText(old))
        assertEquals("canal 6", HotspotRadio.detail(old))
        assertEquals("1234 MHz", HotspotRadio.radioText(HotspotRadio.Radio(1234, 2, 4, null)).substringBefore(" ·"))
        assertEquals("20 MHz", HotspotRadio.widthText(2))
        assertEquals("160 MHz", HotspotRadio.widthText(6))
        assertEquals("320 MHz", HotspotRadio.widthText(11))
        assertNull(HotspotRadio.widthText(0))
        assertEquals("Wi-Fi 7 (802.11be)", HotspotRadio.standardText(8))
        assertNull(HotspotRadio.standardText(0))
    }

    @Test
    fun bridgedBandsAndMaskedMacs() {
        assertEquals("5 GHz", HotspotRadio.joinBands(listOf("5 GHz")))
        assertEquals("2,4+5 GHz", HotspotRadio.joinBands(listOf("2,4 GHz", "5 GHz")))
        assertEquals("…:3f:a1", HotspotRadio.maskMac("02:00:5E:10:3F:A1"))
        assertEquals("?", HotspotRadio.maskMac(null))
        assertEquals("?", HotspotRadio.maskMac(""))
    }

    @Test
    fun withoutAWatchedHotspotTheSummaryHasNoBand() {
        assertEquals(HotspotRadio.State.NONE, HotspotRadio.state)
        assertEquals("", HotspotRadio.summaryBand())
        assertEquals("", HotspotRadio.summaryDetail())
    }
}
