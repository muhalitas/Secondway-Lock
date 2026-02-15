package com.secondwaybrowser.app.dns

import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

/**
 * Resolves hostnames via Cloudflare Family DNS over HTTPS (DoH).
 * Uygulama genelinde tek önbellek: proxy ve OkHttp aynı host için tek DoH yapar (Chrome’a göre gecikmeyi azaltır).
 */
class CloudflareFamilyDns(
    private val dohClient: OkHttpClient
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isBlank()) throw UnknownHostException("hostname is blank")
        sharedCache[hostname]?.let { return it }
        synchronized(sharedCache) {
            sharedCache[hostname]?.let { return it }
            val list = queryDoH(hostname)
            if (list.isEmpty()) throw UnknownHostException("$hostname: no A/AAAA records")
            sharedCache[hostname] = list
            return list
        }
    }

    private fun queryDoH(hostname: String): List<InetAddress> {
        val enc = java.net.URLEncoder.encode(hostname, "UTF-8")
        val latch = CountDownLatch(2)
        val aResult = java.util.concurrent.atomic.AtomicReference<List<InetAddress>>(emptyList())
        val aaaaResult = java.util.concurrent.atomic.AtomicReference<List<InetAddress>>(emptyList())
        sharedExecutor.execute { aResult.set(queryDoHType(hostname, enc, "A", dohClient)); latch.countDown() }
        sharedExecutor.execute { aaaaResult.set(queryDoHType(hostname, enc, "AAAA", dohClient)); latch.countDown() }
        val start = System.nanoTime()
        val primaryDeadline = start + TimeUnit.MILLISECONDS.toNanos(DOH_PRIMARY_WAIT_MS)
        while (System.nanoTime() < primaryDeadline && (aResult.get().isEmpty() && aaaaResult.get().isEmpty())) {
            latch.await(100, TimeUnit.MILLISECONDS)
        }
        val combinedFast = aResult.get() + aaaaResult.get()
        if (combinedFast.isNotEmpty()) return combinedFast
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        val remainingMs = (DOH_MAX_WAIT_MS - elapsedMs).coerceAtLeast(0)
        if (remainingMs > 0) {
            latch.await(remainingMs, TimeUnit.MILLISECONDS)
        }
        return aResult.get() + aaaaResult.get()
    }

    private fun queryDoHType(@Suppress("UNUSED_PARAMETER") hostname: String, encoded: String, type: String, client: OkHttpClient): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        try {
            val url = "https://$DOH_HOST/dns-query?name=$encoded&type=$type"
            val request = Request.Builder().url(url).addHeader("Accept", "application/dns-json").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return out
                val bodyStr = response.body?.string() ?: return out
                val json = JSONObject(bodyStr)
                val answer = json.optJSONArray("Answer") ?: return out
                for (i in 0 until answer.length()) {
                    val ob = answer.optJSONObject(i) ?: continue
                    val data = (ob.optString("data", "") ?: "").trim()
                    if (data.isNotEmpty()) {
                        try {
                            out.add(InetAddress.getByName(data))
                        } catch (_: Exception) { }
                    }
                }
            }
        } catch (_: Exception) { }
        return out
    }

    companion object {
        /** Proxy ve OkHttp aynı önbelleği kullanır; aynı host için tek DoH. */
        private val sharedCache = ConcurrentHashMap<String, List<InetAddress>>()
        /** Tek thread pool; her lookup'ta yeni pool açıp kapatmak gecikme ekliyordu. */
        private val sharedExecutor = Executors.newFixedThreadPool(4)

        private const val DOH_HOST = "family.cloudflare-dns.com"
        private const val DOH_PRIMARY_WAIT_MS = 1200L
        private const val DOH_MAX_WAIT_MS = 6000L

        /** Bootstrap: DoH sunucusu için family.cloudflare-dns.com -> 1.1.1.3 / 1.0.0.3 */
        fun bootstrapDns(hostname: String): List<InetAddress> {
            if (hostname.equals(DOH_HOST, ignoreCase = true)) {
                return listOf(
                    InetAddress.getByName("1.1.1.3"),
                    InetAddress.getByName("1.0.0.3")
                )
            }
            return Dns.SYSTEM.lookup(hostname)
        }

        private val tlsSpecs = listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS)
        private val webSpecs = listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT)

        private fun tlsSocketFactoryAndTrustManager(): Pair<javax.net.ssl.SSLSocketFactory, X509TrustManager> =
            TlsCompat.createSocketFactory()

        fun createDohClient(): OkHttpClient {
            val (sslSF, trustMgr) = tlsSocketFactoryAndTrustManager()
            return OkHttpClient.Builder()
                .connectionSpecs(tlsSpecs)
                .sslSocketFactory(sslSF, trustMgr)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = bootstrapDns(hostname)
                })
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        fun createClientWithFamilyDns(cacheDir: File? = null, cacheSizeBytes: Long = 20L * 1024 * 1024): OkHttpClient {
            val dohClient = createDohClient()
            val (sslSF, trustMgr) = tlsSocketFactoryAndTrustManager()
            val builder = OkHttpClient.Builder()
                .connectionSpecs(webSpecs)
                .sslSocketFactory(sslSF, trustMgr)
                .dns(CloudflareFamilyDns(dohClient))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
            if (cacheDir != null) {
                builder.cache(Cache(cacheDir, cacheSizeBytes))
            }
            return builder.build()
        }

        /** Önbelleği ısıtır; CONNECT gelmeden önce çağrılırsa ilk yükleme hızlanır. */
        private val prefetchDns by lazy { CloudflareFamilyDns(createDohClient()) }
        fun prefetchHost(host: String?) {
            if (host.isNullOrBlank()) return
            if (sharedCache.containsKey(host)) return
            sharedExecutor.execute {
                try { prefetchDns.lookup(host) } catch (_: Exception) { }
            }
        }
    }
}
