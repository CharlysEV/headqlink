package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PBEParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Certificado de cliente (los formatos que acepta LMB10 y alguno más) y TLS mutuo con la clave del servidor fijada.
 * Todas las claves y certificados se generan al vuelo (TestCerts): ninguno real.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class LeapTlsTest {
    private fun identity(kp: java.security.KeyPair, cn: String) = LeapTls.Identity(kp.private, arrayOf(TestCerts.selfSigned(kp, cn)))

    private fun picked(name: String, text: String) = LeapTls.Picked(name, text.toByteArray())
    private fun picked(name: String, bytes: ByteArray) = LeapTls.Picked(name, bytes)

    private fun importKind(files: List<LeapTls.Picked>, pw: CharArray? = null): LeapTls.ImportException.Kind? =
        try {
            LeapTls.parse(files, pw)
            null
        } catch (e: LeapTls.ImportException) {
            e.kind
        }

    @Test
    fun pemPairAsTwoFiles() {
        val cert = TestCerts.selfSigned(TestCerts.rsa, "app")
        val id = LeapTls.parse(listOf(picked("app.crt", TestCerts.certPem(cert)), picked("app.key", TestCerts.pkcs8Pem(TestCerts.rsa.private))), null)
        assertEquals(cert, id.leaf())
        assertEquals("RSA", id.key.algorithm)
        // El orden de los ficheros da igual.
        val id2 = LeapTls.parse(listOf(picked("app.key", TestCerts.pkcs8Pem(TestCerts.rsa.private)), picked("app.crt", TestCerts.certPem(cert))), null)
        assertEquals(cert, id2.leaf())
    }

    @Test
    fun combinedPemWithTextAroundAndAnExtraCaCertificate() {
        val ca = TestCerts.selfSigned(TestCerts.rsa2, "otra")
        val cert = TestCerts.selfSigned(TestCerts.rsa, "app")
        val text = "Bag Attributes\n  friendlyName: x\n" + TestCerts.certPem(ca) + "\r\n" + TestCerts.pkcs8Pem(TestCerts.rsa.private) +
            "subject=CN=app\n" + TestCerts.certPem(cert)
        val id = LeapTls.parse(listOf(picked("app.pem", "﻿" + text)), null)
        assertEquals("el que corresponde a la clave va primero", cert, id.leaf())
        assertEquals(2, id.chain.size)
    }

    @Test
    fun pkcs1RsaAndSec1EcKeys() {
        val rsaCert = TestCerts.selfSigned(TestCerts.rsa, "rsa")
        val rsa = LeapTls.parse(listOf(picked("c.pem", TestCerts.certPem(rsaCert) + TestCerts.pem("RSA PRIVATE KEY", TestCerts.innerKey(TestCerts.rsa.private)))), null)
        assertArrayEquals(TestCerts.rsa.private.encoded, rsa.key.encoded)
        val ecCert = TestCerts.selfSigned(TestCerts.ec, "ec")
        val ec = LeapTls.parse(listOf(picked("c.crt", TestCerts.certPem(ecCert)),
            picked("c.key", TestCerts.pem("EC PARAMETERS", TestCerts.oid("1.2.840.10045.3.1.7")) + TestCerts.pem("EC PRIVATE KEY", TestCerts.sec1WithCurve(TestCerts.ec.private)))), null)
        assertEquals("EC", ec.key.algorithm)
        assertTrue(LeapTls.matches(ec.key, ecCert.publicKey))
    }

    @Test
    fun derFiles() {
        val cert = TestCerts.selfSigned(TestCerts.ec, "der")
        val id = LeapTls.parse(listOf(picked("app.cer", cert.encoded), picked("app.der", TestCerts.ec.private.encoded)), null)
        assertEquals(cert, id.leaf())
    }

    @Test
    fun pkcs12AsksForItsPassword() {
        val cert = TestCerts.selfSigned(TestCerts.rsa, "p12")
        val p12 = TestCerts.pkcs12(TestCerts.rsa.private, arrayOf(cert), "s3creto".toCharArray())
        assertEquals(LeapTls.ImportException.Kind.NEEDS_PASSWORD, importKind(listOf(picked("app.p12", p12))))
        assertEquals(LeapTls.ImportException.Kind.WRONG_PASSWORD, importKind(listOf(picked("app.p12", p12)), "otra".toCharArray()))
        val id = LeapTls.parse(listOf(picked("app.p12", p12)), "s3creto".toCharArray())
        assertEquals(cert, id.leaf())
    }

    @Test
    fun encryptedPkcs8NeedsItsPassword() {
        val cert = TestCerts.selfSigned(TestCerts.rsa, "enc")
        val alg = "PBEWithSHA1AndDESede"
        val params = PBEParameterSpec(ByteArray(8) { it.toByte() }, 2048)
        val c = Cipher.getInstance(alg)
        c.init(Cipher.ENCRYPT_MODE, SecretKeyFactory.getInstance(alg).generateSecret(PBEKeySpec("clave".toCharArray())), params)
        val enc = EncryptedPrivateKeyInfo(c.parameters, c.doFinal(TestCerts.rsa.private.encoded)).encoded
        val files = listOf(picked("a.crt", TestCerts.certPem(cert)), picked("a.key", TestCerts.pem("ENCRYPTED PRIVATE KEY", enc)))
        assertEquals(LeapTls.ImportException.Kind.NEEDS_PASSWORD, importKind(files))
        assertEquals(LeapTls.ImportException.Kind.WRONG_PASSWORD, importKind(files, "mala".toCharArray()))
        assertEquals(cert, LeapTls.parse(files, "clave".toCharArray()).leaf())
    }

    @Test
    fun clearErrors() {
        val cert = TestCerts.selfSigned(TestCerts.rsa, "a")
        val key = TestCerts.pkcs8Pem(TestCerts.rsa.private)
        assertEquals(LeapTls.ImportException.Kind.NO_KEY, importKind(listOf(picked("a.crt", TestCerts.certPem(cert)))))
        assertEquals(LeapTls.ImportException.Kind.NO_CERT, importKind(listOf(picked("a.key", key))))
        assertEquals(LeapTls.ImportException.Kind.MISMATCH, importKind(listOf(picked("a.crt", TestCerts.certPem(cert)),
            picked("b.key", TestCerts.pkcs8Pem(TestCerts.rsa2.private)))))
        assertEquals(LeapTls.ImportException.Kind.UNREADABLE, importKind(listOf(picked("x.bin", ByteArray(64) { 7 }))))
        assertEquals(LeapTls.ImportException.Kind.UNREADABLE, importKind(listOf(picked("x.pem", "-----BEGIN CERTIFICATE-----\nAAAA\n"))))
        assertEquals(LeapTls.ImportException.Kind.TOO_BIG, importKind(listOf(picked("x.bin", ByteArray(LeapTls.MAX_FILE_BYTES + 1)))))
        // Clave cifrada al estilo antiguo de OpenSSL (Proc-Type): se dice que no se puede, no «ilegible».
        val legacy = "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,00\n\nAAAA\n-----END RSA PRIVATE KEY-----\n"
        assertEquals(LeapTls.ImportException.Kind.ENCRYPTED_KEY_UNSUPPORTED,
            importKind(listOf(picked("a.crt", TestCerts.certPem(cert)), picked("a.key", legacy))))
    }

    @Test
    fun identitySurvivesItsJson() {
        val id = identity(TestCerts.ec, "json")
        val back = LeapTls.Identity.fromJson(id.toJson())
        assertArrayEquals(id.key.encoded, back.key.encoded)
        assertEquals(id.leaf(), back.leaf())
    }

    @Test
    fun pinTrustAcceptsOnlyKnownKeys() {
        val cert = TestCerts.selfSigned(TestCerts.rsa, "appgateway")
        val pin = LeapTls.spkiPin(cert.publicKey)
        LeapTls.PinTrust(listOf(pin)).checkServerTrusted(arrayOf(cert), "RSA")
        try {
            LeapTls.PinTrust(emptyList()).checkServerTrusted(arrayOf(cert), "RSA")
            fail("sin el pin no debe aceptarlo")
        } catch (e: LeapTls.UnknownServerKeyException) {
            assertEquals(pin, e.pin)
            assertTrue(e.fingerprint.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
            assertEquals("appgateway", e.subject)
        }
        try {
            LeapTls.PinTrust(emptyList()).checkServerTrusted(arrayOf(), "RSA")
            fail()
        } catch (e: CertificateException) {
            // bien
        }
        // El pin de serie es el de la pasarela de Leapmotor (SPKI SHA-256, Base64).
        assertTrue(LeapTls.BUILT_IN_PINS[0].matches(Regex("[A-Za-z0-9+/]{43}=")))
    }

    /** TLS mutuo de verdad contra un servidor local: presenta el certificado de cliente y exige la clave fijada. */
    @Test
    fun mutualTlsHandshakeWithPinnedServer() {
        val server = identity(TestCerts.rsa2, "servidor")
        val client = identity(TestCerts.ec, "cliente")
        val seen = AtomicReference<X509Certificate>()
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("servidor", server.key, "x".toCharArray(), server.chain)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "x".toCharArray())
        val serverCtx = SSLContext.getInstance("TLS")
        serverCtx.init(kmf.keyManagers, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                seen.set(chain[0])
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), null)
        val ss = serverCtx.serverSocketFactory.createServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress()) as SSLServerSocket
        ss.needClientAuth = true
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit {
                repeat(2) {
                    try {
                        (ss.accept() as SSLSocket).use { s -> s.soTimeout = 5000; s.startHandshake(); s.outputStream.write(1); s.outputStream.flush() }
                    } catch (e: Exception) {
                        // el segundo intento (clave sin fijar) falla en el cliente: aquí no importa
                    }
                }
            }
            val serverPin = LeapTls.spkiPin(server.leaf().publicKey)
            val f = LeapTls.socketFactory(client, LeapTls.PinTrust(listOf(serverPin)))
            (f.createSocket(ss.inetAddress, ss.localPort) as SSLSocket).use { s ->
                s.soTimeout = 5000
                s.startHandshake()
                assertEquals(1, s.inputStream.read())
            }
            assertEquals("el servidor recibió el certificado de cliente", client.leaf(), seen.get())
            // Sin el pin del servidor: el handshake falla y debajo está la clave desconocida (la que se le enseña al usuario).
            val bad = LeapTls.socketFactory(client, LeapTls.PinTrust(emptyList()))
            try {
                (bad.createSocket(ss.inetAddress, ss.localPort) as SSLSocket).use { s ->
                    s.soTimeout = 5000
                    s.startHandshake()
                }
                fail("debía fallar")
            } catch (e: SSLHandshakeException) {
                val k = LeapHttps.unknownKey(e)
                assertNotNull(k)
                assertEquals(serverPin, k!!.pin)
            }
        } finally {
            ss.close()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
