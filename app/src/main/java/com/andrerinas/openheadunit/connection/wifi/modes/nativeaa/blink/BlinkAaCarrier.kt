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
    private val now: () -> Long = { System.currentTimeMillis() }
) : ExternalModuleCarrier {

    companion object {
        /** How long to wait before starting the bridge again after it ended. */
        const val REOPEN_DELAY_MS = 5_000L

        /** How often the run loop looks at the flags the reader thread sets. */
        private const val POLL_MS = 200L
    }

    @Volatile private var channel: BlinkAutoSerialChannel? = null
    @Volatile private var current: BlinkAutoSerialChannel.FrameStream? = null
    @Volatile private var channelOpenedAt = 0L
    @Volatile private var phoneAddress: String? = null
    @Volatile private var stopped = false
    @Volatile private var carrierJob: Job? = null

    private var attempts = 0
    private var framesIn = 0

    suspend fun run() {
        carrierJob = currentCoroutineContext()[Job]
        var blockedOwner: BlinkPortOwner? = null
        while (keepRunning()) {
            val owner = BlinkAutoSerialChannel.stockClientState()
            if (owner != BlinkPortOwner.AVAILABLE) {
                if (blockedOwner != owner) {
                    val reason = if (owner == BlinkPortOwner.STOCK_CLIENT) {
                        "com.syu.carlink owns the same port; disable it with pm disable-user com.syu.carlink"
                    } else {
                        "the stock client's port ownership could not be verified"
                    }
                    AppLog.e("NativeAA: [BLINK] refusing to open /dev/auto_serial because $reason.")
                    blockedOwner = owner
                }
                delay(REOPEN_DELAY_MS)
                continue
            }
            blockedOwner = null
            var endedWhy: String? = null
            val opened = try {
                BlinkAutoSerialChannel.open(::onLine) { why -> endedWhy = why }
            } catch (e: Exception) {
                AppLog.e("NativeAA: [BLINK] could not start the root bridge to ${BlinkAutoSerialChannel.PORT}: ${e.message}")
                delay(REOPEN_DELAY_MS)
                continue
            }
            channel = opened
            AppLog.i("NativeAA: [BLINK] listening on ${BlinkAutoSerialChannel.PORT} for the phone's Android Auto channel.")
            try {
                while (keepRunning() && !opened.isFinished) {
                    val openedAt = channelOpenedAt
                    // After a handoff the bridge stays up to answer the phone's pings, which the
                    // stock client does for the whole session, but no new handshake starts on it.
                    if (openedAt != 0L && isFinishedForSession()) {
                        channelOpenedAt = 0L
                        AppLog.i("NativeAA: [BLINK] the phone reopened Android Auto while a session is up; not starting another handshake.")
                    } else if (openedAt != 0L && mayServeHandshake()) {
                        channelOpenedAt = 0L
                        serveOnce(opened)
                    } else {
                        delay(POLL_MS)
                    }
                }
            } finally {
                current?.close()
                current = null
                opened.close()
                channel = null
                AppLog.i("NativeAA: [BLINK] bridge ended: ${endedWhy ?: "closed"}")
            }
            if (keepRunning()) delay(REOPEN_DELAY_MS)
        }
        AppLog.i("NativeAA: [BLINK] carrier stopped after $attempts handshake(s), $framesIn frame(s) from the phone.")
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
                channelOpenedAt = now()
                onPhoneEvidence()
            }
            BlinkAutoLine.Event.ChannelClosed -> {
                AppLog.i("NativeAA: [BLINK] the phone closed the Android Auto channel.")
                channelOpenedAt = 0L
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

    /** `blink` arms the Android Auto service itself once the phone's hands-free link is up. */
    override fun requestWake() {
        AppLog.i(
            "NativeAA: [BLINK] wake requested — the module arms Android Auto on its own once the phone " +
                "connects for calls; connect the phone to DUDUAUTO."
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
    override val radioLabel: String = "FYT BLINK module (${BlinkAutoSerialChannel.PORT})"

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
