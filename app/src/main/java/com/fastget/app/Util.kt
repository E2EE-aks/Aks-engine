package com.fastget.app

import android.net.Uri
import android.webkit.MimeTypeMap
import java.net.URLDecoder
import java.util.Locale

object Util {

    fun formatSize(b: Long): String {
        if (b < 0) return "?"
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun formatSpeed(bytesPerSec: Long): String = formatSize(bytesPerSec) + "/s"

    fun formatEta(seconds: Long): String {
        if (seconds < 0) return "--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
            else -> "${s}s"
        }
    }

    fun sanitize(name: String): String {
        val n = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        return if (n.isEmpty()) "download" else n.take(120)
    }

    fun nameFromUrl(url: String): String {
        val seg = try {
            Uri.parse(url).lastPathSegment
        } catch (e: Exception) {
            null
        }
        return if (seg.isNullOrBlank()) "download" else seg
    }

    fun nameFromDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
            val raw = it.groupValues[1].trim().trim('"')
            return try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (e: Exception) {
                raw
            }
        }
        Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let {
            return it.groupValues[1].trim()
        }
        return null
    }

    fun mimeFromName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    fun ensureExt(name: String, mime: String?): String {
        if (name.contains('.')) return name
        if (mime.isNullOrBlank()) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return name
        return "$name.$ext"
    }
}
