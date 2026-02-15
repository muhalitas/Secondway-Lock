package com.secondwaybrowser.app

import app.secondway.lock.R

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DownloadItem(
    val id: Long,
    val title: String,
    val status: String,
    val sizeBytes: Long,
    val timestampMs: Long,
    val uri: String?
)

class DownloadsAdapter(
    private var items: MutableList<DownloadItem>
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        val sizeStr = if (item.sizeBytes > 0) formatSize(item.sizeBytes) else "—"
        val dateStr = if (item.timestampMs > 0) dateFormat.format(Date(item.timestampMs)) else "—"
        holder.detail.text = "${item.status} · $sizeStr · $dateStr"
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<DownloadItem>) {
        items = newItems.toMutableList()
        notifyDataSetChanged()
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.1f GB", gb)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.download_title)
        val detail: TextView = itemView.findViewById(R.id.download_detail)
    }
}
