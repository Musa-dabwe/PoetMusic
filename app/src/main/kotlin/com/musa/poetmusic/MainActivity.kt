package com.musa.poetmusic

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.WindowManager
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
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import com.musa.poetmusic.data.JournalPortrait
import com.musa.poetmusic.data.audioMime
import com.musa.poetmusic.data.LibraryScanner
import com.musa.poetmusic.data.LibraryWatcher
import com.musa.poetmusic.data.TagEditor
import com.musa.poetmusic.playback.PlaybackService
import com.musa.poetmusic.server.PoetServer
import com.musa.poetmusic.server.Shell
import com.musa.poetmusic.widget.PoetWidgetProvider
import java.io.File
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

    /** Track whose cover is waiting on the pre-Android-10 write permission. */
    private var pendingArtSaveId: Long? = null

    private val askWriteForArt =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val id = pendingArtSaveId
            pendingArtSaveId = null
            if (granted && id != null) saveArtToGallery(id)
            else runJs("poetToast('Storage permission is needed to save the cover');")
        }

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

    /** Gallery image picker for the Listening Journal's portrait badge. */
    private val pickPortrait = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        thread {
            val db = (application as PoetApp).db
            val ok = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@runCatching false
                if (bytes.size > MAX_ART_BYTES) return@runCatching false
                val mime = contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                JournalPortrait.save(this@MainActivity, db, bytes, mime)
            }.getOrDefault(false)
            if (ok) runJs("poetPortraitPicked(); poetToast('Portrait updated');")
            else runJs("poetToast('Could not load that image (max 8 MB).');")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyDisplayCutoutMode()
        applyStartupStatusBarColor()
        startService(Intent(this, PlaybackService::class.java))
        requestStartupPermissions()
        wireServerCallbacks()

        web = buildWebView()
        setContentView(web)

        installBackHandler()
        loadWhenServerReady()

        // Bring a stale library back in step without the user asking (§3).
        LibraryScanner.maybeAutoScan(this, (application as PoetApp).db)
    }

    /**
     * The library watchdog only runs while Poet is on screen: it is there to
     * notice files appearing under the user's nose, and polling a document
     * provider from the background would cost battery for nothing.
     */
    override fun onStart() {
        super.onStart()
        LibraryWatcher.start(this, (application as PoetApp).db)
    }

    override fun onStop() {
        LibraryWatcher.stop()
        super.onStop()
    }

    /**
     * Status bar matches the app canvas: the selected tint in light mode, the
     * dark primary in dark mode. applyStatusBarColor picks matching icon
     * contrast (dark icons on light, light icons on dark) by luminance.
     */
    private fun applyStartupStatusBarColor() {
        val db = (application as PoetApp).db
        val dark = db.getSetting("dark", "0") == "1"
        val tint = Shell.CANVAS_TINTS[db.getSetting("theme", "Lavender")] ?: "#f2effa"
        applyStatusBarColor(if (dark) DARK_STATUS_BAR else tint)
    }

    /** Hand the server the native capabilities it cannot reach on its own. */
    private fun wireServerCallbacks() {
        PoetServer.host.addFolderRequester = {
            runOnUiThread { pickFolder.launch(null) }
        }
        PoetServer.host.pinWidgetRequester = {
            runOnUiThread { requestPinWidget() }
        }
        PoetServer.host.pickArtRequester = {
            runOnUiThread { runCatching { pickArt.launch("image/*") } }
        }
        PoetServer.host.pickPortraitRequester = {
            runOnUiThread { runCatching { pickPortrait.launch("image/*") } }
        }
        PoetServer.host.shareRequester = { ids -> shareTracks(ids) }
        PoetServer.host.saveArtRequester = { id -> requestSaveArt(id) }
        LibraryScanner.onFinished = {
            runJs("poetToast('Library scan finished'); if (poetScreenUrl.indexOf('/screens/library') === 0) poetGo(poetScreenUrl);")
        }
    }

    private fun buildWebView(): WebView = WebView(this).also { view ->
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Pinch and double-tap zoom would stretch the fixed pastel layout;
            // the viewport meta and touch-action CSS in poet.css back this up.
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        view.addJavascriptInterface(PoetNativeBridge(), "PoetNative")
        view.webChromeClient = WebChromeClient()
        view.webViewClient = PoetWebViewClient()
    }

    private inner class PoetWebViewClient : WebViewClient() {
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

    /** Back closes overlays first; poetBack() reports 'exit' when at the root. */
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("poetBack()") { result ->
                    if (result?.contains("exit") == true) moveTaskToBack(true)
                }
            }
        })
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

    /**
     * Share selected tracks (§6). SAF document URIs can't simply be forwarded
     * — the receiving app has no grant on the user's document tree — so each
     * file is staged into cacheDir/share and handed over as a FileProvider URI
     * with a one-shot read grant. The staging directory is wiped on every
     * share so it can't accumulate copies of the user's library.
     */
    private fun shareTracks(ids: List<Long>) {
        if (ids.isEmpty()) return
        thread {
            val db = (application as PoetApp).db
            val dir = File(cacheDir, "share").apply { mkdirs() }
            runCatching { dir.listFiles()?.forEach { it.delete() } }

            val uris = ArrayList<Uri>()
            val mimes = mutableSetOf<String>()
            var budget = MAX_SHARE_BYTES
            var skipped = 0
            for (id in ids) {
                val track = db.track(id) ?: continue
                val staged = File(dir, sanitizeFileName(track.displayName))
                val ok = runCatching {
                    contentResolver.openInputStream(Uri.parse(track.uri))?.use { input ->
                        staged.outputStream().use { out -> input.copyTo(out) }
                    } ?: return@runCatching false
                    if (staged.length() > budget) {
                        staged.delete()
                        return@runCatching false
                    }
                    budget -= staged.length()
                    true
                }.getOrDefault(false)
                if (!ok) {
                    skipped++
                    continue
                }
                uris += FileProvider.getUriForFile(this, "$packageName.fileprovider", staged)
                mimes += audioMime(track.displayName)
            }

            if (uris.isEmpty()) {
                runJs("poetToast('Could not prepare ${if (ids.size == 1) "that file" else "those files"} for sharing');")
                return@thread
            }
            // A mixed selection has no single accurate type; audio/* is honest.
            val type = mimes.singleOrNull() ?: "audio/*"
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }.apply {
                setType(type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val started = runCatching {
                    startActivity(Intent.createChooser(intent, if (uris.size == 1) "Share song" else "Share ${uris.size} songs"))
                }.isSuccess
                if (!started) runJs("poetToast('No app available to share with');")
                else if (skipped > 0) runJs("poetToast('$skipped file${if (skipped == 1) "" else "s"} skipped (too large or unreadable)');")
            }
        }
    }

    /**
     * "Save to gallery" from the full-screen art viewer (§7). Below Android 10
     * writing to shared storage still needs WRITE_EXTERNAL_STORAGE, and it is
     * asked for here — at the moment the user actually asks to save — rather
     * than bundled into the startup permission dialog.
     */
    private fun requestSaveArt(trackId: Long) {
        pendingArtSaveId = trackId
        if (Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            saveArtToGallery(trackId)
        } else {
            runOnUiThread { askWriteForArt.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        }
    }

    private fun saveArtToGallery(trackId: Long) {
        thread {
            val db = (application as PoetApp).db
            val track = db.track(trackId)
            val art = track?.let { t ->
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(this, Uri.parse(t.uri))
                    mmr.embeddedPicture
                } catch (e: Exception) {
                    null
                } finally {
                    runCatching { mmr.release() }
                }
            }
            if (track == null || art == null) {
                runJs("poetToast('No artwork to save for this song');")
                return@thread
            }
            val name = sanitizeFileName("${track.artist} - ${track.album}".trim().ifBlank { track.title })
            val saved = runCatching { writeImageToGallery(art, "$name.jpg") }.getOrDefault(false)
            runJs(
                if (saved) "poetToast('Cover saved to Pictures/Poet');"
                else "poetToast('Could not save the cover');"
            )
        }
    }

    /**
     * Insert JPEG bytes into MediaStore under Pictures/Poet. RELATIVE_PATH and
     * IS_PENDING only exist from Android 10; older releases get an explicit
     * file written into the public Pictures directory and indexed by path.
     */
    private fun writeImageToGallery(bytes: ByteArray, displayName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Poet")
            values.put(MediaStore.Images.Media.IS_PENDING, 1)
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return true
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Poet")
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = File(dir, displayName)
        file.outputStream().use { it.write(bytes) }
        @Suppress("DEPRECATION")
        values.put(MediaStore.Images.Media.DATA, file.absolutePath)
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        return true
    }

    /** Keep a staged copy's name recognisable but safe as a cache filename. */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").takeLast(120).ifBlank { "track" }

    /** Ask the launcher to place the Poet widget; falls back to a how-to toast. */
    private fun requestPinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java) ?: return
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, PoetWidgetProvider::class.java), null, null)
        } else {
            runJs("poetToast('Long-press your home screen to add the Poet widget');")
        }
    }

    /**
     * Draw into the display cutout region on the short edges.
     *
     * Without this the system letterboxes the whole window away from the
     * notch, which in landscape shows up as a black band down one side of the
     * app (docs/native-ui-solidification.md §3.1) — not something CSS can
     * reach. The content view is still inset by the system, so nothing lands
     * under the notch; only the window background fills that strip, and
     * applyStatusBarColor() keeps that background matching the app canvas.
     */
    private fun applyDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun applyStatusBarColor(hex: String) {
        val color = runCatching { Color.parseColor(hex) }.getOrNull() ?: return
        window.statusBarColor = color
        // The cutout strip (§3.1) and any letterboxed edge show the window
        // background, so it has to track the canvas or it reads as a hard band.
        window.setBackgroundDrawable(ColorDrawable(color))
        // Light canvas → dark status bar icons; dark canvas → light icons.
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
        PoetServer.host.addFolderRequester = null
        PoetServer.host.pinWidgetRequester = null
        PoetServer.host.pickArtRequester = null
        PoetServer.host.pickPortraitRequester = null
        PoetServer.host.shareRequester = null
        PoetServer.host.saveArtRequester = null
        TagEditor.clearPendingArt()
        // Drop the WebView cache including the disk files, so album art HTTP
        // responses don't outlive the session. 'true' is required: with
        // 'false' only the in-memory cache is cleared and the cached responses
        // stay on disk. Cookies and DOM storage are a separate store that
        // clearCache never touches.
        if (::web.isInitialized) web.clearCache(true)
        super.onDestroy()
    }

    private companion object {
        const val MAX_ART_BYTES = 8 * 1024 * 1024

        /** Ceiling on one share's staged copies, so the cache can't balloon. */
        const val MAX_SHARE_BYTES = 250L * 1024 * 1024

        /** Dark-mode primary (--bg in Shell's dark palette). */
        const val DARK_STATUS_BAR = "#16151d"
    }
}
