package com.fastget.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MediaItem(val url: String, var name: String, val referer: String?, var mime: String?) {
    var size: Long = -1
    var started = false
}

class MediaAdapter(
    private val list: List<MediaItem>,
    private val onDownload: (MediaItem, Int) -> Unit
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.mediaName)
        val info: TextView = v.findViewById(R.id.mediaInfo)
        val btn: Button = v.findViewById(R.id.mediaBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun getItemCount() = list.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = list[position]
        h.name.text = item.name
        val type = item.mime ?: Util.mimeFromName(item.name) ?: "media"
        h.info.text = type + " • " + Util.formatSize(item.size)
        h.btn.text = if (item.started) "Started" else "Download"
        h.btn.isEnabled = !item.started
        h.btn.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onDownload(list[p], p)
        }
    }
}
