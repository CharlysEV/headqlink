package com.headqlink.link

import java.net.InetAddress

/**
 * ¿Responde la IP del coche a un ping? Para el diagnóstico de la espera tras un corte de radio («coche en la zona Wi-Fi:
 * sí/no (ping)»): si la radio del coche sigue en la zona Wi-Fi pero no se anuncia ni conecta, el problema es su
 * programa, no la radio. `/system/bin/ping -c 1 -W 1 <ip>` (ICMP sin permisos especiales en Android).
 *
 * Bloquea ~1 s (como mucho [TIMEOUT_MS]): nunca en el hilo principal (lo llama el hilo de vigilancia del enlace). Solo
 * API 1 (minSdk 16): sin `Process.waitFor(timeout)`.
 */
internal object CarPing {
    private const val PING = "/system/bin/ping"
    private const val TIMEOUT_MS = 3_000L

    /** `true`/`false`, o `null` si no se pudo saber (sin ping, error o se acabó el tiempo). */
    fun reachable(ip: InetAddress): Boolean? {
        val host = ip.hostAddress ?: return null
        val p = try {
            ProcessBuilder(PING, "-c", "1", "-W", "1", host).redirectErrorStream(true).start()
        } catch (e: Exception) {
            L.w("ping a $host: no se pudo lanzar ($e)")
            return null
        }
        try {
            val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
            while (true) {
                // La salida (unos cientos de bytes) cabe en la tubería: no hace falta leerla para que termine.
                val code = try {
                    p.exitValue()
                } catch (_: IllegalThreadStateException) {
                    null
                }
                if (code != null) return when (code) {
                    0 -> true
                    1 -> false // sin respuesta
                    else -> null // error (red inalcanzable, sin permiso…)
                }
                if (System.nanoTime() >= deadline) return null
                Thread.sleep(50)
            }
        } catch (_: InterruptedException) {
            // El enlace se cierra (Desconectar): el hilo de vigilancia se interrumpe.
            Thread.currentThread().interrupt()
            return null
        } finally {
            p.destroy()
            try {
                p.inputStream.close()
                p.outputStream.close()
            } catch (_: Exception) {
            }
        }
    }
}
