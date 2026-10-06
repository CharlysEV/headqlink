package dev.qdauto.core.session

/**
 * hql (C2): qué hace la sesión cuando el vídeo no sale tan rápido como llega. En todas se descarta hasta el primer IDR
 * tras `VIDEO_CTRL{1}` y se mantiene la válvula de memoria de [SessionConfig.videoQueueHardLimitBytes].
 */
enum class VideoDropPolicy {
    /**
     * La validada en el coche: al encolar, con ≥ [SessionConfig.videoBacklogFrames] frames o
     * ≥ [SessionConfig.videoBacklogBytes] bytes en cola se tiran los P-frames encolados y el entrante, se espera un
     * IDR y se pide uno.
     */
    BACKLOG,

    /**
     * Al sacar de la cola: si la cabeza es un P-frame que lleva más de [SessionConfig.videoMaxLagMs] en cola, se tira
     * todo el vídeo encolado, se espera un IDR (con SPS/PPS delante) y se pide uno forzado. Para encoders propios.
     */
    MAX_LAG,

    /** No se descarta nada por atasco (reenvío directo de un vídeo ajeno, que no puede dar un IDR al momento). */
    NONE,
}

/**
 * hql (C2): foto barata (sin candados) de la E/S de la sesión. Los instantes son `System.nanoTime()`.
 * Para consultas frecuentes (puerta de vídeo, detector de cortes); [PhoneSession.stats] es la completa.
 */
class IoSnapshot(
    val atNanos: Long,
    /** Último byte recibido del coche (o el arranque de la sesión). */
    val lastReceiveNanos: Long,
    val bytesReceived: Long,
    /** Inicio del `write()` en curso, o 0 si no hay ninguno. */
    val writingSinceNanos: Long,
    /** Qué se está escribiendo (p. ej. `VIDEO_IDR`), o `null`. */
    val writingLabel: String?,
    val writingBytes: Int,
    /** Fin del último `write()` completo (0 = ninguno aún). */
    val lastWriteEndNanos: Long,
    val bytesSent: Long,
    val videoQueueFrames: Int,
    val videoQueueBytes: Long,
    val controlQueue: Int,
    /**
     * hql: antigüedad (ms) del vídeo más viejo que aún no ha salido entero: la cabeza de la cola o el mensaje de vídeo
     * que se está escribiendo; 0 si no hay. Sin cola del kernel que mirar (USB), es la medida del atasco.
     */
    val videoQueueLagMs: Long = 0,
) {
    val receiveSilenceMs: Long get() = (atNanos - lastReceiveNanos) / 1_000_000
    val writingForMs: Long get() = if (writingSinceNanos == 0L) 0 else (atNanos - writingSinceNanos) / 1_000_000

    override fun toString(): String =
        "IoSnapshot(rx hace ${receiveSilenceMs} ms, $bytesReceived B recibidos, " +
            (if (writingSinceNanos != 0L) "escribiendo $writingLabel ($writingBytes B) hace $writingForMs ms, " else "") +
            "$bytesSent B enviados, cola vídeo $videoQueueFrames frames/$videoQueueBytes B ($videoQueueLagMs ms), control $controlQueue)"
}
