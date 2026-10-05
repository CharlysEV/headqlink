package dev.qdauto.core.sim

/**
 * Cómo se juzga un mensaje SPS/PPS recibido. El C10 (visto el 2026-10-05) reinicia su decodificador con cada
 * `VIDEO_CONFIG`, aunque sea idéntico al anterior: cada uno de más produce artefactos visibles.
 */
enum class CodecConfigVerdict {
    /** El primero de la conexión (o tras una reconexión): obligatorio. */
    FIRST,

    /** Precede a un IDR que el coche había pedido con `KEY_FRAME_REQ`: correcto. */
    REQUESTED,

    /** Precede a un IDR que nadie pidió: reinicio del decodificador evitable (aviso). */
    UNREQUESTED,

    /** No le sigue un IDR (sino un P-frame u otro SPS/PPS): reinicio del decodificador sin imagen de referencia (fallo). */
    NO_IDR,

    /** Todavía no ha llegado nada detrás (fin de la sesión). */
    PENDING,
}

/** Un mensaje SPS/PPS recibido y su contexto. */
data class CodecConfigEvent(
    /** Índice entre los mensajes de vídeo de la sesión (0 = primero). */
    val messageIndex: Int,
    /** Es el primero de la conexión. */
    val first: Boolean,
    /** ms desde el SPS/PPS anterior (`null` en el primero). */
    val sinceLastMs: Long?,
    /** Había un `KEY_FRAME_REQ` del coche pendiente (enviado después del SPS/PPS o IDR anterior). */
    val requested: Boolean,
    /** Bytes idénticos a los del SPS/PPS anterior. */
    val sameAsPrevious: Boolean,
    /** Qué mensaje de vídeo llegó justo después (`null` = ninguno todavía). */
    val followedBy: VideoKind?,
) {
    val verdict: CodecConfigVerdict
        get() = when {
            first -> CodecConfigVerdict.FIRST
            followedBy == null -> CodecConfigVerdict.PENDING
            followedBy != VideoKind.IDR -> CodecConfigVerdict.NO_IDR
            requested -> CodecConfigVerdict.REQUESTED
            else -> CodecConfigVerdict.UNREQUESTED
        }
}

/** Resumen de los SPS/PPS de una conexión, para las comprobaciones de carsim y qdsim. */
data class CodecConfigSummary(val events: List<CodecConfigEvent>) {
    val count: Int get() = events.size
    val intervalsMs: List<Long> get() = events.mapNotNull { it.sinceLastMs }
    val identical: Int get() = events.count { it.sameAsPrevious }
    val noIdr: List<CodecConfigEvent> get() = events.filter { it.verdict == CodecConfigVerdict.NO_IDR }
    val unrequested: List<CodecConfigEvent> get() = events.filter { it.verdict == CodecConfigVerdict.UNREQUESTED }
    val pending: List<CodecConfigEvent> get() = events.filter { it.verdict == CodecConfigVerdict.PENDING }

    /** Alguno sin IDR detrás: el coche reinició el decodificador sin imagen de referencia. */
    val failed: Boolean get() = noIdr.isNotEmpty()

    /** Alguno de más delante de un IDR que nadie pidió. */
    val warned: Boolean get() = !failed && unrequested.isNotEmpty()

    /** Texto corto en español con el recuento, los intervalos y lo que haya que señalar. */
    fun describe(): String {
        if (events.isEmpty()) return "ningún SPS/PPS"
        val parts = ArrayList<String>()
        parts += if (count == 1) "1 SPS/PPS" else "$count SPS/PPS"
        val ivs = intervalsMs
        if (ivs.isNotEmpty()) parts += "cada ${ivs.min()}-${ivs.max()} ms"
        if (identical > 0) parts += "$identical ${if (identical == 1) "idéntico" else "idénticos"} al anterior"
        if (noIdr.isNotEmpty()) parts += "${noIdr.size} sin IDR detrás (mensajes ${noIdr.joinToString(", ") { "#${it.messageIndex} seguido de ${it.followedBy}" }})"
        if (unrequested.isNotEmpty()) parts += "${unrequested.size} delante de un IDR que no se pidió con KEY_FRAME_REQ (mensajes ${unrequested.joinToString(", ") { "#${it.messageIndex}" }})"
        val requested = events.count { it.verdict == CodecConfigVerdict.REQUESTED }
        if (requested > 0) parts += "$requested tras KEY_FRAME_REQ"
        if (pending.isNotEmpty()) parts += "${pending.size} al final sin nada detrás"
        return parts.joinToString(", ")
    }
}

/**
 * Sigue los SPS/PPS de una conexión para la comprobación `sps_repetido`: cuánto distan entre sí, si son idénticos,
 * si el coche había pedido un IDR (`KEY_FRAME_REQ`) y qué llegó justo después de cada uno. Un `KEY_FRAME_REQ` se
 * consume con el siguiente SPS/PPS o IDR. No es thread-safe: lo protege [PhoneObserver].
 */
class CodecConfigTracker(private val clock: () -> Long = System::nanoTime) {
    private val events = ArrayList<CodecConfigEvent>()
    private var pendingRequest = false
    private var lastConfigNanos = 0L
    private var lastConfigBytes: ByteArray? = null

    /** Índice en [events] del último SPS/PPS cuyo `followedBy` está sin resolver, o -1. */
    private var open = -1

    val count: Int get() = events.size

    fun noteKeyframeRequest() {
        pendingRequest = true
    }

    /** [configBytes]: el payload Annex-B si [info] es SPS/PPS (para compararlo con el anterior). */
    fun onVideo(info: VideoFrameInfo, configBytes: ByteArray? = null) {
        if (open >= 0) {
            events[open] = events[open].copy(followedBy = info.kind)
            open = -1
        }
        when (info.kind) {
            VideoKind.CONFIG -> {
                val now = clock()
                val first = events.isEmpty()
                events += CodecConfigEvent(
                    messageIndex = info.index,
                    first = first,
                    sinceLastMs = if (first) null else (now - lastConfigNanos) / 1_000_000,
                    requested = pendingRequest,
                    sameAsPrevious = !first && configBytes != null && lastConfigBytes?.contentEquals(configBytes) == true,
                    followedBy = null,
                )
                open = events.size - 1
                pendingRequest = false
                lastConfigNanos = now
                lastConfigBytes = configBytes
            }
            VideoKind.IDR -> pendingRequest = false
            else -> Unit
        }
    }

    fun events(): List<CodecConfigEvent> = events.toList()

    fun summary(): CodecConfigSummary = CodecConfigSummary(events())
}
