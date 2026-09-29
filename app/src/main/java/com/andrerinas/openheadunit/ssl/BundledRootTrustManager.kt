package com.andrerinas.openheadunit.ssl

import android.content.Context
import com.andrerinas.openheadunit.R
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The system trust store first, then two bundled roots. Below API 21 the platform store predates
 * USERTrust ECC, the root GitHub's chain ends at, so the update check could never validate it.
 */
class BundledRootTrustManager(roots: List<X509Certificate>) : X509TrustManager {

    private val system: X509TrustManager = trustManagerFor(null)
    private val bundled: X509TrustManager = trustManagerFor(roots)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            bundled.checkServerTrusted(chain, authType)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        system.acceptedIssuers + bundled.acceptedIssuers

    companion object {
        val ROOT_RESOURCES = intArrayOf(R.raw.usertrust_ecc_root, R.raw.usertrust_rsa_root)

        fun create(context: Context): BundledRootTrustManager =
            BundledRootTrustManager(ROOT_RESOURCES.map { id ->
                context.resources.openRawResource(id).use { parse(it) }
            })

        fun parse(pem: InputStream): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(pem) as X509Certificate

        private fun trustManagerFor(roots: List<X509Certificate>?): X509TrustManager {
            val store = roots?.let {
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    it.forEachIndexed { i, cert -> setCertificateEntry("bundled-root-$i", cert) }
                }
            }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
