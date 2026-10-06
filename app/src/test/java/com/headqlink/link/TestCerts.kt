package com.headqlink.link

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Claves y certificados de usar y tirar para las pruebas, generados al vuelo (nunca se guarda ninguno en el
 * repositorio): pares RSA y EC, un certificado X.509 autofirmado hecho a mano en DER, PEM y PKCS#12.
 */
object TestCerts {
    val rsa: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair() }
    val rsa2: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair() }
    val ec: KeyPair by lazy { KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.genKeyPair() }

    // ------------------------------------------------------------------ DER

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tag)
        val n = content.size
        when {
            n < 0x80 -> o.write(n)
            n < 0x100 -> { o.write(0x81); o.write(n) }
            n < 0x10000 -> { o.write(0x82); o.write(n shr 8); o.write(n and 0xFF) }
            else -> { o.write(0x83); o.write(n shr 16); o.write((n shr 8) and 0xFF); o.write(n and 0xFF) }
        }
        o.write(content)
        return o.toByteArray()
    }

    fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val o = ByteArrayOutputStream()
        o.write((arcs[0] * 40 + arcs[1]).toInt())
        for (a in arcs.drop(2)) {
            val stack = ArrayList<Int>()
            var v = a
            stack.add((v and 0x7F).toInt())
            v = v shr 7
            while (v > 0) {
                stack.add(((v and 0x7F) or 0x80).toInt())
                v = v shr 7
            }
            for (b in stack.reversed()) o.write(b)
        }
        return tlv(0x06, o.toByteArray())
    }

    private fun utc(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(0x17, f.format(d).toByteArray(Charsets.US_ASCII))
    }

    /** Certificado autofirmado (v3 mínimo) para el par dado. */
    fun selfSigned(kp: KeyPair, cn: String, days: Int = 365): X509Certificate {
        val isEc = kp.private.algorithm == "EC"
        val sigAlg = if (isEc) seq(oid("1.2.840.10045.4.3.2")) else seq(oid("1.2.840.113549.1.1.11"), byteArrayOf(0x05, 0x00))
        val name = seq(set(seq(oid("2.5.4.3"), tlv(0x0C, cn.toByteArray(Charsets.UTF_8)))))
        val now = System.currentTimeMillis()
        val validity = seq(utc(Date(now - 86_400_000L)), utc(Date(now + days * 86_400_000L)))
        val serial = tlv(0x02, BigInteger.valueOf(now).toByteArray())
        val tbs = seq(tlv(0xA0, tlv(0x02, byteArrayOf(2))), serial, sigAlg, name, validity, name, kp.public.encoded)
        val s = Signature.getInstance(if (isEc) "SHA256withECDSA" else "SHA256withRSA")
        s.initSign(kp.private)
        s.update(tbs)
        val sig = s.sign()
        val der = seq(tbs, sigAlg, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    // ------------------------------------------------------------------ PEM y PKCS#12

    fun pem(type: String, der: ByteArray): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $type-----\n$b64\n-----END $type-----\n"
    }

    fun certPem(c: X509Certificate) = pem("CERTIFICATE", c.encoded)
    fun pkcs8Pem(k: PrivateKey) = pem("PRIVATE KEY", k.encoded)

    /** El contenido de un PrivateKeyInfo (PKCS#8): la clave PKCS#1 (RSA) o SEC1 (EC) de dentro. */
    fun innerKey(k: PrivateKey): ByteArray {
        val der = k.encoded
        val top = LeapTls.Der(der, 0)
        val version = LeapTls.Der(der, top.start)
        val alg = LeapTls.Der(der, version.end)
        val octets = LeapTls.Der(der, alg.end)
        return der.copyOfRange(octets.start, octets.end)
    }

    /** «BEGIN EC PRIVATE KEY» como el de OpenSSL: SEC1 con la curva en [0] (la de Java la lleva fuera, en PKCS#8). */
    fun sec1WithCurve(k: PrivateKey): ByteArray {
        val inner = innerKey(k)
        val top = LeapTls.Der(inner, 0)
        val version = LeapTls.Der(inner, top.start)
        val priv = LeapTls.Der(inner, version.end)
        return seq(inner.copyOfRange(version.headerStart, version.end), inner.copyOfRange(priv.headerStart, priv.end),
            tlv(0xA0, oid("1.2.840.10045.3.1.7")))
    }

    fun pkcs12(k: PrivateKey, chain: Array<X509Certificate>, password: CharArray): ByteArray {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("test", k, password, chain)
        val o = ByteArrayOutputStream()
        ks.store(o, password)
        return o.toByteArray()
    }
}
