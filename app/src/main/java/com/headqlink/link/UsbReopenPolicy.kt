package com.headqlink.link

/**
 * Cuándo volver a abrir el accesorio del coche tras el fin de una sesión por cable (puro: lo prueban los tests). Lo usa
 * [UsbLink] solo desde su hilo `hql-usb`.
 *
 * Prueba real por cable (2026-10-06): con el accesorio abierto pero el coche aún sin hablar, cada sesión moría por el
 * watchdog a los 10 s y se reabría en 1 s; como duraba ≥ 10 s «no era corta» y la espera no crecía nunca (115 sesiones
 * en 20 min). Y al quitar el cable, `write failed: ENODEV` encadenaba sesiones en milisegundos. Reglas:
 * - **Espera creciente** 1, 2, 5, 10, 30, 60 s por cada sesión fallida seguida: sin ningún mensaje del coche, sin
 *   `CAR_INFO`, o con `CAR_INFO` pero muy corta (< [QUICK_END_MS]). Solo una sesión con `CAR_INFO` que duró al menos
 *   [QUICK_END_MS] la reinicia.
 * - **Accesorio desaparecido**: una sesión que termina con ENODEV o EIO en el descriptor (el cable se quitó o el coche
 *   se fue) no se reabre hasta que el coche vuelva a poner el móvil en modo accesorio (`USB_STATE accessory=true` nuevo
 *   o `USB_ACCESSORY_ATTACHED`: [onAccessoryArrived]).
 */
internal class UsbReopenPolicy {
    /** delayMs < 0: no volver a abrir (accesorio desaparecido). [text]: para el log. */
    class Decision(val delayMs: Long, val text: String) {
        val reopen: Boolean get() = delayMs >= 0
        override fun toString(): String = text
    }

    /** Sesiones fallidas seguidas (índice de la próxima espera). */
    var failures = 0
        private set

    /** El accesorio dio ENODEV/EIO: nada de reabrir hasta que vuelva. */
    var accessoryGone = false
        private set

    /** Fin de una sesión por cable que duró [livedMs], con [carMessages] mensajes del coche y su [reason] de cierre. */
    fun onSessionEnd(carMessages: Long, gotCarInfo: Boolean, livedMs: Long, reason: String): Decision {
        val lived = "${livedMs / 1000} s de sesión"
        if (isAccessoryGone(reason)) {
            accessoryGone = true
            return Decision(
                -1,
                "accesorio desaparecido (${goneWord(reason)}; $lived): no lo vuelvo a abrir hasta que el coche lo vuelva a " +
                    "poner en modo accesorio (USB_STATE accessory=true o USB_ACCESSORY_ATTACHED)",
            )
        }
        if (gotCarInfo && livedMs >= QUICK_END_MS) {
            val had = failures
            failures = 0
            return Decision(
                BACKOFF_MS[0],
                "la sesión terminó con el cable puesto ($lived, con CAR_INFO)" +
                    (if (had > 0) "; espera creciente reiniciada (había $had fallidas seguidas)" else ""),
            )
        }
        val delay = BACKOFF_MS[minOf(failures, BACKOFF_MS.size - 1)]
        failures++
        val why = when {
            carMessages <= 0L -> "sin ningún mensaje del coche"
            !gotCarInfo -> "sin CAR_INFO del coche ($carMessages mensajes)"
            else -> "con CAR_INFO pero de menos de ${QUICK_END_MS / 1000} s"
        }
        return Decision(
            delay,
            "la sesión terminó con el cable puesto ($lived, $why): no reinicia la espera; fallida $failures seguida, " +
                "vuelvo a abrir en ${delay / 1000} s (1, 2, 5, 10, 30, 60 s)",
        )
    }

    /** El coche vuelve a poner el móvil en modo accesorio (o el usuario o Android lo vuelven a ofrecer). true si estaba fuera. */
    fun onAccessoryArrived(): Boolean {
        val was = accessoryGone
        accessoryGone = false
        failures = 0
        return was
    }

    /** Se puede abrir el accesorio (no desapareció con ENODEV/EIO). */
    fun mayOpen(): Boolean = !accessoryGone

    companion object {
        /** Una sesión con CAR_INFO más corta que esto cuenta como fallida (el coche saluda y se va). */
        const val QUICK_END_MS = 10_000L
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000, 60_000)

        private val GONE = Regex("""\b(ENODEV|EIO)\b|No such device|I/O error""", RegexOption.IGNORE_CASE)

        /** El cierre dice que el descriptor del accesorio ya no vale: ENODEV («No such device») o EIO («I/O error»). */
        fun isAccessoryGone(reason: String): Boolean = GONE.containsMatchIn(reason)

        private fun goneWord(reason: String): String = GONE.find(reason)?.value ?: "?"
    }
}
