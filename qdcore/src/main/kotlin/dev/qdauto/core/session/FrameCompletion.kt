package dev.qdauto.core.session

/** hql (C2): cómo terminó un frame entregado a [PhoneSession.sendFrame] con un [FrameCompletion]. */
enum class FrameOutcome {
    /** `write()` + `flush()` completos. */
    WRITTEN,

    /** Política de vídeo: atasco, retraso (`MAX_LAG`), esperando un IDR o válvula de memoria. */
    DROPPED,

    /** El coche aún no ha pedido vídeo (sin `VIDEO_CTRL{1}`) o está en pausa. */
    REJECTED,

    /** La sesión se cerró con el frame en cola, o ya estaba cerrada. */
    CLOSED,

    /** Falló el `write()` que lo escribía. */
    FAILED,
}

/** Lo que se sabe de un frame al terminar. Los instantes son `System.nanoTime()`. */
class FrameDone(
    val outcome: FrameOutcome,
    val isKeyframe: Boolean,
    /** Annex-B, sin las cabeceras 16 + 32. */
    val payloadBytes: Int,
    val ptsUs: Long,
    /** 0 si no llegó a encolarse. */
    val enqueuedNanos: Long,
    /** 0 si no se escribió. */
    val writeStartNanos: Long,
    /** 0 si no se escribió entero. */
    val writeEndNanos: Long,
    /** Frames de vídeo en cola en ese momento. */
    val queuedFramesAfter: Int,
) {
    override fun toString(): String =
        "FrameDone($outcome key=$isKeyframe $payloadBytes B pts=$ptsUs cola=$queuedFramesAfter)"
}

/**
 * hql (C2): aviso de finalización de un frame. Se llama **exactamente una vez** por cada `sendFrame` que lo lleva:
 * - si `sendFrame` devuelve `false`, ya se ha llamado (en el mismo hilo y antes de volver) con `REJECTED`, `DROPPED` o
 *   `CLOSED`;
 * - si devuelve `true`, se llama después con cualquiera de los cinco resultados.
 *
 * Nunca se llama con candados internos tomados. Hilo: el productor (rechazo o descarte al encolar), el escritor
 * (`WRITTEN`, `FAILED` o descarte por retraso al sacar de la cola) o el que cierra la sesión (`CLOSED`). Tiene que ser
 * rápido y no bloquear; si lanza una excepción, se registra y no afecta a la sesión.
 */
fun interface FrameCompletion {
    fun onFrameDone(done: FrameDone)
}
