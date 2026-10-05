package dev.qdauto.core.json

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Modelo JSON mínimo e inmutable. Los objetos conservan el orden de inserción.
 * `toString()` devuelve el JSON compacto, idéntico al que generaría `org.json` de Android.
 */
sealed class JsonValue {
    fun toJson(): String = JsonWriter.write(this)
    final override fun toString(): String = toJson()
}

object JsonNull : JsonValue()

data class JsonBool(val value: Boolean) : JsonValue()

data class JsonString(val value: String) : JsonValue()

/**
 * Número JSON. El valor se normaliza a [Int] (si cabe), [Long] o [Double], igual que `org.json`:
 * un literal sin `.`, `e` ni `E` es entero; con ellos, `Double`.
 * Dos números son iguales solo si coinciden tipo y valor (`5` ≠ `5.0`).
 */
class JsonNumber private constructor(val value: Number) : JsonValue() {
    val isIntegral: Boolean get() = value !is Double

    /** Conversión de Java (`Number.intValue()`): trunca los `double` y recorta los `long`. */
    fun toInt(): Int = value.toInt()
    fun toLong(): Long = value.toLong()
    fun toDouble(): Double = value.toDouble()

    override fun equals(other: Any?): Boolean = other is JsonNumber && other.value == value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun of(v: Int): JsonNumber = JsonNumber(v)
        fun of(v: Long): JsonNumber = if (v in Int.MIN_VALUE..Int.MAX_VALUE) JsonNumber(v.toInt()) else JsonNumber(v)
        fun of(v: Double): JsonNumber = JsonNumber(v)
        fun of(v: Number): JsonNumber = when (v) {
            is Int, is Short, is Byte -> of(v.toInt())
            is Long -> of(v)
            is Double -> of(v)
            // Float → texto decimal → Double, para no arrastrar ruido binario (0.1f → 0.1).
            is Float -> of(v.toString().toDouble())
            is BigInteger -> if (v.bitLength() < 64) of(v.toLong()) else of(v.toDouble())
            is BigDecimal -> of(v.toDouble())
            else -> of(v.toDouble())
        }
    }
}

class JsonArray(items: List<JsonValue> = emptyList()) : JsonValue(), List<JsonValue> by ArrayList(items) {
    override fun equals(other: Any?): Boolean = other is JsonArray && other.size == size && indices.all { this[it] == other[it] }
    override fun hashCode(): Int = fold(1) { h, v -> 31 * h + v.hashCode() }

    companion object {
        fun of(vararg values: Any?): JsonArray = JsonArray(values.map { Json.toValue(it) })
    }
}

/**
 * Objeto JSON inmutable con orden de inserción. Además de la interfaz [Map], ofrece accesores con la misma
 * coerción que los `getX()` de `org.json`, pero devolviendo `null` donde `org.json` lanzaría `JSONException`.
 */
class JsonObject(entries: Map<String, JsonValue> = emptyMap()) : JsonValue(), Map<String, JsonValue> by LinkedHashMap(entries) {

    /** Como `getString`: cadenas tal cual; números, booleanos, objetos y `null` como texto (`JSON null` → `"null"`). */
    fun string(key: String): String? = when (val v = this[key]) {
        null -> null
        is JsonString -> v.value
        is JsonNumber -> v.value.toString()
        is JsonBool -> v.value.toString()
        JsonNull -> "null"
        is JsonObject, is JsonArray -> v.toJson()
    }

    /** Como `getInt`: números con `intValue()`; cadenas con `(int) Double.parseDouble(s)`. */
    fun int(key: String): Int? = when (val v = this[key]) {
        is JsonNumber -> v.toInt()
        is JsonString -> parseJavaDouble(v.value)?.toInt()
        else -> null
    }

    /** Como `getLong`. */
    fun long(key: String): Long? = when (val v = this[key]) {
        is JsonNumber -> v.toLong()
        is JsonString -> parseJavaDouble(v.value)?.toLong()
        else -> null
    }

    /** Como `getDouble`. */
    fun double(key: String): Double? = when (val v = this[key]) {
        is JsonNumber -> v.toDouble()
        is JsonString -> parseJavaDouble(v.value)
        else -> null
    }

    /** Como `getBoolean`: booleanos y las cadenas "true"/"false" (sin distinguir mayúsculas). */
    fun bool(key: String): Boolean? = when (val v = this[key]) {
        is JsonBool -> v.value
        is JsonString -> when {
            v.value.equals("true", ignoreCase = true) -> true
            v.value.equals("false", ignoreCase = true) -> false
            else -> null
        }
        else -> null
    }

    fun obj(key: String): JsonObject? = this[key] as? JsonObject
    fun array(key: String): JsonArray? = this[key] as? JsonArray

    /** Como `isNull`: la clave no existe o vale `null`. */
    fun isNull(key: String): Boolean = this[key].let { it == null || it == JsonNull }

    /** Copia con una clave añadida o sustituida (si ya existía, conserva su posición). */
    fun with(key: String, value: Any?): JsonObject = JsonObject(LinkedHashMap(this).also { it[key] = Json.toValue(value) })

    override fun equals(other: Any?): Boolean =
        other is JsonObject && other.size == size && entries.all { (k, v) -> other[k] == v }

    override fun hashCode(): Int = entries.fold(0) { h, (k, v) -> h + (k.hashCode() xor v.hashCode()) }

    companion object {
        val EMPTY = JsonObject()

        fun of(vararg pairs: Pair<String, Any?>): JsonObject =
            JsonObject(LinkedHashMap<String, JsonValue>().also { m -> pairs.forEach { (k, v) -> m[k] = Json.toValue(v) } })

        private fun parseJavaDouble(s: String): Double? = try {
            java.lang.Double.parseDouble(s)
        } catch (_: NumberFormatException) {
            null
        }
    }
}

class JsonObjectBuilder {
    private val map = LinkedHashMap<String, JsonValue>()
    fun put(key: String, value: Any?): JsonObjectBuilder = apply { map[key] = Json.toValue(value) }
    fun build(): JsonObject = JsonObject(map)
}

inline fun buildJsonObject(block: JsonObjectBuilder.() -> Unit): JsonObject = JsonObjectBuilder().apply(block).build()

object Json {
    /** Convierte valores Kotlin a [JsonValue]: null, String, Char, Boolean, Number, Map, Iterable, Array y JsonValue. */
    fun toValue(v: Any?): JsonValue = when (v) {
        null -> JsonNull
        is JsonValue -> v
        is String -> JsonString(v)
        is Char -> JsonString(v.toString())
        is Boolean -> JsonBool(v)
        is Number -> JsonNumber.of(v)
        is Map<*, *> -> JsonObject(LinkedHashMap<String, JsonValue>().also { m -> v.forEach { (k, x) -> m[k.toString()] = toValue(x) } })
        is Iterable<*> -> JsonArray(v.map { toValue(it) })
        is Array<*> -> JsonArray(v.map { toValue(it) })
        else -> throw IllegalArgumentException("tipo no convertible a JSON: ${v::class.java.name}")
    }

    fun parse(text: String): JsonValue = JsonParser.parse(text)
}
