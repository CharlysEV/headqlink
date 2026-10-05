package dev.qdauto.core.session

import dev.qdauto.core.wire.AppMessage
import dev.qdauto.core.wire.BinBlock
import dev.qdauto.core.wire.BinaryMessage
import dev.qdauto.core.wire.BtAddrRequest
import dev.qdauto.core.wire.CarInfo
import dev.qdauto.core.wire.CarKey
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.TouchEvent
import dev.qdauto.core.wire.TraceEvent
import dev.qdauto.core.wire.UnknownMessage
import dev.qdauto.core.wire.VideoArgs

/**
 * Etapas de la sesión. Las del handshake solo avanzan (un `CAR_INFO` repetido no hace retroceder);
 * [STREAMING] ↔ [PAUSED] según `VIDEO_CTRL`; [CLOSED] es terminal.
 */
enum class SessionState {
    /** TCP aceptado; AppStatus y heartbeats en marcha. */
    CONNECTED,

    /** Llegó `CAR_INFO` (y se respondió `PHONE_INFO` + `UPDATE_NOTIFY`). */
    CAR_INFO,

    /** Se respondió `VIDEO_SUP_RSP` a un `VIDEO_SUP_REQ`. */
    VIDEO_SUPPORTED,

    /** Llegó `VIDEO_ARGS` (y se respondió `SPEECH_ARGS`). */
    VIDEO_ARGS,

    /** `VIDEO_CTRL{PlayStatus:1}`: el coche espera vídeo. */
    STREAMING,

    /** `VIDEO_CTRL{≠1}` con [SessionConfig.pauseOnVideoCtrlStop]. */
    PAUSED,

    CLOSED;

    val isVideoActive: Boolean get() = this == STREAMING
}

/** Por qué se pide un IDR al encoder. */
enum class KeyframeReason {
    /** `KEY_FRAME_REQ` del coche (además se reenvía SPS/PPS). */
    CAR_REQUEST,

    /** Empieza o se reanuda el vídeo: la emisión arranca en un IDR. */
    STREAM_START,

    /** Se descartaron frames por atasco: no se mandan P-frames hasta el siguiente IDR. */
    BACKLOG,

    /**
     * hql: se descartó un frame más grande que [SessionConfig.maxVideoMessageBytes] (el coche se colgaría con él): no se
     * mandan P-frames hasta el siguiente IDR, que tiene que salir **más pequeño**.
     */
    OVERSIZED,
}

/** hql: un frame descartado por pasar de [SessionConfig.maxVideoMessageBytes] ([SessionListener.onVideoFrameOversized]). */
data class OversizedFrame(
    /** Tamaño del mensaje que habría salido: 48 B de cabeceras + Annex-B. */
    val messageBytes: Int,
    val isKeyframe: Boolean,
    /** El tope ([SessionConfig.maxVideoMessageBytes]). */
    val limitBytes: Int,
    /** Frames descartados por tamaño en esta sesión, este incluido. */
    val count: Long,
)

data class CloseReason(val kind: Kind, val message: String, val error: Throwable? = null) {
    enum class Kind {
        /** `close()` local. */
        LOCAL,

        /** El coche cerró la conexión (fin de flujo). */
        EOF,
        READ_ERROR,
        WRITE_ERROR,

        /** Demasiado tiempo sin recibir nada del coche. */
        WATCHDOG,

        /** Un `write()` lleva bloqueado más de [SessionConfig.writeStallTimeoutMs]. */
        WRITE_STALL,

        /** `DISCONNECT_REQ` con [SessionConfig.closeOnDisconnectReq]. */
        DISCONNECT_REQ,
        INTERNAL_ERROR,

        /** hql (C3): el coche volvió a anunciarse con la sesión abierta, así que la dio por muerta ([PhoneLink]). */
        SUPERSEDED,
    }

    override fun toString(): String = "$kind: $message" + (error?.let { " ($it)" } ?: "")
}

/** Lo que conviene configurar en el encoder según `CAR_INFO` y `VIDEO_ARGS` (valores de QDLink si llegan a 0). */
data class EncoderSuggestion(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    /** bps. */
    val bitRate: Int,
    /** Segundos (`KEY_I_FRAME_INTERVAL`). */
    val iFrameIntervalSec: Int,
)

/** Foto de las estadísticas de la sesión. */
data class SessionStats(
    val state: SessionState,
    val uptimeMs: Long,
    val bytesSent: Long,
    val bytesReceived: Long,
    val messagesSent: Long,
    val messagesReceived: Long,
    val videoFramesSent: Long,
    val videoBytesSent: Long,
    val codecConfigsSent: Long,
    val keyframesSent: Long,
    /** Frames descartados por atasco o por esperar un IDR. */
    val videoFramesDropped: Long,
    /** Frames rechazados porque el coche aún no ha pedido vídeo (o está en pausa). */
    val videoFramesRejected: Long,
    val keyframeRequests: Long,
    /** Frames/s enviados en el último segundo. */
    val fps: Double,
    /** kbit/s de vídeo enviados en el último segundo. */
    val kbps: Double,
    val videoQueueFrames: Int,
    val videoQueueBytes: Long,
    val controlQueueDepth: Int,
    /** Tiempo desde el último byte recibido del coche. */
    val lastReceiveAgoMs: Long,
    /** Hueco más largo entre dos mensajes del coche. */
    val maxCarGapMs: Long,
    val carHeartbeats: Long,
    val lastCarHeartbeatIntervalMs: Long?,
    val touchEvents: Long,
    val garbageBytes: Long,
    /** hql: mensaje de vídeo más grande escrito en el socket (48 B de cabeceras + Annex-B). */
    val maxVideoMessageBytes: Long = 0,
    /** hql: frames descartados por pasar de [SessionConfig.maxVideoMessageBytes] (incluidos en [videoFramesDropped]). */
    val videoFramesOversized: Long = 0,
    /** hql: el más grande de esos descartes (mensaje entero), o 0. */
    val maxOversizedBytes: Long = 0,
    /** hql: `write()` bloqueados más de [SessionConfig.writeStallTimeoutMs] con el coche hablando (la sesión aguantó). */
    val writeStalls: Long = 0,
)

/**
 * Eventos de [PhoneSession]. Todos los métodos tienen implementación vacía. Se llaman **en orden y desde un único
 * hilo propio de la sesión** (`qd-sN-events`), nunca desde el lector, el escritor ni el hilo del encoder: se puede
 * hacer trabajo moderado en ellos sin frenar el protocolo, pero no bloquearlos indefinidamente.
 */
interface SessionListener {
    fun onStateChanged(from: SessionState, to: SessionState) {}

    /** `CAR_INFO` leído como QDLink, con el mensaje crudo ([ControlMessage.text]). */
    fun onCarInfo(info: CarInfo, message: ControlMessage) {}

    /** `VIDEO_SUP_REQ` (con `PARA.VideoFormat`, 0 si falta). */
    fun onVideoSupportRequest(videoFormat: Int, message: ControlMessage) {}

    fun onVideoArgs(args: VideoArgs, message: ControlMessage) {}

    /**
     * `VIDEO_CTRL`: `play` = `PlayStatus == 1`. Con `play` la app debe tener el encoder en marcha
     * (se emite además [onKeyframeRequested] con [KeyframeReason.STREAM_START]).
     */
    fun onVideoControl(play: Boolean, playStatus: Int, message: ControlMessage) {}

    /** Hay que pedir un IDR al encoder (`MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME`). */
    fun onKeyframeRequested(reason: KeyframeReason) {}

    /**
     * hql: se descartó un frame por pasar de [SessionConfig.maxVideoMessageBytes]. Llega antes que el
     * [onKeyframeRequested] con [KeyframeReason.OVERSIZED] que lo acompaña.
     */
    fun onVideoFrameOversized(frame: OversizedFrame) {}

    /** Táctil (msgType 2) con todos los dedos y valores crudos. */
    fun onTouch(event: TouchEvent) {}

    /** Tecla interpretada: `PHONE_KEYS` 1/2/3 y msgType 13 `Music/…`. */
    fun onKey(key: CarKey) {}

    /** `PHONE_KEYS` con el código crudo (también los desconocidos). */
    fun onPhoneKey(code: Int, message: ControlMessage) {}

    fun onLandModeRequest(orientation: Int, message: ControlMessage) {}
    fun onBtAddr(request: BtAddrRequest, message: ControlMessage) {}
    fun onDisconnectRequest(message: ControlMessage) {}
    fun onGoInLinkApp(message: ControlMessage) {}

    /** Todos los mensajes de control (msgType 0), conocidos o no, antes de su callback específico. */
    fun onControlMessage(message: ControlMessage) {}

    /** Todos los mensajes msgType 13. */
    fun onAppMessage(message: AppMessage) {}

    /** msgType 12 (voz) y 99 (personalizado). */
    fun onBinaryMessage(message: BinaryMessage) {}

    /** Bloques `!BIN` del coche (el heartbeat legado se contesta solo). */
    fun onLegacyMessage(block: BinBlock) {}

    /** `CMD`/AppID desconocidos, msgType o payLoadFormat inesperados, `!BIN` no soportados y bytes basura. */
    fun onUnknownMessage(message: UnknownMessage) {}

    /** El coche lleva [silentMs] sin mandar nada (una vez por silencio). */
    fun onWatchdogWarning(silentMs: Long) {}

    /** Cada mensaje que entra o sale (el vídeo, resumido). */
    fun onTrace(event: TraceEvent) {}

    /** Último evento de la sesión; se llama una sola vez. */
    fun onClosed(reason: CloseReason) {}
}
