package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/** Errores de red en el log exportado: de cada URL solo queda el host (sin la posición ni el destino, qdauto §7.5). */
class HttpSafeErrorTest {
    @Test
    fun urlsKeepOnlyTheHost() {
        val weather = "https://api.open-meteo.com/v1/forecast?latitude=40.4168&longitude=-3.7038&current=temperature_2m"
        // Lo que lanza HttpURLConnection con un 4xx/5xx: la URL entera como mensaje.
        assertEquals("FileNotFoundException: api.open-meteo.com", Http.safeError(FileNotFoundException(weather)))
        val osrm = "https://router.project-osrm.org/route/v1/driving/-3.703800,40.416800;2.173400,41.385100?overview=full"
        val e = Http.safeError(IOException("unexpected end of stream on $osrm (reintento)"))
        assertEquals("IOException: unexpected end of stream on router.project-osrm.org (reintento)", e)
        assertFalse(e, e.contains("40.4168"))
    }

    @Test
    fun messagesWithoutUrlsAreKept() {
        assertEquals("IllegalStateException: HTTP 503 en overpass-api.de", Http.safeError(IllegalStateException("HTTP 503 en overpass-api.de")))
        assertEquals("SocketTimeoutException", Http.safeError(java.net.SocketTimeoutException()))
        assertEquals("", Http.safeError(null))
    }
}
