package dev.qdauto.core.wire

import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.json.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsersTest {
    private fun root(para: String?): JsonObject =
        JsonParser.parseObject(if (para == null) """{"CMD":"X"}""" else """{"CMD":"X","PARA":$para}""")

    @Test
    fun carInfoFull() {
        val info = CarInfo.parse(
            root(
                """{"Version":"2.0","CarType":"2D4","Platform":1,"PlatformVersion":"9","CarWidth":"1920","CarHeight":720,
                   "CarFactory":"018","HUFactory":119,"MirrorTypeReq":2,"ProjectID":"P","CarUUID":null,"CarFeature":{"legal_app_watch":1}}""",
            ),
        )
        assertEquals("2.0", info.version)
        assertEquals("2D4", info.carType)
        assertEquals(1920, info.carWidth) // getInt acepta cadenas numéricas
        assertEquals(720, info.carHeight)
        assertEquals("119", info.huFactory) // getString acepta números
        assertEquals(2, info.mirrorTypeReq)
        assertEquals("P", info.projectId)
        assertEquals("", info.carUuid) // isNull → ""
        assertEquals(1, info.legalAppWatch)
        assertNull(info.stoppedAt)
    }

    @Test
    fun carInfoStopsAtFirstMissingKeyLikeQdlink() {
        val info = CarInfo.parse(root("""{"Version":"2.0","CarType":"2D4","PlatformVersion":"9","CarWidth":1920,"CarHeight":720,"ProjectID":"P"}"""))
        assertEquals("Platform", info.stoppedAt)
        assertEquals("2D4", info.carType)
        assertEquals(0, info.carWidth) // no se llega a leer
        assertEquals("", info.projectId)
        assertEquals("", info.carUuid)
        // En modo no estricto cada clave se lee por separado.
        val lenient = CarInfo.parse(root("""{"Version":"2.0","CarType":"2D4","PlatformVersion":"9","CarWidth":1920,"CarHeight":720,"ProjectID":"P"}"""), strict = false)
        assertEquals(1920, lenient.carWidth)
        assertEquals("P", lenient.projectId)
        assertEquals("Platform", lenient.stoppedAt)
    }

    @Test
    fun carInfoEdgeCases() {
        val noPara = CarInfo.parse(root(null))
        assertEquals("PARA", noPara.stoppedAt)
        assertEquals("", noPara.projectId)
        val unparseable = CarInfo.parse(null)
        assertEquals(0, unparseable.carWidth)
        // CarFeature que no es objeto: excepción en QDLink → ProjectID/CarUUID vuelven a "".
        val badFeature = CarInfo.parse(
            root(
                """{"Version":"1","CarType":"2D4","Platform":1,"PlatformVersion":"9","CarWidth":1920,"CarHeight":720,"CarFactory":"018",
                   "HUFactory":"119","MirrorTypeReq":0,"ProjectID":"P","CarUUID":"U","CarFeature":"x"}""",
            ),
        )
        assertEquals("CarFeature", badFeature.stoppedAt)
        assertEquals("", badFeature.projectId)
        assertEquals(1920, badFeature.carWidth)
        assertEquals(0, badFeature.legalAppWatch)
    }

    @Test
    fun videoArgsAndOthers() {
        val args = VideoArgs.parse(root("""{"Width":1920,"Height":720,"EncodingType":3,"FrameRate":30,"BitRate":8000000,"FrameInterval":2}"""))
        assertEquals(VideoArgs(1920, 720, 3, 30, 8_000_000, 2, null), args)
        val partial = VideoArgs.parse(root("""{"Width":1920,"EncodingType":3,"FrameRate":30}"""))
        assertEquals("Height", partial.stoppedAt)
        assertEquals(0, partial.frameRate) // las siguientes a la que falta se quedan a 0
        assertEquals(24, partial.effectiveFrameRate)
        assertEquals(2_764_800, partial.effectiveBitRate)
        assertEquals(4, partial.effectiveFrameInterval)

        assertEquals(BtAddrRequest("aa:bb", 1, 0, null), BtAddrRequest.parse(root("""{"BluetoothAddr":"aa:bb","BluetoothStatus":1,"NeedAutoConnect":0}""")))
        assertEquals(1, BtAddrRequest.parse(root("""{"BluetoothAddr":"aa:bb","BluetoothStatus":1}""")).needAutoConnect)
        val noAddr = BtAddrRequest.parse(root("""{"BluetoothStatus":1,"NeedAutoConnect":0}"""))
        assertNull(noAddr.address)
        assertEquals(1, noAddr.needAutoConnect)
        assertEquals(0, noAddr.status)

        assertEquals(1, CarParams.playStatus(root("""{"PlayStatus":1}""")))
        assertEquals(0, CarParams.playStatus(root(null)))
        assertEquals(2, CarParams.orientation(root("""{"Orientation":2}""")))
        assertEquals(3, CarParams.phoneKeys(root("""{"PhoneKeys":"3"}""")))
        assertEquals(3, CarParams.videoFormat(root("""{"VideoFormat":3}""")))
        assertEquals(CarKey.BACK, CarKey.fromPhoneKeys(2))
        assertEquals(CarKey.MEDIA_NEXT, CarKey.fromMusicFunction("Next"))
        assertNull(CarKey.fromPhoneKeys(9))
    }

    @Test
    fun controlAndAppMessagesTolerateGarbage() {
        val h = Header(totalSize = 0, msgType = 0, payloadFormat = 1)
        val ok = ControlMessage.parse(h, "{\"CMD\":\"VIDEO_CTRL\",\"PARA\":{\"PlayStatus\":1}}\u0000")
        assertEquals("VIDEO_CTRL", ok.cmd)
        assertNull(ok.parseError)
        val bad = ControlMessage.parse(h, "not json")
        assertEquals("", bad.cmd)
        assertTrue(bad.parseError != null)
        val noCmd = ControlMessage.parse(h, "{\"PARA\":{}}")
        assertEquals("", noCmd.cmd)
        val app = AppMessage.parse(h, """{"AppID":"Music","FunctionID":"Next"}""")
        assertEquals("Music/Next", app.key)
        assertNull(app.para)
    }

    @Test
    fun udpParserIsTolerant() {
        val json = """{"DeviceUUID":"u1","DeviceName":"C10","Extra":1}"""
        // Longitudes en minúsculas: QDLink no las mira; nosotros tampoco protestamos.
        val lower = ("QDrive_SSPLink_UDP_MSG" + "%04x".format(32 + 17 + json.length) + "11Connect_Broadcast" + "%04x".format(json.length) + json).toByteArray()
        val m1 = UdpCodec.parse(lower)
        assertTrue(m1.envelopeOk)
        assertEquals("u1", m1.json!!.string("DeviceUUID"))

        // Longitudes falsas: se recurre al offset 49.
        val wrong = ("QDrive_SSPLink_UDP_MSG" + "FFFF" + "11Connect_Broadcast" + "0001" + json).toByteArray()
        val m2 = UdpCodec.parse(wrong)
        assertFalse(m2.envelopeOk)
        assertEquals("C10", m2.json!!.string("DeviceName"))
        assertTrue(m2.qdlinkCompatible)

        // Relleno delante y un \0 detrás: se localiza el magic y se ignora la cola.
        val padded = byteArrayOf(0, 0) + UdpCodec.buildConnectBroadcast("u2", "N") + byteArrayOf(0)
        val m3 = UdpCodec.parse(padded)
        assertEquals("u2", m3.json!!.string("DeviceUUID"))
        assertEquals(2, m3.magicOffset)
        assertFalse(m3.qdlinkCompatible) // QDLink usa el offset 49 absoluto

        // Sin magic y sin JSON.
        val m4 = UdpCodec.parse("hola".toByteArray())
        assertNull(m4.type)
        assertNull(m4.json)
        assertTrue(m4.warnings.isNotEmpty())

        // Sin DeviceName: QDLink lo rechaza.
        assertFalse(UdpCodec.parse(UdpCodec.encode(UdpCodec.CONNECT_BROADCAST, """{"DeviceUUID":"x"}""")).qdlinkCompatible)
        assertNull(UdpCodec.parseBroadcastAck(UdpCodec.buildConnectBroadcast("a", "b")))
    }

    @Test
    fun hexHelperUsesUppercase() {
        assertEquals("00AE", UdpCodec.hex(174, 4))
        assertEquals("0D", UdpCodec.hex(13, 2))
        assertEquals("1FFFF", UdpCodec.hex(0x1FFFF, 4))
    }
}
