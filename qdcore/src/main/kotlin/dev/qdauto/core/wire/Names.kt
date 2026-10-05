package dev.qdauto.core.wire

/** Valores de `CMD` (msgType 0). QDLink: h0/c.java:24-122. */
object Cmd {
    // Coche → teléfono (los 10 `case` del switch de LC/a.java:1950-2072 más los declarados sin uso).
    const val CAR_INFO = "CAR_INFO"
    const val VIDEO_SUP_REQ = "VIDEO_SUP_REQ"
    const val VIDEO_ARGS = "VIDEO_ARGS"
    const val VIDEO_CTRL = "VIDEO_CTRL"
    const val KEY_FRAME_REQ = "KEY_FRAME_REQ"
    const val LAND_MODE_REQ = "LAND_MODE_REQ"
    const val BT_ADDR = "BT_ADDR"
    const val PHONE_KEYS = "PHONE_KEYS"
    const val GO_IN_LINK_APP = "GO_IN_LINK_APP"
    const val DISCONNECT_REQ = "DISCONNECT_REQ"
    const val LOCK_SCREEN_REQ = "LOCK_SCREEN_REQ"
    const val UPDATE_PKG_REQ = "UPDATE_PKG_REQ"

    // Ambos sentidos.
    const val HEARTBEAT = "HEARTBEAT"

    // Teléfono → coche.
    const val PHONE_INFO = "PHONE_INFO"
    const val PHONE_INFO_CHANGE = "PHONE_INFO_CHANGE"
    const val UPDATE_NOTIFY = "UPDATE_NOTIFY"
    const val VIDEO_SUP_RSP = "VIDEO_SUP_RSP"
    const val SPEECH_ARGS = "SPEECH_ARGS"
    const val SPEECH_CTRL = "SPEECH_CTRL"
    const val LAND_MODE_RSP = "LAND_MODE_RSP"
    const val LOCK_SCREEN_STATUS = "LOCK_SCREEN_STATUS"
    const val CAR_APP_BACKGROUND = "CAR_APP_BACKGROUND"
    const val CAR_APP_FOREGROUND = "CAR_APP_FOREGROUND"
    const val BT_RESULT = "BT_RESULT"
    const val DISCONNECT_RSP = "DISCONNECT_RSP"

    /** Los `CMD` del coche que QDLink atiende (LC/a.java:1950-2072; dex: 10 claves en el sparse-switch). */
    val CAR_HANDLED: Set<String> = setOf(
        CAR_INFO, VIDEO_ARGS, VIDEO_SUP_REQ, VIDEO_CTRL, GO_IN_LINK_APP,
        LAND_MODE_REQ, BT_ADDR, DISCONNECT_REQ, KEY_FRAME_REQ, PHONE_KEYS,
    )
}

/** `AppID` y `FunctionID` de msgType 13. QDLink: h0/c.java:28-43, LC/a.java:2176-2318. */
object AppIds {
    const val MUSIC = "Music"
    const val AUDIO_SOURCE = "AudioSource"
    const val GLOBAL = "Global"
    const val MIRROR = "Mirror"
}

object FunctionIds {
    const val PLAY_CONTROL = "PlayControl"
    const val PLAY_CONTROL_PLAY = "PlayControlPlay"
    const val PLAY_CONTROL_PAUSE = "PlayControlPause"
    const val PREV = "Prev"
    const val NEXT = "Next"
    const val MUTE_CONTROL = "MuteControl"
    const val AUDIO_SOURCE_STATE = "AudioSourceState"
    const val DARK_MODE_ON = "DarkModeOn"
    const val WHITELIST_APP_ON = "WhitelistAppOn"
    const val PLAY_STATE = "PlayState"
    const val MUTE_STATE = "MuteState"
}
