package com.secondwaybrowser.app

import app.secondway.lock.R

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * Non-Google search engine access is strictly blocked.
 * Matching uses stems (e.g. "yandex") so all TLDs are covered: yandex.com.tr, yandex.fr, etc.
 * Only Google (google.com, google.co.uk, etc.) is allowed for search.
 */
object BlockedSearchEngines {

    /**
     * Brand stems: block if host equals stem OR host starts with "stem."
     * Covers all country TLDs: yandex.com.tr, bing.co.uk, duckduckgo.com, etc.
     */
    private val blockedStems: Set<String> = setOf(
        "yandex", "bing", "yahoo", "duckduckgo", "baidu", "ecosia", "startpage", "qwant",
        "ask", "aol", "swisscows", "mojeek", "metager", "ekoru", "gigablast", "dogpile",
        "excite", "lycos", "webcrawler", "ixquick", "wiby", "presearch", "phind", "kagi",
        "neeva", "givewater", "lilo", "runnaroo", "naver", "daum", "sogou", "shenma",
        "easou", "haosou", "qihoo", "aport", "nigma", "webalta", "seznam", "virgilio",
        "alice", "libero", "tiscali", "voila", "laposte", "freenet", "arcor", "najdi",
        "zapmeta", "findx", "oscobo", "yippy", "searx", "paulgo", "disconnect", "adguard",
    )

    /**
     * Full host suffixes: block if host equals suffix OR host.endsWith("." + suffix).
     * Used for multi-part or short names (mail.ru, search.brave.com, so.com) to avoid false positives.
     */
    private val blockedSuffixes: Set<String> = setOf(
        "mail.ru", "rambler.ru", "creativecommons.org", "search.brave.com",
        "so.com", "sm.cn", "360.cn", "sina.com.cn", "zhihu.com", "toutiao.com",
        "t-online.de", "web.de", "gmx.net", "gmx.de", "orange.fr", "free.fr",
        "wp.pl", "onet.pl", "interia.pl", "zoznam.sk", "atlas.sk", "najdi.si",
        "search.orange.co.uk", "search.bt.com", "search.ntlworld.com",
        "search.zonealarm.com", "search.avg.com", "search.norton.com", "search.avast.com",
        "search.kaspersky.com", "search.mcafee.com", "search.bitdefender.com",
        "search.avira.com", "search.eset.com", "search.trendmicro.com", "search.f-secure.com",
        "search.malwarebytes.com", "search.adguard.com", "search.disconnect.me",
        "aolsearch.com", "info.com", "search.com",
    )

    /**
     * Returns true if the URL is a non-Google search engine that must be blocked.
     * Google (any TLD) is always allowed.
     */
    fun isBlocked(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = AllowlistHelper.hostFromUrl(url) ?: return false
        val normalized = host.removePrefix("www.").lowercase()
        if (normalized == "google" || normalized.contains("google.")) return false
        if (blockedStems.any { stem ->
            normalized == stem || normalized.startsWith("$stem.")
        }) return true
        if (blockedSuffixes.any { suffix ->
            normalized == suffix || normalized.endsWith(".$suffix")
        }) return true
        return false
    }

    /** Data URL for the block page HTML (message from resources). */
    fun getBlockPageDataUrl(context: Context): String {
        val message = context.getString(R.string.blocked_search_engines_message)
        val html = """
            <!DOCTYPE html>
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"/><style>
            body{font-family:system-ui,sans-serif;margin:2em;background:#f5f5f5;color:#212121;display:flex;align-items:center;justify-content:center;min-height:80vh;box-sizing:border-box;}
            .box{background:#fff;padding:2em;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,0.1);text-align:center;max-width:360px;}
            h1{font-size:1.25rem;margin:0 0 1rem;}
            p{margin:0;line-height:1.5;color:#616161;}
            </style></head>
            <body><div class="box"><h1>$message</h1><p>Only Google search is allowed in this browser.</p></div></body>
            </html>
        """.trimIndent()
        val encoded = Base64.encodeToString(html.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        return "data:text/html;base64,$encoded"
    }
}
