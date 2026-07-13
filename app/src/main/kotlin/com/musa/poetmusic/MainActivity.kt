package com.musa.poetmusic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.musa.poetmusic.data.LibraryScanner
import com.musa.poetmusic.playback.PlaybackService
import com.musa.poetmusic.server.PoetServer
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

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startService(Intent(this, PlaybackService::class.java))

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        PoetServer.addFolderRequester = {
            runOnUiThread { pickFolder.launch(null) }
        }
        LibraryScanner.onFinished = {
            runJs("poetToast('Library scan finished'); if (poetScreenUrl.indexOf('/screens/library') === 0) poetGo(poetScreenUrl);")
        }

        web = WebView(this)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Keep navigation inside the embedded server.
                return request.url.host != "127.0.0.1"
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
        super.onDestroy()
    }
}
