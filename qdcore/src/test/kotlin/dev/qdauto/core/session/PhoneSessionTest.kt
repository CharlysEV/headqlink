package dev.qdauto.core.session

import dev.qdauto.core.TestSupport
import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.util.BE
import dev.qdauto.core.wire.BinBlock
import dev.qdauto.core.wire.CarKey
import dev.qdauto.core.wire.CarMessages
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.Direction
import dev.qdauto.core.wire.FunctionIds
import dev.qdauto.core.wire.Header
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.PhoneMessages
import dev.qdauto.core.wire.TouchCodec
import dev.qdauto.core.wire.TouchPointer
import dev.qdauto.core.wire.VideoExtHeader
import dev.qdauto.core.wire.VideoMessage
import dev.qdauto.core.wire.VideoParams
import dev.qdauto.core.wire.WireMessage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhoneSessionTest {
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x29)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33)
    private val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x44, 0x55)

    private val closeables = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() = closeables.reversed().forEach { runCatching { it.close() } }

    private class Rig(val server: MirrorServer, val car: FakeCar, val session: PhoneSession, val listener: RecordingListener)

    private fun rig(config: SessionConfig = SessionConfig(heartbeatInitialDelayMs = 60_000)): Rig {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val listener = RecordingListener()
        val session = PhoneSession(server.accept(5_000), config, listener).also { closeables += it }
        session.start()
        return Rig(server, car, session, listener)
    }

    private fun carInfo1920x1080(extra: Map<String, Any?> = emptyMap()) = CarMessages.carInfo(
        JsonObject(
            JsonObject.of(
                "Version" to "1", "CarType" to "2D4", "Platform" to 0, "PlatformVersion" to "1", "CarWidth" to 1920,
                "CarHeight" to 1080, "CarFactory" to "018", "HUFactory" to "119", "MirrorTypeReq" to 0,
            ) + JsonObject.of(*extra.toList().toTypedArray()),
        ),
    )

    @Test
    fun handshakeRepliesExactlyLikeQdlink() {
        val r = rig()
        // 1) Lo primero que llega es el AppStatus !BIN.
        val first = assertIs<WireMessage.Bin>(r.car.next())
        assertContentEquals(BinBlock.appStatus(36), first.block.bytes)

        // 2) CAR_INFO → PHONE_INFO (idéntico al ejemplo de la spec §6.4) y después UPDATE_NOTIFY.
        r.car.send(carInfo1920x1080())
        val phoneInfo = r.car.nextFrame()
        assertEquals(428, phoneInfo.header.totalSize)
        assertEquals(
            """{"PARA":{"PhoneName":"","PlatformVersion":"36","PhoneModel":"SM-S938B","Platform":0,"PhoneSystemTime":0,""" +
                """"PhoneHeightInApp":1080,"MirrorWidthInApp":1920,"PhoneWidthInApp":1920,"MirrorHeightInApp":1080,"MirrorTypeSupport":0,""" +
                """"PhoneFeature":{"PassistMobileNum":""},"PhoneUUID":"","Version":"1.9.7","MirrorHeight":886,"MirrorWidth":1920,""" +
                """"PhoneBrand":"samsung","PhoneHeight":1080,"PhoneWidth":2340},"CMD":"PHONE_INFO"}""",
            phoneInfo.payloadText(),
        )
        assertEquals(PhoneMessages.updateNotifyJson(5), r.car.nextJson())

        // 3) VIDEO_SUP_REQ → VIDEO_SUP_RSP{3,1}; 4) VIDEO_ARGS → SPEECH_ARGS.
        r.car.send(CarMessages.videoSupReq(3))
        assertContentEquals(PhoneMessages.videoSupportRsp(3, 1), r.car.nextFrame().wireBytes())
        r.car.send(CarMessages.videoArgs(1920, 1080, 3, 30, 4_000_000, 1))
        assertContentEquals(PhoneMessages.speechArgs(), r.car.nextFrame().wireBytes())

        // Vídeo antes de VIDEO_CTRL{1}: rechazado; el SPS/PPS se guarda.
        assertFalse(r.session.sendFrame(idr, true, 0))
        assertTrue(r.session.sendCodecConfig(sps + pps))
        assertEquals(1, r.session.stats().videoFramesRejected)

        // 5) VIDEO_CTRL{1} → STREAMING y petición de IDR.
        r.car.send(CarMessages.videoCtrl(1))
        assertTrue(r.listener.streaming.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(TestSupport.waitUntil(2_000) { KeyframeReason.STREAM_START in r.listener.keyframeReasons })
        assertEquals(SessionState.STREAMING, r.session.state)
        assertEquals(
            listOf(SessionState.CAR_INFO, SessionState.VIDEO_SUPPORTED, SessionState.VIDEO_ARGS, SessionState.STREAMING),
            r.listener.states.toList(),
        )

        // Un P-frame antes del IDR se descarta; el IDR sale precedido del SPS/PPS guardado.
        assertFalse(r.session.sendFrame(pFrame, false, 1))
        assertTrue(r.session.sendFrame(idr, true, 2))
        assertTrue(r.session.sendFrame(pFrame, false, 3))
        val cfg = r.car.nextFrame()
        assertEquals(MsgType.VIDEO, cfg.header.msgType)
        assertContentEquals(sps + pps, cfg.payload())
        val ext = VideoExtHeader.decode(cfg.body, 0).params
        assertEquals(VideoParams(1920, 1080, 30, 4_000_000, 1, 3, appType = 1, angle = 90, orientation = 1), ext)
        assertContentEquals(VideoMessage.build(ext, idr), r.car.nextFrame().wireBytes())
        assertContentEquals(pFrame, r.car.nextFrame().payload())

        // KEY_FRAME_REQ → IDR pedido y SPS/PPS reenviado pegado a ese IDR (hql: nunca delante de un P, que el C10
        // reinicia el decodificador con cada SPS/PPS).
        r.car.send(CarMessages.keyFrameReq())
        assertTrue(TestSupport.waitUntil(2_000) { KeyframeReason.CAR_REQUEST in r.listener.keyframeReasons })
        assertTrue(r.session.sendFrame(pFrame, false, 4))
        assertTrue(r.session.sendFrame(idr, true, 5))
        assertContentEquals(pFrame, r.car.nextFrame().payload())
        assertContentEquals(sps + pps, r.car.nextFrame().payload())
        assertContentEquals(idr, r.car.nextFrame().payload())

        // LAND_MODE_REQ → LAND_MODE_RSP con eco de la orientación.
        r.car.send(CarMessages.landModeReq(2))
        assertEquals(PhoneMessages.landModeRspJson(2), r.car.nextJson())

        // Heartbeat legado → eco con ret = 1.
        r.car.send(BinBlock.legacyHeartbeatRequest())
        val echo = assertIs<WireMessage.Bin>(r.car.nextNonHeartbeat())
        assertEquals(1, echo.block.ret)

        val stats = r.session.stats()
        assertEquals(4, stats.videoFramesSent)
        assertEquals(2, stats.codecConfigsSent)
        assertEquals(2, stats.keyframesSent)
        assertEquals(1, stats.videoFramesDropped)

        // Cierre local: el coche ve EOF, onClosed llega el último y todos los hilos terminan.
        r.session.close()
        r.session.close()
        assertTrue(r.listener.awaitClosed(5_000))
        assertTrue(r.car.awaitEof(5_000))
        assertTrue(r.session.awaitTermination(5_000))
        assertEquals(CloseReason.Kind.LOCAL, r.listener.closeReason?.kind)
        assertEquals("closed:LOCAL", r.listener.events.last())
        assertTrue(TestSupport.liveThreads("qd-s${r.session.id}-").isEmpty())
        assertFalse(r.session.sendFrame(idr, true, 5))
    }

    @Test
    fun inputsAndUnknownMessagesAreSurfaced() {
        val r = rig()
        r.car.next() // AppStatus
        val touch = TouchCodec.build(0, listOf(TouchPointer.down(0, 12.5f, 34.25f), TouchPointer.down(1, 100f, 200f)))
        r.car.send(touch)
        r.car.send(CarMessages.phoneKeys(2))
        r.car.send(CarMessages.music(FunctionIds.NEXT))
        r.car.send(CarMessages.app("Global", "DarkModeOn", JsonObject.of("DarkModeOn" to 1)))
        r.car.send(CarMessages.control("FOO", JsonObject.of("x" to 1)))
        r.car.send(CarMessages.app("Weird", "Thing"))
        r.car.send("basura!".toByteArray())
        r.car.send(CarMessages.heartbeat())
        r.car.send(dev.qdauto.core.wire.Frames.binary(12, 0, byteArrayOf(1, 2, 3)))
        r.car.send(dev.qdauto.core.wire.Frames.binary(42, 1, byteArrayOf(1)))

        assertTrue(TestSupport.waitUntil(5_000) { r.session.stats().carHeartbeats == 1L && r.listener.unknown.size >= 4 })
        assertEquals(1, r.listener.touches.size)
        val t = r.listener.touches[0]
        assertEquals(0, t.action)
        assertEquals(listOf(TouchPointer.down(0, 12.5f, 34.25f), TouchPointer.down(1, 100f, 200f)), t.pointers)
        assertEquals(listOf(CarKey.BACK, CarKey.MEDIA_NEXT), r.listener.keys.toList())
        assertTrue("phoneKey:2" in r.listener.events)
        assertTrue("binary:12" in r.listener.events)
        val reasons = r.listener.unknown.map { it.reason }
        assertTrue(reasons.any { "FOO" in it }, "$reasons")
        assertTrue(reasons.any { "Weird/Thing" in it }, "$reasons")
        assertTrue(reasons.any { "magic" in it }, "$reasons")
        assertTrue(reasons.any { "msgType 42" in it }, "$reasons")
        assertEquals(4, r.listener.unknown.size)
        assertTrue(r.listener.apps.any { it.key == "Global/DarkModeOn" })
        // La traza incluye entrada y salida, con el JSON.
        assertTrue(r.listener.traces.any { it.direction == Direction.OUT && it.kind == "!BIN AppStatus" })
        assertTrue(r.listener.traces.any { it.direction == Direction.IN && it.kind == "FOO" && it.summary.contains("\"x\":1") })
        assertTrue(r.listener.traces.any { it.direction == Direction.IN && it.kind == "TOUCH" })
    }

    @Test
    fun heartbeatTimingAndWatchdog() {
        val config = SessionConfig(
            heartbeatInitialDelayMs = 50,
            heartbeatPeriodMs = 100,
            watchdogCheckIntervalMs = 25,
            watchdogWarnMs = 200,
            watchdogTimeoutMs = 600,
        )
        val r = rig(config)
        r.car.send(CarMessages.heartbeat()) // el coche "habla" 5A5A una vez y se calla
        assertTrue(r.listener.awaitClosed(5_000))
        assertEquals(CloseReason.Kind.WATCHDOG, r.listener.closeReason?.kind)
        assertEquals(1, r.listener.watchdogWarnings.size)
        assertTrue(r.listener.events.indexOf("watchdogWarning") < r.listener.events.indexOf("closed:WATCHDOG"))
        assertTrue(r.car.awaitEof(5_000))
        val heartbeats = FakeCar.heartbeatCount(r.car.drain())
        assertTrue(heartbeats >= 3, "heartbeats recibidos: $heartbeats") // 50 ms + cada 100 ms durante ~600 ms
        assertTrue(r.session.awaitTermination(5_000))
    }

    @Test
    fun watchdogDoesNotCutUntilCarSpeaks() {
        val r = rig(SessionConfig(heartbeatInitialDelayMs = 60_000, watchdogCheckIntervalMs = 20, watchdogWarnMs = 100, watchdogTimeoutMs = 200))
        assertTrue(TestSupport.waitUntil(3_000) { r.listener.watchdogWarnings.isNotEmpty() })
        Thread.sleep(400)
        assertFalse(r.session.isClosed) // como QDLink: sin tráfico 5A5A del coche, no corta
        r.car.send(CarMessages.heartbeat())
        assertTrue(r.listener.awaitClosed(3_000))
        assertEquals(CloseReason.Kind.WATCHDOG, r.listener.closeReason?.kind)
    }

    @Test
    fun watchdogCountsFromAcceptWhenCarTrafficIsNotRequired() {
        // hql: como el fork (SspSession): el coche abre el TCP y no dice nada; se corta igual, contando desde el accept.
        val config = SessionConfig(
            heartbeatInitialDelayMs = 60_000, watchdogCheckIntervalMs = 20, watchdogWarnMs = 100, watchdogTimeoutMs = 300,
            watchdogRequiresCarTraffic = false,
        )
        val started = System.nanoTime()
        val r = rig(config)
        assertTrue(r.listener.awaitClosed(3_000))
        assertEquals(CloseReason.Kind.WATCHDOG, r.listener.closeReason?.kind)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms >= 300, "cortó a los $ms ms")
    }

    @Test
    fun eofFromCarClosesSession() {
        val r = rig()
        r.car.next()
        r.car.close()
        assertTrue(r.listener.awaitClosed(5_000))
        assertEquals(CloseReason.Kind.EOF, r.listener.closeReason?.kind)
        assertTrue(r.session.awaitTermination(5_000))
    }

    @Test
    fun optionalRepliesAndWhitelist() {
        val config = SessionConfig(
            heartbeatInitialDelayMs = 60_000,
            whitelistInitialDelayMs = 20,
            whitelistPeriodMs = 50,
            btResultProvider = { if (it.needAutoConnect == 0) 1 else null },
            replyDisconnectReq = true,
            closeOnDisconnectReq = true,
        )
        val r = rig(config)
        r.car.next()
        r.car.send(carInfo1920x1080(mapOf("CarFeature" to JsonObject.of("legal_app_watch" to 1))))
        r.car.nextJson()
        r.car.nextJson()
        r.car.send(CarMessages.videoSupReq())
        assertEquals(PhoneMessages.videoSupportRspJson(), r.car.nextJson())
        // WhitelistAppOn periódico porque legal_app_watch == 1.
        repeat(3) { assertEquals(PhoneMessages.whitelistAppOnJson(1), r.car.nextJson()) }
        r.session.setWhitelistValue(0)
        assertTrue(TestSupport.waitUntil(2_000) { r.car.nextJson() == PhoneMessages.whitelistAppOnJson(0) })
        // BT_ADDR → BT_RESULT del proveedor de la app.
        r.car.send(CarMessages.btAddr("aa:bb:cc:dd:ee:ff", 0, 0))
        assertTrue(TestSupport.waitUntil(2_000) { r.car.nextJson() == PhoneMessages.btResultJson(1) })
        assertEquals("aa:bb:cc:dd:ee:ff", r.listener.btRequests.single().address)
        // DISCONNECT_REQ → DISCONNECT_RSP y cierre.
        r.car.send(CarMessages.disconnectReq())
        assertTrue(TestSupport.waitUntil(2_000) { r.car.nextJson() == PhoneMessages.disconnectRspJson(1) })
        assertTrue(r.listener.awaitClosed(5_000))
        assertEquals(CloseReason.Kind.DISCONNECT_REQ, r.listener.closeReason?.kind)
    }

    @Test
    fun videoOverridesAndPauseOption() {
        val r = rig(
            SessionConfig(
                heartbeatInitialDelayMs = 60_000,
                pauseOnVideoCtrlStop = true,
                videoOverrides = VideoOverrides(appType = 2, orientation = -1, angle = 0),
            ),
        )
        r.car.next()
        r.car.send(carInfo1920x1080())
        r.car.send(CarMessages.videoArgs(1920, 1080, 3, 60, 8_000_000, 2))
        r.car.send(CarMessages.videoCtrl(1))
        assertTrue(r.listener.streaming.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(EncoderSuggestion(1920, 1080, 60, 8_000_000, 2), r.session.encoderSuggestion)
        assertTrue(r.session.sendFrame(idr, true, 0))
        var f = r.car.nextFrame()
        while (f.header.msgType != MsgType.VIDEO) f = r.car.nextFrame()
        val p = VideoExtHeader.decode(f.body, 0).params
        assertEquals(VideoParams(1920, 1080, 60, 8_000_000, 2, 3, appType = 2, angle = 0, orientation = -1), p)
        assertEquals(0xFF.toByte(), f.wireBytes()[30])
        // VIDEO_CTRL{0} con pauseOnVideoCtrlStop → PAUSED y vídeo rechazado.
        r.car.send(CarMessages.videoCtrl(0))
        assertTrue(TestSupport.waitUntil(2_000) { r.session.state == SessionState.PAUSED })
        assertFalse(r.session.sendFrame(idr, true, 1))
        r.session.setVideoOverrides(VideoOverrides(width = 1280, height = 720))
        assertEquals(1280, r.session.videoParams.width)
        assertEquals(1, r.session.videoParams.appType)
    }

    @Test
    fun repeatedPlayResendsConfigWithoutGatingDeltas() {
        val r = rig()
        r.car.next()
        r.car.send(carInfo1920x1080())
        r.car.send(CarMessages.videoArgs(1920, 1080, 3, 30, 4_000_000, 1))
        r.car.send(CarMessages.videoCtrl(1))
        assertTrue(r.listener.streaming.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(r.session.sendCodecConfig(sps + pps))
        assertTrue(r.session.sendFrame(idr, true, 0))
        assertTrue(r.session.sendFrame(pFrame, false, 1))
        r.car.send(CarMessages.videoCtrl(1))
        assertTrue(TestSupport.waitUntil(2_000) { r.listener.keyframeReasons.count { it == KeyframeReason.STREAM_START } == 2 })
        assertTrue(r.session.sendFrame(pFrame, false, 2)) // no se corta: el coche ya tenía el flujo
        assertTrue(r.session.sendFrame(idr, true, 3))
        val video = generateSequence { r.car.nextFrame() }.filter { it.header.msgType == MsgType.VIDEO }.take(6).map { it.payload() }.toList()
        assertContentEquals(sps + pps, video[0])
        assertContentEquals(idr, video[1])
        assertContentEquals(pFrame, video[2])
        assertContentEquals(pFrame, video[3]) // hql: el SPS/PPS reenviado espera al IDR pedido
        assertContentEquals(sps + pps, video[4])
        assertContentEquals(idr, video[5])
        assertEquals(listOf(true, true), r.listener.playEvents.toList())
        assertEquals(SessionState.STREAMING, r.session.state)
    }

    @Test
    fun malformedHeadersAreSurfacedNotFatal() {
        val r = rig()
        r.car.next()
        val badExt = CarMessages.heartbeat().also { BE.putShort(it, 8, 500) }
        val badFormat = CarMessages.heartbeat().also { it[13] = 0 }
        r.car.send(badExt)
        r.car.send(badFormat)
        r.car.send(CarMessages.landModeReq(1))
        assertEquals(PhoneMessages.landModeRspJson(1), r.car.nextJson())
        assertTrue(TestSupport.waitUntil(2_000) { r.listener.unknown.size == 2 })
        assertTrue(r.listener.unknown.all { it.header != null && it.hexdump.startsWith("0000: 35 41 35 41") })
        assertFalse(r.session.isClosed)
        assertEquals(Header.SIZE + 19, r.listener.unknown[1].bytes.size)
        assertEquals(Cmd.LAND_MODE_REQ, r.listener.controls.last().cmd)
    }
}
