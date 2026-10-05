package dev.qdauto.core.session

import dev.qdauto.core.wire.BtAddrRequest
import dev.qdauto.core.wire.FrameReader
import dev.qdauto.core.wire.SpeechArgs

/** Datos del teléfono que acaban en `PHONE_INFO` y en el AppStatus. Por defecto, un S25 Ultra en FHD+. */
data class PhoneIdentity(
    /** Lado largo físico de la pantalla en px (`getRealSize`, sin importar la rotación). */
    val screenLongSide: Int = 2340,
    /** Lado corto físico en px. */
    val screenShortSide: Int = 1080,
    /** `Build.MANUFACTURER`. */
    val brand: String = "samsung",
    /** `Build.MODEL`. */
    val model: String = "SM-S938B",
    /** `Build.VERSION.SDK_INT` (AppStatus `versionAndroid` y `PHONE_INFO.PlatformVersion`). */
    val sdkInt: Int = 36,
    /** `PHONE_INFO.Version`: QDLink manda su versionName "1.9.7"; el coche podría exigir una versión mínima. */
    val appVersion: String = "1.9.7",
    /** QDLink los manda vacíos (setters sin llamadas, LC/a.java:938-939). */
    val phoneUuid: String = "",
    val phoneName: String = "",
    val passistMobileNum: String = "",
)

/** Fuerza valores concretos de `PHONE_INFO` (spec §10.3); `null` = el cálculo de QDLink. */
data class PhoneInfoOverrides(
    val phoneWidth: Int? = null,
    val phoneHeight: Int? = null,
    val mirrorWidth: Int? = null,
    val mirrorHeight: Int? = null,
    val phoneWidthInApp: Int? = null,
    val phoneHeightInApp: Int? = null,
    val mirrorWidthInApp: Int? = null,
    val mirrorHeightInApp: Int? = null,
    val mirrorTypeSupport: Int? = null,
    val platform: Int? = null,
    val platformVersion: String? = null,
)

/**
 * Fuerza campos de la cabecera de vídeo; `null` = automático: modo in-app de QDLink (spec §8.7):
 * W×H = par(CarWidth)×par(CarHeight), appType 1, ángulo 90, orientación 1 y eco de `VIDEO_ARGS`
 * (EncodingType/FrameRate/BitRate/FrameInterval, el valor crudo aunque sea 0).
 */
data class VideoOverrides(
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val bitrate: Int? = null,
    val gop: Int? = null,
    val encodingType: Int? = null,
    val appType: Int? = null,
    val angle: Int? = null,
    val orientation: Int? = null,
)

/** Envío periódico de `Mirror/WhitelistAppOn` (spec §6.6). */
enum class WhitelistMode {
    /** Como QDLink: solo si `CAR_INFO.CarFeature.legal_app_watch == 1`, desde el primer `VIDEO_SUP_RSP`. */
    AUTO,
    ALWAYS,
    NEVER,
}

/**
 * Configuración de [PhoneSession]. Los valores por defecto imitan a QDLink 1.9.7 (spec §10), salvo:
 * - nunca se informa de pantalla bloqueada ni de app en segundo plano (no se manda `LOCK_SCREEN_STATUS` ni
 *   `CAR_APP_BACKGROUND`; el AppStatus dice `value` 1), para que el coche siga mostrando vídeo con la pantalla apagada;
 * - el watchdog avisa a los 5 s y corta a los 15 s (QDLink corta a los 5-10 s);
 * - `SO_SNDBUF` moderado (ver [sendBufferBytes]).
 */
data class SessionConfig(
    // ---- Socket ----
    val tcpNoDelay: Boolean = true,
    /**
     * `SO_SNDBUF`. QDLink pide 4 MiB (WF/d.java:90): con eso el kernel acumula segundos de vídeo, la latencia crece y
     * el atasco no se nota hasta tarde. Con 256 KiB (≈ 250 ms a 8 Mbit/s, un IDR grande) la presión llega pronto a
     * nuestra cola, que descarta P-frames y pide un IDR. `null` = no tocar (autoajuste del sistema).
     */
    val sendBufferBytes: Int? = 256 * 1024,
    /** `SO_RCVBUF`. QDLink pide 6 MiB (WF/d.java:92); aquí solo entra control y táctil. `null` = no tocar. */
    val receiveBufferBytes: Int? = null,
    val keepAlive: Boolean = true,
    /** Tamaño máximo aceptado para un mensaje entrante (spec §3.6: 8 MiB). */
    val maxMessageBytes: Int = FrameReader.DEFAULT_MAX_MESSAGE_SIZE,
    /** Prioridad Java de los hilos lector y escritor (QDLink pone el lector a 10, LC/a.java:2601). */
    val ioThreadPriority: Int = Thread.MAX_PRIORITY,
    /** Tope de mensajes de control en cola (en uso normal hay 0-2; solo se llena si el escritor se bloquea). */
    val controlQueueCapacity: Int = 1_000,

    // ---- Arranque y mantenimiento ----
    /** AppStatus `!BIN` de 512 B nada más conectar (LC/a.java:1344-1352). */
    val sendAppStatus: Boolean = true,
    /** `{"CMD":"HEARTBEAT"}` a 1 s y luego cada 3 s con retardo fijo (LC/a.java:1761). */
    val heartbeatEnabled: Boolean = true,
    val heartbeatInitialDelayMs: Long = 1_000,
    val heartbeatPeriodMs: Long = 3_000,
    /** Watchdog de recepción: aviso a [watchdogWarnMs] y corte a [watchdogTimeoutMs] sin recibir nada. */
    val watchdogEnabled: Boolean = true,
    val watchdogCheckIntervalMs: Long = 1_000,
    val watchdogWarnMs: Long = 5_000,
    val watchdogTimeoutMs: Long = 15_000,
    /** Como QDLink (`W == "5A5A"`, LC/a.java:690): el watchdog no corta hasta que el coche ha mandado algún 5A5A. */
    val watchdogRequiresCarTraffic: Boolean = true,
    /** Cierra la sesión si un `write()` lleva bloqueado más de esto (`0` = desactivado). */
    val writeStallTimeoutMs: Long = 10_000,

    // ---- Respuestas automáticas (spec §7.2, §10.2) ----
    /** `CAR_INFO` → `PHONE_INFO` (LC/a.java:1951-1966). */
    val replyPhoneInfo: Boolean = true,
    /** … y después `UPDATE_NOTIFY{UpdateStatus}` (LC/a.java:1530-1543). */
    val replyUpdateNotify: Boolean = true,
    val updateNotifyStatus: Int = 5,
    /** `VIDEO_SUP_REQ` → `VIDEO_SUP_RSP{VideoFormat, VideoSupport}` (LC/a.java:2398-2410). */
    val replyVideoSupport: Boolean = true,
    val videoSupportFormat: Int = 3,
    val videoSupportValue: Int = 1,
    /** `VIDEO_ARGS` → `SPEECH_ARGS` (LC/a.java:2472-2488). */
    val replySpeechArgs: Boolean = true,
    val speechArgs: SpeechArgs = SpeechArgs(),
    /** `LAND_MODE_REQ` → `LAND_MODE_RSP{Authority, StatusArg, Orientation = eco}` sin girar nada (spec §6.7). */
    val replyLandMode: Boolean = true,
    val landModeAuthority: Int = 1,
    val landModeStatusArg: Int = 0,
    /** Heartbeat legado `!BIN` (cmd 10) → se devuelve con `ret` = 1 (LC/a.java:1122-1129). */
    val echoLegacyHeartbeat: Boolean = true,
    /** `DISCONNECT_REQ`: QDLink no responde ni corta (DLinkNotifyL.java:1597-1599). */
    val replyDisconnectReq: Boolean = false,
    val closeOnDisconnectReq: Boolean = false,
    /**
     * `BT_ADDR` → `BT_RESULT`: el resultado depende del estado Bluetooth, que solo conoce la app. Si se da, se invoca
     * (en el hilo lector, debe ser rápido) y, si devuelve un código, se envía `BT_RESULT{Result}`. Sin `BluetoothAddr`
     * QDLink no responde nunca. Alternativa: llamar a [PhoneSession.sendBtResult] desde `onBtAddr`.
     */
    val btResultProvider: ((BtAddrRequest) -> Int?)? = null,
    val whitelistMode: WhitelistMode = WhitelistMode.AUTO,
    /** Valor de `WhitelistAppOn` (1 mientras proyectamos nuestra propia UI, spec §6.6). */
    val whitelistValue: Int = 1,
    val whitelistInitialDelayMs: Long = 1_000,
    val whitelistPeriodMs: Long = 1_000,
    /** Mandar `LOCK_SCREEN_STATUS{3}` (desbloqueado) tras el `VIDEO_SUP_RSP`. QDLink solo lo hace al desbloquear. */
    val announceUnlocked: Boolean = false,

    // ---- CAR_INFO / PHONE_INFO ----
    val phone: PhoneIdentity = PhoneIdentity(),
    val phoneInfoOverrides: PhoneInfoOverrides = PhoneInfoOverrides(),
    /** Leer `CAR_INFO`/`VIDEO_ARGS` como QDLink (el primer campo ausente deja los siguientes a 0, spec §6.5). */
    val qdlinkStrictParsing: Boolean = true,

    // ---- Vídeo ----
    /** Como QDLink: `VIDEO_CTRL{1}` sin `VIDEO_ARGS` previo no arranca el vídeo (NPE en LC/a.java:2012). */
    val requireVideoArgsForPlay: Boolean = true,
    /** QDLink ignora `VIDEO_CTRL{PlayStatus≠1}` y sigue emitiendo; con `true` se pausa hasta el siguiente `{1}`. */
    val pauseOnVideoCtrlStop: Boolean = false,
    /** Permite enviar vídeo antes de `VIDEO_CTRL{1}` (para experimentos). */
    val sendVideoBeforePlay: Boolean = false,
    /** `KEY_FRAME_REQ` → reenviar SPS/PPS antes del siguiente frame, como QDLink (SC/managers/a.java:627-631). */
    val resendCodecConfigOnKeyframeRequest: Boolean = true,
    /** Tras descartar frames, reenviar SPS/PPS antes del IDR con el que se reanuda. */
    val resendCodecConfigAfterDrop: Boolean = true,
    val videoOverrides: VideoOverrides = VideoOverrides(),
    /** Frames (IDR + P) en cola a partir de los cuales se descartan los P-frames y se pide un IDR. */
    val videoBacklogFrames: Int = 6,
    /** Bytes de vídeo en cola a partir de los cuales también se descarta. */
    val videoBacklogBytes: Int = 4 * 1024 * 1024,
    /**
     * Válvula de seguridad de memoria: si la cola de vídeo supera esto (solo posible si el encoder no manda más que
     * IDR), se descartan los IDR ya superados por otro IDR posterior de la cola. Nunca se toca en uso normal.
     */
    val videoQueueHardLimitBytes: Int = 32 * 1024 * 1024,
    /** Intervalo mínimo entre dos peticiones de IDR al encoder. */
    val minKeyframeRequestIntervalMs: Long = 1_000,

    // ---- Trazas ----
    val traceVideoFrames: Boolean = true,
    val traceMaxJsonChars: Int = 4_096,
    val traceHexPrefixBytes: Int = 64,

    // ---- hql (C2): política de descarte de vídeo ----
    /** Qué hacer cuando el vídeo no sale tan rápido como llega ([VideoDropPolicy]); `BACKLOG` es la validada. */
    val videoDropPolicy: VideoDropPolicy = VideoDropPolicy.BACKLOG,
    /** Con [VideoDropPolicy.MAX_LAG]: antigüedad máxima de un P-frame en la cabeza de la cola. */
    val videoMaxLagMs: Long = 150,

    // ---- hql (C3): puerta de escritura de vídeo, socket, hilos y PHONE_INFO por coche ----
    /** Puerta consultada antes de cada escritura de vídeo ([VideoWriteGate]); `null` = siempre abierta. */
    val videoWriteGate: VideoWriteGate? = null,
    /** Espera máxima de un mensaje de vídeo ante la puerta cerrada; después sale de todos modos. */
    val videoWriteGateMaxWaitMs: Long = 250,
    /** Cada cuánto se vuelve a preguntar a la puerta cerrada (antes si llega control o se cierra la sesión). */
    val videoWriteGatePollMs: Long = 2,
    /** `IP_TOS` del socket (p. ej. 0xA0 = DSCP CS5 → WMM AC_VI); `null` = no tocar. Algunos sistemas lo ignoran. */
    val trafficClass: Int? = null,
    /** Último paso de la configuración del socket, antes del AppStatus (p. ej. ajustes propios de la app). */
    val socketConfigurator: ((java.net.Socket) -> Unit)? = null,
    /** Se llama al empezar el cuerpo de cada hilo de la sesión, dentro de ese hilo (p. ej. para su prioridad). */
    val onThreadStart: ((ThreadRole) -> Unit)? = null,
    /** Si no es `null`, sustituye a [phoneInfoOverrides]: se evalúa con cada `CAR_INFO` (y en `resendPhoneInfo`). */
    val phoneInfoOverridesFor: ((dev.qdauto.core.wire.CarInfo?) -> PhoneInfoOverrides)? = null,

    // ---- hql: tope de tamaño de los mensajes de vídeo ----
    /**
     * Tamaño máximo de un mensaje de vídeo (48 B de cabeceras + Annex-B). El receptor del C10 se cuelga con mensajes de
     * más de ~512 KiB: deja de leer el TCP (sigue mandando heartbeats) y el `write()` queda bloqueado hasta
     * [writeStallTimeoutMs]. Un frame mayor se descarta, no se mandan los P-frames que dependen de él (se espera al
     * siguiente IDR, con SPS/PPS delante) y se pide otro IDR ([KeyframeReason.OVERSIZED]). `0` = sin tope.
     */
    val maxVideoMessageBytes: Int = DEFAULT_MAX_VIDEO_MESSAGE_BYTES,
) {
    companion object {
        /**
         * Límite medido en el C10 (2026-10-05): mensajes de 525 208, 538 390 y 592 913 B lo colgaron; de 493 568 B y
         * menos pasaron. Cuadra con un búfer de recepción de 512 KiB por mensaje.
         */
        const val CAR_RECEIVER_LIMIT_BYTES = 512 * 1024

        /** Tope por defecto de [maxVideoMessageBytes], con margen bajo [CAR_RECEIVER_LIMIT_BYTES]. */
        const val DEFAULT_MAX_VIDEO_MESSAGE_BYTES = 480 * 1024
    }
}
