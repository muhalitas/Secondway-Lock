package com.secondwaybrowser.app.proxy

import android.os.Handler
import android.os.Looper
import androidx.webkit.WebViewFeature
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import java.util.concurrent.Executors

/**
 * Starts local DNS proxy (Cloudflare Family DoH) and sets WebView proxy override
 * so all requests (GET, POST, CONNECT) use our DNS. No VPN.
 */
object WebViewProxyManager {

    private val proxyServer = DnsProxyServer()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var proxyPort: Int = -1
    @Volatile
    private var proxyReady: Boolean = false

    fun isProxyOverrideSupported(): Boolean {
        return try {
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Start proxy and set WebView proxy override. Call before any WebView loads a URL.
     * @param onReady called on main thread when proxy is set and safe to load pages
     */
    fun ensureProxyAndOverride(onReady: Runnable) {
        executor.execute {
            val port = proxyServer.start()
            if (port <= 0) {
                proxyReady = false
                mainHandler.post(onReady)
                return@execute
            }
            proxyPort = port
            if (!isProxyOverrideSupported()) {
                proxyReady = false
                mainHandler.post(onReady)
                return@execute
            }
            try {
                val config = ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:$port")
                    .build()
                ProxyController.getInstance().setProxyOverride(
                    config,
                    executor,
                    Runnable {
                        proxyReady = true
                        mainHandler.post(onReady)
                    }
                )
            } catch (_: Exception) {
                proxyReady = false
                mainHandler.post(onReady)
            }
        }
    }

    fun clearOverrideAndStop(onCleared: Runnable? = null) {
        executor.execute {
            fun doStop() {
                proxyServer.stop()
                proxyPort = -1
                proxyReady = false
                mainHandler.post { onCleared?.run() ?: Unit }
            }
            if (isProxyOverrideSupported()) {
                try {
                    ProxyController.getInstance().clearProxyOverride(executor, Runnable { doStop() })
                } catch (_: Exception) {
                    doStop()
                }
            } else {
                doStop()
            }
        }
    }

    fun isProxyReady(): Boolean = proxyReady
}
