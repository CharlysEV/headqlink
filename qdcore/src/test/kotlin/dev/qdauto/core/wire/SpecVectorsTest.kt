package dev.qdauto.core.wire

import dev.qdauto.core.TestSupport.fromDump
import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.session.PhoneIdentity
import dev.qdauto.core.session.PhoneInfoFactory
import dev.qdauto.core.util.BE
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Vectores byte a byte sacados de docs/protocol/04-wire-spec.md. */
class SpecVectorsTest {

    /** §5.1 */
    @Test
    fun heartbeat() {
        val expected = fromDump(
            """
            0000: 35 41 35 41 00 00 00 23  00 00 00 00 00 01 00 00  |5A5A...#........|
            0010: 7b 22 43 4d 44 22 3a 22  48 45 41 52 54 42 45 41  |{"CMD":"HEARTBEA|
            0020: 54 22 7d                                          |T"}|
            """,
        )
        assertEquals(35, expected.size)
        assertContentEquals(expected, PhoneMessages.heartbeat())
        val h = Header.decode(expected)
        assertEquals(Header(totalSize = 35, extLen = 0, msgType = 0, payloadFormat = 1), h)
        assertContentEquals(expected.copyOf(16), h.encode())
    }

    /** §4.2 con SDK_INT = 36. */
    @Test
    fun appStatusBlock() {
        val head = fromDump(
            """
            0000: 21 42 49 4e 00 00 00 00  00 00 02 00 00 00 02 00  |!BIN............|
            0010: 00 00 00 40 00 00 00 80  00 00 00 80 00 00 00 02  |...@............|
            0020: 20 21 22 23 24 25 26 27  28 29 2a 2b 2c 2d 2e 2f  | !"#${'$'}%&'()*+,-./|
            0030: 30 31 32 33 34 35 36 37  38 39 3a 3b 3c 3d 3e 3f  |0123456789:;<=>?|
            0040: 00 00 00 00 00 00 00 01  00 00 00 01 00 00 00 24  |...............${'$'}|
            0050: 00 00 00 02 00 00 00 00  00 00 00 00 00 00 00 00  |................|
            """,
        )
        val expected = head + ByteArray(512 - head.size)
        val block = PhoneMessages.appStatus(36)
        assertEquals(512, block.size)
        assertContentEquals(expected, block)
        val parsed = BinBlock(block)
        assertEquals(0, parsed.dataType)
        assertEquals(2, parsed.action)
        assertEquals(1, parsed.cmd)
        assertEquals(1, parsed.value)
    }

    /** §1.4: ACK de QDLink con P = 34567 (174 B). */
    @Test
    fun broadcastAck() {
        val text = "QDrive_SSPLink_UDP_MSG00AE0DBroadcast_ACK0081" +
            """{"ControlPort":0,"MirrorPort":34567,"AudioPort":0,"OS":0,"DeviceName":"","DeviceUUID":"","DeviceFeature":{"PassistMobileNum":""}}"""
        val dump = fromDump(
            """
            0000: 51 44 72 69 76 65 5f 53  53 50 4c 69 6e 6b 5f 55  |QDrive_SSPLink_U|
            0010: 44 50 5f 4d 53 47 30 30  41 45 30 44 42 72 6f 61  |DP_MSG00AE0DBroa|
            0020: 64 63 61 73 74 5f 41 43  4b 30 30 38 31 7b 22 43  |dcast_ACK0081{"C|
            0030: 6f 6e 74 72 6f 6c 50 6f  72 74 22 3a 30 2c 22 4d  |ontrolPort":0,"M|
            0040: 69 72 72 6f 72 50 6f 72  74 22 3a 33 34 35 36 37  |irrorPort":34567|
            0050: 2c 22 41 75 64 69 6f 50  6f 72 74 22 3a 30 2c 22  |,"AudioPort":0,"|
            0060: 4f 53 22 3a 30 2c 22 44  65 76 69 63 65 4e 61 6d  |OS":0,"DeviceNam|
            0070: 65 22 3a 22 22 2c 22 44  65 76 69 63 65 55 55 49  |e":"","DeviceUUI|
            0080: 44 22 3a 22 22 2c 22 44  65 76 69 63 65 46 65 61  |D":"","DeviceFea|
            0090: 74 75 72 65 22 3a 7b 22  50 61 73 73 69 73 74 4d  |ture":{"PassistM|
            00a0: 6f 62 69 6c 65 4e 75 6d  22 3a 22 22 7d 7d        |obileNum":""}}|
            """,
        )
        val built = UdpCodec.buildBroadcastAck(34567)
        assertEquals(174, built.size)
        assertContentEquals(text.toByteArray(), built)
        assertContentEquals(dump, built)
        val parsed = UdpCodec.parseBroadcastAck(built)!!
        assertEquals(34567, parsed.mirrorPort)
        assertEquals("", parsed.deviceName)
        // Con cualquier puerto de 5 cifras mide siempre 174 B.
        assertEquals(174, UdpCodec.buildBroadcastAck(10001).size)
        assertEquals(174, UdpCodec.buildBroadcastAck(65535).size)
    }

    /** §1.3: ejemplo hipotético de Connect_Broadcast (101 B). */
    @Test
    fun connectBroadcast() {
        val dump = fromDump(
            """
            0000: 51 44 72 69 76 65 5f 53  53 50 4c 69 6e 6b 5f 55  |QDrive_SSPLink_U|
            0010: 44 50 5f 4d 53 47 30 30  36 35 31 31 43 6f 6e 6e  |DP_MSG006511Conn|
            0020: 65 63 74 5f 42 72 6f 61  64 63 61 73 74 30 30 33  |ect_Broadcast003|
            0030: 34 7b 22 44 65 76 69 63  65 55 55 49 44 22 3a 22  |4{"DeviceUUID":"|
            0040: 30 31 32 33 34 35 36 37  38 39 61 62 63 64 65 66  |0123456789abcdef|
            0050: 22 2c 22 44 65 76 69 63  65 4e 61 6d 65 22 3a 22  |","DeviceName":"|
            0060: 43 31 30 22 7d                                    |C10"}|
            """,
        )
        assertEquals(101, dump.size)
        assertContentEquals(dump, UdpCodec.buildConnectBroadcast("0123456789abcdef", "C10"))
        val m = UdpCodec.parse(dump)
        assertEquals(UdpCodec.CONNECT_BROADCAST, m.type)
        assertTrue(m.envelopeOk, m.warnings.toString())
        assertTrue(m.qdlinkCompatible)
        assertEquals(0x65, m.declaredTotal)
        assertEquals(0x11, m.declaredTypeLength)
        assertEquals(0x34, m.declaredJsonLength)
        assertEquals("0123456789abcdef", m.json!!.string("DeviceUUID"))
        assertEquals("C10", m.json!!.string("DeviceName"))
    }

    /** §6.2: VIDEO_SUP_RSP (81 B) y WhitelistAppOn (92 B). */
    @Test
    fun videoSupportRspAndWhitelist() {
        val sup = fromDump(
            """
            0000: 35 41 35 41 00 00 00 51  00 00 00 00 00 01 00 00  |5A5A...Q........|
            0010: 7b 22 50 41 52 41 22 3a  7b 22 56 69 64 65 6f 46  |{"PARA":{"VideoF|
            0020: 6f 72 6d 61 74 22 3a 33  2c 22 56 69 64 65 6f 53  |ormat":3,"VideoS|
            0030: 75 70 70 6f 72 74 22 3a  31 7d 2c 22 43 4d 44 22  |upport":1},"CMD"|
            0040: 3a 22 56 49 44 45 4f 5f  53 55 50 5f 52 53 50 22  |:"VIDEO_SUP_RSP"|
            0050: 7d                                                |}|
            """,
        )
        assertContentEquals(sup, PhoneMessages.videoSupportRsp(3, 1))
        val white = fromDump(
            """
            0000: 35 41 35 41 00 00 00 5c  00 00 0d 00 00 01 00 00  |5A5A...\........|
            0010: 7b 22 46 75 6e 63 74 69  6f 6e 49 44 22 3a 22 57  |{"FunctionID":"W|
            0020: 68 69 74 65 6c 69 73 74  41 70 70 4f 6e 22 2c 22  |hitelistAppOn","|
            0030: 41 70 70 49 44 22 3a 22  4d 69 72 72 6f 72 22 2c  |AppID":"Mirror",|
            0040: 22 50 61 72 61 22 3a 7b  22 57 68 69 74 65 6c 69  |"Para":{"Whiteli|
            0050: 73 74 41 70 70 4f 6e 22  3a 31 7d 7d              |stAppOn":1}}|
            """,
        )
        assertContentEquals(white, PhoneMessages.whitelistAppOn(1))
    }

    /** §6.2: JSON exacto y totalSize de cada mensaje del teléfono. */
    @Test
    fun everyPhoneControlMessage() {
        fun check(bytes: ByteArray, json: String, total: Int, msgType: Int = MsgType.CONTROL) {
            assertEquals(total, bytes.size, json)
            assertEquals(Header(totalSize = total, msgType = msgType, payloadFormat = 1), Header.decode(bytes))
            assertEquals(json, String(bytes, 16, bytes.size - 16, Charsets.UTF_8))
        }
        check(PhoneMessages.updateNotify(5), """{"PARA":{"UpdateStatus":5},"CMD":"UPDATE_NOTIFY"}""", 65)
        check(
            PhoneMessages.speechArgs(),
            """{"PARA":{"SampleRate":16000,"ChannelConfig":1,"EncodingType":1,"AudioFormat":16},"CMD":"SPEECH_ARGS"}""",
            117,
        )
        check(PhoneMessages.landModeRsp(orientation = 1), """{"PARA":{"Authority":1,"StatusArg":0,"Orientation":1},"CMD":"LAND_MODE_RSP"}""", 92)
        check(PhoneMessages.btResult(2), """{"PARA":{"Result":2},"CMD":"BT_RESULT"}""", 55)
        check(PhoneMessages.lockScreenStatus(1), """{"PARA":{"LockScreenStatus":1},"CMD":"LOCK_SCREEN_STATUS"}""", 74)
        check(PhoneMessages.carAppBackground(), """{"CMD":"CAR_APP_BACKGROUND"}""", 44)
        check(PhoneMessages.carAppForeground(), """{"CMD":"CAR_APP_FOREGROUND"}""", 44)
        check(PhoneMessages.speechCtrl(1), """{"PARA":{"SpeechStatus":1},"CMD":"SPEECH_CTRL"}""", 63)
        check(PhoneMessages.speechCtrl(0), """{"PARA":{"SpeechStatus":0},"CMD":"SPEECH_CTRL"}""", 63)
        check(PhoneMessages.disconnectRsp(1), """{"PARA":{"CanDisconnect":1},"CMD":"DISCONNECT_RSP"}""", 67)
        check(
            PhoneMessages.phoneInfoChange(PhoneInfoChange(2340, 1080, 1920, 886, 1920, 1080, 1920, 1080)),
            """{"PARA":{"MirrorHeight":886,"MirrorWidth":1920,"PhoneHeightInApp":1080,"PhoneHeight":1080,"MirrorWidthInApp":1920,""" +
                """"PhoneWidth":2340,"PhoneWidthInApp":1920,"MirrorHeightInApp":1080},"CMD":"PHONE_INFO_CHANGE"}""",
            223,
        )
        check(PhoneMessages.playState(1), """{"FunctionID":"PlayState","AppID":"Music","Para":{"PlayState":1}}""", 81, MsgType.APP)
        check(PhoneMessages.whitelistAppOn(1), """{"FunctionID":"WhitelistAppOn","AppID":"Mirror","Para":{"WhitelistAppOn":1}}""", 92, MsgType.APP)
    }

    /** §6.4: PHONE_INFO de un S25 Ultra FHD+ con un CAR_INFO hipotético de 1920×1080 (412 B de JSON, totalSize 428). */
    @Test
    fun phoneInfo() {
        val expectedJson = """{"PARA":{"PhoneName":"","PlatformVersion":"36","PhoneModel":"SM-S938B","Platform":0,"PhoneSystemTime":0,""" +
            """"PhoneHeightInApp":1080,"MirrorWidthInApp":1920,"PhoneWidthInApp":1920,"MirrorHeightInApp":1080,"MirrorTypeSupport":0,""" +
            """"PhoneFeature":{"PassistMobileNum":""},"PhoneUUID":"","Version":"1.9.7","MirrorHeight":886,"MirrorWidth":1920,""" +
            """"PhoneBrand":"samsung","PhoneHeight":1080,"PhoneWidth":2340},"CMD":"PHONE_INFO"}"""
        assertEquals(412, expectedJson.length)
        val carInfo = CarInfo.parse(
            JsonObject.of(
                "CMD" to "CAR_INFO",
                "PARA" to JsonObject.of(
                    "Version" to "1", "CarType" to "2D4", "Platform" to 0, "PlatformVersion" to "1", "CarWidth" to 1920,
                    "CarHeight" to 1080, "CarFactory" to "018", "HUFactory" to "119", "MirrorTypeReq" to 0,
                ),
            ),
        )
        val info = PhoneInfoFactory.build(carInfo, PhoneIdentity(screenLongSide = 2340, screenShortSide = 1080, model = "SM-S938B", sdkInt = 36))
        assertEquals(expectedJson, PhoneMessages.phoneInfoJson(info))
        val msg = PhoneMessages.phoneInfo(info)
        assertEquals(428, msg.size)
        assertContentEquals(fromDump("35 41 35 41 00 00 01 ac  00 00 00 00 00 01 00 00"), msg.copyOf(16))
    }

    /** §8.8: SPS+PPS en modo espejo (64 B). */
    @Test
    fun videoCodecConfigMirror() {
        val expected = fromDump(
            """
            0000: 35 41 35 41 00 00 00 40  00 20 01 00 00 02 00 00  |5A5A...@. ......|
            0010: 00 20 01 00 00 00 07 80  00 00 03 76 00 5a 01 03  |. .........v.Z..|
            0020: 00 00 00 1e 00 3d 09 00  00 00 00 01 02 00 00 00  |.....=..........|
            0030: 00 00 00 01 67 42 c0 29  00 00 00 01 68 ce 3c 80  |....gB.)....h.<.|
            """,
        )
        val params = VideoParams(1920, 886, fps = 30, bitrate = 4_000_000, gop = 1, encodingType = 3, appType = 2, angle = 90, orientation = 1)
        val payload = expected.copyOfRange(48, 64)
        assertContentEquals(expected, VideoMessage.build(params, payload))
        assertContentEquals(expected, VideoMessage.build(params, java.nio.ByteBuffer.wrap(payload)))
        val ext = VideoExtHeader.decode(expected, 16)
        assertEquals(params, ext.params)
        assertEquals(32, ext.extLength)
        assertEquals(1, ext.version)
    }

    /** §8.8: IDR in-app con 12 345 B de payload (totalSize 0x3069). */
    @Test
    fun videoIdrInApp() {
        val head = fromDump(
            """
            0000: 35 41 35 41 00 00 30 69  00 20 01 00 00 02 00 00  |5A5A..0i. ......|
            0010: 00 20 01 00 00 00 07 80  00 00 04 38 00 5a 01 03  |. .........8.Z..|
            0020: 00 00 00 1e 00 3d 09 00  00 00 00 01 01 00 00 00  |.....=..........|
            0030: 00 00 00 01 65
            """,
        )
        val payload = ByteArray(12_345).also { head.copyInto(it, 0, 48, 53) }
        val params = VideoParams(1920, 1080, fps = 30, bitrate = 4_000_000, gop = 1, encodingType = 3, appType = 1, angle = 90, orientation = 1)
        // Variante sin copia extra: el payload ya está en su sitio y se rellenan las cabeceras.
        val msg = ByteArray(VideoMessage.HEADER_SIZE + payload.size)
        payload.copyInto(msg, VideoMessage.HEADER_SIZE)
        VideoMessage.writeHeaders(msg, payload.size, params)
        assertEquals(12_393, msg.size)
        assertContentEquals(head, msg.copyOf(53))
        assertContentEquals(msg, VideoMessage.build(params, payload))
    }

    /** §8.8: cabecera ext justo al entrar en espejo con el teléfono en vertical (orientación FF, ángulo 0). */
    @Test
    fun videoExtHeaderOrientationFF() {
        val ext = fromDump("00 20 01 00 00 00 07 80 00 00 03 76 00 00 ff 03 00 00 00 1e 00 3d 09 00 00 00 00 01 02 00 00 00")
        val params = VideoParams(1920, 886, fps = 30, bitrate = 4_000_000, gop = 1, encodingType = 3, appType = 2, angle = 0, orientation = -1)
        val out = ByteArray(32)
        VideoMessage.writeExt(out, 0, params)
        assertContentEquals(ext, out)
        assertEquals(params, VideoExtHeader.decode(ext, 0).params)
    }

    /** §9.4: un dedo (31 B) y dos dedos (41 B). */
    @Test
    fun touchMessages() {
        val one = fromDump(
            """
            0000: 35 41 35 41 00 00 00 1f  00 00 02 00 00 00 00 00  |5A5A............|
            0010: 00 00 00 00 01 00 01 44  70 00 00 44 07 00 00     |.......Dp..D...|
            """,
        )
        assertContentEquals(one, TouchCodec.build(0, listOf(TouchPointer.down(0, 960f, 540f))))
        val e1 = TouchCodec.parse(one, 16, one.size - 16)!!
        assertEquals(0, e1.action)
        assertEquals(1, e1.declaredCount)
        assertEquals(listOf(TouchPointer(0, TouchCodec.FINGER_DOWN, 960f, 540f)), e1.pointers)
        assertEquals(false, e1.truncated)
        assertEquals(0, e1.extraBytes)

        val two = fromDump(
            """
            0000: 35 41 35 41 00 00 00 29  00 00 02 00 00 00 00 00  |5A5A...)........|
            0010: 00 00 00 02 02 00 03 42  c9 00 00 43 48 40 00 01  |.......B...CH@..|
            0020: 03 43 96 00 00 43 c8 00  00                       |.C...C...|
            """,
        )
        val pointers = listOf(TouchPointer.move(0, 100.5f, 200.25f), TouchPointer.move(1, 300f, 400f))
        assertContentEquals(two, TouchCodec.build(2, pointers))
        val e2 = TouchCodec.parse(two, 16, two.size - 16)!!
        assertEquals(2, e2.action)
        assertEquals(pointers, e2.pointers)
        assertEquals(Header(totalSize = 41, msgType = 2, payloadFormat = 0), Header.decode(two))
    }

    @Test
    fun touchEdgeCasesLikeQdlink() {
        // N = 0: QDLink lo descarta (get(0) del log); fingerId 0x80 es negativo (byte con signo).
        val zero = TouchCodec.parse(byteArrayOf(0, 0, 0, 1, 0))!!
        assertTrue(zero.wouldQdlinkDrop)
        assertTrue(zero.pointers.isEmpty())
        val payload = TouchCodec.encodePayload(1, listOf(TouchPointer(0x80.toByte().toInt(), 2, 1f, 2f)))
        assertEquals(-128, TouchCodec.parse(payload)!!.pointers[0].id)
        // N = 2 pero solo hay un dedo: truncado.
        val short = TouchCodec.encodePayload(1, listOf(TouchPointer.up(0, 1f, 2f))).also { it[4] = 2 }
        val t = TouchCodec.parse(short)!!
        assertTrue(t.truncated)
        assertEquals(1, t.pointers.size)
        // Bytes de sobra: se ignoran pero se cuentan.
        assertEquals(3, TouchCodec.parse(payload + byteArrayOf(1, 2, 3))!!.extraBytes)
        assertEquals(null, TouchCodec.parse(byteArrayOf(0, 0, 0)))
    }

    /** §4.3: el heartbeat legado se devuelve con ret = 1 y el resto igual. */
    @Test
    fun legacyHeartbeatEcho() {
        val req = BinBlock.legacyHeartbeatRequest()
        assertTrue(BinBlock(req).isLegacyHeartbeatRequest)
        val reply = BinBlock.legacyHeartbeatReply(req)
        assertContentEquals(req.copyOf(76), reply.copyOf(76))
        assertEquals(1, BE.getInt(reply, 192))
        assertTrue(reply.copyOfRange(76, 192).all { it == 0.toByte() })
        assertTrue(reply.copyOfRange(196, 512).all { it == 0.toByte() })
        assertEquals(1, BinBlock(reply).action) // action sigue siendo 1
    }

    /**
     * §6.1: el orden de claves que hardcodeamos es el de iteración de `java.util.HashMap` (OpenJDK, igual que Android ≥ 7)
     * insertando los campos del bean en orden alfabético (orden dex de `getDeclaredFields`).
     */
    @Test
    fun keyOrderMatchesHashMapIteration() {
        fun hashMapOrder(vararg keys: String): List<String> = HashMap<String, Int>().apply { keys.sorted().forEach { put(it, 0) } }.keys.toList()
        val phoneInfo = PhoneInfoFactory.build(null, PhoneIdentity()).toPara().keys.toList()
        assertEquals(hashMapOrder(*phoneInfo.toTypedArray()), phoneInfo)
        val change = PhoneInfoChange(1, 2, 3, 4, 5, 6, 7, 8).toPara().keys.toList()
        assertEquals(hashMapOrder(*change.toTypedArray()), change)
        assertEquals(hashMapOrder("PARA", "CMD"), listOf("PARA", "CMD"))
        assertEquals(hashMapOrder("AppID", "FunctionID", "Para"), listOf("FunctionID", "AppID", "Para"))
        assertEquals(hashMapOrder("AudioFormat", "ChannelConfig", "EncodingType", "SampleRate"), listOf("SampleRate", "ChannelConfig", "EncodingType", "AudioFormat"))
        assertEquals(hashMapOrder("Authority", "Orientation", "StatusArg"), listOf("Authority", "StatusArg", "Orientation"))
        assertEquals(hashMapOrder("VideoFormat", "VideoSupport"), listOf("VideoFormat", "VideoSupport"))
    }
}
