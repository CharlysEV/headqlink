package dev.qdauto.core.wire

import dev.qdauto.core.json.JsonObject

/**
 * Parámetros de `PHONE_INFO` con los nombres de QDLink (bean LC/newmessage/bean/PhoneInfoPara.java:5-22).
 * Cómo los rellena QDLink: LC/a.java:914-959 y spec §6.4. Las cadenas `null` salen como `null` JSON.
 */
data class PhoneInfo(
    /** Lado largo físico en px (`getRealSize`, sin importar la rotación). */
    val phoneWidth: Int,
    /** Lado corto físico en px. */
    val phoneHeight: Int,
    /** outHorW (spec §8.6). */
    val mirrorWidth: Int,
    /** outHorH (spec §8.6). */
    val mirrorHeight: Int,
    /** Eco crudo de `CAR_INFO.CarWidth` (0 si no llegó). */
    val phoneWidthInApp: Int,
    val phoneHeightInApp: Int,
    val mirrorWidthInApp: Int,
    val mirrorHeightInApp: Int,
    val phoneFeature: JsonObject = JsonObject.of("PassistMobileNum" to ""),
    val phoneUuid: String? = "",
    val phoneName: String? = "",
    val version: String? = "1.9.7",
    val phoneBrand: String? = "samsung",
    val phoneModel: String? = "SM-S938B",
    val platform: Int = 0,
    val platformVersion: String? = "36",
    /** Eco de `CAR_INFO.MirrorTypeReq`. */
    val mirrorTypeSupport: Int = 0,
    /** `long` que QDLink nunca asigna. */
    val phoneSystemTime: Long = 0,
) {
    /**
     * `PARA` en el orden en que lo serializa QDLink en Android ≥ 7 (orden de iteración de `java.util.HashMap`,
     * spec §6.1). El coche no puede depender del orden, pero así salimos idénticos byte a byte.
     */
    fun toPara(): JsonObject = JsonObject.of(
        "PhoneName" to phoneName,
        "PlatformVersion" to platformVersion,
        "PhoneModel" to phoneModel,
        "Platform" to platform,
        "PhoneSystemTime" to phoneSystemTime,
        "PhoneHeightInApp" to phoneHeightInApp,
        "MirrorWidthInApp" to mirrorWidthInApp,
        "PhoneWidthInApp" to phoneWidthInApp,
        "MirrorHeightInApp" to mirrorHeightInApp,
        "MirrorTypeSupport" to mirrorTypeSupport,
        "PhoneFeature" to phoneFeature,
        "PhoneUUID" to phoneUuid,
        "Version" to version,
        "MirrorHeight" to mirrorHeight,
        "MirrorWidth" to mirrorWidth,
        "PhoneBrand" to phoneBrand,
        "PhoneHeight" to phoneHeight,
        "PhoneWidth" to phoneWidth,
    )
}

/** Parámetros de `PHONE_INFO_CHANGE` (bean PhoneInfoChangePara.java; LC/a.java:1484-1504). */
data class PhoneInfoChange(
    val phoneWidth: Int,
    val phoneHeight: Int,
    val mirrorWidth: Int,
    val mirrorHeight: Int,
    val phoneWidthInApp: Int,
    val phoneHeightInApp: Int,
    val mirrorWidthInApp: Int,
    val mirrorHeightInApp: Int,
) {
    /** Orden de QDLink (spec §6.1). */
    fun toPara(): JsonObject = JsonObject.of(
        "MirrorHeight" to mirrorHeight,
        "MirrorWidth" to mirrorWidth,
        "PhoneHeightInApp" to phoneHeightInApp,
        "PhoneHeight" to phoneHeight,
        "MirrorWidthInApp" to mirrorWidthInApp,
        "PhoneWidth" to phoneWidth,
        "PhoneWidthInApp" to phoneWidthInApp,
        "MirrorHeightInApp" to mirrorHeightInApp,
    )
}

/** Valores de `SPEECH_ARGS`. QDLink: LC/a.java:2472-2488 (1 / 16000 / 1 / 16). */
data class SpeechArgs(
    val sampleRate: Int = 16000,
    val channelConfig: Int = 1,
    val encodingType: Int = 1,
    val audioFormat: Int = 16,
)

/**
 * Todos los mensajes que QDLink puede mandar al coche (spec §6.2), con el JSON exacto que genera
 * `h0.c.q()`/`h0.c.p()`: `{"PARA":{…},"CMD":"…"}` en msgType 0 y `{"FunctionID":…,"AppID":…,"Para":{…}}` en msgType 13.
 * Las funciones `xxxJson` devuelven el texto; las demás, el mensaje 5A5A completo.
 */
object PhoneMessages {
    /** Literal de QDLink (LC/a.java:208); `totalSize` 35. */
    const val HEARTBEAT_JSON = "{\"CMD\":\"HEARTBEAT\"}"

    /** `{"PARA":{…},"CMD":"…"}` (PARA primero, como QDLink), o `{"CMD":"…"}` sin PARA. QDLink: h0/c.java:348-373. */
    fun controlJson(cmd: String, para: JsonObject?): String =
        if (para == null) JsonObject.of("CMD" to cmd).toJson() else JsonObject.of("PARA" to para, "CMD" to cmd).toJson()

    /** `{"FunctionID":…,"AppID":…,"Para":{…}}`, o sin `Para`. QDLink: h0/c.java:319-346. */
    fun appJson(appId: String, functionId: String, para: JsonObject?): String =
        if (para == null) {
            JsonObject.of("FunctionID" to functionId, "AppID" to appId).toJson()
        } else {
            JsonObject.of("FunctionID" to functionId, "AppID" to appId, "Para" to para).toJson()
        }

    fun control(cmd: String, para: JsonObject? = null): ByteArray = Frames.json(MsgType.CONTROL, controlJson(cmd, para))
    fun app(appId: String, functionId: String, para: JsonObject? = null): ByteArray = Frames.json(MsgType.APP, appJson(appId, functionId, para))

    /** AppStatus `!BIN` de 512 B (spec §4.2). */
    fun appStatus(sdkInt: Int): ByteArray = BinBlock.appStatus(sdkInt)

    fun heartbeat(): ByteArray = Frames.json(MsgType.CONTROL, HEARTBEAT_JSON)

    fun phoneInfoJson(info: PhoneInfo): String = controlJson(Cmd.PHONE_INFO, info.toPara())
    fun phoneInfo(info: PhoneInfo): ByteArray = Frames.json(MsgType.CONTROL, phoneInfoJson(info))

    /** `{"PARA":{"UpdateStatus":5},"CMD":"UPDATE_NOTIFY"}`. QDLink: LC/a.java:1530-1543. */
    fun updateNotifyJson(status: Int = 5): String = controlJson(Cmd.UPDATE_NOTIFY, JsonObject.of("UpdateStatus" to status))
    fun updateNotify(status: Int = 5): ByteArray = Frames.json(MsgType.CONTROL, updateNotifyJson(status))

    /** `{"PARA":{"VideoFormat":3,"VideoSupport":1},"CMD":"VIDEO_SUP_RSP"}`. QDLink: LC/a.java:2398-2410. */
    fun videoSupportRspJson(videoFormat: Int = 3, videoSupport: Int = 1): String =
        controlJson(Cmd.VIDEO_SUP_RSP, JsonObject.of("VideoFormat" to videoFormat, "VideoSupport" to videoSupport))
    fun videoSupportRsp(videoFormat: Int = 3, videoSupport: Int = 1): ByteArray =
        Frames.json(MsgType.CONTROL, videoSupportRspJson(videoFormat, videoSupport))

    /** `{"PARA":{"SampleRate":16000,"ChannelConfig":1,"EncodingType":1,"AudioFormat":16},"CMD":"SPEECH_ARGS"}`. */
    fun speechArgsJson(args: SpeechArgs = SpeechArgs()): String = controlJson(
        Cmd.SPEECH_ARGS,
        JsonObject.of(
            "SampleRate" to args.sampleRate,
            "ChannelConfig" to args.channelConfig,
            "EncodingType" to args.encodingType,
            "AudioFormat" to args.audioFormat,
        ),
    )
    fun speechArgs(args: SpeechArgs = SpeechArgs()): ByteArray = Frames.json(MsgType.CONTROL, speechArgsJson(args))

    /** `{"PARA":{"Authority":1,"StatusArg":0,"Orientation":<eco>},"CMD":"LAND_MODE_RSP"}`. QDLink: LC/a.java:2937-2981. */
    fun landModeRspJson(orientation: Int, authority: Int = 1, statusArg: Int = 0): String = controlJson(
        Cmd.LAND_MODE_RSP,
        JsonObject.of("Authority" to authority, "StatusArg" to statusArg, "Orientation" to orientation),
    )
    fun landModeRsp(orientation: Int, authority: Int = 1, statusArg: Int = 0): ByteArray =
        Frames.json(MsgType.CONTROL, landModeRspJson(orientation, authority, statusArg))

    /** `{"PARA":{"Result":r},"CMD":"BT_RESULT"}`. QDLink: LC/a.java:3077-3094. */
    fun btResultJson(result: Int): String = controlJson(Cmd.BT_RESULT, JsonObject.of("Result" to result))
    fun btResult(result: Int): ByteArray = Frames.json(MsgType.CONTROL, btResultJson(result))

    /** 1 = SCREEN_OFF, 2 = SCREEN_ON, 3 = USER_PRESENT. QDLink: LC/a.java:3096-3118. */
    fun lockScreenStatusJson(status: Int): String = controlJson(Cmd.LOCK_SCREEN_STATUS, JsonObject.of("LockScreenStatus" to status))
    fun lockScreenStatus(status: Int): ByteArray = Frames.json(MsgType.CONTROL, lockScreenStatusJson(status))

    /** Sin PARA. QDLink: LC/a.java:3037-3055. */
    fun carAppBackground(): ByteArray = control(Cmd.CAR_APP_BACKGROUND)

    /** Sin PARA; QDLink nunca lo envía. LC/a.java:3057-3075. */
    fun carAppForeground(): ByteArray = control(Cmd.CAR_APP_FOREGROUND)

    fun phoneInfoChangeJson(change: PhoneInfoChange): String = controlJson(Cmd.PHONE_INFO_CHANGE, change.toPara())
    fun phoneInfoChange(change: PhoneInfoChange): ByteArray = Frames.json(MsgType.CONTROL, phoneInfoChangeJson(change))

    /** 1 = empezar, 0 = parar; QDLink nunca lo envía. LC/a.java:2983-3035. */
    fun speechCtrlJson(status: Int): String = controlJson(Cmd.SPEECH_CTRL, JsonObject.of("SpeechStatus" to status))
    fun speechCtrl(status: Int): ByteArray = Frames.json(MsgType.CONTROL, speechCtrlJson(status))

    /** QDLink nunca lo envía. LC/a.java:3120-3138. */
    fun disconnectRspJson(canDisconnect: Int = 1): String = controlJson(Cmd.DISCONNECT_RSP, JsonObject.of("CanDisconnect" to canDisconnect))
    fun disconnectRsp(canDisconnect: Int = 1): ByteArray = Frames.json(MsgType.CONTROL, disconnectRspJson(canDisconnect))

    /** msgType 13 Mirror/WhitelistAppOn. QDLink: LC/a.java:2877-2895. */
    fun whitelistAppOnJson(value: Int): String =
        appJson(AppIds.MIRROR, FunctionIds.WHITELIST_APP_ON, JsonObject.of("WhitelistAppOn" to value))
    fun whitelistAppOn(value: Int): ByteArray = Frames.json(MsgType.APP, whitelistAppOnJson(value))

    /** msgType 13 Music/PlayState. QDLink: LC/a.java:2897-2915. */
    fun playStateJson(state: Int): String = appJson(AppIds.MUSIC, FunctionIds.PLAY_STATE, JsonObject.of("PlayState" to state))
    fun playState(state: Int): ByteArray = Frames.json(MsgType.APP, playStateJson(state))

    /** msgType 99 con `reservedOne` = subtipo y payLoadFormat 1; QDLink nunca lo envía. LC/a.java:2685-2701. */
    fun custom(subtype: Int, payload: ByteArray): ByteArray = Frames.binary(MsgType.CUSTOM, PayloadFormat.JSON, payload, reservedOne = subtype)
}
