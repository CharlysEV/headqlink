package dev.qdauto.core.session

import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.wire.CarMessages
import dev.qdauto.core.wire.FrameReader
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.WireMessage
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * hql (C2): coche de mentira que **no lee** hasta [startReading] (y deja de leer con [pauseReading]): con búferes
 * pequeños el `write()` del teléfono se bloquea, como en un corte de radio.
 */
class ControlledCar(port: Int, receiveBufferBytes: Int = 4096) : Closeable {
    val socket = Socket().apply {
        receiveBufferSize = receiveBufferBytes
        tcpNoDelay = true
        connect(InetSocketAddress("127.0.0.1", port), 5_000)
    }
    private val received = LinkedBlockingQueue<WireMessage>()
    private val gate = Object()

    @Volatile
    private var reading = false

    @Volatile
    var eof = false
        private set

    private var reader: Thread? = null

    fun send(bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    /** CAR_INFO, VIDEO_SUP_REQ, VIDEO_ARGS y VIDEO_CTRL{1} seguidos, sin esperar respuestas (no se leen). */
    fun handshake(width: Int = 1920, height: Int = 1080) {
        send(
            CarMessages.carInfo(
                JsonObject.of(
                    "Version" to "1", "CarType" to "2D4", "Platform" to 0, "PlatformVersion" to "1", "CarWidth" to width,
                    "CarHeight" to height, "CarFactory" to "018", "HUFactory" to "119", "MirrorTypeReq" to 0,
                ),
            ),
        )
        send(CarMessages.videoSupReq(3))
        send(CarMessages.videoArgs(width, height, 3, 30, 4_000_000, 1))
        send(CarMessages.videoCtrl(1))
    }

    /** Empieza (o reanuda) la lectura en un hilo propio. */
    fun startReading() {
        synchronized(gate) {
            reading = true
            gate.notifyAll()
        }
        if (reader != null) return
        reader = Thread({
            val r = FrameReader(socket.getInputStream())
            try {
                while (true) {
                    synchronized(gate) {
                        while (!reading) gate.wait()
                    }
                    val m = r.next() ?: break
                    received.put(m)
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            }
            eof = true
        }, "controlledcar-reader").apply {
            isDaemon = true
            start()
        }
    }

    /** Deja de leer entre mensajes (el que esté a medias se termina de leer). */
    fun pauseReading() {
        synchronized(gate) { reading = false }
    }

    fun next(timeoutMs: Long = 5_000): WireMessage =
        received.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: fail("el teléfono no mandó nada en $timeoutMs ms")

    /** Mensajes de vídeo recibidos (cuerpo sin la cabecera extendida) hasta que pasen [quietMs] sin nada nuevo. */
    fun videoPayloads(count: Int, timeoutMs: Long = 10_000): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (out.size < count && System.currentTimeMillis() < deadline) {
            val m = received.poll(50, TimeUnit.MILLISECONDS) ?: continue
            if (m is WireMessage.Frame && m.header.msgType == MsgType.VIDEO) out += m.payload()
        }
        return out
    }

    fun drain(): List<WireMessage> = ArrayList<WireMessage>().also { received.drainTo(it) }

    /** Cierre con RST (SO_LINGER 0): corta a mitad de lo que esté enviando el teléfono. */
    fun closeAbruptly() {
        try {
            socket.setSoLinger(true, 0)
        } catch (_: IOException) {
        }
        socket.close()
    }

    override fun close() {
        synchronized(gate) {
            reading = true
            gate.notifyAll()
        }
        socket.close()
    }
}
