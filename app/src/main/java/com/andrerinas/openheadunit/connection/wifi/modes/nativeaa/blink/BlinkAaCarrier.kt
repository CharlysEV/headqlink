package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.blink

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalModuleCarrier
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the Android Auto handshake over FYT's BLINK module through `blink`'s `/dev/auto_serial`.
 *
 * On FYT UIS7862 units with DUDUOS the phone pairs with the external module ("DUDUAUTO") for calls,
 * and the module, not this unit's own radio, owns the Android Auto RFCOMM service. `blink` relays
 * that channel line by line (see [BlinkAutoLine]). This takes the place the stock
 * `com.syu.carlink` holds, so the phone keeps calls on the module and the car's speakers.
 *
 * Sequence as captured from the stock client: the phone's hands-free link comes up (`JH`), `blink`
 * arms the Android Auto service by itself, the phone opens it (`AA`), and the head unit speaks
 * first. Nothing has to be sent to `blink` to arm it.
 *
 * After a handshake the phone keeps pinging over this channel for as long as the session lives, and
 * the stock client answers; [BlinkAutoLine.idleReply] does the same while no handshake owns it.
 */
class BlinkAaCarrier(
    private val serve: suspend (HandshakeLink) -> Unit,
    private val isRunning: () -> Boolean,
    private val isFinishedForSession: () -> Boolean,
    private val mayServeHandshake: () -> Boolean,
    private val onPhoneEvidence: () -> Unit,
    /** Called with the reason whenever it changes, and with null once the port is open again. */
    private val onRefusalChanged: (BlinkRefusal?) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
) : ExternalModuleCarrier {

    companion object {
        /** How long to wait before starting the bridge again after an open bridge ended. */
        const val REOPEN_DELAY_MS = 5_000L

        /** How often the run loop looks at the flags the reader thread sets. */
        private const val POLL_MS = 200L
    }

    @Volatile private var channel: BlinkAutoSerialChannel? = null
    @Volatile private var current: BlinkAutoSerialChannel.FrameStream? = null
    private val channelOpenedAt = AtomicLong(0L)
    @Volatile private var phoneAddress: String? = null
    @Volatile private var stopped = false
    @Volatile private var carrierJob: Job? = null

    private var attempts = 0
    private var framesIn = 0

    @Volatile private var refusal: BlinkRefusal? = null
    private var consecutiveRefusals = 0
    @Volatile private var wakeHintLogged = false

    /** The module wakes the phone itself; nothing is sent. */
    override val sendsWake: Boolean get() = false

    suspend fun run() {
        carrierJob = currentCoroutineContext()[Job]
        while (keepRunning()) {
            // One su per pass: the bridge checks the stock client itself before it opens the port,
            // and its exit code says which check refused it.
            val ended = AtomicReference<String?>(null)
            val opened = try {
                BlinkAutoSerialChannel.open(::onLine) { why -> ended.set(why) }
            } catch (e: Exception) {
                refuse(BlinkRefusal.ROOT_DENIED, "could not run su: ${e.message}")
                continue
            }
            channel = opened
            var announced = false
            try {
                while (keepRunning() && !opened.isFinished) {
                    if (!announced && opened.portOpened) {
                        announced = true
                        markOpen()
                    }
                    // After a handoff the bridge stays up to answer the phone's pings, which the
                    // stock client does for the whole session, but no new handshake starts on it.
                    if (channelOpenedAt.get() != 0L && isFinishedForSession()) {
                        channelOpenedAt.set(0L)
                        AppLog.i("NativeAA: [BLINK] the phone reopened Android Auto while a session is up; not starting another handshake.")
                    } else if (channelOpenedAt.get() != 0L && mayServeHandshake()) {
                        // getAndSet, so an AA that lands between a read and a reset is not lost.
                        if (channelOpenedAt.getAndSet(0L) != 0L) serveOnce(opened)
                    } else {
                        delay(POLL_MS)
                    }
                }
            } finally {
                current?.close()
                current = null
                opened.close()
                channel = null
            }
            if (!keepRunning()) break
            if (opened.portOpened) {
                AppLog.i("NativeAA: [BLINK] bridge ended: ${ended.get() ?: "closed"}")
                delay(REOPEN_DELAY_MS)
            } else {
                // Ended before it opened the port: the guard in the bridge refused it.
                refuse(
                    BlinkAutoSerialChannel.refusalFor(opened.exitCode, opened.scriptSpoke),
                    "${ended.get() ?: "the bridge ended"}, exit ${opened.exitCode}"
                )
            }
        }
        AppLog.i("NativeAA: [BLINK] carrier stopped after $attempts handshake(s), $framesIn frame(s) from the phone.")
    }

    /** Logs a refusal once per distinct reason and waits longer each time it repeats. */
    private suspend fun refuse(reason: BlinkRefusal, detail: String) {
        if (refusal != reason) {
            refusal = reason
            AppLog.e("NativeAA: [BLINK] not opening ${BlinkAutoSerialChannel.PORT}: $reason ($detail).")
            onRefusalChanged(reason)
        }
        val wait = BlinkRefusalBackoff.delayMs(consecutiveRefusals++)
        delay(wait)
    }

    private fun markOpen() {
        AppLog.i("NativeAA: [BLINK] listening on ${BlinkAutoSerialChannel.PORT} for the phone's Android Auto channel.")
        consecutiveRefusals = 0
        if (refusal != null) {
            refusal = null
            onRefusalChanged(null)
        }
    }

    private suspend fun serveOnce(open: BlinkAutoSerialChannel) {
        attempts++
        val stream = BlinkAutoSerialChannel.FrameStream(open::sendFrame)
        current = stream
        AppLog.i(
            "NativeAA: [BLINK] the phone${phoneAddress?.let { " ($it)" } ?: ""} opened Android Auto " +
                "on the module — handshake #$attempts."
        )
        try {
            serve(BlinkLink(stream, phoneAddress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("NativeAA: [BLINK] handshake ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            stream.close()
            if (current === stream) current = null
            wakeHintLogged = false
        }
    }

    /** Reader thread. Routes a line; never blocks on the handshake. */
    private fun onLine(line: String) {
        when (val event = BlinkAutoLine.parse(line)) {
            is BlinkAutoLine.Event.PhoneLinked -> {
                phoneAddress = event.address
                AppLog.i("NativeAA: [BLINK] hands-free link up to ${event.address}.")
            }
            BlinkAutoLine.Event.ChannelOpened -> {
                AppLog.i("NativeAA: [BLINK] the phone opened the Android Auto channel.")
                // A new channel supersedes whatever handshake was waiting on the old one.
                current?.close()
                channelOpenedAt.set(now())
                onPhoneEvidence()
            }
            BlinkAutoLine.Event.ChannelClosed -> {
                AppLog.i("NativeAA: [BLINK] the phone closed the Android Auto channel.")
                channelOpenedAt.set(0L)
                current?.close()
            }
            is BlinkAutoLine.Event.Frame -> {
                framesIn++
                val live = current
                if (live != null && !live.closed) {
                    live.deliver(event.bytes)
                    return
                }
                val reply = BlinkAutoLine.idleReply(event.bytes)
                if (reply != null) {
                    runCatching { channel?.sendFrame(reply) }
                        .onFailure { AppLog.w("NativeAA: [BLINK] idle reply failed: ${it.message}") }
                } else {
                    AppLog.d("NativeAA: [BLINK] [RX] unowned frame ${BlinkAutoLine.toHex(event.bytes)}")
                }
            }
            is BlinkAutoLine.Event.Other -> AppLog.d("NativeAA: [BLINK] [RX] ${event.line}")
        }
    }

    /**
     * `blink` arms the Android Auto service itself once the phone's hands-free link is up, so
     * nothing is sent. Called on every credential delivery and resume, so the hint is logged once
     * per arming.
     */
    override fun requestWake() {
        if (wakeHintLogged) return
        wakeHintLogged = true
        AppLog.i(
            "NativeAA: [BLINK] nothing to send for a wake — the module opens Android Auto on its own " +
                "once the phone connects to it for calls."
        )
    }

    override fun close() {
        stopped = true
        current?.close()
        channel?.close()
    }

    /** Unlike the ZBT carrier, a finished session does not end this: the pings still need answers. */
    private fun keepRunning(): Boolean =
        !stopped && isRunning() && carrierJob?.isActive != false
}

/** One handshake over the BLINK bridge. */
class BlinkLink(
    private val stream: BlinkAutoSerialChannel.FrameStream,
    override val peerAddress: String?
) : HandshakeLink {
    override val input: InputStream get() = stream.input
    override val output: OutputStream get() = stream.output
    override val peerName: String? = null
    override val radioLabel: String = "FYT external Bluetooth module (${BlinkAutoSerialChannel.PORT})"

    /** The phone is bonded to the module, not to anything `android.bluetooth` can dial. */
    override val persistPeerForAutoStart: Boolean = false

    /** `AA` is the phone opening the channel, so it is known to be there. */
    override val peerReportedPresent: Boolean = true

    /** The channel is open before we speak, as on the unit's own radio. */
    override val retransmitsWhileSilent: Boolean = false

    /** Ends this handshake's view only; the bridge stays up for the next one and for pings. */
    override fun close() {
        stream.close()
    }
}
