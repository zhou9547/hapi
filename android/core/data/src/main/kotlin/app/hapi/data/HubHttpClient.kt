package app.hapi.data

import android.annotation.SuppressLint
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

fun hubHttpClientBuilder(
    allowUntrustedHttps: Boolean = BuildConfig.ALLOW_UNTRUSTED_HTTPS,
): OkHttpClient.Builder = OkHttpClient.Builder().apply {
    if (allowUntrustedHttps) {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(UntrustedHubTrustManager), null)
        }
        sslSocketFactory(sslContext.socketFactory, UntrustedHubTrustManager)
    }
}

@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
private object UntrustedHubTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
