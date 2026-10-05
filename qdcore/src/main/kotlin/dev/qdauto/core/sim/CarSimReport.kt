package dev.qdauto.core.sim

import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.wire.AppMessage
import dev.qdauto.core.wire.BinBlock
import dev.qdauto.core.wire.BroadcastAck
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.TouchPointer
import dev.qdauto.core.wire.TraceEvent
import dev.qdauto.core.wire.VideoExtHeader
import java.net.InetSocketAddress

enum class CarSimState { IDLE, DISCOVERING, CONNECTING, HANDSHAKE, STREAMING, CLOSED }

/** Un mensaje táctil enviado por el simulador. */
data class SentTouch(val action: Int, val pointers: List<TouchPointer>)

/** Eventos del simulador. Se llaman desde sus hilos internos: no bloquearlos. */
interface CarSimListener {
    fun onStateChanged(state: CarSimState) {}
    fun onAck(ack: BroadcastAck, from: InetSocketAddress) {}
    fun onAppStatus(block: BinBlock, errors: List<String>) {}
    fun onPhoneControl(message: ControlMessage) {}
    fun onPhoneApp(message: AppMessage) {}
    fun onVideoFrame(info: VideoFrameInfo) {}
    fun onTrace(event: TraceEvent) {}
    fun onClosed(reason: String) {}
}

/** Foto del estado del simulador y de todo lo que ha validado. */
data class CarSimReport(
    val state: CarSimState,
    val broadcastsSent: Int,
    val ackFrom: String?,
    val ackJson: String?,
    val mirrorPort: Int?,
    val connectedTo: String?,
    /** AppStatus `!BIN` recibidos y problemas encontrados en ellos. */
    val appStatusReceived: Int,
    val appStatusSdkInt: Int?,
    val appStatusErrors: List<String>,
    /** Mensajes del teléfono por tipo (`CMD`, `AppID/FunctionID`, `VIDEO`, `!BIN`…). */
    val phoneMessageCounts: Map<String, Int>,
    /** Orden en que llegaron los primeros mensajes de control/app del teléfono (hasta 50). */
    val phoneMessageOrder: List<String>,
    val lastPhoneInfo: JsonObject?,
    val phoneHeartbeats: Int,
    /** Intervalos entre heartbeats del teléfono (últimos 20). */
    val phoneHeartbeatIntervalsMs: List<Long>,
    val videoMessages: Int,
    val codecConfigMessages: Int,
    val idrFrames: Int,
    val pFrames: Int,
    val videoPayloadBytes: Long,
    val firstVideoKind: VideoKind?,
    val lastVideoHeader: VideoExtHeader?,
    val videoErrorCount: Int,
    val videoErrors: List<String>,
    /** Mensajes del teléfono que el simulador no esperaba. */
    val unexpected: List<String>,
    val touchesSent: Int,
    val keysSent: Int,
    val handshakeErrors: List<String>,
    val closeReason: String?,
) {
    /** Hubo vídeo, el primero fue SPS/PPS, hubo IDR y ningún error de validación. */
    val videoValid: Boolean
        get() = videoMessages > 0 && firstVideoKind == VideoKind.CONFIG && idrFrames > 0 && videoErrorCount == 0

    fun summary(): String = buildString {
        appendLine("estado=$state broadcasts=$broadcastsSent ack=$ackFrom MirrorPort=$mirrorPort conectado=$connectedTo")
        appendLine("AppStatus=$appStatusReceived (SDK $appStatusSdkInt) errores=$appStatusErrors")
        appendLine("mensajes del teléfono=$phoneMessageCounts")
        appendLine("orden=$phoneMessageOrder")
        appendLine("heartbeats=$phoneHeartbeats intervalos=$phoneHeartbeatIntervalsMs")
        appendLine(
            "vídeo: $videoMessages mensajes (config=$codecConfigMessages IDR=$idrFrames P=$pFrames, $videoPayloadBytes B) " +
                "primero=$firstVideoKind válido=$videoValid errores=$videoErrorCount",
        )
        lastVideoHeader?.let { appendLine("última cabecera: $it") }
        videoErrors.take(10).forEach { appendLine("  ! $it") }
        if (unexpected.isNotEmpty()) appendLine("inesperados=$unexpected")
        if (handshakeErrors.isNotEmpty()) appendLine("handshake=$handshakeErrors")
        appendLine("táctiles=$touchesSent teclas=$keysSent cierre=$closeReason")
    }
}
