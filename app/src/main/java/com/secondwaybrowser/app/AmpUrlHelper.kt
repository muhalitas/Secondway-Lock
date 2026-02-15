package com.secondwaybrowser.app

import android.net.Uri

object AmpUrlHelper {

    /**
     * Returns the canonical (non-AMP viewer) URL if the given URL is a Google AMP viewer
     * or AMP cache URL. Returns null if no rewrite is needed.
     */
    fun unwrap(url: String): String? {
        return unwrapGoogleAmp(url) ?: unwrapAmpCache(url)
    }

    private fun unwrapGoogleAmp(url: String): String? {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return null
        if (!host.contains("google.")) return null
        val path = uri.path ?: return null
        if (!path.startsWith("/amp/")) return null
        return when {
            path.startsWith("/amp/s/") -> "https://" + path.removePrefix("/amp/s/").trimStart('/')
            path.startsWith("/amp/") -> "http://" + path.removePrefix("/amp/").trimStart('/')
            else -> null
        }
    }

    private fun unwrapAmpCache(url: String): String? {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return null
        if (!host.endsWith("cdn.ampproject.org")) return null
        val path = uri.path ?: return null
        return when {
            path.startsWith("/c/s/") -> "https://" + path.removePrefix("/c/s/").trimStart('/')
            path.startsWith("/c/") -> "http://" + path.removePrefix("/c/").trimStart('/')
            else -> null
        }
    }
}
