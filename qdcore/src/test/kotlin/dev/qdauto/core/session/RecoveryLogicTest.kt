package dev.qdauto.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** hql: la lógica de la espera del coche tras un corte, con un reloj de mentira (ms → nanos). */
class RecoveryLogicTest {
    private val t0 = 5_000_000_000L
    private fun at(ms: Long) = t0 + ms * 1_000_000

    @Test
    fun watchReopensTheUdpAfterSilenceAtMostEveryIntervalAndExpires() {
        val w = LossWatch(t0, RecoveryConfig(watchMs = 100_000, diagIntervalMs = 10_000, udpRefreshSilenceMs = 20_000, udpRefreshMinIntervalMs = 20_000))
        // Un datagrama de antes de la pérdida no cuenta: el silencio se mide desde la pérdida.
        val before = t0 - 600_000_000_000L
        w.due(at(5_000), before).let {
            assertFalse(it.expired)
            assertEquals(-1, it.reopenQuietMs)
            assertFalse(it.diag)
        }
        assertTrue(w.due(at(10_000), before).diag)
        assertEquals(-1, w.due(at(19_999), before).reopenQuietMs)
        w.due(at(20_000), before).let {
            assertEquals(20_000, it.reopenQuietMs)
            assertTrue(it.diag)
        }
        // Como mucho cada 20 s, aunque siga el silencio.
        assertEquals(-1, w.due(at(30_000), before).reopenQuietMs)
        assertEquals(40_000, w.due(at(40_000), before).reopenQuietMs)
        // Llega un datagrama a los 45 s: el silencio vuelve a contar desde él.
        assertEquals(-1, w.due(at(50_000), at(45_000)).reopenQuietMs)
        assertEquals(-1, w.due(at(64_999), at(45_000)).reopenQuietMs)
        assertEquals(20_000, w.due(at(65_000), at(45_000)).reopenQuietMs)
        // Fin de la vigilancia.
        w.due(at(100_000), at(45_000)).let {
            assertTrue(it.expired)
            assertFalse(it.diag)
            assertEquals(-1, it.reopenQuietMs)
        }
    }

    @Test
    fun watchWithoutDiagnosticsOrRefresh() {
        val w = LossWatch(t0, RecoveryConfig(watchMs = 0, diagIntervalMs = 0, udpRefreshSilenceMs = 0))
        for (ms in listOf(10_000L, 20_000L, 600_000L)) {
            val d = w.due(at(ms), 0)
            assertFalse(d.expired)
            assertFalse(d.diag)
            assertEquals(-1, d.reopenQuietMs)
        }
    }

    @Test
    fun carReturnClassification() {
        val accept = at(100_000)
        // Anuncio en los 20 s anteriores: por anuncio (aunque haya un ACK no pedido más reciente).
        assertEquals(CarReturn.BROADCAST, CarReturn.classify(accept, at(95_000), at(99_900), 20_000, 1_000))
        // Anuncio viejo y ACK no pedido justo antes: por ACK no pedido.
        assertEquals(CarReturn.UNSOLICITED_ACK, CarReturn.classify(accept, at(50_000), at(99_700), 20_000, 1_000))
        assertEquals(CarReturn.UNSOLICITED_ACK, CarReturn.classify(accept, 0, at(99_000), 20_000, 1_000))
        // Sin anuncio y con el último ACK no pedido lejos (o ninguno): por el puerto de antes.
        assertEquals(CarReturn.OLD_PORT, CarReturn.classify(accept, 0, at(98_500), 20_000, 1_000))
        assertEquals(CarReturn.OLD_PORT, CarReturn.classify(accept, 0, 0, 20_000, 1_000))
        // Un ACK «posterior» a la conexión no la explica.
        assertEquals(CarReturn.OLD_PORT, CarReturn.classify(accept, 0, at(100_200), 20_000, 1_000))
    }

    @Test
    fun defaultsAreOnAndOffTurnsEverythingOff() {
        val on = RecoveryConfig()
        assertTrue(on.stableMirrorPort && on.reclaim && on.unsolicitedAcks)
        assertEquals(setOf(CloseReason.Kind.WATCHDOG, CloseReason.Kind.WRITE_STALL, CloseReason.Kind.READ_ERROR), on.reclaimOn)
        assertEquals(300_000, on.reclaimWindowMs)
        assertEquals(20_000, on.udpRefreshSilenceMs)
        assertEquals(10_000, on.diagIntervalMs)
        val off = RecoveryConfig.OFF
        assertFalse(off.stableMirrorPort || off.reclaim)
        assertEquals(0, off.watchMs)
        assertTrue(PhoneLinkConfig().reAckOnBroadcast)
    }
}
