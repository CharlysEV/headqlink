package dev.qdauto.core.wire

import dev.qdauto.core.util.Hex

enum class Direction { IN, OUT }

/**
 * Una línea de traza por cada mensaje que entra o sale.
 * - [kind]: `CMD` del control, `AppID/FunctionID`, `TOUCH`, `VIDEO_CONFIG`/`VIDEO_IDR`/`VIDEO_P`, `!BIN …`, `GARBAGE`…
 * - [summary]: el JSON (recortado), el táctil decodificado, un resumen del frame o un prefijo hex.
 * - [bytes]: el mensaje completo tal y como viajó, salvo en vídeo (`null`, para no copiar megas por segundo).
 */
class TraceEvent(
    val timeMillis: Long,
    val direction: Direction,
    val kind: String,
    val msgType: Int?,
    val size: Int,
    val summary: String,
    val isVideo: Boolean,
    val bytes: ByteArray?,
) {
    override fun toString(): String = "${if (direction == Direction.IN) "<-" else "->"} $kind ($size B) $summary"
}

/** Construcción de [TraceEvent] común a la sesión del teléfono y al simulador. */
object Traces {
    fun ofJson(direction: Direction, kind: String, msgType: Int, wire: ByteArray, json: String, maxChars: Int): TraceEvent =
        TraceEvent(System.currentTimeMillis(), direction, kind, msgType, wire.size, clip(json, maxChars), false, wire)

    fun ofBinary(direction: Direction, kind: String, msgType: Int?, wire: ByteArray, summary: String, hexPrefix: Int): TraceEvent =
        TraceEvent(
            System.currentTimeMillis(), direction, kind, msgType, wire.size,
            if (hexPrefix > 0) "$summary | ${Hex.prefix(wire, maxBytes = hexPrefix)}" else summary,
            false, wire,
        )

    fun ofVideo(direction: Direction, kind: String, size: Int, summary: String): TraceEvent =
        TraceEvent(System.currentTimeMillis(), direction, kind, MsgType.VIDEO, size, summary, true, null)

    /** Clasificación y resumen de un frame 5A5A cualquiera (lo usan el simulador y la sesión). */
    fun ofFrame(direction: Direction, frame: WireMessage.Frame, maxChars: Int, hexPrefix: Int): TraceEvent {
        val h = frame.header
        return when (h.msgType) {
            MsgType.CONTROL, MsgType.APP -> {
                val text = frame.payloadText()
                val kind = if (h.msgType == MsgType.CONTROL) {
                    ControlMessage.parse(h, text).cmd.ifEmpty { "CONTROL?" }
                } else {
                    AppMessage.parse(h, text).key
                }
                ofJson(direction, kind, h.msgType, frame.wireBytes(), text, maxChars)
            }
            MsgType.TOUCH -> {
                val p = frame.payloadOffset
                val t = TouchCodec.parse(frame.body, p, frame.payloadLength)
                ofBinary(direction, "TOUCH", h.msgType, frame.wireBytes(), t?.describe() ?: "táctil ilegible", 0)
            }
            MsgType.VIDEO -> ofVideo(direction, "VIDEO", h.totalSize, h.describe())
            else -> ofBinary(direction, MsgType.name(h.msgType), h.msgType, frame.wireBytes(), h.describe(), hexPrefix)
        }
    }

    fun clip(s: String, maxChars: Int): String = if (s.length <= maxChars) s else s.take(maxChars) + "… (+${s.length - maxChars} car.)"
}
