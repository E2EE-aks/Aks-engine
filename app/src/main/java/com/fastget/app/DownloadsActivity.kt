package com.fastget.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

class DownloadsActivity : AppCompatActivity() {

    private lateinit var adapter: DownloadsAdapter
    private lateinit var empty: TextView
    private lateinit var totalSpeed: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var lastCount = -1

    private val tick = object : Runnable {
        override fun run() {
            val n = Engine.items.size
            if (n != lastCount) {
                lastCount = n
                adapter.notifyDataSetChanged()
            } else if (n > 0) {
                adapter.notifyItemRangeChanged(0, n, "progress")
            }
            empty.visibility = if (n == 0) View.VISIBLE else View.GONE
            var s = 0L
            for (i in Engine.items) if (i.status == Engine.DOWNLOADING) s += i.speed
            totalSpeed.text = if (s > 0) "↓ ${Util.formatSpeed(s)}" else ""
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        Engine.init(this)

        empty = findViewById(R.id.emptyText)
        totalSpeed = findViewById(R.id.totalSpeed)
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        val rv = findViewById<RecyclerView>(R.id.downloadList)
        rv.layoutManager = LinearLayoutManager(this)
        (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        adapter = DownloadsAdapter(this)
        rv.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }
}

class DownloadsAdapter(private val ctx: Context) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

    init {
        setHasStableIds(true)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.dlName)
        val info: TextView = v.findViewById(R.id.dlInfo)
        val bar: ProgressBar = v.findViewById(R.id.dlProgress)
        val toggle: ImageButton = v.findViewById(R.id.dlToggle)
        val delete: ImageButton = v.findViewById(R.id.dlDelete)
    }

    override fun getItemId(position: Int): Long =
        if (position < Engine.items.size) Engine.items[position].id else RecyclerView.NO_ID

    override fun getItemCount() = Engine.items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        if (position >= Engine.items.size) return
        val item = Engine.items[position]
        h.name.text = item.fileName
        val done = item.downloaded()
        val totalTxt = Util.formatSize(item.total)

        when (item.status) {
            Engine.DOWNLOADING -> {
                val remaining = if (item.total > 0 && item.speed > 0) (item.total - done) / item.speed else -1L
                val pct = if (item.total > 0) " (${item.progress()}%)" else ""
                h.info.text = "${Util.formatSize(done)} / $totalTxt$pct\n" +
                    "${Util.formatSpeed(item.speed)} • ETA ${Util.formatEta(remaining)}"
                h.bar.visibility = View.VISIBLE
                h.bar.isIndeterminate = item.total <= 0
                h.bar.progress = item.progress()
                h.toggle.visibility = View.VISIBLE
                h.toggle.setImageResource(R.drawable.ic_pause)
            }
            Engine.PAUSED, Engine.QUEUED -> {
                h.info.text = "Paused • ${Util.formatSize(done)} / $totalTxt"
                h.bar.visibility = View.VISIBLE
                h.bar.isIndeterminate = false
                h.bar.progress = item.progress()
                h.toggle.visibility = View.VISIBLE
                h.toggle.setImageResource(R.drawable.ic_play)
            }
            Engine.FAILED -> {
                h.info.text = "Failed: ${item.error ?: "error"}\n${Util.formatSize(done)} / $totalTxt"
                h.bar.visibility = View.VISIBLE
                h.bar.isIndeterminate = false
                h.bar.progress = item.progress()
                h.toggle.visibility = View.VISIBLE
                h.toggle.setImageResource(R.drawable.ic_play)
            }
            else -> {
                h.info.text = "Completed • $totalTxt\nSaved in Downloads/FastGet"
                h.bar.visibility = View.GONE
                h.toggle.visibility = View.GONE
            }
        }

        h.toggle.setOnClickListener {
            if (item.status == Engine.DOWNLOADING) Engine.pause(item) else Engine.start(item)
        }
        h.delete.setOnClickListener { confirmDelete(item) }
        h.itemView.setOnClickListener {
            if (item.status == Engine.COMPLETED) openFile(item)
        }
    }

    private fun confirmDelete(item: DlItem) {
        val completed = item.status == Engine.COMPLETED
        val options = if (completed) arrayOf("Remove from list", "Delete file too") else arrayOf("Cancel download")
        AlertDialog.Builder(ctx)
            .setTitle(item.fileName)
            .setItems(options) { _, which ->
                Engine.remove(item, completed && which == 1)
                notifyDataSetChanged()
            }
            .show()
    }

    private fun openFile(item: DlItem) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Storage.viewUri(ctx, item), Storage.effectiveMime(item))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Can't open this file", Toast.LENGTH_SHORT).show()
        }
    }
}
