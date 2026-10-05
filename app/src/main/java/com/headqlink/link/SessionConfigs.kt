package com.headqlink.link

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import dev.qdauto.core.session.PhoneIdentity
import dev.qdauto.core.session.PhoneInfoOverrides
import dev.qdauto.core.session.SessionConfig
import dev.qdauto.core.session.VideoDropPolicy
import dev.qdauto.core.session.VideoWriteGate
import dev.qdauto.core.session.WhitelistMode
import dev.qdauto.core.wire.CarInfo

/**
 * Configuración de cada sesión del motor QDAuto (qdauto §4.12), calculada al aceptar el TCP con los ajustes de ese
 * momento. Replica lo que hace SspSession (búferes, TOS, prioridad de hilos, lista blanca, `DISCONNECT_REQ`,
 * `PHONE_INFO` del fork) y elige la política de vídeo: `MAX_LAG` con encoder propio, `NONE` en el reenvío directo de
 * AA (con la puerta de escritura del freno si está activo).
 */
internal object SessionConfigs {
    /** Con el freno, la cola real tiene que ser la nuestra (SspSession.BRAKE_SEND_BUFFER). */
    const val BRAKE_SEND_BUFFER = 48 * 1024
    const val SEND_BUFFER = 192 * 1024
    const val RECEIVE_BUFFER = 6 * 1024 * 1024

    /** DSCP CS5 (0xA0) → categoría WMM de vídeo (AC_VI). */
    const val TOS_VIDEO = 0xA0

    /** Freno: el siguiente frame no sale hasta que la cola del kernel baja de esto (SspSession.BRAKE_OUTQ). */
    const val BRAKE_OUTQ = 24 * 1024

    /** Reenvío directo: el coche o el núcleo piden IDR, pero cada ciclo de foco congela la imagen; 1,5 s entre peticiones. */
    const val PASSTHROUGH_MIN_KEYFRAME_MS = 1_500L

    /**
     * Tope de un mensaje de vídeo (48 B de cabeceras + Annex-B): el receptor del C10 se cuelga con más de ~512 KiB
     * (`SessionConfig.CAR_RECEIVER_LIMIT_BYTES`). Un frame mayor lo descarta el núcleo y se pide otro IDR más pequeño.
     */
    const val MAX_VIDEO_MESSAGE_BYTES = SessionConfig.DEFAULT_MAX_VIDEO_MESSAGE_BYTES

    fun forCurrentSettings(cfg: Config, phone: PhoneIdentity, portFor: (Int) -> SessionPort?): SessionConfig {
        val mode = cfg.mode()
        val passthrough = Config.MODE_AA == mode && !cfg.aaReencode()
        val brake = Config.isAa(mode) && cfg.aaBrake()
        val qdlinkPhoneInfo = "qdlink" == cfg.qdPhoneInfo()
        return SessionConfig(
            tcpNoDelay = true,
            sendBufferBytes = if (brake) BRAKE_SEND_BUFFER else SEND_BUFFER,
            receiveBufferBytes = RECEIVE_BUFFER,
            keepAlive = true,
            trafficClass = TOS_VIDEO,
            onThreadStart = { LowLatency.boostCurrentThread() },
            heartbeatInitialDelayMs = 1_000,
            heartbeatPeriodMs = 3_000,
            watchdogCheckIntervalMs = 1_000,
            watchdogWarnMs = 5_000,
            watchdogTimeoutMs = 10_000,
            // Como el fork (SspSession.lastRx desde el accept): un coche que abre el TCP y nunca manda un 5A5A también se
            // corta a los 10 s. El núcleo, como QDLink, no cortaba hasta el primer 5A5A.
            watchdogRequiresCarTraffic = false,
            writeStallTimeoutMs = 10_000,
            whitelistMode = if (cfg.sendWhitelist()) WhitelistMode.ALWAYS else WhitelistMode.NEVER,
            whitelistValue = 1,
            whitelistInitialDelayMs = 1_000,
            whitelistPeriodMs = 1_000,
            replyDisconnectReq = true,
            closeOnDisconnectReq = true,
            phone = phone,
            phoneInfoOverridesFor = if (qdlinkPhoneInfo) null else { car -> forkPhoneInfo(cfg, car) },
            requireVideoArgsForPlay = false,
            videoDropPolicy = if (passthrough) VideoDropPolicy.NONE else VideoDropPolicy.MAX_LAG,
            videoMaxLagMs = 150,
            minKeyframeRequestIntervalMs = if (passthrough) PASSTHROUGH_MIN_KEYFRAME_MS else 1_000,
            videoWriteGate = if (passthrough && brake) brakeGate(portFor) else null,
            videoWriteGateMaxWaitMs = 250,
            videoWriteGatePollMs = 2,
            maxVideoMessageBytes = MAX_VIDEO_MESSAGE_BYTES,
        )
    }

    /** Puerta del freno: una muestra de la cola del kernel por consulta; sin NetStat (-1) queda abierta. */
    private fun brakeGate(portFor: (Int) -> SessionPort?) = VideoWriteGate { s ->
        val q = portFor(s.id)?.writerOutq() ?: -1
        q < 0 || q <= BRAKE_OUTQ
    }

    /** `PHONE_INFO` del fork (SspSession.sendPhoneInfo): los ocho campos al tamaño del vídeo; 800×480 sin coche. */
    fun forkPhoneInfo(cfg: Config, car: CarInfo?): PhoneInfoOverrides {
        var cw = car?.carWidth ?: 0
        var ch = car?.carHeight ?: 0
        if (cw <= 0 || ch <= 0) {
            cw = 800
            ch = 480
        }
        val d = cfg.videoSize(cw, ch, 0, 0)
        val w = d[0]
        val h = d[1]
        return PhoneInfoOverrides(
            phoneWidth = w, phoneHeight = h, mirrorWidth = w, mirrorHeight = h,
            phoneWidthInApp = w, phoneHeightInApp = h, mirrorWidthInApp = w, mirrorHeightInApp = h,
        )
    }

    /** Identidad del móvil para `PHONE_INFO` y el AppStatus: la del fork (MODEL y UUID propio) o vacía como QDLink. */
    fun phoneIdentity(ctx: Context, cfg: Config): PhoneIdentity {
        val size = realScreenSize(ctx)
        val qdlink = "qdlink" == cfg.qdPhoneInfo()
        return PhoneIdentity(
            screenLongSide = maxOf(size.x, size.y),
            screenShortSide = minOf(size.x, size.y),
            brand = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = Config.LINK_PROTOCOL_VERSION,
            phoneUuid = if (qdlink) "" else cfg.deviceUuid(),
            phoneName = if (qdlink) "" else cfg.deviceName(),
        )
    }

    private fun realScreenSize(ctx: Context): Point {
        val p = Point(2340, 1080)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                p.set(b.width(), b.height())
            } else {
                val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                @Suppress("DEPRECATION")
                d?.getRealSize(p)
            }
        } catch (e: RuntimeException) {
            L.w("tamaño de pantalla no disponible: ${e.message}")
        }
        return p
    }
}
