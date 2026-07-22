package com.musa.poetmusic

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.musa.poetmusic.data.LibraryScanner
import com.musa.poetmusic.data.TagEditor
import com.musa.poetmusic.playback.PlaybackService
import com.musa.poetmusic.server.PoetServer
import com.musa.poetmusic.widget.PoetWidgetProvider
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Native WebView container hosting the htmx frontend served by the embedded
 * Ktor server. Also owns the SAF folder picker and runtime permissions.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val db = (application as PoetApp).db
        val label = readableTreePath(uri)
        db.addFolder(uri.toString(), label)
        runJs("poetToast('Folder added — scanning…');")
        LibraryScanner.startScan(this, db)
        runJs("poetGo('/screens/settings');")
    }

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    /** Gallery image picker for the tag editor's album-art grabber. */
    private val pickArt = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        thread {
            val ok = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@runCatching false
                // Guard the heap: cover art past a few MB is almost never intended.
                if (bytes.size > MAX_ART_BYTES) return@runCatching false
                val mime = contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                TagEditor.pendingArt = bytes
                TagEditor.pendingArtMime = mime
                true
            }.getOrDefault(false)
            if (ok) runJs("poetArtPicked();")
            else runJs("poetToast('Could not load that image (max 8 MB).');")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Status bar follows the user's accent color instead of the theme default.
        applyStatusBarColor((application as PoetApp).db.getSetting("accent", "#b9a5ec"))

        startService(Intent(this, PlaybackService::class.java))

        requestStartupPermissions()

        PoetServer.addFolderRequester = {
            runOnUiThread { pickFolder.launch(null) }
        }
        PoetServer.pinWidgetRequester = {
            runOnUiThread { requestPinWidget() }
        }
        PoetServer.pickArtRequester = {
            runOnUiThread { runCatching { pickArt.launch("image/*") } }
        }
        LibraryScanner.onFinished = {
            runJs("poetToast('Library scan finished'); if (poetScreenUrl.indexOf('/screens/library') === 0) poetGo(poetScreenUrl);")
        }

        web = WebView(this)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Pinch and double-tap zoom would stretch the fixed pastel layout;
            // the viewport meta and touch-action CSS in Shell.kt back this up.
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        web.addJavascriptInterface(PoetNativeBridge(), "PoetNative")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Keep in-app navigation inside the embedded server; hand any
                // external http(s) link (e.g. the About screen's GitHub links)
                // to the system browser instead of loading it in the WebView.
                val url = request.url
                if (url.host == "127.0.0.1") return false
                if (url.scheme == "http" || url.scheme == "https") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Serve static assets straight from the APK: no Ktor round trip
                // and, because intercepted responses bypass Chromium's network
                // stack, nothing lands in the WebView HTTP disk cache.
                val url = request.url
                if (url.host != "127.0.0.1" || url.path?.startsWith("/assets/") != true) return null
                val name = url.lastPathSegment ?: return null
                if (!name.matches(Regex("[A-Za-z0-9._-]+"))) return null
                val mime = when {
                    name.endsWith(".js") -> "application/javascript"
                    name.endsWith(".woff2") -> "font/woff2"
                    name.endsWith(".css") -> "text/css"
                    else -> "application/octet-stream"
                }
                return try {
                    WebResourceResponse(mime, null, assets.open("web/$name"))
                } catch (e: Exception) {
                    null // Fall through to the Ktor /assets route.
                }
            }
        }
        setContentView(web)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("poetBack()") { result ->
                    if (result?.contains("exit") == true) moveTaskToBack(true)
                }
            }
        })

        loadWhenServerReady()
    }

    /**
     * One combined system dialog at initial launch: broad storage access
     * (READ_MEDIA_AUDIO on 13+, READ_EXTERNAL_STORAGE below) plus
     * notifications (13+) so the media playback notification can show.
     */
    private fun requestStartupPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())
    }

    /** Ask the launcher to place the Poet widget; falls back to a how-to toast. */
    private fun requestPinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java) ?: return
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, PoetWidgetProvider::class.java), null, null)
        } else {
            runJs("poetToast('Long-press your home screen to add the Poet widget');")
        }
    }

    private fun applyStatusBarColor(hex: String) {
        val color = runCatching { Color.parseColor(hex) }.getOrNull() ?: return
        window.statusBarColor = color
        // Pastel accents are light, so keep the status bar icons dark.
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = luminance > 0.5
    }

    /** Exposed to the WebView so the frontend can drive native chrome. */
    inner class PoetNativeBridge {
        @JavascriptInterface
        fun setStatusBarColor(hex: String) {
            runOnUiThread { if (!isDestroyed) applyStatusBarColor(hex) }
        }
    }

    private fun loadWhenServerReady() {
        thread {
            for (attempt in 0 until 40) {
                try {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", 8080), 250) }
                    break
                } catch (_: Exception) {
                    Thread.sleep(150)
                }
            }
            runOnUiThread { web.loadUrl("http://127.0.0.1:8080/") }
        }
    }

    private fun runJs(script: String) {
        runOnUiThread {
            if (!isDestroyed) web.evaluateJavascript(script, null)
        }
    }

    private fun readableTreePath(uri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
        // Typical SAF ids look like "primary:Music" — render as /storage/emulated/0/Music.
        val parts = docId.split(":", limit = 2)
        return if (parts.size == 2 && parts[0] == "primary") "/storage/emulated/0/${parts[1]}"
        else if (parts.size == 2) "/storage/${parts[0]}/${parts[1]}"
        else docId
    }

    override fun onDestroy() {
        PoetServer.addFolderRequester = null
        PoetServer.pinWidgetRequester = null
        PoetServer.pickArtRequester = null
        TagEditor.clearPendingArt()
        // Drop the WebView disk cache (album art HTTP responses); 'false'
        // keeps cookies and DOM storage intact.
        if (::web.isInitialized) web.clearCache(false)
        super.onDestroy()
    }

    private companion object {
        const val MAX_ART_BYTES = 8 * 1024 * 1024
    }
}
