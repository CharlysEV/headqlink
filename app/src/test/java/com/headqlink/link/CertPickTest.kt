package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * El certificado elegido en una o en varias veces (CarCloudActivity): el .crt y el .key juntos o uno detrás de otro,
 * qué falta, «Empezar de nuevo», la caducidad y los ficheros que no sirven. Claves y certificados de usar y tirar.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class CertPickTest {
    private val cert = TestCerts.selfSigned(TestCerts.rsa, "app")
    private fun crt(name: String = "app.crt") = LeapTls.Picked(name, TestCerts.certPem(cert).toByteArray())
    private fun key(name: String = "app.key") = LeapTls.Picked(name, TestCerts.pkcs8Pem(TestCerts.rsa.private).toByteArray())

    @Test
    fun classifiesByContentNotByName() {
        assertEquals(CertPick.Content.CERT, CertPick.classify(crt("raro.txt")))
        assertEquals(CertPick.Content.KEY, CertPick.classify(key("app.crt")))
        assertEquals(CertPick.Content.BOTH, CertPick.classify(LeapTls.Picked("app.pem",
            (TestCerts.certPem(cert) + TestCerts.pkcs8Pem(TestCerts.rsa.private)).toByteArray())))
        assertEquals(CertPick.Content.CERT, CertPick.classify(LeapTls.Picked("app.cer", cert.encoded)))
        assertEquals(CertPick.Content.KEY, CertPick.classify(LeapTls.Picked("app.der", TestCerts.rsa.private.encoded)))
        assertEquals(CertPick.Content.KEY, CertPick.classify(LeapTls.Picked("k.pem",
            TestCerts.pem("RSA PRIVATE KEY", TestCerts.innerKey(TestCerts.rsa.private)).toByteArray())))
        assertEquals(CertPick.Content.BUNDLE, CertPick.classify(LeapTls.Picked("app.p12",
            TestCerts.pkcs12(TestCerts.rsa.private, arrayOf(cert), "x".toCharArray()))))
        assertEquals(CertPick.Content.UNKNOWN, CertPick.classify(LeapTls.Picked("léeme.txt", "hola".toByteArray())))
        assertEquals(CertPick.Content.UNKNOWN, CertPick.classify(LeapTls.Picked("x.bin", ByteArray(64) { 7 })))
        assertEquals(CertPick.Content.BUNDLE, CertPick.classify(LeapTls.Picked("x.bin", ByteArray(LeapTls.MAX_FILE_BYTES + 1))))
    }

    @Test
    fun bothAtOnceIsReadyStraightAway() {
        val p = CertPick()
        val s = p.add(listOf(crt(), key()), 0)
        assertEquals(CertPick.Step.Kind.READY, s.kind)
        assertEquals(2, s.files.size)
        assertEquals(cert, LeapTls.parse(s.files, null).leaf())
        assertEquals(CertPick.Step.Kind.NOTHING, p.state(0).kind)
    }

    @Test
    fun certificateThenKeyOneAtATime() {
        val p = CertPick()
        val first = p.add(listOf(crt()), 0)
        assertEquals(CertPick.Step.Kind.NEED_KEY, first.kind)
        assertEquals("app.crt", first.have)
        assertEquals(CertPick.Step.Kind.NEED_KEY, p.state(1_000).kind)
        val second = p.add(listOf(key()), 2_000)
        assertEquals(CertPick.Step.Kind.READY, second.kind)
        assertEquals(cert, LeapTls.parse(second.files, null).leaf())
        assertEquals("ya entregado: no queda nada a medias", CertPick.Step.Kind.NOTHING, p.state(3_000).kind)
    }

    @Test
    fun keyThenCertificateOneAtATime() {
        val p = CertPick()
        val first = p.add(listOf(key()), 0)
        assertEquals(CertPick.Step.Kind.NEED_CERT, first.kind)
        assertEquals("app.key", first.have)
        val second = p.add(listOf(crt()), 1)
        assertEquals(CertPick.Step.Kind.READY, second.kind)
        assertEquals(cert, LeapTls.parse(second.files, null).leaf())
    }

    @Test
    fun pickingTheSameHalfAgainReplacesIt() {
        val p = CertPick()
        val old = crt("viejo.crt")
        p.add(listOf(old), 0)
        val s = p.add(listOf(crt("nuevo.crt")), 1)
        assertEquals(CertPick.Step.Kind.NEED_KEY, s.kind)
        assertEquals("nuevo.crt", s.have)
        assertTrue("el sustituido se borra", old.bytes.all { it.toInt() == 0 })
        val done = p.add(listOf(key()), 2)
        assertEquals(listOf("nuevo.crt", "app.key"), done.files.map { it.name })
    }

    @Test
    fun aCompleteFileReplacesAHalfPair() {
        val p = CertPick()
        p.add(listOf(key("otra.key")), 0)
        val p12 = LeapTls.Picked("app.p12", TestCerts.pkcs12(TestCerts.rsa.private, arrayOf(cert), "x".toCharArray()))
        val s = p.add(listOf(p12), 1)
        assertEquals(CertPick.Step.Kind.READY, s.kind)
        assertEquals(listOf("app.p12"), s.files.map { it.name })
        assertEquals(CertPick.Step.Kind.NOTHING, p.state(2).kind)
    }

    @Test
    fun uselessFilesAreReportedAndDoNotLoseTheHalfPair() {
        val p = CertPick()
        p.add(listOf(crt()), 0)
        val bad = p.add(listOf(LeapTls.Picked("léeme.txt", "hola".toByteArray())), 1)
        assertEquals(CertPick.Step.Kind.UNREADABLE, bad.kind)
        assertEquals(listOf("léeme.txt"), bad.unknown)
        assertEquals(CertPick.Step.Kind.NEED_KEY, p.state(2).kind)
        // Con algo útil al lado, lo que no sirve se aparta (LeapTls.parse lo daría por ilegible).
        val ok = p.add(listOf(key(), LeapTls.Picked("notas.txt", "x".toByteArray())), 3)
        assertEquals(CertPick.Step.Kind.READY, ok.kind)
        assertEquals(listOf("notas.txt"), ok.unknown)
        assertEquals(listOf("app.crt", "app.key"), ok.files.map { it.name })
        assertEquals(cert, LeapTls.parse(ok.files, null).leaf())
    }

    @Test
    fun startOverForgetsAndWipes() {
        val p = CertPick()
        val k = key()
        p.add(listOf(k), 0)
        p.clear()
        assertEquals(CertPick.Step.Kind.NOTHING, p.state(1).kind)
        assertTrue(k.bytes.all { it.toInt() == 0 })
        assertEquals(CertPick.Step.Kind.NEED_CERT, p.add(listOf(key()), 2).kind)
    }

    @Test
    fun aHalfPairExpires() {
        val p = CertPick()
        p.add(listOf(crt()), 0)
        assertEquals(CertPick.Step.Kind.NEED_KEY, p.state(CertPick.EXPIRE_MS).kind)
        assertEquals(CertPick.Step.Kind.NOTHING, p.state(CertPick.EXPIRE_MS + 1).kind)
        assertEquals("caducado: la clave sola vuelve a pedir el certificado", CertPick.Step.Kind.NEED_CERT,
            p.add(listOf(key()), CertPick.EXPIRE_MS + 2).kind)
    }

    @Test
    fun emptyPickChangesNothing() {
        val p = CertPick()
        p.add(listOf(crt()), 0)
        val s = p.add(emptyList(), 1)
        assertEquals(CertPick.Step.Kind.NEED_KEY, s.kind)
        assertEquals("app.crt", s.have)
    }

    @Test
    fun namesForTheLogOnly() {
        assertEquals("app.crt, (sin nombre)", CertPick.names(listOf(crt(), LeapTls.Picked("", ByteArray(1)))))
    }
}
