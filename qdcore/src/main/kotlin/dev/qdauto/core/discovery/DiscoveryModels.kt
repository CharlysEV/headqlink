package dev.qdauto.core.discovery

import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.wire.UdpCodec
import java.net.InetAddress

/**
 * Configuración del descubrimiento UDP. Valores por defecto = QDLink (spec §1.1, §1.4).
 * [port]/[ackPort] solo se cambian en tests o con el simulador.
 */
data class DiscoveryConfig(
    /** Puerto local en el que se escuchan los `Connect_Broadcast` (QDLink: 18463, WF/a.java:30). */
    val port: Int = UdpCodec.PHONE_PORT,
    /** Puerto del coche al que se manda el ACK (QDLink: 18464, WF/a.java:31). */
    val ackPort: Int = UdpCodec.CAR_PORT,
    /** `null` = todas las interfaces (0.0.0.0), como QDLink. */
    val bindAddress: InetAddress? = null,
    /** QDLink usa 1024 B y trunca (WF/a.java:490); nosotros cogemos el datagrama entero para el log. */
    val receiveBufferSize: Int = 65_535,
    val reuseAddress: Boolean = true,
    /** QDLink no toca SO_BROADCAST; activarlo no cambia lo que se recibe y permite responder a broadcast si hiciera falta. */
    val broadcast: Boolean = true,
    /** Reenvío del ACK. Por defecto un solo envío, como QDLink: no se sabe qué hace el coche con ACKs repetidos (§11.2). */
    val ackPolicy: AckPolicy = AckPolicy.QDLINK,
    /** `DeviceName`/`DeviceUUID`/`PassistMobileNum` del ACK: QDLink los manda vacíos (IU/e.java:83-89). */
    val deviceName: String = "",
    val deviceUuid: String = "",
    val passistMobileNum: String = "",
)

/**
 * Política de reenvío del `Broadcast_ACK`.
 * QDLink lo manda una sola vez (WF/d.java:78-83); la spec (§1.4) sugiere, como opción, repetir el mismo ACK
 * cada 2 s desde los 3 s hasta los 20 s si no ha llegado la conexión TCP. Cancelar con [AckHandle.cancel] al aceptar.
 */
data class AckPolicy(
    val retries: Int = 0,
    val firstRetryDelayMs: Long = 3_000,
    val retryIntervalMs: Long = 2_000,
) {
    companion object {
        val QDLINK = AckPolicy()
        val RESEND_UNTIL_20S = AckPolicy(retries = 9, firstRetryDelayMs = 3_000, retryIntervalMs = 2_000)
    }
}

/** Envío de un ACK en curso (con sus reintentos programados). */
interface AckHandle {
    /** El datagrama exacto que se envía. */
    val bytes: ByteArray

    /** Envíos hechos hasta ahora. */
    val attempts: Int

    /** Para los reintentos pendientes (idempotente). Cuando vuelve, no hay ningún envío en curso ni habrá más. */
    fun cancel()
}

/**
 * Un coche descubierto. Se deduplica por (`DeviceUUID`, IP), como QDLink (WF/a.java:580-585);
 * [count] y [lastSeenMillis] se actualizan con cada broadcast.
 */
class CarAnnouncement(
    val address: InetAddress,
    val sourcePort: Int,
    val uuid: String,
    val name: String,
    val rawJson: String,
    val rawBytes: ByteArray,
    val json: JsonObject?,
    /** QDLink lo aceptaría (JSON en el offset 49 con `DeviceUUID` y `DeviceName`). */
    val qdlinkCompatible: Boolean,
    val warnings: List<String>,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val count: Int,
) {
    val host: String get() = address.hostAddress ?: address.toString()
    val key: String get() = keyOf(uuid, address)

    override fun toString(): String = "Coche('$name' uuid=$uuid ip=$host:$sourcePort visto ${count}x)"

    companion object {
        fun keyOf(uuid: String, address: InetAddress): String = "$uuid@${address.hostAddress}"
    }
}
