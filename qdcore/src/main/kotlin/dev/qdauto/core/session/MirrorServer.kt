package dev.qdauto.core.session

import dev.qdauto.core.util.QdLog
import dev.qdauto.core.util.i
import dev.qdauto.core.util.w
import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * `ServerSocket` en el que el teléfono espera la conexión del coche: el teléfono escucha y el coche conecta
 * (WF/d.java:78-83). Se abre en el constructor, así que ya está escuchando cuando se envía el ACK (spec §1.4).
 *
 * - [port] = [RANDOM_PORT]: puerto aleatorio en 10001-65535 como QDLink (WF/a.java:249-264); 0: lo elige el sistema.
 * - [accept] espera con límite (QDLink: 20 s, WF/d.java:65) y se puede llamar varias veces: el servidor sigue abierto
 *   tras aceptar, como en QDLink, así que se pueden registrar conexiones extra del coche.
 */
class MirrorServer(
    port: Int = RANDOM_PORT,
    bindAddress: InetAddress? = null,
    private val log: QdLog = QdLog.NONE,
) : Closeable {
    private val server: ServerSocket = open(port, bindAddress)

    /** Puerto real en el que se escucha (el `MirrorPort` del ACK). */
    val port: Int = server.localPort

    val isClosed: Boolean get() = server.isClosed

    init {
        log.i(TAG, "escuchando TCP en ${server.localSocketAddress}")
    }

    /**
     * Espera la conexión del coche. Lanza [SocketTimeoutException] si no llega en [timeoutMs] (el servidor sigue
     * abierto) y `SocketException` si se cierra mientras se espera.
     */
    @Throws(IOException::class)
    fun accept(timeoutMs: Long = DEFAULT_ACCEPT_TIMEOUT_MS): Socket {
        server.soTimeout = timeoutMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val s = server.accept()
        log.i(TAG, "conexión TCP de ${s.remoteSocketAddress} en el puerto $port")
        return s
    }

    override fun close() {
        try {
            server.close()
        } catch (_: IOException) {
        }
    }

    private fun open(port: Int, bindAddress: InetAddress?): ServerSocket {
        if (port != RANDOM_PORT) return bind(bindAddress, port)
        var last: IOException? = null
        repeat(RANDOM_ATTEMPTS) {
            val candidate = Random.nextInt(QDLINK_PORT_MIN, QDLINK_PORT_MAX + 1)
            try {
                return bind(bindAddress, candidate)
            } catch (e: BindException) {
                log.w(TAG, "puerto $candidate ocupado")
                last = e
            }
        }
        throw last ?: IOException("no hay puerto libre")
    }

    /** Opciones por defecto de la plataforma, como el `new ServerSocket(P)` de QDLink (backlog 50). */
    private fun bind(bindAddress: InetAddress?, port: Int): ServerSocket {
        val s = ServerSocket()
        try {
            s.bind(InetSocketAddress(bindAddress, port), 50)
        } catch (e: IOException) {
            s.close()
            throw e
        }
        return s
    }

    companion object {
        private const val TAG = "QD/MirrorServer"
        const val RANDOM_PORT = -1
        const val DEFAULT_ACCEPT_TIMEOUT_MS = 20_000L
        const val QDLINK_PORT_MIN = 10_001
        const val QDLINK_PORT_MAX = 65_535
        private const val RANDOM_ATTEMPTS = 50
    }
}
