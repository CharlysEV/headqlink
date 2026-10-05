package dev.qdauto.core.sim

import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.session.MirrorGeometry
import dev.qdauto.core.util.BE
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.w
import dev.qdauto.core.wire.AppMessage
import dev.qdauto.core.wire.BinBlock
import dev.qdauto.core.wire.BroadcastAck
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.Direction
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.Traces
import dev.qdauto.core.wire.WireMessage
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Todo lo que el simulador ve del teléfono y de sí mismo, protegido por un único lock: estado, mensajes recibidos
 * (por tipo y en orden), AppStatus, heartbeats, validación del vídeo y lo que se ha inyectado. Permite esperar a que
 * se cumpla una condición y sacar el [CarSimReport].
 */
internal class PhoneObserver(
    private val config: CarSimConfig,
    private val listener: CarSimListener,
    private val log: QdLog,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    private var simState = CarSimState.IDLE
    private val counts = LinkedHashMap<String, Int>()
    private val order = ArrayList<String>()
    private var broadcastsSent = 0
    private var ack: BroadcastAck? = null
    private var ackFrom: InetSocketAddress? = null
    private var connectedTo: String? = null
    private var appStatusReceived = 0
    private var appStatusSdk: Int? = null
    private val appStatusErrors = ArrayList<String>()
    private var lastPhoneInfo: JsonObject? = null
    private var phoneHeartbeats = 0
    private var lastHeartbeatNanos = 0L
    private val heartbeatIntervals = ArrayDeque<Long>()
    private val unexpected = ArrayList<String>()
    private var touchesSent = 0
    private val touchLog = ArrayList<SentTouch>()
    private var keysSent = 0
    private val handshakeErrors = ArrayList<String>()
    private var closeReason: String? = null
    private val record: OutputStream? = config.recordVideoTo?.let { BufferedOutputStream(FileOutputStream(it)) }
    private val validator = VideoValidator(defaultExpectations(config), record)

    val state: CarSimState get() = lock.withLock { simState }
    val hasAck: Boolean get() = lock.withLock { ack != null }

    /** `true` si cambió (y no estaba cerrado). */
    fun setState(s: CarSimState): Boolean = lock.withLock {
        if (simState == s || simState == CarSimState.CLOSED) {
            false
        } else {
            simState = s
            changed.signalAll()
            true
        }
    }

    fun count(key: String): Int = lock.withLock { counts[key] ?: 0 }

    /** Espera a que se cumpla [condition] (evaluada con el lock); `false` si vence o se cierra antes. */
    fun await(timeoutMs: Long, condition: () -> Boolean): Boolean = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            if (left <= 0 || simState == CarSimState.CLOSED) return condition()
            left = changed.awaitNanos(left)
        }
        true
    }

    fun awaitState(target: CarSimState, timeoutMs: Long): Boolean {
        await(timeoutMs) { simState == target || simState == CarSimState.CLOSED }
        return lock.withLock { simState == target }
    }

    fun awaitCount(key: String, count: Int, timeoutMs: Long): Boolean = await(timeoutMs) { (counts[key] ?: 0) >= count }

    fun awaitVideo(count: Int, timeoutMs: Long): Boolean = await(timeoutMs) { validator.messages >= count }

    fun noteBroadcast() = lock.withLock { broadcastsSent++ }

    fun noteAck(a: BroadcastAck, from: InetSocketAddress) = lock.withLock {
        ack = a
        ackFrom = from
        changed.signalAll()
    }

    fun noteConnected(to: String) = lock.withLock { connectedTo = to }

    fun noteTouch(t: SentTouch) = lock.withLock {
        touchesSent++
        if (touchLog.size < CarSim.MAX_TOUCH_LOG) touchLog += t
    }

    fun noteKey() = lock.withLock { keysSent++ }

    fun noteHandshakeError(text: String) = lock.withLock { handshakeErrors += text }

    fun noteUnexpected(text: String) {
        log.w(TAG, text)
        lock.withLock { if (unexpected.size < 100) unexpected += text }
    }

    fun updateExpectations(e: VideoExpectations) = lock.withLock { validator.expectations = e }

    fun sentTouches(): List<SentTouch> = lock.withLock { touchLog.toList() }

    /** Marca el cierre (una vez) y cierra la grabación de vídeo. */
    fun close(reason: String) = lock.withLock {
        closeReason = reason
        simState = CarSimState.CLOSED
        changed.signalAll()
        try {
            record?.close() // el lector escribe en `record` con este mismo lock
        } catch (_: IOException) {
        }
    }

    /** Clasifica, valida y cuenta un mensaje del teléfono; avisa al listener fuera del lock. */
    fun onPhoneMessage(m: WireMessage) {
        when (m) {
            is WireMessage.Frame -> onFrame(m)
            is WireMessage.Bin -> onBin(m.block)
            is WireMessage.Garbage -> noteUnexpected("${m.totalBytes} bytes no válidos: ${m.reason}")
        }
    }

    private fun onFrame(m: WireMessage.Frame) {
        val h = m.header
        when (h.msgType) {
            MsgType.CONTROL -> {
                val c = ControlMessage.parse(h, m.payloadText())
                val key = c.cmd.ifEmpty { "CONTROL?" }
                lock.withLock {
                    if (c.cmd == Cmd.PHONE_INFO) lastPhoneInfo = c.para
                    if (c.cmd == Cmd.HEARTBEAT) notePhoneHeartbeat()
                    countLocked(key, ordered = true)
                }
                listener.onTrace(Traces.ofJson(Direction.IN, key, h.msgType, m.wireBytes(), c.text, config.traceMaxJsonChars))
                listener.onPhoneControl(c)
            }
            MsgType.APP -> {
                val a = AppMessage.parse(h, m.payloadText())
                lock.withLock { countLocked(a.key, ordered = true) }
                listener.onTrace(Traces.ofJson(Direction.IN, a.key, h.msgType, m.wireBytes(), a.text, config.traceMaxJsonChars))
                listener.onPhoneApp(a)
            }
            MsgType.VIDEO -> {
                val info = lock.withLock { validator.onMessage(h, m.body).also { countLocked("VIDEO", ordered = false) } }
                if (info.errors.isNotEmpty()) log.w(TAG, "vídeo #${info.index}: ${info.errors}")
                listener.onTrace(Traces.ofVideo(Direction.IN, "VIDEO_${info.kind}", h.totalSize, "${info.payloadSize} B ${info.header?.params}"))
                listener.onVideoFrame(info)
            }
            else -> {
                lock.withLock { countLocked(MsgType.name(h.msgType), ordered = true) }
                noteUnexpected("msgType ${h.msgType} del teléfono: ${h.describe()}")
                listener.onTrace(Traces.ofFrame(Direction.IN, m, config.traceMaxJsonChars, 64))
            }
        }
    }

    private fun onBin(b: BinBlock) {
        val isAppStatus = b.cmd == BinBlock.CMD_APP_STATUS
        val errs = if (isAppStatus) validateAppStatus(b) else listOf("!BIN inesperado: ${b.describe()}")
        lock.withLock {
            if (isAppStatus) {
                appStatusReceived++
                appStatusSdk = BE.getInt(b.bytes, 76)
                appStatusErrors += errs
                countLocked("!BIN AppStatus", ordered = true)
            } else {
                countLocked("!BIN", ordered = true)
            }
        }
        listener.onTrace(Traces.ofBinary(Direction.IN, "!BIN", null, b.bytes, b.describe(), 0))
        if (isAppStatus) listener.onAppStatus(b, errs) else noteUnexpected(errs.first())
    }

    /** El AppStatus debe ser exactamente el de QDLink (spec §4.2), salvo `versionAndroid` en [76]. */
    private fun validateAppStatus(b: BinBlock): List<String> {
        val bytes = b.bytes
        val expected = BinBlock.appStatus(BE.getInt(bytes, 76))
        val errs = ArrayList<String>()
        for (i in bytes.indices) {
            if (bytes[i] != expected[i]) {
                errs += "AppStatus: byte $i = ${bytes[i].toInt() and 0xFF}, se esperaba ${expected[i].toInt() and 0xFF}"
                if (errs.size >= 10) break
            }
        }
        return errs
    }

    /** Llamar con [lock]. */
    private fun notePhoneHeartbeat() {
        phoneHeartbeats++
        val now = System.nanoTime()
        if (lastHeartbeatNanos != 0L) {
            heartbeatIntervals.addLast((now - lastHeartbeatNanos) / 1_000_000)
            while (heartbeatIntervals.size > 20) heartbeatIntervals.removeFirst()
        }
        lastHeartbeatNanos = now
    }

    /** Llamar con [lock]. */
    private fun countLocked(key: String, ordered: Boolean) {
        counts[key] = (counts[key] ?: 0) + 1
        if (ordered && order.size < 50) order += key
        changed.signalAll()
    }

    fun report(): CarSimReport = lock.withLock {
        CarSimReport(
            state = simState,
            broadcastsSent = broadcastsSent,
            ackFrom = ackFrom?.toString(),
            ackJson = ack?.json?.toJson(),
            mirrorPort = ack?.mirrorPort,
            connectedTo = connectedTo,
            appStatusReceived = appStatusReceived,
            appStatusSdkInt = appStatusSdk,
            appStatusErrors = appStatusErrors.toList(),
            phoneMessageCounts = LinkedHashMap(counts),
            phoneMessageOrder = order.toList(),
            lastPhoneInfo = lastPhoneInfo,
            phoneHeartbeats = phoneHeartbeats,
            phoneHeartbeatIntervalsMs = heartbeatIntervals.toList(),
            videoMessages = validator.messages,
            codecConfigMessages = validator.configMessages,
            idrFrames = validator.idrFrames,
            pFrames = validator.pFrames,
            videoPayloadBytes = validator.payloadBytes,
            firstVideoKind = validator.firstKind,
            lastVideoHeader = validator.lastHeader,
            videoErrorCount = validator.errorCount,
            videoErrors = validator.errors,
            unexpected = unexpected.toList(),
            touchesSent = touchesSent,
            keysSent = keysSent,
            handshakeErrors = handshakeErrors.toList(),
            closeReason = closeReason,
        )
    }

    private companion object {
        const val TAG = "QD/CarSim"

        /** Modo in-app: W×H = par(CarW)×par(CarH) y eco de `VIDEO_ARGS` (spec §8.7). */
        fun defaultExpectations(config: CarSimConfig): VideoExpectations {
            val inCar = MirrorGeometry.forCarInfo(1, 1, config.carInfo.carWidth, config.carInfo.carHeight)
            val va = config.videoArgs
            val echo = config.expectVideoArgsEcho
            return VideoExpectations(
                width = config.expectedWidth ?: inCar.inCarWidth,
                height = config.expectedHeight ?: inCar.inCarHeight,
                appType = config.expectedAppType,
                encodingType = va.encodingType.takeIf { echo },
                fps = va.frameRate.takeIf { echo },
                bitrate = va.bitRate.takeIf { echo },
                gop = va.frameInterval.takeIf { echo },
            )
        }
    }
}
