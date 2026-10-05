package dev.qdauto.core.sim

import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.wire.FrameReader
import dev.qdauto.core.wire.UdpCodec
import java.io.File
import java.net.InetAddress

/**
 * `CAR_INFO` que manda el simulador. Por defecto, una suposición del C10: `CarFactory` 018, `CarType` 2D4 y
 * `HUFactory` 119 salen de la BD de QDLink (RES/res/raw/linkmanager.db3, filas 167-168); el resto es inventado.
 */
data class CarInfoValues(
    val version: String = "1.0",
    val carType: String = "2D4",
    val platform: Int = 1,
    val platformVersion: String = "1.0",
    val carWidth: Int = 1920,
    val carHeight: Int = 1080,
    val carFactory: String = "018",
    val huFactory: String = "119",
    val mirrorTypeReq: Int = 0,
    /** `null` = la clave no se manda. */
    val projectId: String? = null,
    val carUuid: String? = null,
    /** `null` = sin `CarFeature`; si no, `CarFeature{"legal_app_watch":n}`. */
    val legalAppWatch: Int? = null,
) {
    /** `PARA` con las claves en el orden en que las lee QDLink (h0/c.java:166-200). */
    fun toPara(): JsonObject {
        val m = LinkedHashMap<String, Any?>()
        m["Version"] = version
        m["CarType"] = carType
        m["Platform"] = platform
        m["PlatformVersion"] = platformVersion
        m["CarWidth"] = carWidth
        m["CarHeight"] = carHeight
        m["CarFactory"] = carFactory
        m["HUFactory"] = huFactory
        m["MirrorTypeReq"] = mirrorTypeReq
        projectId?.let { m["ProjectID"] = it }
        carUuid?.let { m["CarUUID"] = it }
        legalAppWatch?.let { m["CarFeature"] = JsonObject.of("legal_app_watch" to it) }
        return dev.qdauto.core.json.Json.toValue(m) as JsonObject
    }
}

/** `VIDEO_ARGS` del simulador; `width`/`height` `null` = los de [CarInfoValues]. */
data class VideoArgsValues(
    val width: Int? = null,
    val height: Int? = null,
    val encodingType: Int = 3,
    val frameRate: Int = 30,
    val bitRate: Int = 4_000_000,
    val frameInterval: Int = 1,
)

/** Configuración de [CarSim]. Puertos y destino configurables para probar en local. */
data class CarSimConfig(
    /** Destino del `Connect_Broadcast`: broadcast limitado por defecto; en local, 127.0.0.1. */
    val broadcastAddress: InetAddress = InetAddress.getByName("255.255.255.255"),
    /** Puerto UDP del teléfono (18463). */
    val phoneDiscoveryPort: Int = UdpCodec.PHONE_PORT,
    /** Puerto UDP local en el que el simulador espera el ACK (18464); también es el origen del broadcast. */
    val carAckPort: Int = UdpCodec.CAR_PORT,
    val broadcastIntervalMs: Long = 1_000,
    /** Seguir emitiendo broadcasts después del ACK (no se sabe si el coche real lo hace). */
    val keepBroadcasting: Boolean = false,
    val discoveryTimeoutMs: Long = 60_000,
    val deviceUuid: String = "QDAUTO-SIM-0001",
    val deviceName: String = "C10-SIM",
    /** Claves extra del JSON del broadcast (el JSON real del C10 es desconocido). */
    val broadcastExtra: JsonObject? = null,
    /** Host al que conectar por TCP; `null` = la IP origen del ACK (lo que se supone que hace el coche). */
    val connectHost: String? = null,
    val connectTimeoutMs: Int = 5_000,
    val carInfo: CarInfoValues = CarInfoValues(),
    val videoArgs: VideoArgsValues = VideoArgsValues(),
    /** `VIDEO_SUP_REQ.PARA.VideoFormat`. */
    val videoFormat: Int = 3,
    /** Esperar la respuesta del teléfono antes de cada paso del handshake (si no, solo [stepDelayMs]). */
    val waitForReplies: Boolean = true,
    val replyTimeoutMs: Long = 5_000,
    val stepDelayMs: Long = 0,
    /** Mandar `VIDEO_CTRL{PlayStatus:1}` al final del handshake. */
    val sendPlay: Boolean = true,
    /** Heartbeats del coche: formato y periodo reales desconocidos; QDLink corta si pasan > 5 s sin nada. */
    val heartbeatEnabled: Boolean = true,
    val heartbeatPeriodMs: Long = 2_000,
    /** Si se da, se escribe ahí el Annex-B recibido (reproducible con `ffplay -f h264`). */
    val recordVideoTo: File? = null,
    /** Validar W×H de la cabecera; `null` = par(CarWidth)×par(CarHeight) (modo in-app). 0 = no validar. */
    val expectedWidth: Int? = null,
    val expectedHeight: Int? = null,
    /** Validar appType (1 in-app, 2 espejo); `null` = no validar. */
    val expectedAppType: Int? = 1,
    /** Comprobar que encodingType/fps/bitrate/GOP de la cabecera son el eco de `VIDEO_ARGS`. */
    val expectVideoArgsEcho: Boolean = true,
    val maxMessageBytes: Int = FrameReader.DEFAULT_MAX_MESSAGE_SIZE,
    val traceMaxJsonChars: Int = 4_096,
    /** hql (C4): descartar los N primeros `Broadcast_ACK` y seguir anunciándose (para probar el re-ACK del teléfono). */
    val ignoreAcks: Int = 0,
)
