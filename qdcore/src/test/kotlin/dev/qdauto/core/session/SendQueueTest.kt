package dev.qdauto.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendQueueTest {
    private fun item(kind: OutKind, label: String, size: Int = 10) = Outgoing(ByteArray(size), kind, label, null)
    private fun cfg(n: Int) = item(OutKind.VIDEO_CONFIG, "C$n")
    private fun key(n: Int, size: Int = 10) = item(OutKind.VIDEO_KEY, "I$n", size)
    private fun delta(n: Int, size: Int = 10) = item(OutKind.VIDEO_DELTA, "P$n", size)
    private fun ctl(n: Int) = item(OutKind.CONTROL, "K$n")

    private fun SendQueue.drain(): List<String> {
        val out = ArrayList<String>()
        while (controlDepth() > 0 || videoFrameDepth() > 0 || videoByteDepth() > 0) out += take()!!.label
        return out
    }

    @Test
    fun controlHasPriorityOverVideo() {
        val q = SendQueue(backlogFrames = 100, backlogBytes = 1_000_000, hardLimitBytes = 10_000_000, resendConfigAfterDrop = true)
        q.offerConfig(cfg(1))
        q.offerFrame(key(1)) { null }
        q.offerFrame(delta(1)) { null }
        q.offerControl(ctl(1))
        q.offerControl(ctl(2))
        assertEquals(listOf("K1", "K2", "C1", "I1", "P1"), q.drain())
    }

    @Test
    fun backlogDropsDeltasKeepsKeysAndWaitsForIdr() {
        val q = SendQueue(backlogFrames = 3, backlogBytes = 1_000_000, hardLimitBytes = 10_000_000, resendConfigAfterDrop = true)
        var configsBuilt = 0
        val factory = { configsBuilt++; cfg(100 + configsBuilt) }
        assertTrue(q.offerConfig(cfg(1)))
        assertEquals(FrameOffer.ACCEPTED, q.offerFrame(key(1), factory))
        assertEquals(FrameOffer.ACCEPTED, q.offerFrame(delta(1), factory))
        assertEquals(FrameOffer.ACCEPTED, q.offerFrame(delta(2), factory))
        // 3 frames en cola → el siguiente P provoca descarte de los P encolados y de sí mismo.
        assertEquals(FrameOffer.DROPPED_BACKLOG, q.offerFrame(delta(3), factory))
        assertTrue(q.isWaitingForIdr)
        assertEquals(FrameOffer.DROPPED_WAITING_IDR, q.offerFrame(delta(4), factory))
        assertEquals(4, q.droppedFrames)
        // El IDR se acepta, precedido del SPS/PPS reenviado; luego vuelven los P.
        assertEquals(FrameOffer.ACCEPTED, q.offerFrame(key(2), factory))
        assertEquals(FrameOffer.ACCEPTED, q.offerFrame(delta(5), factory))
        assertEquals(listOf("C1", "I1", "C101", "I2", "P5"), q.drain())
    }

    @Test
    fun keyframeArrivingDuringBacklogSupersedesQueuedDeltas() {
        val q = SendQueue(backlogFrames = 2, backlogBytes = 1_000_000, hardLimitBytes = 10_000_000, resendConfigAfterDrop = false)
        q.offerFrame(key(1)) { null }
        q.offerFrame(delta(1)) { null }
        assertEquals(FrameOffer.ACCEPTED, q.offerFrame(key(2)) { null })
        assertEquals(1, q.droppedFrames)
        assertEquals(listOf("I1", "I2"), q.drain())
    }

    @Test
    fun byteThresholdAlsoTriggersDrop() {
        val q = SendQueue(backlogFrames = 100, backlogBytes = 100, hardLimitBytes = 10_000, resendConfigAfterDrop = false)
        q.offerFrame(key(1, 60)) { null }
        q.offerFrame(delta(1, 60)) { null }
        assertEquals(FrameOffer.DROPPED_BACKLOG, q.offerFrame(delta(2, 10)) { null })
        assertEquals(listOf("I1"), q.drain())
    }

    @Test
    fun configAndIdrAreNeverDroppedByBacklog() {
        val q = SendQueue(backlogFrames = 1, backlogBytes = 1, hardLimitBytes = 1_000_000, resendConfigAfterDrop = false)
        repeat(5) {
            assertTrue(q.offerConfig(cfg(it)))
            assertEquals(FrameOffer.ACCEPTED, q.offerFrame(key(it)) { null })
        }
        assertEquals(10, q.drain().size)
    }

    @Test
    fun hardLimitDropsOnlySupersededFrames() {
        val q = SendQueue(backlogFrames = 1_000, backlogBytes = 1_000_000, hardLimitBytes = 250, resendConfigAfterDrop = false)
        q.offerFrame(key(1, 100)) { null }
        q.offerFrame(delta(1, 100)) { null }
        q.offerFrame(key(2, 100)) { null } // 300 B > 250: I1 y P1 quedan superados por I2
        assertEquals(listOf("I2"), q.drain())
        assertEquals(2, q.droppedFrames)
    }

    @Test
    fun startStreamWaitsForIdrAndResendsConfig() {
        val q = SendQueue(backlogFrames = 10, backlogBytes = 1_000_000, hardLimitBytes = 10_000_000, resendConfigAfterDrop = true)
        q.startStream()
        assertEquals(FrameOffer.DROPPED_WAITING_IDR, q.offerFrame(delta(1)) { cfg(9) })
        assertEquals(FrameOffer.ACCEPTED, q.offerFrame(key(1)) { cfg(9) })
        q.requestConfigResend()
        assertEquals(FrameOffer.ACCEPTED, q.offerFrame(delta(2)) { cfg(10) })
        assertEquals(listOf("C9", "I1", "C10", "P2"), q.drain())
    }

    /** hql: write bloqueado con el coche hablando: todo el vídeo encolado fuera, y se reanuda en un IDR con SPS/PPS. */
    @Test
    fun flushVideoDropsEverythingQueuedAndWaitsForAnIdrWithConfig() {
        for (policy in VideoDropPolicy.values()) {
            val q = SendQueue(backlogFrames = 100, backlogBytes = 10_000_000, hardLimitBytes = 100_000_000, resendConfigAfterDrop = true, dropPolicy = policy)
            q.offerConfig(cfg(1))
            q.offerFrame(key(1)) { null }
            q.offerFrame(delta(1)) { null }
            q.offerFrame(delta(2)) { null }
            q.offerControl(ctl(1))
            val dropped = q.flushVideo()
            assertEquals(listOf("I1", "P1", "P2"), dropped.map { it.label }, policy.name)
            assertEquals(0, q.videoFrameDepth())
            assertEquals(0L, q.videoByteDepth())
            assertEquals(1, q.controlDepth())
            assertEquals(3L, q.droppedFrames)
            assertEquals(1L, q.flushes)
            assertTrue(q.isWaitingForIdr)
            // Los P que lleguen se tiran; el IDR entra con SPS/PPS delante; el control sigue primero.
            assertEquals(FrameOffer.DROPPED_WAITING_IDR, q.offerFrame(delta(3)) { cfg(9) })
            assertEquals(FrameOffer.ACCEPTED, q.offerFrame(key(2)) { cfg(9) })
            assertEquals(listOf("K1", "C9", "I2"), q.drain())
            // Sin nada en cola no cuenta como vaciado.
            assertTrue(q.flushVideo().isEmpty())
            assertEquals(1L, q.flushes)
        }
    }

    /** hql: tope de mensaje de vídeo; el escenario del C10 (IDR de 538 KB) con todas las políticas. */
    @Test
    fun oversizedIdrIsDroppedAndItsDeltasWaitForTheNextIdr() {
        for (policy in VideoDropPolicy.values()) {
            val q = SendQueue(
                backlogFrames = 100, backlogBytes = 10_000_000, hardLimitBytes = 100_000_000, resendConfigAfterDrop = true,
                dropPolicy = policy, maxMessageBytes = 480 * 1024,
            )
            assertEquals(FrameOffer.ACCEPTED, q.offerFrame(key(1, 300_000)) { cfg(1) }, "$policy")
            assertEquals(FrameOffer.ACCEPTED, q.offerFrame(delta(1)) { cfg(1) })
            // El IDR de 538 390 B (mensaje entero) no entra; los P que dependen de él tampoco.
            assertEquals(FrameOffer.DROPPED_OVERSIZED, q.offerFrame(key(2, 538_390)) { cfg(2) }, "$policy")
            assertTrue(q.isWaitingForIdr)
            assertEquals(FrameOffer.DROPPED_WAITING_IDR, q.offerFrame(delta(2)) { cfg(2) })
            assertEquals(FrameOffer.DROPPED_WAITING_IDR, q.offerFrame(delta(3)) { cfg(2) })
            // El IDR siguiente (más pequeño) entra con SPS/PPS delante, y con él vuelven los P.
            assertEquals(FrameOffer.ACCEPTED, q.offerFrame(key(3, 300_000)) { cfg(3) })
            assertEquals(FrameOffer.ACCEPTED, q.offerFrame(delta(4)) { cfg(4) })
            // El P encolado antes del IDR grande sale: no depende de él.
            assertEquals(listOf("I1", "P1", "C3", "I3", "P4"), q.drain(), "$policy")
            assertEquals(1, q.oversizedFrames)
            assertEquals(3, q.droppedFrames)
        }
    }

    @Test
    fun oversizedLimitIsInclusiveAndZeroMeansNoLimit() {
        val q = SendQueue(100, 10_000_000, 100_000_000, false, maxMessageBytes = 1_000)
        assertEquals(FrameOffer.ACCEPTED, q.offerFrame(key(1, 1_000)) { null })
        assertEquals(FrameOffer.DROPPED_OVERSIZED, q.offerFrame(key(2, 1_001)) { null })
        // Un P demasiado grande también corta la cadena hasta el siguiente IDR.
        assertEquals(FrameOffer.ACCEPTED, q.offerFrame(key(3, 10)) { null })
        assertEquals(FrameOffer.DROPPED_OVERSIZED, q.offerFrame(delta(1, 5_000)) { null })
        assertEquals(FrameOffer.DROPPED_WAITING_IDR, q.offerFrame(delta(2)) { null })
        assertEquals(listOf("I1", "I3"), q.drain())
        assertEquals(2, q.oversizedFrames)

        val free = SendQueue(100, 10_000_000, 100_000_000, false, maxMessageBytes = 0)
        assertEquals(false, free.isOversized(5_000_000))
        assertEquals(FrameOffer.ACCEPTED, free.offerFrame(key(1, 2_000_000)) { null })
        assertEquals(0, free.oversizedFrames)
    }

    @Test
    fun rejectOversizedWithoutBuildingTheMessage() {
        val q = SendQueue(100, 10_000_000, 100_000_000, true, maxMessageBytes = 480 * 1024)
        assertTrue(q.isOversized(525_208))
        assertEquals(false, q.isOversized(480 * 1024))
        assertEquals(FrameOffer.ACCEPTED, q.offerFrame(key(1)) { cfg(1) })
        assertEquals(FrameOffer.DROPPED_OVERSIZED, q.rejectOversized(isKeyframe = true))
        // Ya esperando un IDR, un P grande es un descarte normal (no cuenta como «por tamaño»).
        assertEquals(FrameOffer.DROPPED_WAITING_IDR, q.rejectOversized(isKeyframe = false))
        assertEquals(1, q.oversizedFrames)
        assertEquals(2, q.droppedFrames)
        assertEquals(FrameOffer.ACCEPTED, q.offerFrame(key(2)) { cfg(2) })
        assertEquals(listOf("I1", "C2", "I2"), q.drain())
        q.close()
        assertEquals(FrameOffer.CLOSED, q.rejectOversized(isKeyframe = true))
    }

    @Test
    fun controlQueueIsBounded() {
        val q = SendQueue(10, 1_000, 10_000, true, controlCapacity = 3)
        repeat(3) { assertTrue(q.offerControl(ctl(it))) }
        assertEquals(false, q.offerControl(ctl(9)))
        assertEquals("K0", q.take()!!.label)
        assertTrue(q.offerControl(ctl(10)))
    }

    @Test
    fun closeWakesTheConsumer() {
        val q = SendQueue(10, 1_000, 10_000, true)
        val t = Thread { assertNull(q.take()) }
        t.start()
        Thread.sleep(50)
        q.close()
        t.join(2_000)
        assertTrue(!t.isAlive)
        assertEquals(false, q.offerControl(ctl(1)))
        assertEquals(FrameOffer.CLOSED, q.offerFrame(key(1)) { null })
    }
}
