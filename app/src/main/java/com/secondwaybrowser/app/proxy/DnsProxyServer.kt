package com.secondwaybrowser.app.proxy

import android.util.Log
import com.secondwaybrowser.app.dns.CloudflareFamilyDns
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Minimal local HTTP proxy that resolves all hostnames via Cloudflare Family DoH.
 * Used with ProxyController so WebView traffic (GET, POST, CONNECT/HTTPS) uses our DNS.
 * Runs on 127.0.0.1; one thread per connection.
 */
class DnsProxyServer {
    companion object {
        private const val TAG = "SafeBrowserProxy"
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile
    private var executor: ExecutorService = Executors.newCachedThreadPool()
    private val dns = CloudflareFamilyDns(CloudflareFamilyDns.createDohClient())

    @Volatile
    private var port: Int = -1

    fun start(): Int {
        if (serverSocket != null && acceptThread?.isAlive == true) return port
        synchronized(this) {
            if (serverSocket != null && acceptThread?.isAlive == true) return port
            if (executor.isShutdown || executor.isTerminated) {
                executor = Executors.newCachedThreadPool()
            }
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", 0))
            port = ss.localPort
            serverSocket = ss
            acceptThread = Thread {
                try {
                    while (ss.isBound && !ss.isClosed) {
                        val client = ss.accept()
                        executor.execute { handleConnection(client) }
                    }
                } catch (_: IOException) { }
                catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
        }
        return port
    }

    fun stop() {
        synchronized(this) {
            try {
                serverSocket?.close()
            } catch (_: Exception) { }
            serverSocket = null
            acceptThread = null
            port = -1
            executor.shutdownNow()
        }
    }

    fun getPort(): Int = port

    private fun handleConnection(client: Socket) {
        try {
            client.soTimeout = 0
            val firstLine = readLine(client)
            if (firstLine.isNullOrBlank()) {
                client.close()
                return
            }
            val parts = firstLine.split(" ")
            if (parts.size < 3) {
                client.close()
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]
            val version = parts[2]
            Log.d(TAG, "req $method $target $version")

            if (method == "CONNECT") {
                handleConnect(client, firstLine, target, version)
            } else {
                handleHttp(client, firstLine, method, target, version)
            }
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) { }
        }
    }

    private fun handleConnect(client: Socket, _firstLine: String, target: String, _version: String) {
        // CONNECT isteğinden sonra gelen HTTP başlıklarını tüket; yoksa pipe TLS yerine bu başlıkları sunucuya gönderir → ERR_SSL_PROTOCOL_ERROR
        readHeadersAndBody(client)
        val hostPort = target.split(":")
        val host = hostPort.getOrNull(0) ?: return
        val port = (hostPort.getOrNull(1) ?: "443").toIntOrNull() ?: 443
        val remote = connectTo(host, port) ?: run {
            replyBadGateway(client)
            return
        }
        try {
            val out = client.getOutputStream()
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            pipe(client, remote)
        } finally {
            try { remote.close() } catch (_: Exception) { }
        }
    }

    private fun handleHttp(client: Socket, _firstLine: String, method: String, target: String, version: String) {
        val (headers, bodyBytes) = readHeadersAndBody(client)
        val hostHeaderValue = headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        var host: String? = null
        var port = 80
        var targetUri: java.net.URI? = null
        if (!hostHeaderValue.isNullOrBlank()) {
            val colonIdx = hostHeaderValue.indexOf(':')
            host = if (colonIdx >= 0) hostHeaderValue.substring(0, colonIdx).trim() else hostHeaderValue.trim()
            port = if (colonIdx >= 0) hostHeaderValue.substring(colonIdx + 1).trim().toIntOrNull() ?: 80 else 80
        } else if (target.contains("://")) {
            try {
                targetUri = java.net.URI.create(target)
                host = targetUri.host
                port = if (targetUri.port != -1) targetUri.port else if (targetUri.scheme.equals("https", ignoreCase = true)) 443 else 80
            } catch (e: Exception) {
                Log.w(TAG, "bad target uri: $target msg=${e.message}")
            }
        }
        if (host.isNullOrBlank()) {
            Log.w(TAG, "missing host header for target=$target")
            replyBadGateway(client)
            return
        }
        val remote = connectTo(host, port) ?: run {
            Log.w(TAG, "connect failed host=$host port=$port")
            replyBadGateway(client)
            return
        }
        try {
            val path = if (target.contains("://")) {
                val u = targetUri ?: java.net.URI.create(target)
                (u.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
            } else target
            val newFirstLine = "$method $path $version"
            val headerList = if (hostHeaderValue.isNullOrBlank()) {
                listOf("Host: $host") + headers
            } else headers
            val request = buildRequest(newFirstLine, headerList, bodyBytes)
            remote.getOutputStream().use { out ->
                out.write(request)
                out.flush()
            }
            remote.getInputStream().use { `in` ->
                client.getOutputStream().use { out ->
                    `in`.copyTo(out)
                    out.flush()
                }
            }
        } finally {
            try { remote.close() } catch (_: Exception) { }
        }
    }

    private fun buildRequest(firstLine: String, headers: List<String>, body: ByteArray): ByteArray {
        val sb = StringBuilder()
        sb.append(firstLine).append("\r\n")
        for (h in headers) {
            if (!h.startsWith("Proxy-", ignoreCase = true)) sb.append(h).append("\r\n")
        }
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.US_ASCII) + body
    }

    private fun readHeadersAndBody(client: Socket): Pair<List<String>, ByteArray> {
        val headers = mutableListOf<String>()
        var line: String?
        var contentLength = -1
        while (readLine(client).also { line = it } != null && line != "") {
            headers.add(line!!)
            if (line!!.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line!!.substringAfter(":", "").trim().toIntOrNull() ?: -1
            }
        }
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var off = 0
            val `in` = client.getInputStream()
            while (off < contentLength) {
                val n = `in`.read(buf, off, contentLength - off)
                if (n <= 0) break
                off += n
            }
            buf
        } else ByteArray(0)
        return headers to body
    }

    private fun readLine(socket: Socket): String? {
        val `in` = socket.getInputStream()
        val sb = StringBuilder()
        while (true) {
            val b = `in`.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun connectTo(host: String, port: Int): Socket? {
        return try {
            val addrs = dns.lookup(host)
            if (addrs.isEmpty()) return null
            val ordered = addrs.sortedBy { addr ->
                if (addr is java.net.Inet4Address) 0 else 1
            }
            for (addr in ordered) {
                try {
                    return Socket().apply {
                        connect(InetSocketAddress(addr, port), 8_000)
                        soTimeout = 30_000
                    }
                } catch (_: Exception) {
                    // try next address
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun replyBadGateway(client: Socket) {
        try {
            val out = BufferedOutputStream(client.getOutputStream())
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        } catch (_: Exception) { }
        try { client.close() } catch (_: Exception) { }
    }

    private fun pipe(a: Socket, b: Socket) {
        val t1 = Thread {
            try {
                a.getInputStream().use { aIn -> b.getOutputStream().use { bOut -> aIn.copyTo(bOut) } }
            } catch (_: Exception) { }
            try { a.close(); b.close() } catch (_: Exception) { }
        }
        val t2 = Thread {
            try {
                b.getInputStream().use { bIn -> a.getOutputStream().use { aOut -> bIn.copyTo(aOut) } }
            } catch (_: Exception) { }
            try { a.close(); b.close() } catch (_: Exception) { }
        }
        t1.isDaemon = true
        t2.isDaemon = true
        t1.start()
        t2.start()
        t1.join(60_000)
        t2.join(60_000)
    }
}
