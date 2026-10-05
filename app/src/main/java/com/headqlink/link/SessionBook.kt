package com.headqlink.link

/**
 * Contabilidad de las sesiones del motor QDAuto entre hilos (qdauto §4.4), sin Android: la prueban los tests.
 *
 * - **Sesión actual.** El inicio de una sesión llega en el hilo de aceptación de la nueva y el fin, en el de eventos de
 *   la vieja; en un relevo o una reconexión rápida se cruzan. Cambiar la actual y encolar su aviso al hilo principal
 *   ([post]) van juntos con un candado: el fin de la vieja nunca borra la nueva, y el hilo principal ve «conectado» y
 *   «perdido» en el mismo orden en que cambió la actual.
 * - **Hueco de reconexión** («reconexión X ms», qdauto §6.1): fin de la sesión anterior, primer broadcast y primer ACK
 *   sin sesión viva. La sesión nueva se lleva el hueco al empezar y lo resuelve con su primer IDR: el fin de la anterior
 *   ([EndStamp]) lo sella su puente al empezar onClosed (o el relevo), aunque eso pase después de que empiece la nueva.
 */
internal class SessionBook(private val post: (Runnable) -> Unit) {
    /** Fin de una sesión (`System.nanoTime`, 0 = aún no): vale el primer sello. */
    class EndStamp(val sid: Int) {
        @Volatile
        var nanos = 0L
            private set

        @Synchronized
        fun stamp(now: Long) {
            if (nanos == 0L) nanos = now
        }
    }

    /** Hueco antes de una sesión: el fin de la anterior se lee cuando haga falta (puede sellarse más tarde). */
    class Gap(val prev: EndStamp, val broadcastNanos: Long, val ackNanos: Long) {
        val prevSid: Int get() = prev.sid
        val endNanos: Long get() = prev.nanos
    }

    /** Lo que deja [started]: el hueco (null si es la primera sesión) y si la sesión pasó a ser la actual. */
    class Start(val gap: Gap?, val current: Boolean)

    private val lock = Any()
    private var current = 0
    private var lastStarted: EndStamp? = null
    private var broadcastNanos = 0L
    private var ackNanos = 0L

    /** La sesión actual (0 = ninguna). */
    val currentId: Int get() = synchronized(lock) { current }

    /** [sid] es la actual o no hay ninguna: para no borrar ni pisar lo de una sesión más nueva. */
    fun isCurrentOrNone(sid: Int): Boolean = synchronized(lock) { current == sid || current == 0 }

    /**
     * [sid] empieza (hilo de aceptación): se lleva el hueco abierto y pasa a ser la actual, salvo que ya esté cerrada
     * ([closed], mirado con el candado): entonces su fin ya pasó por [ended] sin encontrarla, o lo hará y no la
     * encontrará; en los dos casos, sin avisos. [end] es el sello de su fin, para el hueco de la siguiente.
     */
    fun started(sid: Int, end: EndStamp?, closed: () -> Boolean): Start = synchronized(lock) {
        val prev = lastStarted
        val gap = if (prev != null && prev.sid != sid) Gap(prev, broadcastNanos, ackNanos) else null
        lastStarted = end
        broadcastNanos = 0L
        ackNanos = 0L
        if (closed()) return Start(gap, false)
        current = sid
        Start(gap, true)
    }

    /** Tras preparar el puente de [sid]: encola [connected] si sigue siendo la actual. */
    fun confirm(sid: Int, connected: Runnable): Boolean = synchronized(lock) {
        if (current != sid) return false
        post(connected)
        true
    }

    /** [sid] terminó (hilo de eventos de esa sesión): si era la actual, deja de serlo y encola [lost]; si no, nada. */
    fun ended(sid: Int, lost: Runnable): Boolean = synchronized(lock) {
        if (current != sid) return false
        current = 0
        post(lost)
        true
    }

    /** Broadcast del coche sin sesión viva: el primero desde que empezó la última sesión. */
    fun noteBroadcast(now: Long) = synchronized(lock) {
        if (lastStarted != null && broadcastNanos == 0L) broadcastNanos = now
    }

    /** ACK al coche (siempre sin sesión viva): el primero desde que empezó la última sesión. */
    fun noteAck(now: Long) = synchronized(lock) {
        if (lastStarted != null && ackNanos == 0L) ackNanos = now
    }

    /** Relevo: la sesión vieja se da por muerta ahora, con el broadcast que lo provoca. */
    fun superseded(old: EndStamp?, now: Long) {
        old?.stamp(now)
        noteBroadcast(now)
    }
}
