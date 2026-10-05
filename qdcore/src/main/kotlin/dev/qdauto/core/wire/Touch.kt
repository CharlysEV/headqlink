package dev.qdauto.core.wire

import dev.qdauto.core.util.BE

/**
 * Un dedo del mensaje táctil (10 bytes).
 * QDLink: h0/e.java:52-62 lee `fingerId` y `fingerAction` como byte con signo y `x`/`y` como float32 BE.
 */
data class TouchPointer(
    /** `fingerId` leído como byte con signo, igual que QDLink (rango seguro en QDLink: 0-9). */
    val id: Int,
    /** `fingerAction` (u8): 1 = down, 2 = up, 3 = move (LC/a.java:2144-2153). */
    val action: Int,
    val x: Float,
    val y: Float,
    /** Bits crudos de los floats (por si llegan NaN o valores raros). */
    val xBits: Int = x.toRawBits(),
    val yBits: Int = y.toRawBits(),
) {
    val actionName: String get() = TouchCodec.fingerActionName(action)

    companion object {
        fun down(id: Int, x: Float, y: Float) = TouchPointer(id, TouchCodec.FINGER_DOWN, x, y)
        fun move(id: Int, x: Float, y: Float) = TouchPointer(id, TouchCodec.FINGER_MOVE, x, y)
        fun up(id: Int, x: Float, y: Float) = TouchPointer(id, TouchCodec.FINGER_UP, x, y)
    }
}

/**
 * Mensaje táctil del coche (msgType 2, payLoadFormat 0) ya decodificado, con todos los valores crudos.
 * [pointers] trae solo los dedos completos; si el payload se queda corto, [truncated] es `true`.
 */
data class TouchEvent(
    /** `action` global (i32). QDLink solo distingue 0 (empieza el gesto) y 1 (termina). */
    val action: Int,
    /** `fingerCount` tal y como llega (byte con signo). */
    val declaredCount: Int,
    val pointers: List<TouchPointer>,
    /** Bytes de payload recibidos. */
    val payloadLength: Int,
    /** El payload no tenía los 10·N bytes que anuncia `fingerCount`. */
    val truncated: Boolean,
    /** Bytes que sobran tras el último dedo declarado (QDLink los ignora). */
    val extraBytes: Int,
    /** `System.nanoTime()` al recibirlo (0 si no viene de una sesión). */
    val receivedAtNanos: Long = 0,
) {
    /** QDLink descartaría este mensaje: N ≤ 0 o payload corto (spec §9.1). */
    val wouldQdlinkDrop: Boolean get() = declaredCount <= 0 || truncated

    fun describe(): String = buildString {
        append("action=").append(action).append(" N=").append(declaredCount)
        pointers.forEach { p -> append(" [id=").append(p.id).append(' ').append(p.actionName).append(" x=").append(p.x).append(" y=").append(p.y).append(']') }
        if (truncated) append(" TRUNCADO")
        if (extraBytes > 0) append(" +").append(extraBytes).append("B")
    }
}

/** Codificación del táctil (spec §9.1). QDLink: h0/e.java:43-64, h0/f.java:18-46. */
object TouchCodec {
    const val FINGER_DOWN = 1
    const val FINGER_UP = 2
    const val FINGER_MOVE = 3

    /** `action` global: QDLink solo distingue 0 = empieza y 1 = termina (MouseAccessibilityService.java:174-210). */
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    const val POINTER_SIZE = 10
    const val FIXED_SIZE = 5

    fun fingerActionName(action: Int): String = when (action) {
        FINGER_DOWN -> "down"
        FINGER_UP -> "up"
        FINGER_MOVE -> "move"
        else -> "acción$action"
    }

    /** Decodifica el payload (sin la cabecera 5A5A). Devuelve `null` si no llega ni a los 5 bytes fijos. */
    fun parse(payload: ByteArray, off: Int = 0, len: Int = payload.size - off, receivedAtNanos: Long = 0): TouchEvent? {
        if (len < FIXED_SIZE) return null
        val action = BE.getInt(payload, off)
        val n = BE.getByte(payload, off + 4) // QDLink: byte con signo (h0/e.java:45)
        val pointers = ArrayList<TouchPointer>(maxOf(n, 0))
        var p = off + FIXED_SIZE
        val end = off + len
        var truncated = false
        for (k in 0 until maxOf(n, 0)) {
            if (p + POINTER_SIZE > end) {
                truncated = true
                break
            }
            val xBits = BE.getInt(payload, p + 2)
            val yBits = BE.getInt(payload, p + 6)
            pointers.add(
                TouchPointer(
                    id = BE.getByte(payload, p),
                    action = BE.getUByte(payload, p + 1),
                    x = Float.fromBits(xBits),
                    y = Float.fromBits(yBits),
                    xBits = xBits,
                    yBits = yBits,
                ),
            )
            p += POINTER_SIZE
        }
        return TouchEvent(
            action = action,
            declaredCount = n,
            pointers = pointers,
            payloadLength = len,
            truncated = truncated,
            extraBytes = if (truncated) 0 else end - p,
            receivedAtNanos = receivedAtNanos,
        )
    }

    /** Payload del táctil (para el simulador). */
    fun encodePayload(action: Int, pointers: List<TouchPointer>): ByteArray {
        val out = ByteArray(FIXED_SIZE + POINTER_SIZE * pointers.size)
        BE.putInt(out, 0, action)
        out[4] = pointers.size.toByte()
        pointers.forEachIndexed { k, ptr ->
            val p = FIXED_SIZE + POINTER_SIZE * k
            out[p] = ptr.id.toByte()
            out[p + 1] = ptr.action.toByte()
            BE.putInt(out, p + 2, ptr.xBits)
            BE.putInt(out, p + 6, ptr.yBits)
        }
        return out
    }

    /** Mensaje 5A5A completo: msgType 2, payLoadFormat 0, `totalSize` = 16 + 5 + 10·N. */
    fun build(action: Int, pointers: List<TouchPointer>): ByteArray =
        Frames.binary(MsgType.TOUCH, PayloadFormat.BINARY, encodePayload(action, pointers))
}
