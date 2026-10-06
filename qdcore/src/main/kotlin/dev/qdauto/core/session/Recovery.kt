package dev.qdauto.core.session

import dev.qdauto.core.discovery.AckPolicy
import java.net.InetAddress

/**
 * hql: vuelta del coche tras un corte de radio ([PhoneLink]). En el C10 (2026-10-06), tras un cierre normal el coche se
 * reanuncia a los ~5 s y conecta al instante; tras un corte de radio (WATCHDOG/WRITE_STALL con el enlace muerto en los
 * dos sentidos) tardó 61-175 s en reanunciarse (alguna vez, nunca), y dos veces se anunció una sola vez, recibió (se
 * supone) el ACK y nunca abrió el TCP. No se sabe si el coche intenta volver al puerto de antes, si hace caso a un ACK
 * que no pidió ni si sus broadcasts llegan al socket con la pantalla apagada. Todo esto es defensivo, va al log y se
 * puede apagar:
 *
 * - **Puerto estable** ([stableMirrorPort]): los intentos siguientes reutilizan el `MirrorPort` de la última sesión
 *   (`SO_REUSEADDR`; si no se puede, uno aleatorio, con aviso).
 * - **Re-acogida** ([reclaim]): tras un final anormal ([reclaimOn]) se vuelve a escuchar en el mismo puerto hasta
 *   [reclaimWindowMs] (por si el coche vuelve directamente a IP:puerto de antes) y se le mandan ACK **no pedidos**
 *   con ese puerto ([unsolicitedAckPolicy]: cada 2 s el primer minuto y luego cada 5 s). Un broadcast suyo durante la
 *   re-acogida se contesta en el acto con el mismo puerto, sin abrir otro servidor.
 * - **Vigilancia** mientras se espera al coche tras perder la sesión (hasta [watchMs]): cada [diagIntervalMs] una línea
 *   de diagnóstico (datagramas, ACK pedidos/no pedidos, TCP y, con [carReachable], si la IP del coche responde) y, si
 *   no llega ningún datagrama en [udpRefreshSilenceMs], se reabre el socket UDP (como mucho cada
 *   [udpRefreshMinIntervalMs]).
 * - Al volver: «vuelta del coche tras X s: por anuncio / por ACK no pedido / por puerto anterior» ([CarReturn]).
 */
data class RecoveryConfig(
    /** Reutilizar el `MirrorPort` de la última sesión en los intentos siguientes (solo con un puerto aleatorio). */
    val stableMirrorPort: Boolean = true,
    /** Re-acogida tras un final anormal de la sesión. */
    val reclaim: Boolean = true,
    /** Finales de sesión que abren la re-acogida. */
    val reclaimOn: Set<CloseReason.Kind> = DEFAULT_RECLAIM_ON,
    /** Cuánto se espera al coche en la re-acogida (lo que la app espera al coche, p. ej. 5 min). */
    val reclaimWindowMs: Long = 300_000,
    /** ACK no pedidos durante la re-acogida (`false` = solo se escucha). */
    val unsolicitedAcks: Boolean = true,
    val unsolicitedAckPolicy: AckPolicy = AckPolicy.UNSOLICITED,
    /** Vigilancia tras perder una sesión (0 = sin vigilancia: ni diagnóstico ni reapertura del UDP). */
    val watchMs: Long = 300_000,
    /** Periodo de la comprobación de la vigilancia. */
    val tickMs: Long = 1_000,
    /** Línea de diagnóstico de la espera (0 = no). */
    val diagIntervalMs: Long = 10_000,
    /** Reabrir el UDP tras esto sin ningún datagrama desde la pérdida (0 = no). */
    val udpRefreshSilenceMs: Long = 20_000,
    val udpRefreshMinIntervalMs: Long = 20_000,
    /**
     * Para decir «por ACK no pedido»: el TCP llega como mucho esto después de uno (el C10 conecta a los 15-320 ms del
     * ACK). Si llega más tarde y sin anuncio, «por puerto anterior». Es una estimación (un coche que vuelve solo justo
     * tras un ACK no pedido no se distingue): el log da los tiempos.
     */
    val ackReactionMs: Long = 500,
    /**
     * ¿Responde la IP del coche? (`true`/`false`; `null` = no se sabe). Lo pone la app (ping); se llama en el hilo de
     * la vigilancia, nunca en el principal. `null` = no se mira.
     */
    val carReachable: ((InetAddress) -> Boolean?)? = null,
) {
    companion object {
        val DEFAULT_RECLAIM_ON: Set<CloseReason.Kind> =
            setOf(CloseReason.Kind.WATCHDOG, CloseReason.Kind.WRITE_STALL, CloseReason.Kind.READ_ERROR)

        /** Todo apagado: un puerto nuevo por intento, sin re-acogida ni vigilancia (como antes). */
        val OFF = RecoveryConfig(stableMirrorPort = false, reclaim = false, watchMs = 0, diagIntervalMs = 0, udpRefreshSilenceMs = 0)
    }
}

/** hql: por dónde volvió el coche tras perder la sesión (estimación, ver [RecoveryConfig.ackReactionMs]). */
enum class CarReturn(val label: String) {
    /** Se anunció (broadcast) y conectó tras nuestro ACK. */
    BROADCAST("por anuncio"),

    /** Sin anunciarse, conectó justo después de un ACK no pedido. */
    UNSOLICITED_ACK("por ACK no pedido"),

    /** Sin anunciarse ni ACK reciente: volvió al puerto de antes por su cuenta. */
    OLD_PORT("por puerto anterior"),
    ;

    companion object {
        /**
         * Con los instantes (`System.nanoTime`, 0 = nunca) del último broadcast y del último ACK no pedido durante la
         * espera: anuncio si el TCP llega en [acceptWindowMs] del broadcast; si no, ACK no pedido si llega en
         * [ackReactionMs] de uno; si no, puerto anterior.
         */
        fun classify(acceptNanos: Long, lastBroadcastNanos: Long, lastUnsolicitedAckNanos: Long, acceptWindowMs: Long, ackReactionMs: Long): CarReturn {
            fun within(t: Long, ms: Long) = t != 0L && acceptNanos - t in 0..ms * 1_000_000
            return when {
                within(lastBroadcastNanos, acceptWindowMs) -> BROADCAST
                within(lastUnsolicitedAckNanos, ackReactionMs) -> UNSOLICITED_ACK
                else -> OLD_PORT
            }
        }
    }
}

/**
 * hql: qué toca en cada comprobación de la vigilancia tras perder la sesión, sin hilos ni reloj propio (los instantes,
 * `System.nanoTime`, se pasan): la prueban los tests con un reloj de mentira.
 */
internal class LossWatch(val startNanos: Long, private val config: RecoveryConfig) {
    private var lastDiagNanos = startNanos
    private var lastReopenNanos = 0L

    /** [expired]: se acabó la vigilancia. [reopenQuietMs] ≥ 0: reabrir el UDP (ms sin datagramas). [diag]: diagnóstico. */
    class Due(val expired: Boolean, val reopenQuietMs: Long, val diag: Boolean)

    /** Con el instante actual y el del último datagrama recibido (0 = ninguno). */
    fun due(now: Long, lastDatagramNanos: Long): Due {
        if (config.watchMs > 0 && now - startNanos >= ms(config.watchMs)) return Due(true, -1, false)
        var reopen = -1L
        if (config.udpRefreshSilenceMs > 0) {
            val quietMs = (now - maxOf(startNanos, lastDatagramNanos)) / 1_000_000
            val sinceReopen = if (lastReopenNanos == 0L) Long.MAX_VALUE else (now - lastReopenNanos) / 1_000_000
            if (quietMs >= config.udpRefreshSilenceMs && sinceReopen >= config.udpRefreshMinIntervalMs) {
                reopen = quietMs
                lastReopenNanos = now
            }
        }
        var diag = false
        if (config.diagIntervalMs > 0 && now - lastDiagNanos >= ms(config.diagIntervalMs)) {
            diag = true
            lastDiagNanos = now
        }
        return Due(false, reopen, diag)
    }

    private fun ms(v: Long) = v * 1_000_000
}
