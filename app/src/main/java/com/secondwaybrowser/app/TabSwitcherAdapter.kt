package com.secondwaybrowser.app

import app.secondway.lock.R

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TabSwitcherAdapter(
    private var tabs: List<TabItem>,
    private val previewProvider: (tabId: String) -> android.graphics.Bitmap?,
    private val onTabClick: (position: Int) -> Unit,
    private val onCloseClick: (position: Int) -> Unit
) : RecyclerView.Adapter<TabSwitcherAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tab_card, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tab = tabs[position]
        holder.title.text = tab.title.ifBlank { holder.itemView.context.getString(R.string.new_tab_title) }
        val preview = previewProvider(tab.id)
        if (preview != null) {
            holder.preview.setImageBitmap(preview)
        } else {
            holder.preview.setImageDrawable(null)
        }
        holder.itemView.setOnClickListener { onTabClick(holder.bindingAdapterPosition) }
        holder.btnClose.setOnClickListener { onCloseClick(holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = tabs.size

    fun updateTabs(newTabs: List<TabItem>) {
        tabs = newTabs
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.tab_title)
        val btnClose: ImageButton = itemView.findViewById(R.id.btn_close_tab)
        val preview: android.widget.ImageView = itemView.findViewById(R.id.tab_preview_image)
    }
}
