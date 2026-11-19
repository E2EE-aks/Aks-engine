package com.adm.lite

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); Engine.init(this); setContent { App() } }
}

@Composable fun App() {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF4F46E5), background = Color(0xFFF5F6FA), surface = Color.White)) {
        var tab by remember { mutableStateOf(0) }
        val names = listOf("Downloads", "Browser", "Queue", "Settings")
        val icons = listOf(Icons.Default.Download, Icons.Default.Public, Icons.Default.FormatListBulleted, Icons.Default.Settings)
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
            NavigationBar { names.forEachIndexed { i, n -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icons[i], n) }, label = { Text(n) }) } }
        }) { pad -> Box(Modifier.padding(pad).fillMaxSize()) { when (tab) { 0 -> Downloads(); 1 -> BrowserScreen(); 2 -> QueueScreen(); else -> SettingsScreen() } } }
    }
}

fun fmt(b: Long) = when { b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9); b >= 1_000_000 -> "%.1f MB".format(b / 1e6); else -> "${b / 1000} KB" }

@Composable fun ItemList(list: List<Item>) {
    if (list.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Nothing here yet") }; return }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.id }) { i ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(i.name, maxLines = 1)
                        val meta = when (i.state) {
                            S.ACTIVE -> "${fmt(i.done)}${if (i.total > 0) " of " + fmt(i.total) else ""} · ${fmt(i.speed)}/s"
                            S.DONE -> "Completed · ${fmt(i.done)}"
                            S.QUEUED -> "Queued"; S.PAUSED -> "Paused · ${fmt(i.done)}"; S.FAILED -> "Failed · tap resume to retry"
                        }
                        Text(meta, style = MaterialTheme.typography.bodySmall)
                        if (i.total > 0 && i.state != S.DONE) LinearProgressIndicator(progress = { (i.done.toFloat() / i.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    if (i.state == S.ACTIVE || i.state == S.QUEUED) IconButton({ Engine.pause(i.id) }) { Icon(Icons.Default.Pause, "Pause") }
                    else if (i.state != S.DONE) IconButton({ Engine.resume(i.id) }) { Icon(Icons.Default.PlayArrow, "Resume") }
                    IconButton({ Engine.remove(i.id) }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }
    }
}

@Composable fun Downloads() {
    val list by Engine.items.collectAsState()
    var f by remember { mutableStateOf(0) }
    var dlg by remember { mutableStateOf(false) }
    val shown = when (f) { 1 -> list.filter { it.state == S.ACTIVE }; 2 -> list.filter { it.state == S.DONE }; 3 -> list.filter { it.state == S.QUEUED || it.state == S.PAUSED }; else -> list }
    Box(Modifier.fillMaxSize()) {
        Column {
            Text("Downloads", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(20.dp, 24.dp, 20.dp, 8.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Active", "Done", "Queue").forEachIndexed { i, n -> FilterChip(selected = f == i, onClick = { f = i }, label = { Text(n) }) }
            }
            Card(Modifier.padding(20.dp, 12.dp, 20.dp, 0.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Column(Modifier.padding(16.dp)) { Text("Total speed", color = Color.White); Text("${fmt(list.sumOf { it.speed })}/s", style = MaterialTheme.typography.headlineSmall, color = Color.White) }
            }
            ItemList(shown)
        }
        FloatingActionButton(onClick = { dlg = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) { Icon(Icons.Default.Add, "Add download") }
    }
    if (dlg) AddDialog { dlg = false }
}

@Composable fun AddDialog(close: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("Add download") },
        text = { Column { OutlinedTextField(url, { url = it }, label = { Text("Download link") }, singleLine = true); Spacer(Modifier.height(8.dp)); OutlinedTextField(name, { name = it }, label = { Text("File name (optional)") }, singleLine = true) } },
        confirmButton = { TextButton({ if (url.trim().startsWith("http")) Engine.add(url.trim(), name.trim()); close() }) { Text("Start") } },
        dismissButton = { TextButton(close) { Text("Cancel") } })
}

@Composable fun QueueScreen() {
    val list by Engine.items.collectAsState()
    Column {
        Text("Queue", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(20.dp, 24.dp, 20.dp, 8.dp))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ Engine.resumeAll() }) { Text("Start all") }; OutlinedButton({ Engine.pauseAll() }) { Text("Pause all") }
        }
        ItemList(list.filter { it.state == S.QUEUED || it.state == S.PAUSED || it.state == S.FAILED })
    }
}

val MEDIA = Regex("\\.(mp4|webm|mkv|mov|3gp|mp3|m4a|aac|ogg|wav|flac)(\\?|$)", RegexOption.IGNORE_CASE)
const val JS = "(function(){var r=[];document.querySelectorAll('video,audio,source').forEach(function(e){var s=e.currentSrc||e.src;if(s&&s.indexOf('http')==0)r.push(s)});return r.join('\\n')})()"
fun SnapshotStateList<String>.addU(u: String) { if (u !in this) add(u) }
fun fix(a: String): String = when { a.startsWith("http") -> a; a.contains(".") && !a.contains(" ") -> "https://$a"; else -> "https://www.google.com/search?q=" + Uri.encode(a) }

@SuppressLint("SetJavaScriptEnabled")
@Composable fun BrowserScreen() {
    var addr by remember { mutableStateOf("https://www.google.com") }
    val found = remember { mutableStateListOf<String>() }
    var web by remember { mutableStateOf<WebView?>(null) }
    var show by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ web?.goBack() }) { Icon(Icons.Default.ArrowBack, "Back") }
            OutlinedTextField(addr, { addr = it }, Modifier.weight(1f), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { web?.loadUrl(fix(addr)) }))
            IconButton({ web?.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
        }
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.mediaPlaybackRequiresUserGesture = true
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                        val u = r.url.toString(); if (MEDIA.containsMatchIn(u)) v.post { found.addU(u) }; return null
                    }
                    override fun onPageStarted(v: WebView, u: String, f: android.graphics.Bitmap?) { addr = u; found.clear() }
                    override fun onPageFinished(v: WebView, u: String) {
                        v.evaluateJavascript(JS) { s ->
                            val t = try { org.json.JSONTokener(s).nextValue() as? String } catch (e: Exception) { null }
                            t?.split("\n")?.filter { it.startsWith("http") }?.forEach { found.addU(it) }
                        }
                    }
                }
                setDownloadListener { u, _, _, _, _ -> post { found.addU(u) } }
                loadUrl(addr)
            }.also { web = it }
        })
        if (found.isNotEmpty()) Button({ show = true }, Modifier.fillMaxWidth().padding(12.dp)) { Icon(Icons.Default.Download, null); Text("  ${found.size} media found") }
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("Found media") }, confirmButton = { TextButton({ show = false }) { Text("Close") } },
        text = { LazyColumn { items(found.toList()) { u ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(URLUtil.guessFileName(u, null, null), Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                TextButton({ Engine.add(u, ""); show = false }) { Text("Download") }
            }
        } } })
}

@Composable fun SettingsScreen() {
    var max by remember { mutableStateOf(Engine.maxActive) }
    var lim by remember { mutableStateOf(Engine.limitBps) }
    var wifi by remember { mutableStateOf(Engine.wifiOnly) }
    val limits = listOf(0L, 512 * 1024L, 1024 * 1024L, 2 * 1024 * 1024L)
    @Composable fun Row2(t: String, s: String, click: (() -> Unit)?, end: @Composable () -> Unit) =
        Row(Modifier.fillMaxWidth().then(if (click != null) Modifier.clickable { click() } else Modifier).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(t); Text(s, style = MaterialTheme.typography.bodySmall) }; end()
        }
    Column {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(20.dp, 24.dp, 20.dp, 8.dp))
        Row2("Download folder", Engine.dir.absolutePath, null) {}
        Row2("Simultaneous downloads", "Tap to change", { max = max % 5 + 1; Engine.maxActive = max }) { Text("$max") }
        Row2("Speed limit", "Tap to change", { lim = limits[(limits.indexOf(lim) + 1) % limits.size]; Engine.limitBps = lim }) { Text(if (lim == 0L) "Off" else "${lim / 1024} KB/s") }
        Row2("Wi‑Fi only", "Save mobile data", null) { Switch(wifi, { wifi = it; Engine.wifiOnly = it }) }
    }
}
