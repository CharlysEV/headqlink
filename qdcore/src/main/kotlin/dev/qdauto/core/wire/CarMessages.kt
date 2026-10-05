package dev.qdauto.core.wire

import dev.qdauto.core.json.JsonObject

/**
 * `CAR_INFO` (spec §6.5). Con `strict = true` se lee igual que QDLink (h0/c.java:166-200): las claves en orden y,
 * en cuanto falta una o tiene un tipo incompatible, las siguientes se quedan a 0/null y `ProjectID`/`CarUUID` a "".
 * [stoppedAt] dice dónde se habría parado el parser de QDLink (`null` si lo leyó todo).
 */
data class CarInfo(
    val version: String?,
    val carType: String?,
    val platform: Int,
    val platformVersion: String?,
    val carWidth: Int,
    val carHeight: Int,
    val carFactory: String?,
    val huFactory: String?,
    val mirrorTypeReq: Int,
    val projectId: String?,
    val carUuid: String?,
    val carFeature: JsonObject?,
    /** `CarFeature.legal_app_watch` con `getInt`, o 0 (LC/a.java:1044-1067). */
    val legalAppWatch: Int,
    val stoppedAt: String?,
) {
    companion object {
        fun parse(root: JsonObject?, strict: Boolean = true): CarInfo {
            val para = root?.obj("PARA")
            val r = SequentialReader(para, strict)
            val version = r.string("Version")
            val carType = r.string("CarType")
            val platform = r.int("Platform")
            val platformVersion = r.string("PlatformVersion")
            val carWidth = r.int("CarWidth")
            val carHeight = r.int("CarHeight")
            val carFactory = r.string("CarFactory")
            val huFactory = r.string("HUFactory")
            val mirrorTypeReq = r.int("MirrorTypeReq")
            var projectId: String? = null
            var carUuid: String? = null
            var carFeature: JsonObject? = null
            if (para != null && (!strict || r.ok)) {
                projectId = if (para.isNull("ProjectID")) "" else para.string("ProjectID")
                carUuid = if (para.isNull("CarUUID")) "" else para.string("CarUUID")
                if (!para.isNull("CarFeature")) {
                    carFeature = para.obj("CarFeature")
                    if (carFeature == null) r.fail("CarFeature")
                }
            }
            if (strict && !r.ok) {
                // QDLink: el catch de h0/c.java:194-198 pone ProjectID y CarUUID a "".
                projectId = ""
                carUuid = ""
            }
            return CarInfo(
                version = version,
                carType = carType,
                platform = platform ?: 0,
                platformVersion = platformVersion,
                carWidth = carWidth ?: 0,
                carHeight = carHeight ?: 0,
                carFactory = carFactory,
                huFactory = huFactory,
                mirrorTypeReq = mirrorTypeReq ?: 0,
                projectId = projectId,
                carUuid = carUuid,
                carFeature = carFeature,
                legalAppWatch = carFeature?.int("legal_app_watch") ?: 0,
                stoppedAt = r.stoppedAt,
            )
        }
    }
}

/**
 * `VIDEO_ARGS` (spec §6.9). Todas con `getInt` y en este orden; si falta una, las siguientes quedan a 0
 * (h0/c.java:283-297). Los valores se usan tal cual en la cabecera de vídeo (eco crudo, aunque sean 0).
 */
data class VideoArgs(
    val width: Int,
    val height: Int,
    val encodingType: Int,
    val frameRate: Int,
    /** bps (semántica de `MediaFormat.KEY_BIT_RATE`). */
    val bitRate: Int,
    /** Segundos para Android (`KEY_I_FRAME_INTERVAL`); no se sabe qué entiende el coche. */
    val frameInterval: Int,
    val stoppedAt: String?,
) {
    /** Lo que usaría QDLink para el encoder si el valor llega a 0 (c0/a.java:12-30). */
    val effectiveFrameRate: Int get() = if (frameRate > 0) frameRate else DEFAULT_FRAME_RATE
    val effectiveBitRate: Int get() = if (bitRate > 0) bitRate else DEFAULT_BIT_RATE
    val effectiveFrameInterval: Int get() = if (frameInterval > 0) frameInterval else DEFAULT_FRAME_INTERVAL

    companion object {
        const val DEFAULT_FRAME_RATE = 24
        const val DEFAULT_BIT_RATE = 2_764_800
        const val DEFAULT_FRAME_INTERVAL = 4

        fun parse(root: JsonObject?, strict: Boolean = true): VideoArgs {
            val r = SequentialReader(root?.obj("PARA"), strict)
            return VideoArgs(
                width = r.int("Width") ?: 0,
                height = r.int("Height") ?: 0,
                encodingType = r.int("EncodingType") ?: 0,
                frameRate = r.int("FrameRate") ?: 0,
                bitRate = r.int("BitRate") ?: 0,
                frameInterval = r.int("FrameInterval") ?: 0,
                stoppedAt = r.stoppedAt,
            )
        }
    }
}

/**
 * `BT_ADDR` (spec §6.8). QDLink: h0/c.java:152-164. Ante cualquier fallo `NeedAutoConnect` = 1.
 * Sin `BluetoothAddr` ([address] `null`) QDLink revienta con una NPE y no responde.
 */
data class BtAddrRequest(
    val address: String?,
    val status: Int,
    val needAutoConnect: Int,
    val stoppedAt: String?,
) {
    companion object {
        fun parse(root: JsonObject?): BtAddrRequest {
            val r = SequentialReader(root?.obj("PARA"), strict = true)
            val address = r.string("BluetoothAddr")
            val status = r.int("BluetoothStatus") ?: 0
            val need = r.int("NeedAutoConnect")
            return BtAddrRequest(address, status, if (r.ok) need ?: 1 else 1, r.stoppedAt)
        }
    }
}

/** Lectores de un solo campo `PARA.<clave>` con `getInt`, como QDLink (0 si falta). */
object CarParams {
    fun videoFormat(root: JsonObject?): Int = root?.obj("PARA")?.int("VideoFormat") ?: 0
    fun playStatus(root: JsonObject?): Int = root?.obj("PARA")?.int("PlayStatus") ?: 0
    fun orientation(root: JsonObject?): Int = root?.obj("PARA")?.int("Orientation") ?: 0
    fun phoneKeys(root: JsonObject?): Int = root?.obj("PARA")?.int("PhoneKeys") ?: 0

    /** msgType 13 `AudioSource/AudioSourceState` y `Global/DarkModeOn`: `Para.<clave>`. */
    fun appInt(root: JsonObject?, key: String): Int? = root?.obj("Para")?.int(key)
}

/** Teclas de alto nivel que pueden llegar del coche (PHONE_KEYS y msgType 13 `Music/…`). */
enum class CarKey {
    HOME, BACK, RECENTS,
    MEDIA_PLAY_PAUSE, MEDIA_PLAY, MEDIA_PAUSE, MEDIA_PREVIOUS, MEDIA_NEXT, MUTE_TOGGLE;

    companion object {
        /** PHONE_KEYS: 1 Home, 2 Atrás, 3 Recientes (LC/a.java:2055-2069). */
        fun fromPhoneKeys(code: Int): CarKey? = when (code) {
            1 -> HOME
            2 -> BACK
            3 -> RECENTS
            else -> null
        }

        /** `Music/<FunctionID>` (LC/a.java:2228-2317). */
        fun fromMusicFunction(functionId: String): CarKey? = when (functionId) {
            FunctionIds.PLAY_CONTROL -> MEDIA_PLAY_PAUSE
            FunctionIds.PLAY_CONTROL_PLAY -> MEDIA_PLAY
            FunctionIds.PLAY_CONTROL_PAUSE -> MEDIA_PAUSE
            FunctionIds.PREV -> MEDIA_PREVIOUS
            FunctionIds.NEXT -> MEDIA_NEXT
            FunctionIds.MUTE_CONTROL -> MUTE_TOGGLE
            else -> null
        }
    }
}

/**
 * Mensajes que manda el coche, para el simulador. El orden de claves del coche real es desconocido;
 * aquí se usa el natural `{"CMD":…,"PARA":{…}}`.
 */
object CarMessages {
    fun controlJson(cmd: String, para: JsonObject? = null): String =
        if (para == null) JsonObject.of("CMD" to cmd).toJson() else JsonObject.of("CMD" to cmd, "PARA" to para).toJson()

    fun control(cmd: String, para: JsonObject? = null): ByteArray = Frames.json(MsgType.CONTROL, controlJson(cmd, para))

    fun app(appId: String, functionId: String, para: JsonObject? = null): ByteArray {
        val root = if (para == null) {
            JsonObject.of("AppID" to appId, "FunctionID" to functionId)
        } else {
            JsonObject.of("AppID" to appId, "FunctionID" to functionId, "Para" to para)
        }
        return Frames.json(MsgType.APP, root.toJson())
    }

    fun heartbeat(): ByteArray = control(Cmd.HEARTBEAT)
    fun carInfo(para: JsonObject): ByteArray = control(Cmd.CAR_INFO, para)
    fun videoSupReq(videoFormat: Int = 3): ByteArray = control(Cmd.VIDEO_SUP_REQ, JsonObject.of("VideoFormat" to videoFormat))

    fun videoArgs(width: Int, height: Int, encodingType: Int, frameRate: Int, bitRate: Int, frameInterval: Int): ByteArray =
        control(
            Cmd.VIDEO_ARGS,
            JsonObject.of(
                "Width" to width, "Height" to height, "EncodingType" to encodingType,
                "FrameRate" to frameRate, "BitRate" to bitRate, "FrameInterval" to frameInterval,
            ),
        )

    fun videoCtrl(playStatus: Int): ByteArray = control(Cmd.VIDEO_CTRL, JsonObject.of("PlayStatus" to playStatus))
    fun keyFrameReq(): ByteArray = control(Cmd.KEY_FRAME_REQ)
    fun landModeReq(orientation: Int): ByteArray = control(Cmd.LAND_MODE_REQ, JsonObject.of("Orientation" to orientation))

    fun btAddr(address: String, status: Int, needAutoConnect: Int): ByteArray = control(
        Cmd.BT_ADDR,
        JsonObject.of("BluetoothAddr" to address, "BluetoothStatus" to status, "NeedAutoConnect" to needAutoConnect),
    )

    fun phoneKeys(code: Int): ByteArray = control(Cmd.PHONE_KEYS, JsonObject.of("PhoneKeys" to code))
    fun goInLinkApp(): ByteArray = control(Cmd.GO_IN_LINK_APP)
    fun disconnectReq(): ByteArray = control(Cmd.DISCONNECT_REQ)
    fun music(functionId: String): ByteArray = app(AppIds.MUSIC, functionId)
}

/** Lector secuencial con la semántica de los parsers de QDLink: el primer fallo detiene la lectura (si `strict`). */
internal class SequentialReader(private val para: JsonObject?, private val strict: Boolean) {
    var stoppedAt: String? = if (para == null) "PARA" else null
        private set
    val ok: Boolean get() = stoppedAt == null

    fun fail(key: String) {
        if (stoppedAt == null) stoppedAt = key
    }

    fun string(key: String): String? = read(key) { it.string(key) }
    fun int(key: String): Int? = read(key) { it.int(key) }

    private fun <T> read(key: String, get: (JsonObject) -> T?): T? {
        if (para == null || (strict && stoppedAt != null)) return null
        val v = get(para)
        if (v == null) fail(key)
        return v
    }
}
