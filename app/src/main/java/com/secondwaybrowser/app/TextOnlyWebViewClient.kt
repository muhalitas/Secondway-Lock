package com.secondwaybrowser.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.secondwaybrowser.app.dns.CloudflareFamilyDns
import com.secondwaybrowser.app.proxy.WebViewProxyManager
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Varsayılan: Sadece metin (resim, video, ses blok).
 * Allowlist'teki host'larda tam yükleme.
 * Tüm istekler (GET) Cloudflare Family DNS ile yapılır; VPN yok, cihaz DNS/VPN ayarı devre dışı.
 */
@SuppressLint("SetJavaScriptEnabled")
class TextOnlyWebViewClient(
    private val context: Context,
    private val onUrlChanged: ((String) -> Unit)? = null
) : WebViewClient() {

    companion object {
        private const val PERF_TAG = "SafeBrowserPerf"
        private const val EMPTY_RESPONSE_TAG = "SafeBrowserEmpty"
        private const val EMPTY_RETRY_COOLDOWN_MS = 8_000L
    }

    private val cloudflareClient by lazy {
        val cacheDir = File(context.cacheDir, "cf_http_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        CloudflareFamilyDns.createClientWithFamilyDns(cacheDir = cacheDir)
    }

    /** Blocked media placeholder SVG (pattern). */
    private val blockedMediaSvg: ByteArray by lazy {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="200" height="150" viewBox="0 0 200 150" preserveAspectRatio="none">
              <defs>
                <pattern id="p" width="24" height="24" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
                  <rect width="24" height="24" fill="#fafbfa"/>
                  <line x1="0" y1="0" x2="0" y2="24" stroke="#f1f3f2" stroke-width="3"/>
                </pattern>
              </defs>
              <rect width="100%" height="100%" fill="url(#p)"/>
            </svg>
        """.trimIndent()
        svg.toByteArray(Charsets.UTF_8)
    }

    /** Doğrulama / captcha servisleri: bu host'lardan veya URL kalıplarından gelen istekler engellenmez. */
    private val verificationHosts = setOf(
        "recaptcha.net", "www.recaptcha.net", "api2.recaptcha.net",
        "challenges.cloudflare.com", "captcha-api.cloudflare.com", "turnstile.cloudflare.com",
        "hcaptcha.com", "newassets.hcaptcha.com", "js.hcaptcha.com", "api.hcaptcha.com",
        "assets.hcaptcha.com", "imgs.hcaptcha.com",
        "recaptcha.google.com", "www.recaptcha.google.com",
        "datadome.co", "api-js.datadome.co", "captcha-delivery.com", "geo.captcha-delivery.com", "t.captcha-delivery.com",
        "perimeterx.net", "px-cdn.net", "px-cdn.perimeterx.net",
        "arkoselabs.com", "client-api.arkoselabs.com", "iframe.arkoselabs.com", "api.arkoselabs.com", "funcaptcha.com",
        "geetest.com", "api.geetest.com", "static.geetest.com",
        "botdetect.com",
        "kasada.io", "kasada.com",
        "imperva.com", "incapsula.com",
        "sucuri.net", "sucuri.com",
        "distilnetworks.com", "distil.io",
        "shapesecurity.com", "shape.security", "f5.com",
        "akamai.com", "akamaihd.net", "akamaized.net",
        "radware.com"
    )
    private val verificationUrlIndicators = setOf(
        "/recaptcha/", "recaptcha/api", "g-recaptcha", "grecaptcha", "g-recaptcha-response",
        "hcaptcha", "turnstile",
        "cf-chl", "cf_chl", "__cf_chl", "__cf_bm", "cf_bm", "cf-bm",
        "/cdn-cgi/challenge-platform/", "/cdn-cgi/challenge", "/cdn-cgi/turnstile",
        "/cdn-cgi/l/chk_captcha", "/cdn-cgi/bm/",
        "/captcha", "captcha=", "captcha/", "captcha-", "captcha?", "botcheck", "bot-check", "browser-check", "browsercheck",
        "/challenge", "challenge=", "challenge/", "challenge-platform",
        "arkoselabs", "funcaptcha",
        "perimeterx", "pxcaptcha", "px-captcha", "px_challenge",
        "datadome", "captcha-delivery",
        "geetest",
        "botdetect", "distil", "distil_r_captcha",
        "kpsdk", "kasada",
        "incapsula", "_incapsula_resource",
        "sucuri",
        "awswaf", "aws-waf", "waf-captcha", "wafcaptcha",
        "/akamai/", "/akamai/bm", "/_bm", "akamai", "bm-verify", "bm_verify", "ak_bmsc", "abck", "sensor_data"
    )
    private fun isVerificationRequest(host: String, url: String): Boolean {
        val hostLower = host.lowercase()
        val urlLower = url.lowercase()
        if (verificationHosts.any { h -> hostLower == h || hostLower.endsWith(".$h") }) return true
        if (verificationUrlIndicators.any { urlLower.contains(it) }) return true
        if ((hostLower.contains("google") || hostLower.contains("gstatic.com") || hostLower.contains("googleusercontent.com")) &&
            (urlLower.contains("recaptcha") || urlLower.contains("captcha"))
        ) return true
        return false
    }
    private fun isVerificationUrl(url: String?): Boolean {
        val safeUrl = url ?: return false
        val host = AllowlistHelper.hostFromUrl(safeUrl) ?: return false
        return isVerificationRequest(host, safeUrl)
    }

    /** Reklam ağı host veya URL kalıpları: eşleşen istekler bloklanır. */
    private val adHostPatterns = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google", "pagead2.", "tpc.googlesyndication",
        "adnxs.com", "adsystem.com", "scorecardresearch", "moatads",
        "facebook.com/tr", "connect.facebook.net/signals",
        "ads.", "ad.", "banner.", "adv."
    )
    private val adUrlPatterns = setOf(
        "/ads/", "/ad/", "/banner/", "/adv/", "doubleclick", "pagead",
        "googlesyndication", "googleadservices", "adsystem", "adserver"
    )

    /** Uzantıya göre blok. .ico (favicon) hariç; fontlar izinli. */
    private val blockedExtensions = setOf(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".bmp",
        ".mp4", ".webm", ".ogg", ".mp3", ".wav", ".m4a", ".avi", ".mov",
        ".m3u8", ".m3u", ".ts", ".m4s", ".m4v", ".mpd", ".f4m", ".f4v",
        ".ismv", ".isma", ".ism",
        ".m4f", ".mkv", ".flv", ".3gp", ".aac",  // Grok: m4f/mkv/flv/3gp/aac
        "header.mp4", "init.m4s"
    )

    /** Tüm stream türleri + Grok önerileri: path/query/API kalıpları. */
    private val blockedVideoUrlPatterns = setOf(
        ".m3u8", ".m3u", ".mpd", ".ts", ".m4s", ".m4v", ".f4m", ".f4v", "init.mp4", "header.mp4",
        "/stream", "/stream/", "/streams/", "/hls/", "/hls-", "/dash/", "/hds/", "/hds-vod/",
        "/segment", "/segments/", "/fragments/", "/chunk", "/chunks/", "frag.ts", "chunk.ts",
        "/mp4/", "/webm/", "/video/", "/videos/", "/vid/", "/video_id/", "/media/", "/smooth/", "/ism/",
        "/play/", "/player/", "/manifest/", "/live/", "/vod/", "/cdn/", "/delivery/", "/adaptive/", "/abr/",
        "/get_video", "/load_media", "/api/video", "/fetch_segment", "/qualitylevels=", ".bootstrap",
        "videoplayback", "video_redirect", "master.m3u8", "master.f4m",
        "format=mp4", "type=video", "video=1", "mime=video", "segment=", "fragment=",
        "quality=hd", "quality=sd", "res=1080", "res=720", "res=480", "bitrate=", "profile=", "variant=",
        "playlist=", "manifest=", "key=", "token=", "expires=", "signature=", "auth=",
        "drm=", "license=", "widevine=", "playready=", "range=bytes"
    )

    /** Accept header’da video/stream isteği göstergesi (fetch/XHR ile blob’a giden istekler). */
    private val videoAcceptPatterns = setOf(
        "video/", "application/vnd.apple.mpegurl", "application/x-mpegurl"
    )
    @Volatile
    private var currentPageUrl: String? = null
    @Volatile
    private var currentPageIsVerification: Boolean = false
    /** Sayfa başına bir kez SharedPreferences okumak için; yeni sayfada sıfırlanır. */
    private val allowlistCache = ConcurrentHashMap<String, Boolean>()
    @Volatile
    private var pageLoadStartMs: Long = 0L
    @Volatile
    private var pageLoadStartUrl: String? = null
    @Volatile
    private var lastProxyErrorUrl: String? = null
    @Volatile
    private var lastProxyErrorAt: Long = 0L
    private val emptyResponseRetryAt = ConcurrentHashMap<String, Long>()
    private val mainFrameFallbackUntil = ConcurrentHashMap<String, Long>()
    private val httpUpgradeRetryAt = ConcurrentHashMap<String, Long>()

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        currentPageUrl = url
        currentPageIsVerification = isVerificationUrl(url)
        allowlistCache.clear()
        pageLoadStartMs = SystemClock.elapsedRealtime()
        pageLoadStartUrl = url
        if (!url.isNullOrBlank()) {
            Log.i(PERF_TAG, "page_start url=$url")
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        currentPageUrl = url
        currentPageIsVerification = isVerificationUrl(url)
        if (url != null) onUrlChanged?.invoke(url)
        view?.evaluateJavascript(INJECT_MIN_SIZE_CSS, null)
        val host = AllowlistHelper.hostFromUrl(url)
        val allowlisted = host != null && isHostAllowlistedCached(host)
        if (!allowlisted && !currentPageIsVerification) {
            view?.evaluateJavascript(INJECT_BLOCKED_MEDIA_CSS, null)
        }
        val startMs = pageLoadStartMs
        val startUrl = pageLoadStartUrl
        if (startMs > 0 && !startUrl.isNullOrBlank()) {
            val elapsed = SystemClock.elapsedRealtime() - startMs
            Log.i(PERF_TAG, "page_finish url=$url elapsedMs=$elapsed")
        }
    }
    private fun isHostAllowlistedCached(host: String): Boolean =
        allowlistCache.getOrPut(host) { AllowlistHelper.isHostAllowlisted(context, host) }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (view == null || request == null || error == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !request.isForMainFrame) return
        val desc = error.description?.toString() ?: return
        if (desc.contains("ERR_EMPTY_RESPONSE", ignoreCase = true)) {
            val url = request.url?.toString() ?: return
            val isMain = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request.isForMainFrame
            val proxyReady = WebViewProxyManager.isProxyReady()
            Log.w(EMPTY_RESPONSE_TAG, "empty_response url=$url main=$isMain proxyReady=$proxyReady")
            if (isMain) {
                if (url.startsWith("http://")) {
                    val now = SystemClock.elapsedRealtime()
                    val last = httpUpgradeRetryAt[url] ?: 0L
                    if (now - last > 10_000L) {
                        httpUpgradeRetryAt[url] = now
                        val httpsUrl = "https://" + url.removePrefix("http://")
                        Log.i(EMPTY_RESPONSE_TAG, "retry_https url=$httpsUrl")
                        view.post { view.loadUrl(httpsUrl) }
                        return
                    }
                }
                AllowlistHelper.hostFromUrl(url)?.let { host ->
                    mainFrameFallbackUntil[host] = SystemClock.elapsedRealtime() + 60_000L
                }
            }
            attemptEmptyResponseRecovery(view, request, url)
            return
        }
        if (!desc.contains("ERR_PROXY_CONNECTION_FAILED", ignoreCase = true)) return
        val url = request.url?.toString() ?: return
        val now = SystemClock.elapsedRealtime()
        if (url == lastProxyErrorUrl && now - lastProxyErrorAt < 10_000) return
        lastProxyErrorUrl = url
        lastProxyErrorAt = now
        WebViewProxyManager.clearOverrideAndStop {
            WebViewProxyManager.ensureProxyAndOverride(Runnable {
                view.post { view.reload() }
            })
        }
    }

    private fun attemptEmptyResponseRecovery(view: WebView, request: WebResourceRequest, url: String) {
        val now = SystemClock.elapsedRealtime()
        val last = emptyResponseRetryAt[url] ?: 0L
        if (now - last < EMPTY_RETRY_COOLDOWN_MS) return
        emptyResponseRetryAt[url] = now
        thread(name = "empty-response-retry") {
            try {
                val builder = Request.Builder().url(url).get()
                val ua = view.settings?.userAgentString
                if (!ua.isNullOrBlank()) builder.header("User-Agent", ua)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    request.requestHeaders?.forEach { (name, value) ->
                        if (name.equals("Host", ignoreCase = true)) return@forEach
                        if (name.equals("User-Agent", ignoreCase = true)) return@forEach
                        if (name.equals("Accept", ignoreCase = true) || name.equals("Accept-Language", ignoreCase = true)) {
                            builder.header(name, value)
                        }
                    }
                }
                val response = cloudflareClient.newCall(builder.build()).execute()
                val finalUrl = response.request.url.toString()
                response.close()
                if (finalUrl.isNotBlank() && finalUrl != url) {
                    Log.i(EMPTY_RESPONSE_TAG, "retry_redirect to=$finalUrl")
                    view.post { view.loadUrl(finalUrl) }
                }
            } catch (_: Exception) { }
        }
    }

    /** Bloklanan img/video/audio alanı korunsun; boşluk kalsın, diğer öğeler üst üste binmesin. */
    private val INJECT_MIN_SIZE_CSS = """
        (function(){
            if (document.getElementById('safebrowser-placeholder-style')) return;
            var s = document.createElement('style');
            s.id = 'safebrowser-placeholder-style';
            s.textContent = 'img:not([width]):not([height]){min-width:1px;min-height:1px;} video,audio{min-width:80px;min-height:60px;display:inline-block;}';
            (document.head||document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    private val INJECT_BLOCKED_MEDIA_CSS = """
        (function(){
            if (document.getElementById('safebrowser-blocked-media-style')) return;
            var s = document.createElement('style');
            s.id = 'safebrowser-blocked-media-style';
            s.textContent = 'img,video,audio{background-color:#fafbfa;background-image:repeating-linear-gradient(135deg,rgba(0,0,0,0.02) 0,rgba(0,0,0,0.02) 10px,rgba(0,0,0,0.005) 10px,rgba(0,0,0,0.005) 30px);}';
            (document.head||document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && request != null && request.isForMainFrame) {
            val url = request.url?.toString() ?: return false
            AmpUrlHelper.unwrap(url)?.let { canonical ->
                view?.loadUrl(canonical)
                return true
            }
            if (BlockedSearchEngines.isBlocked(url)) {
                view?.loadUrl(BlockedSearchEngines.getBlockPageDataUrl(context))
                return true
            }
            val safeUrl = GoogleSafeSearchHelper.ensureUrl(url)
            if (safeUrl != url) {
                view?.loadUrl(safeUrl)
                return true
            }
        }
        return false
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null) return null
        val url = request.url?.toString() ?: return null
        val host = AllowlistHelper.hostFromUrl(url) ?: return null
        val isMainFrame = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request.isForMainFrame
        val isVerification = isVerificationRequest(host, url)
        if (isMainFrame) {
            currentPageIsVerification = isVerification
            currentPageUrl = url
        }
        val isVerificationContext = isVerification || currentPageIsVerification || isVerificationUrl(currentPageUrl)
        if (isVerificationContext) return null
        // Allowlist'te main frame'i intercept etme: WebView yüklesin, çerezler otomatik.
        // Aynı origin alt kaynakları sadece allowlist'teyse WebView yapsın; değilse text-only (resim blok).
        val proxyReady = WebViewProxyManager.isProxyReady()
        if (!isMainFrame) {
            val pageHost = currentPageUrl?.let { AllowlistHelper.hostFromUrl(it) }
            if (pageHost != null && isHostAllowlistedCached(pageHost)) return null
        }
        val isAllowlisted = isHostAllowlistedCached(host)
        if (isMainFrame && !isAllowlisted && isGet(request)) {
            Log.i(EMPTY_RESPONSE_TAG, "intercept_main url=$url proxyReady=$proxyReady")
            val resp = fetchWithCloudflareFamily(request, url)
            if (resp != null) return resp
            Log.w(EMPTY_RESPONSE_TAG, "intercept_main_null url=$url proxyReady=$proxyReady")
            return null
        }
        if (isAllowlisted) {
            if (isMainFrame) return null
            if (proxyReady) return null
            return if (isGet(request)) fetchWithCloudflareFamily(request, url) else null
        }
        if (isMainFrame) {
            if (isMainFrameBlockedByExtension(request, url)) {
                return if (isBlockedImageUrl(request, url)) placeholderImageResponse() else emptyResponse()
            }
        } else {
            if (isAdRequest(url, host)) return emptyResponse()
            if (isVideoRequestByHeaders(request)) return emptyResponse()
            if (isBlockedResource(request, url)) {
                return if (isBlockedImageUrl(request, url)) placeholderImageResponse() else emptyResponse()
            }
        }
        // Allowlist'te değilse uzantısız görsel URL'lerini de blokla (Accept: image/*; örn. kitapyurdu.com /resim/123)
        if (!isMainFrame && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val pageHost = currentPageUrl?.let { AllowlistHelper.hostFromUrl(it) }
            if (pageHost != null && !isHostAllowlistedCached(pageHost)) {
                val accept = request.requestHeaders?.get("Accept")?.lowercase() ?: ""
                if (accept.contains("image/")) return placeholderImageResponse()
                // Proxy kapalıyken bazı istekler Accept: */* ile geliyor; CSS/JS/JSON dışındaki GET'leri blokla (görsel sızmasın)
                if (isGet(request)) {
                    val isLikelyCssJsJson = accept.contains("text/css") || accept.contains("javascript") || accept.contains("json")
                    if (!isLikelyCssJsJson) return emptyResponse()
                }
            }
        }
        if (proxyReady) return null
        return if (isGet(request)) fetchWithCloudflareFamily(request, url) else null
    }

    private fun isGet(request: WebResourceRequest): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            request.method.equals("GET", ignoreCase = true)
        } else true
    }

    private fun shouldForceOkHttpMainFrame(host: String): Boolean {
        if (isHostAllowlistedCached(host)) return false
        val until = mainFrameFallbackUntil[host] ?: return false
        return SystemClock.elapsedRealtime() < until
    }

    private fun fetchWithCloudflareFamily(request: WebResourceRequest, url: String): WebResourceResponse? {
        return try {
            val builder = Request.Builder().url(url).get()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                request.requestHeaders?.forEach { (name, value) ->
                    if (!name.equals("Host", ignoreCase = true)) builder.addHeader(name, value)
                }
            }
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(url)
            if (!cookie.isNullOrBlank()) builder.addHeader("Cookie", cookie)
            val response = cloudflareClient.newCall(builder.build()).execute()
            Log.i(EMPTY_RESPONSE_TAG, "okhttp_fetch code=${response.code} url=$url final=${response.request.url}")
            saveCookiesFromResponseChain(response)
            val body = response.body
            val contentType = body?.contentType()
            val mimeType = contentType?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
            val charset = contentType?.charset()?.name() ?: "UTF-8"
            val stream = if (body != null) responseBodyStream(response, body) else ByteArrayInputStream(ByteArray(0))
            val webResponse = WebResourceResponse(mimeType, charset, stream)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val headerMap = mutableMapOf<String, String>()
                val headers = response.headers
                for (name in headers.names()) {
                    headerMap[name] = headers.values(name).joinToString("; ")
                }
                webResponse.responseHeaders = headerMap
                val reason = response.message.takeIf { it.isNotBlank() } ?: "OK"
                webResponse.setStatusCodeAndReasonPhrase(response.code, reason)
            }
            if (!response.isSuccessful) {
                if (body == null) {
                    try { response.close() } catch (_: Exception) { }
                }
                return null
            }
            if (body == null) {
                try { response.close() } catch (_: Exception) { }
            }
            webResponse
        } catch (e: Exception) {
            Log.w(EMPTY_RESPONSE_TAG, "okhttp_fetch_failed url=$url msg=${e.message}")
            null
        }
    }

    private fun responseBodyStream(
        response: okhttp3.Response,
        body: okhttp3.ResponseBody
    ): java.io.InputStream {
        val stream = body.byteStream()
        return object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    response.close()
                }
            }
        }
    }

    /**
     * Redirect zinciri dahil tüm yanıtlardaki Set-Cookie'leri WebView CookieManager'a yazar.
     * Oturum çerezleri sıklıkla redirect yanıtında gelir.
     */
    private fun saveCookiesFromResponseChain(response: okhttp3.Response) {
        var r: okhttp3.Response? = response
        while (r != null) {
            val responseUrl = r.request.url.toString()
            saveCookiesFromResponseSingle(responseUrl, r)
            r = r.priorResponse
        }
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) { }
    }

    private fun saveCookiesFromResponseSingle(url: String, response: okhttp3.Response) {
        try {
            val cookieManager = CookieManager.getInstance()
            val headers = response.headers
            for (i in 0 until headers.size) {
                if (headers.name(i).equals("Set-Cookie", ignoreCase = true)) {
                    val value = headers.value(i)
                    if (value.isNotBlank()) cookieManager.setCookie(url, value)
                }
            }
        } catch (_: Exception) { }
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Bloklanan görsel istekleri için 1x1 şeffaf GIF; alan korunur (img width/height ile gerilir). */
    private fun placeholderImageResponse(): WebResourceResponse =
        WebResourceResponse("image/svg+xml", "utf-8", ByteArrayInputStream(blockedMediaSvg))

    private fun isBlockedImageUrl(request: WebResourceRequest, url: String): Boolean {
        val path = request.url?.path ?: url
        val lower = path.lowercase()
        val urlLower = url.lowercase()
        val imageExtensions = setOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg")
        return imageExtensions.any { lower.endsWith(it) || urlLower.contains("$it?") || urlLower.contains("$it&") }
    }

    /** Main-frame için sadece gerçek uzantıya bak; query tabanlı eşleşmeler çok false-positive üretiyor. */
    private fun isMainFrameBlockedByExtension(request: WebResourceRequest, url: String): Boolean {
        val path = request.url?.path ?: url
        val lower = path.lowercase()
        return blockedExtensions.any { lower.endsWith(it) }
    }

    /** Range veya video Accept = streaming/media isteği (blob/MSE ile yüklenen içerik). */
    private fun isVideoRequestByHeaders(request: WebResourceRequest): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val headers = request.requestHeaders ?: return false
        if (headers.containsKey("Range")) return true
        val accept = headers["Accept"]?.lowercase() ?: return false
        return videoAcceptPatterns.any { accept.contains(it) }
    }

    private fun isAdRequest(url: String, host: String): Boolean {
        val urlLower = url.lowercase()
        val hostLower = host.lowercase()
        if (adHostPatterns.any { hostLower.contains(it) }) return true
        if (adUrlPatterns.any { urlLower.contains(it) }) return true
        return false
    }

    private fun isBlockedResource(request: WebResourceRequest, url: String): Boolean {
        val path = request.url?.path ?: url
        val lower = path.lowercase()
        val urlLower = url.lowercase()
        if (lower.endsWith(".css") || urlLower.contains(".css?")) return false
        if (lower.endsWith(".js") || lower.endsWith(".mjs") || urlLower.contains(".js?") || urlLower.contains(".mjs?")) return false
        if (lower.endsWith(".json") || urlLower.contains(".json?")) return false
        if (blockedExtensions.any { lower.contains(it) }) return true
        if (blockedExtensions.any { urlLower.contains(it) }) return true
        if (blockedVideoUrlPatterns.any { urlLower.contains(it) }) return true
        return false
    }
}
