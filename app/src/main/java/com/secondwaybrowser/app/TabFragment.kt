package com.secondwaybrowser.app

import app.secondway.lock.R

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.fragment.app.Fragment

class TabFragment : Fragment() {

    interface Listener {
        fun onTabUrlChanged(tabId: String, url: String)
        fun onTabTitleChanged(tabId: String, title: String)
        fun onLoadProgress(tabId: String, progress: Int)
        fun onTabPreview(tabId: String, bitmap: Bitmap)
    }

    private var tabId: String = ""
    private var initialUrl: String = ""
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabId = arguments?.getString(ARG_TAB_ID) ?: ""
        initialUrl = arguments?.getString(ARG_INITIAL_URL) ?: "https://www.google.com"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_tab, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val wv = view.findViewById<WebView>(R.id.webview)
        webView = wv
        try {
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        } catch (_: Exception) { }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = true
        }
        wv.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        wv.isVerticalScrollBarEnabled = true
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    (activity as? MainActivity)?.onContentTouched()
                    var p: ViewGroup? = v.parent as? ViewGroup
                    while (p != null) {
                        p.requestDisallowInterceptTouchEvent(true)
                        p = p.parent as? ViewGroup
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    var p: ViewGroup? = v.parent as? ViewGroup
                    while (p != null) {
                        p.requestDisallowInterceptTouchEvent(true)
                        p = p.parent as? ViewGroup
                    }
                }
            }
            false
        }
        wv.webViewClient = TextOnlyWebViewClient(requireContext()) { url ->
            (activity as? Listener)?.onTabUrlChanged(tabId, url)
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(v: WebView?, title: String?) {
                (activity as? Listener)?.onTabTitleChanged(tabId, title ?: "")
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                (activity as? Listener)?.onLoadProgress(tabId, newProgress)
            }
        }
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            (activity as? MainActivity)?.handleDownload(url, userAgent, contentDisposition, mimeType, contentLength)
        }
        com.secondwaybrowser.app.dns.CloudflareFamilyDns.prefetchHost(AllowlistHelper.hostFromUrl(initialUrl))
        wv.loadUrl(initialUrl)
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setCurrentWebView(webView)
    }

    override fun onPause() {
        capturePreview()
        (activity as? MainActivity)?.clearCurrentWebView(webView)
        super.onPause()
    }

    override fun onDestroyView() {
        capturePreview()
        (activity as? MainActivity)?.clearCurrentWebView(webView)
        webView?.destroy()
        webView = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TAB_ID = "tab_id"
        private const val ARG_INITIAL_URL = "initial_url"

        fun newInstance(tabId: String, initialUrl: String): TabFragment =
            TabFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAB_ID, tabId)
                    putString(ARG_INITIAL_URL, initialUrl)
                }
            }
    }

    private fun capturePreview() {
        val wv = webView ?: return
        val width = wv.width
        val height = wv.height
        if (width <= 0 || height <= 0) return
        val targetWidth = 360
        val targetHeight = 240
        val scale = minOf(
            targetWidth.toFloat() / width.toFloat(),
            targetHeight.toFloat() / height.toFloat(),
            1f
        )
        val bmpW = (width * scale).toInt().coerceAtLeast(1)
        val bmpH = (height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        wv.draw(canvas)
        (activity as? Listener)?.onTabPreview(tabId, bitmap)
    }
}
