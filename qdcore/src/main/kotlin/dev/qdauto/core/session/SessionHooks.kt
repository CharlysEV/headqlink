package dev.qdauto.core.session

/**
 * hql (C3): puerta de escritura de vídeo. El escritor la consulta (sin el candado de la cola) **antes** de cada
 * mensaje de vídeo (SPS/PPS o frame); mientras devuelva `false` el vídeo espera, pero el control (heartbeats y
 * respuestas) sigue saliendo en cuanto llega. Pasados [SessionConfig.videoWriteGateMaxWaitMs] el vídeo sale de todos
 * modos; si lanza una excepción, cuenta como abierta. Se llama en el hilo escritor: tiene que ser rápida.
 */
fun interface VideoWriteGate {
    fun canWriteVideo(session: PhoneSession): Boolean
}

/** hql (C3): hilos de una sesión, para [SessionConfig.onThreadStart]. */
enum class ThreadRole { READER, WRITER, TIMER, EVENTS }
