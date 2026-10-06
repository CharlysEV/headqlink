package dev.qdauto.core.session

import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.i
import dev.qdauto.core.util.w
import dev.qdauto.core.wire.BlockFraming
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/** hql: los flujos de un transporte, ya abiertos. */
class TransportStreams(val input: InputStream, val output: OutputStream)

/**
 * hql: por dónde viaja una [PhoneSession]. El protocolo y la sesión son los mismos (handshake, heartbeats, watchdog,
 * táctil, vídeo, tope de 480 KiB); cambia la trama y lo que se puede medir:
 * - [TcpTransport]: el TCP que abre el coche tras el ACK (Wi-Fi). [socket] da la cola del kernel (NetStat), la IP y
 *   las opciones del socket.
 * - [StreamTransport]: un par de flujos ya abiertos, como el accesorio USB (AOA) de QDLink o tubos en memoria en los
 *   tests. Sin [socket]: sin cola del kernel; el atasco se mide con la cola de la sesión ([IoSnapshot.videoQueueLagMs]).
 *
 * [blockSize] > 0 = trama por bloques del USB ([BlockFraming]): cada mensaje escrito con relleno de ceros hasta un
 * múltiplo de [blockSize] (en un solo `write()`) y lectura en bloques que salta el relleno.
 */
interface SessionTransport {
    /** «TCP», «USB»… para el log. */
    val kind: String

    /** 0 = flujo continuo (TCP); > 0 = bloques con relleno ([BlockFraming.BLOCK] en USB). */
    val blockSize: Int

    /** El socket (solo lectura: NetStat, IP local, log), o `null` si no es TCP. */
    val socket: Socket?

    /** El coche, si es TCP. */
    val remoteAddress: InetSocketAddress?

    /** Para el log: «/10.0.0.2:41234 (local /10.0.0.1:20001)» o «USB · Neusoft QDriveLink 1». */
    fun describe(): String

    /** Aplica sus opciones (TCP) y devuelve los flujos. Una vez, en [PhoneSession.start]. */
    @Throws(IOException::class)
    fun open(config: SessionConfig, log: QdLog, tag: String): TransportStreams

    /** Cierra (idempotente). Lo llama la sesión al cerrarse; con él tiene que acabar la lectura bloqueada. */
    fun close()
}

/**
 * hql: el TCP del Wi-Fi (el comportamiento de siempre). Con [blockSize] > 0, la trama del USB sobre el TCP, para probar
 * la trama contra `qdsim --usb-framing` desde el PC.
 */
class TcpTransport @JvmOverloads constructor(
    override val socket: Socket,
    override val blockSize: Int = 0,
) : SessionTransport {
    override val kind: String get() = if (blockSize > 0) "TCP con bloques de $blockSize B" else "TCP"

    override val remoteAddress: InetSocketAddress? get() = socket.remoteSocketAddress as? InetSocketAddress

    override fun describe(): String =
        "${socket.remoteSocketAddress} (local ${socket.localSocketAddress})" + if (blockSize > 0) " · bloques de $blockSize B (trama USB)" else ""

    override fun open(config: SessionConfig, log: QdLog, tag: String): TransportStreams {
        configure(config, log, tag)
        return TransportStreams(socket.getInputStream(), socket.getOutputStream())
    }

    override fun close() {
        // hql (C3): shutdown antes de close: si alguien tiene un duplicado del descriptor (NetStat), close() no basta
        // para que el kernel mande el FIN al coche.
        try {
            socket.shutdownInput()
        } catch (_: Exception) {
        }
        try {
            socket.shutdownOutput()
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    /**
     * Opciones de WF/d.java:88-94, salvo los tamaños de buffer (ver [SessionConfig.sendBufferBytes]).
     * hql (C3): después, `IP_TOS` ([SessionConfig.trafficClass], con su propio `try`: hay sistemas que lo ignoran o lo
     * rechazan) y el configurador de la app.
     */
    private fun configure(config: SessionConfig, log: QdLog, tag: String) {
        try {
            socket.tcpNoDelay = config.tcpNoDelay
            config.sendBufferBytes?.let { socket.sendBufferSize = it }
            config.receiveBufferBytes?.let { socket.receiveBufferSize = it }
            socket.keepAlive = config.keepAlive
        } catch (e: SocketException) {
            log.w(tag, "no se pudieron aplicar las opciones del socket", e)
        }
        config.trafficClass?.let { tc ->
            try {
                socket.trafficClass = tc
            } catch (e: Exception) {
                log.w(tag, "no se pudo marcar IP_TOS=0x${Integer.toHexString(tc)}", e)
            }
        }
        config.socketConfigurator?.let { configure ->
            try {
                configure(socket)
            } catch (e: Exception) {
                log.w(tag, "el configurador del socket falló", e)
            }
        }
        try {
            log.i(
                tag,
                "socket: TCP_NODELAY=${socket.tcpNoDelay} SO_SNDBUF=${socket.sendBufferSize} " +
                    "SO_RCVBUF=${socket.receiveBufferSize} SO_KEEPALIVE=${socket.keepAlive} " +
                    "IP_TOS=0x${Integer.toHexString(socket.trafficClass)}",
            )
        } catch (e: SocketException) {
            log.w(tag, "no se pudieron leer las opciones del socket", e)
        }
    }
}

/**
 * hql: un par de flujos ya abiertos: el accesorio USB (AOA) del coche (`ParcelFileDescriptor` de
 * `UsbManager.openAccessory`) o tubos en memoria en los tests. Por defecto con la trama por bloques del USB. Al cerrar
 * se cierran los dos flujos y [onClose] (el descriptor del accesorio: cerrarlo despierta la lectura bloqueada).
 */
class StreamTransport @JvmOverloads constructor(
    private val input: InputStream,
    private val output: OutputStream,
    override val kind: String = "USB",
    override val blockSize: Int = BlockFraming.BLOCK,
    private val description: String = kind,
    private val onClose: Closeable? = null,
) : SessionTransport {
    private val closed = AtomicBoolean(false)

    override val socket: Socket? get() = null
    override val remoteAddress: InetSocketAddress? get() = null

    override fun describe(): String = description + if (blockSize > 0) " · bloques de $blockSize B" else ""

    override fun open(config: SessionConfig, log: QdLog, tag: String): TransportStreams {
        log.i(tag, "transporte $kind: ${describe()} (sin socket: sin cola del kernel; el atasco se mide con la cola de la sesión)")
        return TransportStreams(input, output)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        for (c in listOf<Closeable?>(output, input, onClose)) {
            try {
                c?.close()
            } catch (_: Exception) {
            }
        }
    }
}
