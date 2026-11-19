package com.adm.lite

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.webkit.URLUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class S { QUEUED, ACTIVE, PAUSED, DONE, FAILED }
data class Item(val id: Int, val url: String, val name: String, val total: Long = 0, val done: Long = 0, val state: S = S.QUEUED, val speed: Long = 0)

object Engine {
    val items = MutableStateFlow<List<Item>>(emptyList())
    var maxActive = 3
    var limitBps = 0L
    var wifiOnly = false
    lateinit var dir: File
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<Int, Job>()
    private var next = 1

    fun init(c: Context) {
        app = c.applicationContext
        dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
    }

    private fun upd(id: Int, f: (Item) -> Item) = items.update { l -> l.map { if (it.id == id) f(it) else it } }

    @Synchronized fun add(url: String, name: String) {
        val n = name.ifBlank { URLUtil.guessFileName(url, null, null) }
        items.update { it + Item(next++, url, n) }
        pump()
    }

    @Synchronized fun pump() {
        var slots = maxActive - items.value.count { it.state == S.ACTIVE }
        for (i in items.value.filter { it.state == S.QUEUED }) {
            if (slots <= 0) break
            slots--
            upd(i.id) { it.copy(state = S.ACTIVE) }
            jobs[i.id] = scope.launch { run(i.id) }
        }
    }

    fun pause(id: Int) { upd(id) { it.copy(state = S.PAUSED, speed = 0) }; scope.launch { jobs[id]?.cancelAndJoin(); pump() } }
    fun resume(id: Int) { scope.launch { jobs[id]?.join(); upd(id) { it.copy(state = S.QUEUED) }; pump() } }
    fun remove(id: Int) { scope.launch { jobs[id]?.cancelAndJoin(); items.update { l -> l.filter { it.id != id } }; pump() } }
    fun pauseAll() = items.value.filter { it.state == S.ACTIVE || it.state == S.QUEUED }.forEach { pause(it.id) }
    fun resumeAll() = items.value.filter { it.state == S.PAUSED || it.state == S.FAILED }.forEach { resume(it.id) }

    private fun onWifi(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private suspend fun run(id: Int) {
        val it0 = items.value.first { it.id == id }
        val file = File(dir, it0.name)
        try {
            if (wifiOnly && !onWifi()) { upd(id) { it.copy(state = S.FAILED) }; pump(); return }
            val c = URL(it0.url).openConnection() as HttpURLConnection
            val have = if (file.exists()) file.length() else 0L
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            c.connectTimeout = 15000; c.readTimeout = 15000; c.connect()
            if (c.responseCode == 416) { upd(id) { it.copy(state = S.DONE, done = have, total = have) }; pump(); return }
            val resumed = c.responseCode == 206
            val start = if (resumed) have else 0L
            val len = c.contentLengthLong
            upd(id) { it.copy(total = if (len > 0) len + start else 0, done = start) }
            c.inputStream.use { ins ->
                FileOutputStream(file, resumed).use { out ->
                    val buf = ByteArray(16384)
                    var done = start; var bytes = 0L; var t0 = System.nanoTime()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n; bytes += n
                        val limit = limitBps
                        if (limit > 0) {
                            val wait = bytes * 1_000_000_000L / limit - (System.nanoTime() - t0)
                            if (wait > 0) delay(wait / 1_000_000)
                        }
                        val el = System.nanoTime() - t0
                        if (el > 500_000_000L) { val sp = bytes * 1_000_000_000L / el; upd(id) { it.copy(done = done, speed = sp) }; bytes = 0; t0 = System.nanoTime() }
                    }
                    upd(id) { it.copy(done = done, state = S.DONE, speed = 0) }
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { upd(id) { it.copy(state = S.FAILED, speed = 0) } }
        pump()
    }
}
