package com.fastget.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/** One download. Progress is kept per segment so a download can be paused/resumed (even after app restart). */
class DlItem(
    val id: Long,
    val url: String,
    var fileName: String,
    val userAgent: String,
    val cookie: String?,
    val referer: String?,
    var mime: String?
) {
    @Volatile var total: Long = -1
    @Volatile var status: Int = Engine.QUEUED
    @Volatile var error: String? = null
    @Volatile var speed: Long = 0
    @Volatile var uri: String? = null
    @Volatile var rangeOk: Boolean = false
    @Volatile var probed: Boolean = false
    @Volatile var stop: Boolean = false
    @Volatile var failMsg: String? = null
    @Volatile var gen: Int = 0
    @Volatile var segStart: LongArray = LongArray(0)
    @Volatile var segEnd: LongArray = LongArray(0)
    @Volatile var segDone: AtomicLongArray = AtomicLongArray(0)
    var lastBytes: Long = 0
    var createdAt: Long = System.currentTimeMillis()
    val running = AtomicInteger(0)
    val conns = CopyOnWriteArrayList<HttpURLConnection>()

    fun downloaded(): Long {
        val d = segDone
        var s = 0L
        for (i in 0 until d.length()) s += d.get(i)
        return s
    }

    fun progress(): Int =
        if (total > 0) ((downloaded() * 100) / total).toInt().coerceIn(0, 100) else 0
}

class PeekInfo(val total: Long, val mime: String?, val name: String?)

object Engine {
    const val QUEUED = 0
    const val DOWNLOADING = 1
    const val PAUSED = 2
    const val COMPLETED = 3
    const val FAILED = 4

    /** Parallel connections per file (like IDM's segments). */
    const val CONNECTIONS = 4
    private const val MIN_SPLIT_SIZE = 2L * 1024 * 1024

    val items = CopyOnWriteArrayList<DlItem>()

    private lateinit var app: Context
    private var inited = false
    private val pool = Executors.newCachedThreadPool()
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private val nextId = AtomicLong(System.currentTimeMillis())
    private var tickCount = 0

    @Synchronized
    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        load()
        ticker.scheduleWithFixedDelay({
            try {
                tick()
            } catch (e: Exception) {
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- public API

    fun add(url: String, name: String, ua: String, cookie: String?, referer: String?, mime: String?): DlItem {
        val item = DlItem(nextId.incrementAndGet(), url, Util.sanitize(name), ua, cookie, referer, mime)
        items.add(0, item)
        save()
        start(item)
        return item
    }

    fun start(item: DlItem) {
        var g = 0
        var go = false
        synchronized(item) {
            if (item.status != DOWNLOADING && item.status != COMPLETED) {
                item.status = DOWNLOADING
                item.error = null
                item.failMsg = null
                item.stop = false
                item.gen += 1
                g = item.gen
                go = true
            }
        }
        if (!go) return
        item.lastBytes = item.downloaded()
        DownloadService.ensure(app)
        pool.execute { runItem(item, g) }
        save()
    }

    fun pause(item: DlItem) {
        if (item.status != DOWNLOADING && item.status != QUEUED) return
        item.stop = true
        item.status = PAUSED
        item.speed = 0
        killConns(item)
        save()
    }

    fun remove(item: DlItem, deleteFile: Boolean) {
        val wasCompleted = item.status == COMPLETED
        item.stop = true
        item.gen += 1
        item.speed = 0
        killConns(item)
        items.remove(item)
        if (!wasCompleted || deleteFile) Storage.delete(app, item)
        save()
    }

    /** Quick HEAD-like request (range 0-0) to learn size / type / name of a URL. Call from a background thread. */
    fun peek(url: String, ua: String, cookie: String?, referer: String?): PeekInfo? {
        return try {
            val tmp = DlItem(0, url, "", ua, cookie, referer, null)
            val c = open(tmp, "bytes=0-0")
            try {
                val code = c.responseCode
                if (code !in 200..299) return null
                val total = if (code == 206) parseTotal(c.getHeaderField("Content-Range")) else c.contentLengthLong
                val mime = c.contentType?.substringBefore(';')?.trim()?.lowercase()
                val name = Util.nameFromDisposition(c.getHeaderField("Content-Disposition"))
                PeekInfo(total, mime, name)
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- worker

    private fun aborted(item: DlItem, g: Int) = item.stop || item.gen != g || item.failMsg != null

    private fun killConns(item: DlItem) {
        for (c in item.conns) {
            try {
                c.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    private fun runItem(item: DlItem, g: Int) {
        try {
            // wait for threads of a previous run (pause -> quick resume) to finish
            var w = 0
            while (item.running.get() > 0 && w < 100) {
                Thread.sleep(50)
                w++
            }
            if (aborted(item, g)) return
            if (!item.probed) probe(item)
            if (item.uri == null) Storage.create(app, item)
            if (aborted(item, g)) return

            val futures = ArrayList<Future<*>>()
            for (i in item.segStart.indices) {
                val end = item.segEnd[i]
                if (end >= 0 && item.segStart[i] + item.segDone.get(i) > end) continue
                item.running.incrementAndGet()
                futures.add(pool.submit(Runnable {
                    try {
                        segment(item, i, g)
                    } catch (e: Exception) {
                        if (!aborted(item, g)) item.failMsg = e.message ?: e.javaClass.simpleName
                    } finally {
                        item.running.decrementAndGet()
                    }
                }))
            }
            for (f in futures) {
                try {
                    f.get()
                } catch (e: Exception) {
                }
            }

            if (item.gen != g || item.stop) return
            val fm = item.failMsg
            if (fm != null) {
                fail(item, fm)
                return
            }
            if (item.total > 0 && item.downloaded() < item.total) {
                fail(item, "Incomplete download")
                return
            }
            item.status = COMPLETED
            item.speed = 0
            Storage.finish(app, item)
            save()
        } catch (e: Exception) {
            if (item.gen == g && !item.stop) fail(item, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(item: DlItem, msg: String) {
        item.status = FAILED
        item.error = msg
        item.speed = 0
        save()
    }

    private fun parseTotal(contentRange: String?): Long =
        contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1

    private fun probe(item: DlItem) {
        val c = open(item, "bytes=0-0")
        try {
            val code = c.responseCode
            if (code == 206) {
                val t = parseTotal(c.getHeaderField("Content-Range"))
                item.rangeOk = t > 0
                item.total = t
            } else if (code in 200..299) {
                item.rangeOk = false
                item.total = c.contentLengthLong
            } else {
                throw IOException("HTTP $code")
            }
            val ct = c.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (!ct.isNullOrEmpty()) item.mime = ct
            Util.nameFromDisposition(c.getHeaderField("Content-Disposition"))?.let { item.fileName = Util.sanitize(it) }
            item.fileName = Util.ensureExt(item.fileName, item.mime)
        } finally {
            c.disconnect()
        }

        val n = if (item.rangeOk && item.total >= MIN_SPLIT_SIZE) CONNECTIONS else 1
        val starts = LongArray(n)
        val ends = LongArray(n)
        if (item.total > 0) {
            val chunk = item.total / n
            for (i in 0 until n) {
                starts[i] = i * chunk
                ends[i] = if (i == n - 1) item.total - 1 else starts[i] + chunk - 1
            }
        } else {
            starts[0] = 0
            ends[0] = -1
        }
        item.segStart = starts
        item.segEnd = ends
        item.segDone = AtomicLongArray(n)
        item.probed = true
        save()
    }

    private fun open(item: DlItem, range: String?): HttpURLConnection {
        var url = item.url
        val origHost = URL(item.url).host
        for (hop in 0..7) {
            val u = URL(url)
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", item.userAgent)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (u.host == origHost) {
                if (!item.cookie.isNullOrEmpty()) c.setRequestProperty("Cookie", item.cookie)
            }
            if (!item.referer.isNullOrEmpty()) c.setRequestProperty("Referer", item.referer)
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("Redirect without Location")
                url = URL(u, loc).toString()
                continue
            }
            return c
        }
        throw IOException("Too many redirects")
    }

    private fun segment(item: DlItem, i: Int, g: Int) {
        val start = item.segStart[i]
        val end = item.segEnd[i]
        var fails = 0
        val ch = Storage.openChannel(app, item)
        try {
            val buf = ByteArray(64 * 1024)
            while (!aborted(item, g)) {
                val done = item.segDone.get(i)
                if (!item.rangeOk && done > 0) {
                    // server cannot resume: start this segment again from zero
                    item.segDone.set(i, 0)
                    continue
                }
                val pos = start + done
                if (end >= 0 && pos > end) break
                val range = if (item.rangeOk) "bytes=$pos-" + (if (end >= 0) end.toString() else "") else null

                var c: HttpURLConnection? = null
                try {
                    c = open(item, range)
                    item.conns.add(c)
                    val code = c.responseCode
                    if (item.rangeOk && code != 206) throw IOException("Server ignored range (HTTP $code)")
                    if (!item.rangeOk && code !in 200..299) throw IOException("HTTP $code")
                    val input = c.inputStream
                    var p = pos
                    var progressed = false
                    while (!aborted(item, g)) {
                        var want = buf.size
                        if (end >= 0) {
                            val left = end - p + 1
                            if (left <= 0) break
                            if (left < want) want = left.toInt()
                        }
                        val n = input.read(buf, 0, want)
                        if (n < 0) {
                            if (end < 0 && !aborted(item, g)) item.total = start + item.segDone.get(i)
                            break
                        }
                        val bb = ByteBuffer.wrap(buf, 0, n)
                        var w = 0L
                        while (bb.hasRemaining()) w += ch.write(bb, p + w)
                        p += n
                        item.segDone.addAndGet(i, n.toLong())
                        progressed = true
                    }
                    if (end < 0 && !aborted(item, g)) break // unknown length: EOF means finished
                    if (progressed) fails = 0 else if (!aborted(item, g)) {
                        fails++
                        if (fails > 5) throw IOException("Connection closed by server")
                    }
                } catch (e: IOException) {
                    if (aborted(item, g)) break
                    fails++
                    if (fails > 5) throw e
                    Thread.sleep(1000L * fails)
                } finally {
                    if (c != null) {
                        item.conns.remove(c)
                        try {
                            c.disconnect()
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        } finally {
            try {
                ch.close()
            } catch (e: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- speed + persistence

    private fun tick() {
        var any = false
        for (item in items) {
            if (item.status == DOWNLOADING) {
                val cur = item.downloaded()
                val delta = cur - item.lastBytes
                item.lastBytes = cur
                item.speed = if (item.speed == 0L) delta else (item.speed + delta) / 2
                any = true
            } else {
                item.speed = 0
            }
        }
        tickCount++
        if (any && tickCount % 3 == 0) save()
    }

    @Synchronized
    private fun save() {
        try {
            val arr = JSONArray()
            for (it in items) {
                val o = JSONObject()
                o.put("id", it.id)
                o.put("url", it.url)
                o.put("name", it.fileName)
                o.put("ua", it.userAgent)
                o.put("cookie", it.cookie ?: "")
                o.put("ref", it.referer ?: "")
                o.put("mime", it.mime ?: "")
                o.put("total", it.total)
                o.put("status", it.status)
                o.put("uri", it.uri ?: "")
                o.put("rangeOk", it.rangeOk)
                o.put("probed", it.probed)
                o.put("error", it.error ?: "")
                o.put("created", it.createdAt)
                val ss = JSONArray()
                val se = JSONArray()
                val sd = JSONArray()
                val d = it.segDone
                val starts = it.segStart
                val ends = it.segEnd
                for (k in starts.indices) {
                    ss.put(starts[k])
                    se.put(ends[k])
                    sd.put(if (k < d.length()) d.get(k) else 0L)
                }
                o.put("ss", ss)
                o.put("se", se)
                o.put("sd", sd)
                arr.put(o)
            }
            app.getSharedPreferences("fastget_dl", Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
        } catch (e: Exception) {
        }
    }

    private fun readArr(a: JSONArray?): LongArray {
        if (a == null) return LongArray(0)
        return LongArray(a.length()) { a.getLong(it) }
    }

    private fun load() {
        val s = app.getSharedPreferences("fastget_dl", Context.MODE_PRIVATE).getString("items", null) ?: return
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val d = DlItem(
                    o.getLong("id"),
                    o.getString("url"),
                    o.getString("name"),
                    o.optString("ua", ""),
                    o.optString("cookie", "").ifEmpty { null },
                    o.optString("ref", "").ifEmpty { null },
                    o.optString("mime", "").ifEmpty { null }
                )
                d.total = o.optLong("total", -1)
                val st = o.optInt("status", PAUSED)
                d.status = if (st == DOWNLOADING || st == QUEUED) PAUSED else st
                d.uri = o.optString("uri", "").ifEmpty { null }
                d.rangeOk = o.optBoolean("rangeOk", false)
                d.probed = o.optBoolean("probed", false)
                d.error = o.optString("error", "").ifEmpty { null }
                d.createdAt = o.optLong("created", System.currentTimeMillis())
                d.segStart = readArr(o.optJSONArray("ss"))
                d.segEnd = readArr(o.optJSONArray("se"))
                val sd = readArr(o.optJSONArray("sd"))
                d.segDone = AtomicLongArray(sd)
                items.add(d)
            }
        } catch (e: Exception) {
        }
    }
}

/** Where files go: Downloads/FastGet (via MediaStore on Android 10+, plain file before that). */
object Storage {

    fun effectiveMime(item: DlItem): String {
        val m = item.mime
        if (!m.isNullOrBlank() && m != "application/octet-stream") return m
        return Util.mimeFromName(item.fileName) ?: "application/octet-stream"
    }

    fun create(ctx: Context, item: DlItem) {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues()
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, item.fileName)
            v.put(MediaStore.MediaColumns.MIME_TYPE, effectiveMime(item))
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FastGet")
            v.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IOException("Cannot create file in Downloads")
            item.uri = uri.toString()
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) item.fileName = it.getString(0)
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FastGet")
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create folder (storage permission?)")
            val base = item.fileName.substringBeforeLast('.', item.fileName)
            val ext = item.fileName.substringAfterLast('.', "")
            var f = File(dir, item.fileName)
            var n = 1
            while (f.exists()) {
                f = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
                n++
            }
            item.fileName = f.name
            item.uri = f.absolutePath
        }
    }

    fun openChannel(ctx: Context, item: DlItem): FileChannel {
        val u = item.uri ?: throw IOException("No target file")
        return if (Build.VERSION.SDK_INT >= 29) {
            val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(u), "rw")
                ?: throw IOException("Cannot open target file")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).channel
        } else {
            RandomAccessFile(File(u), "rw").channel
        }
    }

    fun finish(ctx: Context, item: DlItem) {
        val u = item.uri ?: return
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(Uri.parse(u), v, null, null)
            } else {
                MediaScannerConnection.scanFile(ctx, arrayOf(u), null, null)
            }
        } catch (e: Exception) {
        }
    }

    fun delete(ctx: Context, item: DlItem) {
        val u = item.uri ?: return
        try {
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.delete(Uri.parse(u), null, null)
            else File(u).delete()
        } catch (e: Exception) {
        }
    }

    fun viewUri(ctx: Context, item: DlItem): Uri {
        val u = item.uri ?: throw IOException("No file")
        return if (Build.VERSION.SDK_INT >= 29) Uri.parse(u)
        else FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", File(u))
    }
}
