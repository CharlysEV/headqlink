package com.andrerinas.openheadunit.ssl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate

class BundledRootTrustManagerTest {

    private fun load(name: String): X509Certificate =
        File("src/main/res/raw/$name.pem").inputStream().use { BundledRootTrustManager.parse(it) }

    private fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    @Test
    fun eccRootIsUserTrustEccPinnedByFingerprint() {
        val cert = load("usertrust_ecc_root")
        assertEquals(
            "4F:F4:60:D5:4B:9C:86:DA:BF:BC:FC:57:12:E0:40:0D:2B:ED:3F:BC:4D:4F:BD:AA:86:E0:6A:DC:D2:A9:AD:7A",
            sha256(cert)
        )
        assertTrue(cert.subjectX500Principal.name.contains("USERTrust ECC Certification Authority"))
    }

    @Test
    fun rsaRootIsUserTrustRsaPinnedByFingerprint() {
        val cert = load("usertrust_rsa_root")
        assertEquals(
            "E7:93:C9:B0:2F:D8:AA:13:E2:1C:31:22:8A:CC:B0:81:19:64:3B:74:9C:89:89:64:B1:74:6D:46:C3:D4:CB:D2",
            sha256(cert)
        )
        assertTrue(cert.subjectX500Principal.name.contains("USERTrust RSA Certification Authority"))
    }

    @Test
    fun bothRootsAreSelfSignedCertificateAuthorities() {
        for (cert in listOf(load("usertrust_ecc_root"), load("usertrust_rsa_root"))) {
            assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
            cert.verify(cert.publicKey)
            assertTrue(cert.basicConstraints >= 0)
        }
    }

    @Test
    fun acceptedIssuersCarryTheBundledRootsBesideTheSystemOnes() {
        val roots = listOf(load("usertrust_ecc_root"), load("usertrust_rsa_root"))
        val issuers = BundledRootTrustManager(roots).acceptedIssuers.toList()
        assertTrue(issuers.containsAll(roots))
    }
}
