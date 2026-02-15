package com.secondwaybrowser.app

import app.secondway.lock.R

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private var items: MutableList<HistoryEntry>,
    private val onItemClick: (HistoryEntry) -> Unit,
    private val onDelete: (HistoryEntry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = if (item.title.isNotBlank()) item.title else item.url
        holder.url.text = item.url
        holder.time.text = dateFormat.format(Date(item.timestampMs))
        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<HistoryEntry>) {
        items = newItems.toMutableList()
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.history_title)
        val url: TextView = itemView.findViewById(R.id.history_url)
        val time: TextView = itemView.findViewById(R.id.history_time)
        val delete: ImageButton = itemView.findViewById(R.id.history_delete)
    }
}
