package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Random

/**
 * Firma y contraseñas del protocolo de Leapmotor portados de LMB10 (leapmotor_engine.dart). LMB10 no trae pruebas ni
 * vectores: los de aquí salen de pasar las mismas entradas por un port literal en Python del código Dart, y el núcleo
 * SM4 y el HKDF se comprueban además con sus vectores oficiales (SM4 de GB/T 32907 y el caso 1 del RFC 5869).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class LeapCryptoTest {
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Programa de claves estándar de SM4 (solo para probar la función de ronda con el vector oficial). */
    private fun sm4KeySchedule(key: ByteArray): IntArray {
        val fk = intArrayOf(0xA3B1BAC6.toInt(), 0x56AA3350, 0x677D9197, 0xB27022DC.toInt())
        val ck = IntArray(32) { i ->
            var v = 0
            for (j in 0 until 4) v = (v shl 8) or (((4 * i + j) * 7) and 0xFF)
            v
        }
        val k = IntArray(4) { i ->
            (((key[4 * i].toInt() and 0xFF) shl 24) or ((key[4 * i + 1].toInt() and 0xFF) shl 16) or
                ((key[4 * i + 2].toInt() and 0xFF) shl 8) or (key[4 * i + 3].toInt() and 0xFF)) xor fk[i]
        }
        val rk = IntArray(32)
        for (i in 0 until 32) {
            val b = LeapCrypto.tau(k[1] xor k[2] xor k[3] xor ck[i])
            val nk = k[0] xor b xor Integer.rotateLeft(b, 13) xor Integer.rotateLeft(b, 23)
            rk[i] = nk
            k[0] = k[1]; k[1] = k[2]; k[2] = k[3]; k[3] = nk
        }
        return rk
    }

    @Test
    fun sm4RoundFunctionMatchesTheOfficialVector() {
        val key = unhex("0123456789abcdeffedcba9876543210")
        val out = LeapCrypto.sm4Block(key, 0, sm4KeySchedule(key))
        assertEquals("681edf34d206965e86b3e94f536e4246", LeapCrypto.hex(out))
    }

    @Test
    fun sm4WithTheFixedProtocolRoundKeys() {
        assertEquals("6b9850ab3b21514a0626c2b8dced7d60", LeapCrypto.hex(LeapCrypto.sm4Block(ByteArray(16), 0, LeapCrypto.P12_ROUND_KEYS)))
        val seq = ByteArray(16) { it.toByte() }
        assertEquals("e892c46f5c32dd67a492796c91abbab1", LeapCrypto.hex(LeapCrypto.sm4Block(seq, 0, LeapCrypto.P12_ROUND_KEYS)))
        // Relleno PKCS#7 de un bloque entero: 32 bytes → 48 cifrados.
        assertEquals(48, LeapCrypto.p12MemoryEncode(ByteArray(32)).size)
        assertEquals(16, LeapCrypto.p12MemoryEncode(ByteArray(5)).size)
    }

    @Test
    fun accountP12PasswordVectors() {
        // Vectores del port literal del Dart (deriveAccountP12Password) con datos inventados.
        assertEquals("wzxG9OqocoRBIhG", LeapCrypto.accountP12Password("1234567", "u-ABCDEFGH-0001"))
        assertEquals("NFXT4dSJceNBR7I", LeapCrypto.accountP12Password("900001", "abcdef0123456789"))
        assertEquals(15, LeapCrypto.accountP12Password("1", "").length)
    }

    @Test
    fun hkdfMatchesRfc5869ForOneBlock() {
        // RFC 5869, caso 1: los 32 primeros bytes del OKM son T(1), igual en el HKDF de LMB10.
        val okm = LeapCrypto.hkdfSha256(unhex("0b".repeat(22)), unhex("000102030405060708090a0b0c"), unhex("f0f1f2f3f4f5f6f7f8f9"), 32)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", LeapCrypto.hex(okm))
        assertEquals("c9e6400d5e8b83fa2154e7887eb96e96f290abd394f0dbad2670faf4263bfb14",
            LeapCrypto.hex(LeapCrypto.signKey("ikm-demo", "salt-demo", "info-demo")))
        // Sal vacía: 32 ceros (como el Dart).
        assertEquals("7c4f962b2d91492f58c8b88ce44bd1ee0be69a15b4932e6fd1156d7dc9dc3214",
            LeapCrypto.hex(LeapCrypto.signKey("ikm-demo", "", "info-demo")))
    }

    private fun baseFields() = linkedMapOf(
        "version" to "1.12.3", "acceptLanguage" to "en-GB", "nonce" to "4242424", "channel" to "1",
        "deviceId" to "0123456789abcdef0123456789abcdef", "timestamp" to "1791297600000", "deviceType" to "1", "source" to "leapmotor")

    @Test
    fun signedHeadersSortTheFieldsByName() {
        val key = LeapCrypto.signKey("ikm-demo", "salt-demo", "info-demo")
        val f = baseFields()
        assertEquals("en-GB10123456789abcdef0123456789abcdef14242424leapmotor17912976000001.12.3", LeapCrypto.signInput(f))
        assertEquals("03c6406037a9e7124acde4cc3312938c02a2df55f572faacd79c34d447da5c0a", LeapCrypto.sign(key, f))
        // Con el VIN (estado): «version» < «vin».
        val withVin = baseFields().apply { put("vin", "TESTVIN0000000001") }
        assertEquals("f421e282dc4cc1c0d0430b4537f1a69b22270fea576c5a56e29c250d61340ee6", LeapCrypto.sign(key, withVin))
        // Con el refreshToken (refresco): va entre «nonce» y «source».
        val refresh = baseFields().apply { put("refreshToken", "rt-demo") }
        assertTrue(LeapCrypto.signInput(refresh).contains("4242424rt-demoleapmotor"))
        assertEquals("b26651425381ea825a2c07cc4296c07e6931ee64b3feb8f13987018086a2c69b", LeapCrypto.sign(key, refresh))
    }

    @Test
    fun loginSignature() {
        assertEquals("4e30bcfb934132b4e8544d511a065b5cdc27857ba9daec5d934265c042ac8435",
            LeapCrypto.loginSign("0123456789abcdef0123456789abcdef", "demo@example.com", "4242424", "p@ss w0rd", "1791297600000"))
    }

    @Test
    fun deviceIdComesFromTheToken() {
        val token = "xx.eyJ1c2VyX25hbWUiOiAiYSxiLGRldi1mcm9tLXRva2VuLGQifQ.yy"
        assertEquals("dev-from-token", LeapCrypto.sessionDeviceId(token, "fallback"))
        assertEquals("fallback", LeapCrypto.sessionDeviceId("not-a-jwt", "fallback"))
        assertEquals("fallback", LeapCrypto.sessionDeviceId("", "fallback"))
        assertEquals("fallback", LeapCrypto.sessionDeviceId(null, "fallback"))
        // user_name con menos de 4 campos o el tercero vacío: el de reserva.
        val few = "a." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"user_name":"a,b"}""".toByteArray()) + ".b"
        assertEquals("fallback", LeapCrypto.sessionDeviceId(few, "fallback"))
        val empty = "a." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"user_name":"a,b,,d"}""".toByteArray()) + ".b"
        assertEquals("fallback", LeapCrypto.sessionDeviceId(empty, "fallback"))
    }

    @Test
    fun encodeComponentLikeDart() {
        assertEquals("a%20b%2Bc%40d.e%2F~*'()!-_", LeapCrypto.encodeComponent("a b+c@d.e/~*'()!-_"))
        assertEquals("%C3%B1%26%3D", LeapCrypto.encodeComponent("ñ&="))
    }

    @Test
    fun nonceAndDeviceIdShapes() {
        val r = Random(7)
        repeat(1000) {
            val n = LeapCrypto.nonce(r).toInt()
            assertTrue(n in 100000..9999999)
        }
        val id = LeapCrypto.newDeviceId(java.security.SecureRandom())
        assertTrue(id.matches(Regex("[0-9a-f]{32}")))
    }
}
