package com.fastget.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

/** Decides whether a network request looks like downloadable media. */
object MediaSniffer {
    private val exts = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "3gp",
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac"
    )

    fun isHttp(url: String) = url.startsWith("http://") || url.startsWith("https://")

    fun isMedia(url: String): Boolean {
        if (!isHttp(url)) return false
        val u = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return false
        }
        val host = u.host ?: return false
        if (host.endsWith("googlevideo.com")) return false // signed, chunked streams: not downloadable this way
        val path = u.path?.lowercase() ?: return false
        val ext = path.substringAfterLast('.', "")
        if (ext in exts) return true
        val q = u.query?.lowercase() ?: ""
        return q.contains("mime=video") || q.contains("mime=audio")
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var urlInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var speedText: TextView

    private val found = ArrayList<MediaItem>()
    private val foundUrls = HashSet<String>()
    private var pageUrl = ""
    private var ua = ""
    private val ui = Handler(Looper.getMainLooper())

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val speedTick = object : Runnable {
        override fun run() {
            var s = 0L
            var n = 0
            for (i in Engine.items) {
                if (i.status == Engine.DOWNLOADING) {
                    s += i.speed
                    n++
                }
            }
            speedText.text = if (n > 0) "↓ ${Util.formatSpeed(s)}" else ""
            ui.postDelayed(this, 1000)
        }
    }

    private val sniffJs = """
        (function(){
          if (window.__fgSniff) return; window.__fgSniff = true;
          function send(u){ if (u && /^https?:/i.test(u)) { try { FG.onMedia(u); } catch(e){} } }
          function scan(){
            document.querySelectorAll('video,audio,source').forEach(function(e){ send(e.currentSrc || e.src); });
          }
          scan(); setInterval(scan, 2000);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Engine.init(this)

        web = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        progress = findViewById(R.id.pageProgress)
        fab = findViewById(R.id.fabMedia)
        speedText = findViewById(R.id.speedText)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        ua = s.userAgentString
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "FG")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.method == "GET") {
                    val url = request.url.toString()
                    if (MediaSniffer.isMedia(url)) {
                        val ref = request.requestHeaders["Referer"]
                        ui.post { addFound(url, ref, null) }
                    }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val sch = request.url.scheme ?: return false
                return sch !in setOf("http", "https", "about", "data", "blob", "javascript")
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageUrl = url
                if (!urlInput.hasFocus()) urlInput.setText(url)
                clearFound()
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(sniffJs, null)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCb = callback
                (window.decorView as FrameLayout).addView(
                    view,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() {
                hideCustom()
            }
        }

        // Normal file links (zip, apk, pdf...) that the page wants to download
        web.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            if (!MediaSniffer.isHttp(url)) {
                Toast.makeText(this, "This link type can't be downloaded", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            val name = Util.nameFromDisposition(contentDisposition) ?: Util.nameFromUrl(url)
            val sizeLine = if (contentLength > 0) "\n" + Util.formatSize(contentLength) else ""
            AlertDialog.Builder(this)
                .setTitle("Download file?")
                .setMessage(name + sizeLine)
                .setPositiveButton("Download") { _, _ ->
                    val cookie = CookieManager.getInstance().getCookie(url)
                    Engine.add(url, name, userAgent ?: ua, cookie, pageUrl, mimetype)
                    Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        urlInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                go(urlInput.text.toString())
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
                urlInput.clearFocus()
                true
            } else false
        }

        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { web.reload() }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener { if (web.canGoForward()) web.goForward() }
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { web.loadUrl(HOME) }
        findViewById<ImageButton>(R.id.btnDownloads).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        fab.setOnClickListener { showSheet() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) hideCustom()
                else if (web.canGoBack()) web.goBack()
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        askPermissions()
        if (!handleIntent(intent)) web.loadUrl(HOME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?): Boolean {
        val data = i?.data ?: return false
        if (i.action != Intent.ACTION_VIEW) return false
        web.loadUrl(data.toString())
        return true
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        ui.post(speedTick)
    }

    override fun onPause() {
        ui.removeCallbacks(speedTick)
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun hideCustom() {
        customView?.let { (window.decorView as FrameLayout).removeView(it) }
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun askPermissions() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }

    private fun go(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(" ") || !t.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(t)
            else -> "https://$t"
        }
        web.loadUrl(url)
    }

    // ------------------------------------------------------------ found media

    private fun clearFound() {
        found.clear()
        foundUrls.clear()
        updateFab()
    }

    private fun addFound(url: String, referer: String?, mime: String?) {
        if (!foundUrls.add(url)) return
        found.add(MediaItem(url, Util.sanitize(Util.nameFromUrl(url)), referer ?: pageUrl, mime))
        updateFab()
    }

    private fun updateFab() {
        if (found.isEmpty()) {
            fab.hide()
        } else {
            fab.text = "Download (${found.size})"
            fab.show()
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun onMedia(url: String) {
            if (MediaSniffer.isHttp(url)) ui.post { addFound(url, null, null) }
        }
    }

    private fun showSheet() {
        if (found.isEmpty()) return
        val dlg = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_media, null)
        val rv = v.findViewById<RecyclerView>(R.id.mediaList)
        rv.layoutManager = LinearLayoutManager(this)
        val adapter = MediaAdapter(found) { item, pos ->
            val cookie = CookieManager.getInstance().getCookie(item.url)
            Engine.add(item.url, item.name, ua, cookie, item.referer, item.mime)
            item.started = true
            rv.adapter?.notifyItemChanged(pos)
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        }
        rv.adapter = adapter
        dlg.setContentView(v)
        dlg.show()

        // fetch size / real name for every entry in the background
        for (item in ArrayList(found)) {
            if (item.size >= 0) continue
            Thread {
                val info = Engine.peek(item.url, ua, CookieManager.getInstance().getCookie(item.url), item.referer)
                if (info != null) {
                    item.size = info.total
                    if (!info.mime.isNullOrEmpty()) item.mime = info.mime
                    info.name?.let { item.name = Util.sanitize(it) }
                    runOnUiThread { adapter.notifyDataSetChanged() }
                }
            }.start()
        }
    }

    companion object {
        private const val HOME = "https://www.google.com"
    }
}
