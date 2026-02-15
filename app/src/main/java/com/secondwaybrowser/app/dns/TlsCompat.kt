package com.secondwaybrowser.app.dns

import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS 1.2 ve 1.3 zorunlu; bazı cihazlarda varsayılan TLS 1.0 ile ERR_SSL_VERSION_OR_CIPHER_MISMATCH önlenir.
 */
object TlsCompat {

    private val TLS_12_13 = arrayOf("TLSv1.2", "TLSv1.3")

    fun createSocketFactory(): Pair<SSLSocketFactory, X509TrustManager> {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }
        val trustManager = trustManagerFactory.trustManagers.first { it is X509TrustManager } as X509TrustManager
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustManagerFactory.trustManagers, SecureRandom())
        }
        val delegate = sslContext.socketFactory
        val factory = object : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
            override fun createSocket(s: java.net.Socket?, host: String?, port: Int, autoClose: Boolean) =
                wrap(delegate.createSocket(s, host, port, autoClose) as SSLSocket)
            override fun createSocket(host: String?, port: Int) = wrap(delegate.createSocket(host, port) as SSLSocket)
            override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int) =
                wrap(delegate.createSocket(host, port, localHost, localPort) as SSLSocket)
            override fun createSocket(host: java.net.InetAddress?, port: Int) =
                wrap(delegate.createSocket(host, port) as SSLSocket)
            override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int) =
                wrap(delegate.createSocket(address, port, localAddress, localPort) as SSLSocket)
            private fun wrap(socket: SSLSocket): SSLSocket {
                val allowed = socket.supportedProtocols.filter { it in TLS_12_13 }.toTypedArray()
                if (allowed.isNotEmpty()) socket.enabledProtocols = allowed
                return socket
            }
        }
        return factory to trustManager
    }
}
