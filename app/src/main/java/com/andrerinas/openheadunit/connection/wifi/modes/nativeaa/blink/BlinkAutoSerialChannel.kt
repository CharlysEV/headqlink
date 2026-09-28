package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.blink

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppFraming
import com.andrerinas.openheadunit.utils.AppLog
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

internal enum class BlinkPortOwner { AVAILABLE, STOCK_CLIENT, UNKNOWN }

/**
 * A root-owned bridge to `/dev/auto_serial`, the pseudo-terminal FYT's `blink` daemon relays the
 * module's Android Auto RFCOMM channel through.
 *
 * The node belongs to root and the vendor app, so it is reached through `su`: one shell puts the
 * terminal in raw mode without echo (as shipped it echoes every line back to the daemon, which
 * logs each one as an unsupported command), then copies the port to our stdin and our stdout to
 * the port. Lines are dispatched to [onLine] on a reader thread.
 *
 * The stock `com.syu.carlink` must not be running: two readers on one terminal split the phone's
 * lines between them.
 */
class BlinkAutoSerialChannel internal constructor(
    private val process: Process,
    private val onLine: (String) -> Unit,
    private val onEnded: (String) -> Unit,
    private val cleanupScheduler: ((() -> Unit) -> Unit) = ::launchBridgeCleanup,
) {
    companion object {
        const val PORT = "/dev/auto_serial"

        private const val PID_TAG = "BLINKBRIDGE_PID "
        private const val ERR_TAG = "BLINKBRIDGE_ERR "

        // Toybox's `stty raw` on this FYT build leaves IUCLC, ICRNL, IXON/IXOFF, and OPOST on.
        // The captured result was lowercase `aa`/`jh...`, extra newlines, and would also transform
        // commands written back to blink. Name every transformation explicitly instead.
        internal const val TTY_MODE_COMMAND =
            "stty raw -echo -iuclc -icrnl -inlcr -igncr -ixon -ixoff -opost"

        private const val PIDOF_RC_TAG = "PIDOF_RC="

        internal val STOCK_CLIENT_GUARD = """
            STOCK_PACKAGE=com.syu.carlink
            DISABLED_PACKAGE=${'$'}(pm list packages -d "${'$'}STOCK_PACKAGE" 2>/dev/null)
            PM_STATUS=${'$'}?
            if [ "${'$'}PM_STATUS" -ne 0 ] || [ "${'$'}DISABLED_PACKAGE" != "package:${'$'}STOCK_PACKAGE" ]; then
                echo "${ERR_TAG}stock client must be disabled before opening $PORT"
                exit 7
            fi
            RUNNING_PIDS=${'$'}(pidof "${'$'}STOCK_PACKAGE" 2>/dev/null)
            PIDOF_STATUS=${'$'}?
            if [ "${'$'}PIDOF_STATUS" -eq 0 ] && [ -n "${'$'}RUNNING_PIDS" ]; then
                echo "${ERR_TAG}stock client is still running (${ '$' }RUNNING_PIDS)"
                exit 7
            fi
            if [ "${'$'}PIDOF_STATUS" -ne 1 ] || [ -n "${'$'}RUNNING_PIDS" ]; then
                echo "${ERR_TAG}cannot verify that the stock client is stopped"
                exit 7
            fi
        """.trimIndent()

        internal val BRIDGE_SCRIPT = """
            $STOCK_CLIENT_GUARD
            P=${'$'}(readlink -f $PORT)
            if [ ! -c "${'$'}P" ]; then echo "${ERR_TAG}$PORT is not a character device (${'$'}P)"; exit 3; fi
            OLD_MODE=${'$'}(stty -g < "${'$'}P") || { echo "${ERR_TAG}cannot read tty mode on ${'$'}P"; exit 4; }
            READER_PID=
            cleanup() {
                trap - EXIT HUP INT TERM
                if [ -n "${'$'}READER_PID" ]; then
                    kill "${'$'}READER_PID" 2>/dev/null
                    wait "${'$'}READER_PID" 2>/dev/null
                fi
                stty "${'$'}OLD_MODE" < "${'$'}P" 2>/dev/null
            }
            trap cleanup EXIT HUP INT TERM
            $TTY_MODE_COMMAND < "${'$'}P" || { echo "${ERR_TAG}stty failed on ${'$'}P"; exit 5; }
            exec 3<>"${'$'}P" || { echo "${ERR_TAG}cannot open ${'$'}P"; exit 6; }
            cat <&3 &
            READER_PID=${'$'}!
            echo "$PID_TAG${'$'}READER_PID"
            cat >&3
        """.trimIndent()

        /** Starts the bridge. Throws if `su` cannot be run at all. */
        fun open(onLine: (String) -> Unit, onEnded: (String) -> Unit): BlinkAutoSerialChannel {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", BRIDGE_SCRIPT))
            return BlinkAutoSerialChannel(process, onLine, onEnded).also { it.start() }
        }

        /** Whether `blink` has made the node. The link itself is world-visible; its target is not. */
        fun isPresent(): Boolean = runCatching {
            // exists() follows the link, and SELinux may hide the pts behind it from an app, so a
            // listing of /dev is the fallback. minSdk 16 rules out java.nio.file.
            java.io.File(PORT).exists() || java.io.File("/dev").list()?.contains("auto_serial") == true
        }.getOrDefault(false)

        /** Whether the stock client owns the shared port. Unknown is deliberately not available. */
        internal fun stockClientState(
            timeoutMs: Long = 3_000,
            processLauncher: (Array<String>) -> Process = { Runtime.getRuntime().exec(it) },
        ): BlinkPortOwner = try {
            // su failing also exits 1 with no output, like pidof finding nothing, so the root
            // shell reports pidof's own status and only that marker counts as an answer.
            val process = processLauncher(
                arrayOf("su", "-c", "pidof com.syu.carlink; echo \"$PIDOF_RC_TAG\$?\"")
            )
            if (!waitForProcess(process, timeoutMs)) {
                process.destroy()
                BlinkPortOwner.UNKNOWN
            } else {
                val lines = process.inputStream.bufferedReader().readLines()
                    .map { it.trim() }.filter { it.isNotEmpty() }
                val pids = lines.dropLast(1)
                when (lines.lastOrNull()) {
                    "${PIDOF_RC_TAG}0" ->
                        if (pids.isNotEmpty()) BlinkPortOwner.STOCK_CLIENT else BlinkPortOwner.UNKNOWN
                    "${PIDOF_RC_TAG}1" ->
                        if (pids.isEmpty()) BlinkPortOwner.AVAILABLE else BlinkPortOwner.UNKNOWN
                    else -> BlinkPortOwner.UNKNOWN
                }
            }
        } catch (_: Exception) {
            BlinkPortOwner.UNKNOWN
        }
    }

    @Volatile
    var isFinished = false
        private set

    @Volatile
    private var readerPid: String? = null

    private val sink: OutputStream = process.outputStream
    private val writeLock = Any()

    private fun start() {
        Thread({ pump() }, "BlinkAutoSerial-reader").apply { isDaemon = true }.start()
        Thread({
            runCatching {
                process.errorStream.bufferedReader().forEachLine { AppLog.w("NativeAA: [BLINK] su: $it") }
            }
        }, "BlinkAutoSerial-stderr").apply { isDaemon = true }.start()
    }

    private fun pump() {
        var reason = "the bridge ended"
        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.US_ASCII), 8192)
            while (true) {
                val raw = reader.readLine() ?: break
                val line = raw.trim('\r', '\n', ' ', '\u0000')
                if (line.isEmpty()) continue
                when {
                    line.startsWith(PID_TAG) -> {
                        readerPid = line.removePrefix(PID_TAG).trim()
                        AppLog.i("NativeAA: [BLINK] bridge up on $PORT (reader pid $readerPid)")
                    }
                    line.startsWith(ERR_TAG) -> {
                        reason = line.removePrefix(ERR_TAG)
                        AppLog.e("NativeAA: [BLINK] $reason")
                    }
                    else -> onLine(line)
                }
            }
        } catch (e: IOException) {
            if (!isFinished) reason = "read failed: ${e.message}"
        } catch (e: Exception) {
            reason = "reader crashed: ${e.javaClass.simpleName}: ${e.message}"
            AppLog.e("NativeAA: [BLINK] $reason", e)
        } finally {
            val exit = runCatching { process.exitValue() }.getOrNull()
            if (!isFinished) {
                isFinished = true
                onEnded(reason + (exit?.let { " (exit $it)" } ?: ""))
            }
        }
    }

    /** Sends one complete WPP frame to the phone. */
    @Throws(IOException::class)
    fun sendFrame(frame: ByteArray) {
        if (isFinished) throw IOException("the $PORT bridge is closed")
        val line = BlinkAutoLine.encode(frame)
        synchronized(writeLock) {
            sink.write(line.toByteArray(Charsets.US_ASCII))
            sink.flush()
        }
        AppLog.d("NativeAA: [BLINK] [TX] ${line.trimEnd()}")
    }

    fun close() {
        isFinished = true
        readerPid = null
        cleanupScheduler { stopBridgeProcess(process, sink) }
    }

    /**
     * One handshake's view of the channel. Frames from the phone are queued by [deliver]; bytes
     * written to [output] go out a whole frame at a time on flush. Closing it ends only the view.
     */
    class FrameStream(private val sendFrame: (ByteArray) -> Unit) {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private var head: ByteArray? = null
        private var headOffset = 0

        @Volatile
        var closed = false
            private set

        private val END = ByteArray(0)

        fun deliver(frame: ByteArray) {
            if (!closed) queue.put(frame)
        }

        fun close() {
            synchronized(pendingOut) {
                if (closed) return
                closed = true
                pendingOut.reset()
            }
            queue.put(END)
        }

        val input: InputStream = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (true) {
                    val current = head
                    if (current != null && headOffset < current.size) {
                        val take = minOf(len, current.size - headOffset)
                        System.arraycopy(current, headOffset, b, off, take)
                        headOffset += take
                        return take
                    }
                    head = null
                    headOffset = 0
                    if (closed && queue.isEmpty()) return -1
                    val next = try {
                        queue.take()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return -1
                    }
                    if (next === END) return -1
                    head = next
                }
            }

            override fun available(): Int = (head?.size ?: 0) - headOffset

            override fun close() = this@FrameStream.close()
        }

        private val pendingOut = ByteArrayOutputStream()

        val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                synchronized(pendingOut) {
                    if (closed) throw IOException("the BLINK handshake stream is closed")
                    pendingOut.write(b)
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                synchronized(pendingOut) {
                    if (closed) throw IOException("the BLINK handshake stream is closed")
                    pendingOut.write(b, off, len)
                }
            }

            override fun flush() {
                synchronized(pendingOut) {
                    if (closed) throw IOException("the BLINK handshake stream is closed")
                    val bytes = pendingOut.toByteArray()
                    val (frames, used) = splitFrames(bytes)
                    pendingOut.reset()
                    if (used < bytes.size) pendingOut.write(bytes, used, bytes.size - used)
                    for (frame in frames) sendFrame(frame)
                }
            }

            override fun close() = this@FrameStream.close()
        }
    }
}

/** Runs blocking bridge teardown away from lifecycle callers. */
internal fun launchBridgeCleanup(cleanup: () -> Unit) {
    Thread(cleanup, "BlinkAutoSerial-cleanup").apply { isDaemon = true }.start()
}

/** Closes the bridge's stdin first so its shell trap can stop the reader and restore the tty. */
internal fun stopBridgeProcess(process: Process, sink: OutputStream, timeoutSeconds: Long = 3) {
    runCatching { sink.close() }
    val exited = waitForProcess(process, timeoutSeconds * 1_000)
    if (!exited) {
        runCatching { process.destroy() }
        waitForProcess(process, timeoutSeconds * 1_000)
    }
}

/** API-16-compatible bounded wait; Process.waitFor(timeout, unit) is unavailable on old Android. */
internal fun waitForProcess(process: Process, timeoutMs: Long): Boolean {
    val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000L
    while (true) {
        try {
            process.exitValue()
            return true
        } catch (_: IllegalThreadStateException) {
            if (System.nanoTime() >= deadline) return false
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }
}

/** Splits [bytes] into whole WPP frames; returns them and how many bytes they used. */
internal fun splitFrames(bytes: ByteArray): Pair<List<ByteArray>, Int> {
    val out = ArrayList<ByteArray>()
    var at = 0
    while (bytes.size - at >= WppFraming.HEADER_SIZE) {
        val size = WppFraming.decodePayloadSize(bytes.copyOfRange(at, at + WppFraming.HEADER_SIZE))
        val end = at + WppFraming.HEADER_SIZE + size
        if (end > bytes.size) break
        out.add(bytes.copyOfRange(at, end))
        at = end
    }
    return out to at
}
