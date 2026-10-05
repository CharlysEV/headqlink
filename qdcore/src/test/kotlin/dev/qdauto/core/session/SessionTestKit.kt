package dev.qdauto.core.session

import dev.qdauto.core.wire.AppMessage
import dev.qdauto.core.wire.BinBlock
import dev.qdauto.core.wire.BinaryMessage
import dev.qdauto.core.wire.BtAddrRequest
import dev.qdauto.core.wire.CarInfo
import dev.qdauto.core.wire.CarKey
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.FrameReader
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.TouchEvent
import dev.qdauto.core.wire.TraceEvent
import dev.qdauto.core.wire.UnknownMessage
import dev.qdauto.core.wire.VideoArgs
import dev.qdauto.core.wire.WireMessage
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/** Coche de mentira con un socket TCP crudo: manda bytes y recoge lo que llega. */
class FakeCar(port: Int) : Closeable {
    val socket = Socket("127.0.0.1", port)
    private val received = LinkedBlockingQueue<WireMessage>()

    @Volatile
    var eof = false
        private set

    private val reader = Thread({
        val r = FrameReader(socket.getInputStream())
        try {
            while (true) {
                val m = r.next() ?: break
                received.put(m)
            }
        } catch (_: IOException) {
        }
        eof = true
    }, "fakecar-reader").apply {
        isDaemon = true
        start()
    }

    fun send(bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    fun next(timeoutMs: Long = 5_000): WireMessage = received.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: fail("el teléfono no mandó nada en $timeoutMs ms")

    /** Siguiente mensaje que no sea un heartbeat del teléfono. */
    fun nextNonHeartbeat(timeoutMs: Long = 5_000): WireMessage {
        while (true) {
            val m = next(timeoutMs)
            if (m is WireMessage.Frame && m.header.msgType == MsgType.CONTROL && m.payloadText() == "{\"CMD\":\"HEARTBEAT\"}") continue
            return m
        }
    }

    fun nextFrame(timeoutMs: Long = 5_000): WireMessage.Frame =
        nextNonHeartbeat(timeoutMs) as? WireMessage.Frame ?: fail("se esperaba un mensaje 5A5A")

    fun nextJson(timeoutMs: Long = 5_000): String = nextFrame(timeoutMs).payloadText()

    fun drain(): List<WireMessage> = ArrayList<WireMessage>().also { received.drainTo(it) }

    fun awaitEof(timeoutMs: Long): Boolean {
        reader.join(timeoutMs)
        return eof
    }

    override fun close() = socket.close()

    companion object {
        fun heartbeatCount(msgs: List<WireMessage>) =
            msgs.count { it is WireMessage.Frame && it.payloadText() == "{\"CMD\":\"${Cmd.HEARTBEAT}\"}" }
    }
}

/** Listener que lo apunta todo. */
class RecordingListener : SessionListener {
    val events: MutableList<String> = Collections.synchronizedList(ArrayList())
    val states: MutableList<SessionState> = Collections.synchronizedList(ArrayList())
    val touches: MutableList<TouchEvent> = Collections.synchronizedList(ArrayList())
    val keys: MutableList<CarKey> = Collections.synchronizedList(ArrayList())
    val keyframeReasons: MutableList<KeyframeReason> = Collections.synchronizedList(ArrayList())
    val unknown: MutableList<UnknownMessage> = Collections.synchronizedList(ArrayList())
    val controls: MutableList<ControlMessage> = Collections.synchronizedList(ArrayList())
    val apps: MutableList<AppMessage> = Collections.synchronizedList(ArrayList())
    val traces: MutableList<TraceEvent> = Collections.synchronizedList(ArrayList())
    val btRequests: MutableList<BtAddrRequest> = Collections.synchronizedList(ArrayList())
    val playEvents: MutableList<Boolean> = Collections.synchronizedList(ArrayList())
    val watchdogWarnings: MutableList<Long> = Collections.synchronizedList(ArrayList())

    @Volatile
    var carInfo: CarInfo? = null

    @Volatile
    var videoArgs: VideoArgs? = null

    @Volatile
    var closeReason: CloseReason? = null
    val closed = CountDownLatch(1)
    val streaming = CountDownLatch(1)

    override fun onStateChanged(from: SessionState, to: SessionState) {
        states += to
        events += "state:$to"
        if (to == SessionState.STREAMING) streaming.countDown()
    }

    override fun onCarInfo(info: CarInfo, message: ControlMessage) {
        carInfo = info
        events += "carInfo"
    }

    override fun onVideoArgs(args: VideoArgs, message: ControlMessage) {
        videoArgs = args
        events += "videoArgs"
    }

    override fun onVideoControl(play: Boolean, playStatus: Int, message: ControlMessage) {
        playEvents += play
        events += "videoCtrl:$playStatus"
    }

    override fun onKeyframeRequested(reason: KeyframeReason) {
        keyframeReasons += reason
        events += "keyframe:$reason"
    }

    override fun onTouch(event: TouchEvent) {
        touches += event
    }

    override fun onKey(key: CarKey) {
        keys += key
        events += "key:$key"
    }

    override fun onPhoneKey(code: Int, message: ControlMessage) {
        events += "phoneKey:$code"
    }

    override fun onBtAddr(request: BtAddrRequest, message: ControlMessage) {
        btRequests += request
    }

    override fun onControlMessage(message: ControlMessage) {
        controls += message
    }

    override fun onAppMessage(message: AppMessage) {
        apps += message
    }

    override fun onBinaryMessage(message: BinaryMessage) {
        events += "binary:${message.header.msgType}"
    }

    override fun onLegacyMessage(block: BinBlock) {
        events += "legacy:${block.cmd}"
    }

    override fun onUnknownMessage(message: UnknownMessage) {
        unknown += message
        events += "unknown"
    }

    override fun onWatchdogWarning(silentMs: Long) {
        watchdogWarnings += silentMs
        events += "watchdogWarning"
    }

    override fun onTrace(event: TraceEvent) {
        traces += event
    }

    override fun onClosed(reason: CloseReason) {
        closeReason = reason
        events += "closed:${reason.kind}"
        closed.countDown()
    }

    fun awaitClosed(ms: Long): Boolean = closed.await(ms, TimeUnit.MILLISECONDS)
}
