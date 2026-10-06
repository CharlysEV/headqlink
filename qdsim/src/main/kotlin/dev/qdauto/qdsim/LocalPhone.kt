package dev.qdauto.qdsim

import dev.qdauto.core.discovery.AckPolicy
import dev.qdauto.core.discovery.CarAnnouncement
import dev.qdauto.core.discovery.DiscoveryConfig
import dev.qdauto.core.session.CarReturn
import dev.qdauto.core.session.CloseReason
import dev.qdauto.core.session.KeyframeReason
import dev.qdauto.core.session.PhoneLink
import dev.qdauto.core.session.PhoneLinkConfig
import dev.qdauto.core.session.PhoneLinkListener
import dev.qdauto.core.session.PhoneSession
import dev.qdauto.core.session.RecoveryConfig
import dev.qdauto.core.session.SessionConfig
import dev.qdauto.core.session.SessionListener
import dev.qdauto.core.session.VideoDropPolicy
import dev.qdauto.core.session.WhitelistMode
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.wire.ControlMessage
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * «Móvil» de prueba dentro del propio qdsim (`--local-phone`): el núcleo con la configuración del motor QDAuto del
 * fork (ACK en cada broadcast y reenviado cada 2 s hasta el TCP, relevo, reconexión inmediata, puerto estable,
 * re-acogida tras un corte con ACK no pedidos, `MAX_LAG`, `DISCONNECT_RSP` y cierre) y un vídeo de mentira que vive
 * entre sesiones, como el `VideoHub`. Sirve para probar los escenarios en el PC sin el teléfono; no sustituye a la
 * prueba con el móvil. Con [usbFraming], la trama por bloques del USB sobre el TCP (como el móvil con `qd_usb_over_tcp`).
 */
class LocalPhone(private val log: QdLog, private val usbFraming: Boolean = false) : Closeable {
    /** Por dónde volvió el coche la última vez (`vuelta del coche tras X s: …`), o `null` (se puede borrar). */
    @Volatile
    var lastReturn: Pair<CarReturn, Long>? = null
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xC0.toByte(), 0x29)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

    @Volatile
    private var active: PhoneSession? = null
    private val keyRequested = AtomicBoolean(true)
    private val running = AtomicBoolean(true)

    private val link = PhoneLink(
        PhoneLinkConfig(
            discovery = DiscoveryConfig(deviceName = "qdsim-movil", deviceUuid = "qdsim-movil-uuid", ackPolicy = AckPolicy.UNTIL_CONNECTED),
            session = SessionConfig(
                watchdogTimeoutMs = 10_000,
                watchdogRequiresCarTraffic = false, // como SessionConfigs del fork: cuenta desde el accept
                whitelistMode = WhitelistMode.ALWAYS,
                replyDisconnectReq = true,
                closeOnDisconnectReq = true,
                requireVideoArgsForPlay = false,
                videoDropPolicy = VideoDropPolicy.MAX_LAG,
            ),
            retryDelayMs = 0,
            reAckIntervalMs = 400,
            supersedeOnRebroadcast = true,
            recovery = RecoveryConfig(reclaimWindowMs = 300_000, watchMs = 300_000),
            blockFraming = usbFraming,
        ),
        object : PhoneLinkListener {
            override fun onSessionStarted(session: PhoneSession) = say("móvil: S${session.id} conectada desde ${session.remoteAddress}")
            override fun onSessionEnded(session: PhoneSession, reason: CloseReason) = say("móvil: S${session.id} terminada: $reason")
            override fun onSessionSuperseded(old: PhoneSession, car: CarAnnouncement) =
                say("móvil: relevo de S${old.id} (el coche se reanunció)")

            override fun onReclaimStarted(car: CarAnnouncement, mirrorPort: Int, reason: CloseReason) =
                say("móvil: re-acogida tras ${reason.kind} en el puerto $mirrorPort (ACK no pedidos a ${car.host})")

            override fun onCarBack(car: CarAnnouncement, how: CarReturn, afterMs: Long) {
                lastReturn = how to afterMs
                say("móvil: vuelta del coche tras ${afterMs} ms: ${how.label}")
            }
        },
        object : SessionListener {},
        log,
    ) { s ->
        object : SessionListener {
            override fun onVideoControl(play: Boolean, playStatus: Int, message: ControlMessage) {
                if (!play) return
                s.sendCodecConfig(sps + pps)
                active = s
            }

            override fun onKeyframeRequested(reason: KeyframeReason) {
                if (active === s) keyRequested.set(true)
            }

            override fun onClosed(reason: CloseReason) {
                if (active === s) active = null
            }
        }
    }

    private val pump = Thread({
        var i = 0
        while (running.get()) {
            val s = active
            if (s != null) {
                val key = keyRequested.getAndSet(false)
                val body = ByteArray(if (key) 30_000 else 3_000 + (i % 7) * 500) { j -> ((j * 7 + i) % 255 + 1).toByte() }
                val frame = byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41) + body
                s.sendFrame(frame, 0, frame.size, key, i * 33_333L, null)
                i++
            }
            try {
                Thread.sleep(33)
            } catch (_: InterruptedException) {
                return@Thread
            }
        }
    }, "qdsim-movil-video").apply { isDaemon = true }

    fun start(): LocalPhone {
        link.start()
        pump.start()
        say("móvil local escuchando en UDP 18463 (motor QDAuto con los ajustes del fork" + (if (usbFraming) "; trama USB de 512 B sobre el TCP" else "") + ")")
        return this
    }

    override fun close() {
        running.set(false)
        link.close()
        pump.interrupt()
    }

    private fun say(msg: String) = println("               $msg")
}
