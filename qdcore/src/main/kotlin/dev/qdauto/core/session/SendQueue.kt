package dev.qdauto.core.session

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class OutKind { CONTROL, VIDEO_CONFIG, VIDEO_KEY, VIDEO_DELTA }

/** Un mensaje completo listo para un único `write()`. */
internal class Outgoing(
    val bytes: ByteArray,
    val kind: OutKind,
    /** `CMD`, `AppID/FunctionID`, `!BIN …` o `VIDEO_*`, para la traza. */
    val label: String,
    val msgType: Int?,
    /** JSON del mensaje (solo control), para la traza. */
    val text: String? = null,
    /** Cerrar la sesión justo después de escribirlo. */
    val closeAfter: Boolean = false,
    val ptsUs: Long = -1,
    val enqueuedNanos: Long = System.nanoTime(),
    /** hql (C2): aviso de finalización (solo frames de vídeo). */
    val completion: FrameCompletion? = null,
    /** hql (C2): bytes de Annex-B del frame (sin las cabeceras 16 + 32). */
    val payloadBytes: Int = 0,
) {
    val isVideo: Boolean get() = kind != OutKind.CONTROL
    val isFrame: Boolean get() = kind == OutKind.VIDEO_KEY || kind == OutKind.VIDEO_DELTA

    /** Llegó a meterse en la cola (si no, `FrameDone.enqueuedNanos` = 0). */
    @Volatile
    var queued = false

    @Volatile
    var writeStartNanos = 0L

    @Volatile
    var writeEndNanos = 0L

    private val done = AtomicBoolean(false)

    /**
     * Avisa a [completion] la primera vez (las siguientes no hacen nada). Devuelve la excepción del callback, si la hubo,
     * para que la registre quien llama. Nunca llamar con candados internos tomados.
     */
    fun complete(outcome: FrameOutcome, queuedFramesAfter: Int): Throwable? {
        val c = completion ?: return null
        if (!done.compareAndSet(false, true)) return null
        return try {
            c.onFrameDone(
                FrameDone(
                    outcome = outcome,
                    isKeyframe = kind == OutKind.VIDEO_KEY,
                    payloadBytes = payloadBytes,
                    ptsUs = ptsUs,
                    enqueuedNanos = if (queued) enqueuedNanos else 0L,
                    writeStartNanos = writeStartNanos,
                    writeEndNanos = writeEndNanos,
                    queuedFramesAfter = queuedFramesAfter,
                ),
            )
            null
        } catch (t: Throwable) {
            t
        }
    }
}

internal enum class FrameOffer {
    ACCEPTED,
    DROPPED_WAITING_IDR,
    DROPPED_BACKLOG,

    /** hql: más grande que el tope de mensaje de vídeo; se espera un IDR (que tiene que salir más pequeño). */
    DROPPED_OVERSIZED,
    CLOSED,
}

/** hql (C2): lo que ve el escritor al mirar la cola ([SendQueue.poll]). */
internal sealed class Polled {
    /** Mensaje de control, ya sacado de la cola. */
    class Control(val item: Outgoing) : Polled()

    /** Cabeza de vídeo, todavía en la cola (se saca con [SendQueue.takeVideoHead]). */
    class Video(val item: Outgoing) : Polled()

    /** `MAX_LAG`: se tiró todo el vídeo encolado ([frames] frames); hay que pedir un IDR forzado. */
    class Flushed(val frames: Int) : Polled()

    object Empty : Polled()
    object Closed : Polled()
}

/**
 * Colas de salida de la sesión: control con prioridad absoluta sobre vídeo, un solo consumidor (el hilo escritor) y
 * productores que nunca se bloquean. Política de vídeo ([VideoDropPolicy]):
 * - SPS/PPS (config) e IDR nunca se descartan por atasco;
 * - `BACKLOG`: si al llegar un frame hay ≥ [backlogFrames] frames o ≥ [backlogBytes] bytes de vídeo en cola, se
 *   descartan los P-frames encolados; si el que llega es un P-frame también se descarta y no se acepta ninguno más
 *   hasta el siguiente IDR (hay que pedirlo al encoder); si es un IDR, se encola (deja obsoletos los P anteriores);
 * - `MAX_LAG`: al sacar, una cabeza P con más de [maxLagNanos] en cola vacía todo el vídeo y se espera un IDR;
 * - hql: con cualquier política, un frame (IDR o P) de más de [maxMessageBytes] (mensaje entero) no entra nunca: se
 *   descarta y se espera al siguiente IDR, porque los P que vengan detrás dependen de él ([rejectOversized]);
 * - antes del primer IDR aceptado tras [requestConfigResend] o [startStream] se reenvía SPS/PPS (hql: nunca delante
 *   de un P-frame).
 *
 * hql (C2): los elementos que se quitan (descartes, cierre) se devuelven a quien llama, que avisa a su
 * [FrameCompletion] **después** de soltar el candado. Contadores espejo `@Volatile` para consultas sin candado.
 */
internal class SendQueue(
    private val backlogFrames: Int,
    private val backlogBytes: Long,
    private val hardLimitBytes: Long,
    private val resendConfigAfterDrop: Boolean,
    private val controlCapacity: Int = 1_000,
    private val dropPolicy: VideoDropPolicy = VideoDropPolicy.BACKLOG,
    private val maxLagNanos: Long = 150_000_000L,
    /** hql: tope de un mensaje de vídeo (cabeceras incluidas); `0` = sin tope. */
    private val maxMessageBytes: Int = 0,
) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    /** Llega control o se cierra la cola (lo espera el escritor con la puerta de vídeo cerrada). */
    private val controlOrClosed = lock.newCondition()
    private val control = ArrayDeque<Outgoing>()
    private val video = ArrayDeque<Outgoing>()
    private var videoBytes = 0L
    private var videoFrames = 0
    private var closed = false

    private var waitingForIdr = false
    private var pendingConfig = false

    // Espejos de los contadores (se escriben con el candado tomado; se leen sin él).
    @Volatile
    private var videoFramesView = 0

    @Volatile
    private var videoBytesView = 0L

    @Volatile
    private var controlView = 0

    @Volatile
    private var waitingView = false

    /** hql: `enqueuedNanos` de la cabeza de la cola de vídeo (0 = vacía), sin candado. */
    @Volatile
    private var videoHeadNanosView = 0L

    @Volatile
    var droppedFrames = 0L
        private set

    /** Vaciados por `MAX_LAG`. */
    @Volatile
    var flushes = 0L
        private set

    /** hql: frames descartados por pasar de [maxMessageBytes] (también cuentan en [droppedFrames]). */
    @Volatile
    var oversizedFrames = 0L
        private set

    /** hql: ¿un mensaje de [messageBytes] pasa del tope? */
    fun isOversized(messageBytes: Int): Boolean = maxMessageBytes in 1 until messageBytes

    /** `false` si está cerrada o llena (solo pasa si el escritor lleva mucho bloqueado). */
    fun offerControl(item: Outgoing): Boolean = lock.withLock {
        if (closed || control.size >= controlCapacity) return false
        control.addLast(item)
        controlView = control.size
        notEmpty.signal()
        controlOrClosed.signal()
        true
    }

    /** SPS/PPS: siempre se encola. */
    fun offerConfig(item: Outgoing): Boolean = lock.withLock {
        if (closed) return false
        pendingConfig = false
        addVideo(item)
        notEmpty.signal()
        true
    }

    /** Como el de abajo, sin devolver los descartados (sin avisos de finalización). */
    fun offerFrame(item: Outgoing, configFactory: () -> Outgoing?): FrameOffer = offerFrame(item, null, configFactory)

    /**
     * Encola un frame aplicando la política de descarte. [configFactory] construye el mensaje SPS/PPS a reenviar
     * delante (o `null` si aún no hay ninguno). Los frames ya encolados que se tiran van a [dropped]; el propio [item]
     * no (si no se acepta, lo dice el resultado).
     */
    fun offerFrame(item: Outgoing, dropped: MutableList<Outgoing>?, configFactory: () -> Outgoing?): FrameOffer = lock.withLock {
        if (closed) return FrameOffer.CLOSED
        val isKey = item.kind == OutKind.VIDEO_KEY
        if (waitingForIdr && !isKey) {
            droppedFrames++
            return FrameOffer.DROPPED_WAITING_IDR
        }
        if (isOversized(item.bytes.size)) return oversizedLocked()
        // Atasco: solo con BACKLOG, salvo que la cola siga por encima de la válvula de memoria (lo que con BACKLOG y los
        // valores por defecto no pasa nunca): entonces se aplica a cualquier política.
        val overHardLimit = videoBytes > hardLimitBytes
        val backlogged = dropPolicy == VideoDropPolicy.BACKLOG && (videoFrames >= backlogFrames || videoBytes >= backlogBytes)
        if (overHardLimit || backlogged) {
            droppedFrames += dropQueuedDeltas(dropped)
            if (!isKey) {
                droppedFrames++
                setWaiting(true)
                if (resendConfigAfterDrop) pendingConfig = true
                updateViews()
                return FrameOffer.DROPPED_BACKLOG
            }
        }
        if (isKey) setWaiting(false)
        // hql: el SPS/PPS pendiente va pegado al IDR, nunca delante de un P: el C10 reinicia el decodificador con cada
        // SPS/PPS y un P sin su IDR sale con artefactos. Con KEY_FRAME_REQ el IDR tarda ~100 ms en salir del encoder y,
        // en casa (2026-10-07), se colaban P-frames entre el SPS/PPS reenviado y el IDR.
        if (pendingConfig && isKey) {
            configFactory()?.let {
                addVideo(it)
                pendingConfig = false
            }
        }
        addVideo(item)
        if (videoBytes > hardLimitBytes) droppedFrames += dropSupersededFrames(dropped)
        updateViews()
        notEmpty.signal()
        FrameOffer.ACCEPTED
    }

    /**
     * hql: un frame que pasa del tope, sin construir su mensaje (lo comprueba quien llama con [isOversized]). Igual que
     * en [offerFrame]: un P esperando un IDR sale como `DROPPED_WAITING_IDR`; si no, `DROPPED_OVERSIZED` y a esperar
     * el siguiente IDR (con SPS/PPS delante si [resendConfigAfterDrop]).
     */
    fun rejectOversized(isKeyframe: Boolean): FrameOffer = lock.withLock {
        if (closed) return FrameOffer.CLOSED
        if (waitingForIdr && !isKeyframe) {
            droppedFrames++
            return FrameOffer.DROPPED_WAITING_IDR
        }
        oversizedLocked()
    }

    /** Con el candado: descarta por tamaño. Los P ya encolados se quedan: van antes y no dependen de este frame. */
    private fun oversizedLocked(): FrameOffer {
        droppedFrames++
        oversizedFrames++
        setWaiting(true)
        if (resendConfigAfterDrop) pendingConfig = true
        return FrameOffer.DROPPED_OVERSIZED
    }

    /** `KEY_FRAME_REQ`: SPS/PPS delante del siguiente IDR aceptado (no de un P). */
    fun requestConfigResend() = lock.withLock { pendingConfig = true }

    /**
     * hql: vacía todo el vídeo encolado (write bloqueado con el coche hablando: lo que hay en cola ya es viejo) y se
     * espera un IDR con SPS/PPS delante. Devuelve los frames quitados (para avisar a sus finalizaciones fuera del
     * candado); con cualquier política.
     */
    fun flushVideo(): List<Outgoing> = lock.withLock {
        if (closed || video.isEmpty()) return emptyList()
        val out = ArrayList<Outgoing>(video.size)
        for (v in video) if (v.isFrame) out.add(v)
        video.clear()
        videoBytes = 0
        videoFrames = 0
        droppedFrames += out.size
        if (out.isNotEmpty()) flushes++
        setWaiting(true)
        if (resendConfigAfterDrop) pendingConfig = true
        updateViews()
        out
    }

    /** Empieza (o se reanuda) el vídeo: se arranca en un IDR precedido de SPS/PPS. */
    fun startStream() = lock.withLock {
        setWaiting(true)
        pendingConfig = true
    }

    val isWaitingForIdr: Boolean get() = lock.withLock { waitingForIdr }

    /** Lo mismo sin candado (puede ir un instante por detrás). */
    val waitingForIdrView: Boolean get() = waitingView

    /**
     * hql (C2): siguiente cosa que hacer, sin bloquear. El control sale ya de la cola; el vídeo se queda en ella hasta
     * [takeVideoHead]. Con `MAX_LAG` aquí se vacía el vídeo atrasado (los frames van a [dropped]).
     */
    fun poll(dropped: MutableList<Outgoing>, nowNanos: Long = System.nanoTime()): Polled = lock.withLock {
        if (closed) return Polled.Closed
        val c = control.removeFirstOrNull()
        if (c != null) {
            controlView = control.size
            return Polled.Control(c)
        }
        val head = video.firstOrNull() ?: return Polled.Empty
        if (dropPolicy == VideoDropPolicy.MAX_LAG && head.kind == OutKind.VIDEO_DELTA && nowNanos - head.enqueuedNanos > maxLagNanos) {
            var frames = 0
            for (v in video) {
                if (v.isFrame) {
                    dropped.add(v)
                    frames++
                }
            }
            video.clear()
            videoBytes = 0
            videoFrames = 0
            droppedFrames += frames
            flushes++
            setWaiting(true)
            if (resendConfigAfterDrop) pendingConfig = true
            updateViews()
            return Polled.Flushed(frames)
        }
        Polled.Video(head)
    }

    /** Saca la cabeza de vídeo si sigue siendo [expected] (pudo cambiar por un descarte o un cierre). */
    fun takeVideoHead(expected: Outgoing): Outgoing? = lock.withLock {
        if (closed || video.firstOrNull() !== expected) return null
        val it = video.removeFirst()
        videoBytes -= it.bytes.size
        if (it.isFrame) videoFrames--
        updateViews()
        it
    }

    /** Espera a que llegue algo (o al cierre). Puede volver antes de tiempo: el escritor vuelve a mirar. */
    fun awaitAny() {
        lock.withLock {
            if (closed || control.isNotEmpty() || video.isNotEmpty()) return
            try {
                notEmpty.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** Espera como mucho [nanos] o hasta que llegue control o se cierre la cola. */
    fun awaitControl(nanos: Long) {
        lock.withLock {
            if (closed || control.isNotEmpty()) return
            try {
                controlOrClosed.awaitNanos(nanos)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Siguiente mensaje (control primero), sin puerta de vídeo. Bloquea; `null` cuando la cola se cierra.
     * Los descartes por retraso van a [dropped] (si se da).
     */
    fun take(dropped: MutableList<Outgoing>? = null): Outgoing? {
        val sink = dropped ?: ArrayList()
        while (true) {
            when (val r = poll(sink)) {
                is Polled.Control -> return r.item
                is Polled.Video -> takeVideoHead(r.item)?.let { return it }
                is Polled.Flushed -> Unit
                Polled.Empty -> {
                    if (Thread.currentThread().isInterrupted) return null
                    awaitAny()
                }
                Polled.Closed -> return null
            }
        }
    }

    /** Cierra y devuelve lo que quedaba (control y vídeo), para avisar a sus finalizaciones fuera del candado. */
    fun close(): List<Outgoing> = lock.withLock {
        closed = true
        val left = ArrayList<Outgoing>(control.size + video.size)
        left.addAll(control)
        left.addAll(video)
        control.clear()
        video.clear()
        videoBytes = 0
        videoFrames = 0
        updateViews()
        controlView = 0
        notEmpty.signalAll()
        controlOrClosed.signalAll()
        left
    }

    fun controlDepth(): Int = controlView
    fun videoFrameDepth(): Int = videoFramesView
    fun videoByteDepth(): Long = videoBytesView

    /** hql: `System.nanoTime()` en que se encoló la cabeza de la cola de vídeo, o 0 si está vacía (sin candado). */
    fun videoHeadEnqueuedNanos(): Long = videoHeadNanosView

    /** Para tests: el candado lo tiene este hilo (las finalizaciones nunca deben verlo así). */
    fun isLockHeldByCurrentThread(): Boolean = lock.isHeldByCurrentThread

    private fun setWaiting(w: Boolean) {
        waitingForIdr = w
        waitingView = w
    }

    private fun updateViews() {
        videoFramesView = videoFrames
        videoBytesView = videoBytes
        videoHeadNanosView = video.firstOrNull()?.enqueuedNanos ?: 0L
    }

    private fun addVideo(item: Outgoing) {
        video.addLast(item)
        item.queued = true
        videoBytes += item.bytes.size
        if (item.isFrame) videoFrames++
        updateViews()
    }

    private fun dropQueuedDeltas(dropped: MutableList<Outgoing>?): Int {
        var n = 0
        val it = video.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (v.kind == OutKind.VIDEO_DELTA) {
                it.remove()
                videoBytes -= v.bytes.size
                videoFrames--
                dropped?.add(v)
                n++
            }
        }
        updateViews()
        return n
    }

    /** Válvula de memoria: quita los frames (también IDR) anteriores al último IDR de la cola. */
    private fun dropSupersededFrames(dropped: MutableList<Outgoing>?): Int {
        val lastKey = video.indexOfLast { it.kind == OutKind.VIDEO_KEY }
        if (lastKey <= 0) return 0
        var n = 0
        var i = 0
        val it = video.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (i >= lastKey) break
            if (v.isFrame) {
                it.remove()
                videoBytes -= v.bytes.size
                videoFrames--
                dropped?.add(v)
                n++
            }
            i++
        }
        updateViews()
        return n
    }

    companion object {
        fun lagNanos(ms: Long): Long = TimeUnit.MILLISECONDS.toNanos(ms)
    }
}
