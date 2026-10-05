package dev.qdauto.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Sumidero de logs de `:core` (sin dependencias de Android). La app lo conecta a Logcat y a su fichero de log;
 * `:carsim` y los tests usan [STDOUT]. Las implementaciones deben ser thread-safe: se llama desde varios hilos.
 */
fun interface QdLog {
    fun log(level: LogLevel, tag: String, message: String, error: Throwable?)

    companion object {
        /** Descarta todo. */
        val NONE: QdLog = QdLog { _, _, _, _ -> }

        /** Escribe en la salida estándar con marca de tiempo. */
        val STDOUT: QdLog = stdout(LogLevel.DEBUG)

        fun stdout(minLevel: LogLevel): QdLog = QdLog { level, tag, message, error ->
            if (level >= minLevel) {
                val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
                synchronized(System.out) {
                    println("$ts ${level.name[0]}/$tag: $message")
                    error?.printStackTrace(System.out)
                }
            }
        }
    }
}

fun QdLog.d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
fun QdLog.i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
fun QdLog.w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)
fun QdLog.e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)
