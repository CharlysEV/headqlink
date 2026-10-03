package com.andrerinas.openheadunit.decoder.video

/**
 * c10link: grifo del vídeo de Android Auto. Cada unidad H.264/H.265 ensamblada que llega a
 * [VideoDecoder.decode] se entrega también a [sink] (reenvío directo al coche por SSPLink).
 * Con [bypassDecoder] el decodificador local no la procesa (sin pantalla en el móvil).
 */
object VideoTap {
    fun interface Sink {
        fun onAccessUnit(buffer: ByteArray, offset: Int, size: Int)
    }

    @JvmStatic
    @Volatile
    var sink: Sink? = null

    @JvmStatic
    @Volatile
    var bypassDecoder: Boolean = false

    /**
     * Freno a Android Auto. Con una puerta puesta, la confirmación (MediaAck) del mensaje que
     * completa un frame entregado a [sink] no sale al acabar de procesarlo: se entrega a la puerta,
     * que la suelta cuando el frame ha salido hacia el coche. Si [AckGate.hold] devuelve false, se
     * confirma en el acto.
     */
    fun interface AckGate {
        fun hold(release: Runnable): Boolean
    }

    @JvmStatic
    @Volatile
    var ackGate: AckGate? = null

    /** Ventana max_unacked de vídeo anunciada a AA; 0 = la de Open Headunit. */
    @JvmStatic
    @Volatile
    var videoWindow: Int = 0

    /** Ventana de vídeo que se anunció a AA en su última conexión (0 = aún ninguna). */
    @JvmStatic
    @Volatile
    var announcedWindow: Int = 0

    /** Marca de tiempo de AA (µs) del frame en curso, o -1 si el mensaje no la trae. Hilo de vídeo. */
    @JvmStatic
    @Volatile
    var frameTimestampUs: Long = -1

    /** Hilo de vídeo: se ha entregado un frame a [sink] durante el mensaje en curso. */
    @Volatile
    internal var delivered: Boolean = false

    internal fun takeDelivered(): Boolean {
        val d = delivered
        delivered = false
        return d
    }

    /** Modo C10 sin pantalla: no se abre la vista de proyección en el móvil. */
    @JvmStatic
    @Volatile
    var headless: Boolean = false
}
