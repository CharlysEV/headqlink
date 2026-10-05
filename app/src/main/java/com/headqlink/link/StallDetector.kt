package com.headqlink.link

import java.util.Locale

/**
 * Detector de cortes de radio de una sesión (qdauto §7.3). Se evalúa cada ~100 ms con la foto de E/S del núcleo y la
 * muestra de `NetStat`:
 * - **RX parado**: nada recibido del coche en ≥ [thresholdMs];
 * - **TX atascado**: un `write()` en curso desde hace ≥ [thresholdMs], o datos en la cola del kernel que ni bajan ni
 *   terminan ningún `write()` en ≥ [thresholdMs].
 *
 * Tipos al empezar: `RADIO` (TX atascado sin recepción, o subida atascada sin señales de que el coche no lea),
 * `COCHE_NO_LEE` (TX atascado con la ventana del coche a 0 o el tiempo limitado por el receptor creciendo) y
 * `MOVIL_CONGELADO` (el propio monitor no corrió en más de [freezeMs]: GC, CPU, doze). Da INICIO, PROGRESO cada
 * [progressMs] y FIN (vuelve el RX o avanza el TX, o se cierra la sesión). Sin Android y con reloj inyectado (ms
 * monótonos; [wall] los pasa a hora del día): lo prueban los tests. Lo usan dos hilos (el monitor de red con
 * [onSample], el de eventos con [onClose] y el resumen): todo va con el candado del objeto.
 */
internal class StallDetector(
    private val sid: Int,
    private val wall: (Long) -> String,
    private val thresholdMs: Long = 400,
    private val freezeMs: Long = 150,
    private val progressMs: Long = 1_000,
) {
    enum class Kind { RADIO, COCHE_NO_LEE, MOVIL_CONGELADO }

    /** Una muestra. Tiempos en ms monótonos (0 = no hay); cifras de NetStat a -1 si no se pueden leer. */
    class Sample(
        val nowMs: Long,
        val lastReceiveMs: Long,
        val lastRxKind: String?,
        val writingSinceMs: Long,
        val writingLabel: String?,
        val writingBytes: Int,
        val lastWriteEndMs: Long,
        val outq: Int = -1,
        val unacked: Int = -1,
        val retrans: Int = -1,
        val rttMs: Int = -1,
        val rttVarMs: Int = -1,
        val cwnd: Int = -1,
        val rwndLimitedMs: Int = -1,
        val carWindow: Int = -1,
        val context: String = "",
    )

    enum class Phase { START, PROGRESS, END }

    class Event(val phase: Phase, val kind: Kind, val durationMs: Long, val text: String) {
        override fun toString(): String = text
    }

    private var lastSampleMs = 0L
    private var lastOutq = -1
    private var lastTxProgressMs = 0L
    private var lastWriteEnd = 0L
    private var lastRwnd = -1
    private var rwndGrowingSinceMs = 0L

    private var active: Kind? = null
    private var startMs = 0L
    private var lastProgressMs = 0L
    private var startRetrans = -1
    private var startTxStuckMs = 0L

    /** El corte empezó con el coche hablando (solo la subida atascada): no termina porque llegue RX. */
    private var startedWithRx = false

    // Estadísticas de la sesión.
    @get:Synchronized
    var count = 0
        private set

    @get:Synchronized
    var maxMs = 0L
        private set

    @get:Synchronized
    var radio = 0
        private set

    @get:Synchronized
    var peer = 0
        private set

    @get:Synchronized
    var freezes = 0
        private set

    @get:Synchronized
    var maxFreezeMs = 0L
        private set

    @get:Synchronized
    var retransAtFirst = -1
        private set

    @get:Synchronized
    var lastRetrans = -1
        private set

    @get:Synchronized
    var maxOutq = 0
        private set

    /** Hay un corte en curso. */
    val inStall: Boolean
        @Synchronized get() = active != null

    @Synchronized
    fun onSample(s: Sample): List<Event> {
        val out = ArrayList<Event>(1)
        if (retransAtFirst < 0 && s.retrans >= 0) retransAtFirst = s.retrans
        if (s.retrans >= 0) lastRetrans = s.retrans
        if (s.outq > maxOutq) maxOutq = s.outq
        // Móvil congelado: el monitor no corrió.
        if (lastSampleMs != 0L) {
            val gap = s.nowMs - lastSampleMs
            if (gap > freezeMs) {
                freezes++
                if (gap > maxFreezeMs) maxFreezeMs = gap
                out += Event(
                    Phase.START, Kind.MOVIL_CONGELADO, gap,
                    "Corte S$sid MOVIL_CONGELADO: el monitor no corrió en $gap ms (${wall(lastSampleMs)} → ${wall(s.nowMs)}): " +
                        "GC, CPU o ahorro de energía",
                )
            }
        }
        lastSampleMs = s.nowMs
        // Progreso del TX: baja la cola del kernel, termina un write o la cola está vacía.
        if (s.lastWriteEndMs != lastWriteEnd) {
            lastWriteEnd = s.lastWriteEndMs
            lastTxProgressMs = s.nowMs
        }
        if (s.outq >= 0 && (s.outq == 0 || (lastOutq >= 0 && s.outq < lastOutq))) lastTxProgressMs = s.nowMs
        if (s.outq >= 0) lastOutq = s.outq
        if (lastTxProgressMs == 0L) lastTxProgressMs = s.nowMs
        if (s.rwndLimitedMs >= 0) {
            if (lastRwnd >= 0 && s.rwndLimitedMs > lastRwnd) {
                if (rwndGrowingSinceMs == 0L) rwndGrowingSinceMs = s.nowMs
            } else if (lastRwnd >= 0) {
                rwndGrowingSinceMs = 0L
            }
            lastRwnd = s.rwndLimitedMs
        }

        val rxStopped = s.lastReceiveMs > 0 && s.nowMs - s.lastReceiveMs >= thresholdMs
        val writeStuck = s.writingSinceMs > 0 && s.nowMs - s.writingSinceMs >= thresholdMs
        val queueStuck = s.outq > 0 && s.nowMs - lastTxProgressMs >= thresholdMs
        // Con un write en curso manda él (es lo más preciso); sin write, la cola del kernel que no baja.
        val txStuck = if (s.writingSinceMs > 0) writeStuck else queueStuck
        val peerNotReading = s.carWindow == 0 || rwndGrowingSinceMs != 0L

        val cur = active
        if (cur == null) {
            if (txStuck) {
                // Una ventana del coche a 0 la anuncia su propio TCP: la radio funciona y es la app del coche la que no
                // lee, aunque además esté callada.
                val kind = if (peerNotReading) Kind.COCHE_NO_LEE else Kind.RADIO
                active = kind
                startMs = s.nowMs
                lastProgressMs = s.nowMs
                startRetrans = s.retrans
                startTxStuckMs = if (writeStuck) s.writingSinceMs else lastTxProgressMs
                startedWithRx = !rxStopped
                count++
                if (kind == Kind.RADIO) radio++ else peer++
                out += Event(Phase.START, kind, 0, startText(kind, s, rxStopped, writeStuck))
            }
        } else {
            val txFlowing = !txStuck
            val rxBack = !rxStopped
            val rxEnds = cur == Kind.RADIO && rxBack && !startedWithRx
            if (txFlowing || rxEnds) {
                val why = when {
                    txFlowing && rxBack && !startedWithRx -> "vuelven RX y TX"
                    txFlowing -> "el TX avanza"
                    else -> "vuelve RX"
                }
                out += end(s.nowMs, why, s)
            } else if (s.nowMs - lastProgressMs >= progressMs) {
                lastProgressMs = s.nowMs
                out += Event(Phase.PROGRESS, cur, s.nowMs - startMs, progressText(cur, s))
            }
        }
        return out
    }

    /** La sesión se cierra: termina el corte en curso (si lo hay) con el motivo. */
    @Synchronized
    fun onClose(nowMs: Long, reason: String): Event? = if (active == null) null else end(nowMs, "sesión cerrada ($reason)", null)

    private fun end(nowMs: Long, why: String, s: Sample?): Event {
        val kind = active!!
        val d = nowMs - startMs
        active = null
        if (d > maxMs) maxMs = d
        val sb = StringBuilder("Corte S").append(sid).append(" FIN tras ").append(d).append(" ms: ").append(why)
        if (s != null) {
            if (s.outq >= 0) sb.append(" · outq ").append(kb(s.outq))
            if (s.retrans >= 0 && startRetrans >= 0) sb.append(" · retrans ").append(s.retrans).append(" (+").append(s.retrans - startRetrans).append(')')
        }
        return Event(Phase.END, kind, d, sb.toString())
    }

    private fun startText(kind: Kind, s: Sample, rxStopped: Boolean, writeStuck: Boolean): String {
        val sb = StringBuilder("Corte S").append(sid).append(" INICIO ").append(kind).append(": ")
        sb.append("último RX ").append(if (s.lastReceiveMs > 0) wall(s.lastReceiveMs) else "?")
        s.lastRxKind?.let { sb.append(" (").append(it).append(')') }
        if (!rxStopped) sb.append(" (el coche sigue hablando)")
        sb.append(", TX atascado desde ").append(wall(startTxStuckMs))
        if (writeStuck) {
            sb.append(" (write de ").append(s.writingLabel ?: "?").append(' ').append(s.writingBytes).append(" B, ")
                .append(s.nowMs - s.writingSinceMs).append(" ms)")
        } else {
            sb.append(" (cola del kernel sin bajar)")
        }
        netText(sb, s)
        if (kind == Kind.COCHE_NO_LEE) sb.append(" · ventana del coche ").append(if (s.carWindow >= 0) kb(s.carWindow) else "?")
        if (s.context.isNotEmpty()) sb.append(" · ").append(s.context)
        return sb.toString()
    }

    private fun progressText(kind: Kind, s: Sample): String {
        val sb = StringBuilder("Corte S").append(sid).append(" sigue ").append(s.nowMs - startMs).append(" ms (").append(kind).append(')')
        netText(sb, s)
        if (s.retrans >= 0 && startRetrans >= 0) sb.append(" (+").append(s.retrans - startRetrans).append(')')
        return sb.toString()
    }

    private fun netText(sb: StringBuilder, s: Sample) {
        if (s.outq >= 0) sb.append(" · outq ").append(kb(s.outq))
        if (s.unacked >= 0) sb.append(" · unacked ").append(s.unacked)
        if (s.retrans >= 0) sb.append(" · retrans ").append(s.retrans)
        if (s.rttMs >= 0) sb.append(" · rtt ").append(s.rttMs).append('/').append(maxOf(0, s.rttVarMs)).append(" ms")
        if (s.cwnd >= 0) sb.append(" · cwnd ").append(s.cwnd)
    }

    /** Resumen para el bloque de la sesión: «sin cortes» o «N cortes (radio a, coche b), máx M ms». */
    @Synchronized
    fun summary(): String {
        val sb = StringBuilder()
        if (count == 0) sb.append("sin cortes") else sb.append(count).append(" cortes (radio ").append(radio)
            .append(", coche sin leer ").append(peer).append("), máx. ").append(maxMs).append(" ms")
        if (freezes > 0) sb.append(" · móvil congelado ").append(freezes).append(" veces (máx. ").append(maxFreezeMs).append(" ms)")
        if (retransAtFirst >= 0 && lastRetrans >= 0) sb.append(" · retrans +").append(lastRetrans - retransAtFirst)
        sb.append(" · outq máx. ").append(kb(maxOutq))
        return sb.toString()
    }

    private fun kb(bytes: Int): String = if (bytes < 1024) "$bytes B" else String.format(Locale.US, "%d KB", bytes / 1024)
}
