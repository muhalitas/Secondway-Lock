package com.secondwaybrowser.app

/**
 * Ensures Google search URLs always include Safe Search (safe=active).
 * Used from address bar and from in-page navigation so user cannot bypass.
 */
object GoogleSafeSearchHelper {

    /**
     * If url is a Google /search URL without safe=active or safe=strict, returns url with safe=active added.
     * Otherwise returns url unchanged.
     */
    fun ensureUrl(url: String): String {
        val lower = url.lowercase()
        if (!lower.contains("google.") || !lower.contains("/search")) return url
        if (lower.contains("safe=active") || lower.contains("safe=strict")) return url
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}safe=active"
    }
}
