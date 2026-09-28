package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.blink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BlinkAutoSerialChannelTest {

    @Test
    fun `bridge script restores tty mode and cleans up its reader`() {
        val script = BlinkAutoSerialChannel.BRIDGE_SCRIPT
        assertTrue(script.contains("stty -g"))
        assertTrue(script.contains("trap cleanup"))
        assertTrue(script.contains("wait \"${'$'}READER_PID\""))
        assertTrue(script.contains("stty \"${'$'}OLD_MODE\""))
    }

    @Test
    fun `bridge startup guard only accepts a disabled and stopped stock client`() {
        val guard = BlinkAutoSerialChannel.STOCK_CLIENT_GUARD
        assertTrue(BlinkAutoSerialChannel.BRIDGE_SCRIPT.indexOf(guard) < BlinkAutoSerialChannel.BRIDGE_SCRIPT.indexOf("exec 3<>"))

        assertEquals(0, runGuard(guard, "echo package:com.syu.carlink", "return 1"))
        assertEquals(7, runGuard(guard, "return 0", "return 1"))
        assertEquals(7, runGuard(guard, "echo package:com.syu.carlink", "echo 1234; return 0"))
        assertEquals(7, runGuard(guard, "echo package:com.syu.carlink", "return 2"))
        assertEquals(7, runGuard(guard, "return 2", "return 1"))
    }

    @Test
    fun `stopping a bridge closes stdin and destroys a process that does not exit`() {
        val process = FakeProcess(terminates = false)

        stopBridgeProcess(process, process.outputStream, timeoutSeconds = 0)

        assertTrue(process.stdin.closed)
        assertTrue(process.destroyed)
    }

    @Test
    fun `closing a channel schedules process cleanup without running it on the caller`() {
        val process = FakeProcess(terminates = false)
        var cleanup: (() -> Unit)? = null
        val channel = BlinkAutoSerialChannel(process, {}, {}, cleanupScheduler = { cleanup = it })

        channel.close()

        assertTrue(channel.isFinished)
        assertFalse(process.stdin.closed)
        assertFalse(process.destroyed)
        assertTrue(cleanup != null)

        cleanup!!.invoke()
        assertTrue(process.stdin.closed)
        assertTrue(process.destroyed)
    }

    @Test
    fun `a closed handshake stream cannot write into a later phone channel`() {
        val stream = BlinkAutoSerialChannel.FrameStream { fail("closed stream sent a frame") }
        stream.close()

        expectIOException { stream.output.write(1) }
        expectIOException { stream.output.flush() }
    }

    @Test
    fun `close cannot complete while flush is sending a frame`() {
        val closeStarted = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closedDuringSend = AtomicBoolean(false)
        lateinit var stream: BlinkAutoSerialChannel.FrameStream
        lateinit var closer: Thread
        stream = BlinkAutoSerialChannel.FrameStream {
            closer = Thread {
                closeStarted.countDown()
                stream.close()
                closeReturned.countDown()
            }.apply { start() }
            assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
            closedDuringSend.set(closeReturned.await(1, TimeUnit.SECONDS))
        }
        stream.output.write(BlinkAutoLine.decodeHex("000200060800")!!)

        stream.output.flush()
        closer.join(1_000)

        assertFalse("close returned while sendFrame was in progress", closedDuringSend.get())
        assertEquals(0, closeReturned.count)
    }

    @Test
    fun `stock client ownership is tri-state and uncertainty fails closed`() {
        assertEquals(
            BlinkPortOwner.STOCK_CLIENT,
            BlinkAutoSerialChannel.stockClientState { FakeProcess(stdout = "1234\nPIDOF_RC=0\n") }
        )
        // pidof's normal "no such process" answer, reported by the root shell itself.
        assertEquals(
            BlinkPortOwner.AVAILABLE,
            BlinkAutoSerialChannel.stockClientState { FakeProcess(stdout = "PIDOF_RC=1\n") }
        )
        // su refused or missing: same exit code as pidof, but no marker, so it is not proof.
        assertEquals(
            BlinkPortOwner.UNKNOWN,
            BlinkAutoSerialChannel.stockClientState { FakeProcess(stdout = "", exitCode = 1) }
        )
        assertEquals(
            BlinkPortOwner.UNKNOWN,
            BlinkAutoSerialChannel.stockClientState { FakeProcess(stdout = "") }
        )
        assertEquals(
            BlinkPortOwner.UNKNOWN,
            BlinkAutoSerialChannel.stockClientState { FakeProcess(stdout = "PIDOF_RC=127\n") }
        )
        assertEquals(
            BlinkPortOwner.UNKNOWN,
            BlinkAutoSerialChannel.stockClientState { FakeProcess(stdout = "1234\nPIDOF_RC=1\n") }
        )
        val stuck = FakeProcess(terminates = false)
        assertEquals(
            BlinkPortOwner.UNKNOWN,
            BlinkAutoSerialChannel.stockClientState(timeoutMs = 0) { stuck }
        )
        assertTrue(stuck.destroyed)
        assertEquals(
            BlinkPortOwner.UNKNOWN,
            BlinkAutoSerialChannel.stockClientState { throw IOException("su denied") }
        )
    }

    private fun runGuard(guard: String, pmBody: String, pidofBody: String): Int {
        val script = """
            pm() { $pmBody; }
            pidof() { $pidofBody; }
            $guard
        """.trimIndent()
        return ProcessBuilder("sh", "-c", script).start().waitFor()
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FakeProcess(
        stdout: String = "",
        private val terminates: Boolean = true,
        private val exitCode: Int = 0,
    ) : Process() {
        val stdin = TrackingOutputStream()
        var destroyed = false
        private val stdoutBytes = stdout.toByteArray()

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = ByteArrayInputStream(stdoutBytes)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = if (destroyed) 0 else exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = terminates || destroyed
        override fun exitValue(): Int = if (terminates || destroyed) exitCode else throw IllegalThreadStateException()
        override fun destroy() {
            destroyed = true
        }
    }
}
