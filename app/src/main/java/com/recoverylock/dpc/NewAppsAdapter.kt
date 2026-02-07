package com.recoverylock.dpc

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

data class NewAppRow(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val desiredAllowed: Boolean,
    val isActuallyBlocked: Boolean,
    val pendingUnlockEndMillis: Long
)

class NewAppsAdapter(
    private val items: MutableList<NewAppRow>,
    private val onSwitchChanged: (NewAppRow, Boolean) -> Unit
) : RecyclerView.Adapter<NewAppsAdapter.VH>() {

    var nowMillis: Long = System.currentTimeMillis()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.item_app_icon)
        val label: TextView = itemView.findViewById(R.id.item_app_label)
        val status: TextView = itemView.findViewById(R.id.item_app_status)
        val switch: Switch = itemView.findViewById(R.id.item_app_switch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_switch, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.icon.setImageDrawable(
            item.icon ?: ContextCompat.getDrawable(holder.itemView.context, android.R.drawable.sym_def_app_icon)
        )
        holder.label.text = item.label

        val isUnlocking = item.pendingUnlockEndMillis > nowMillis
        val remainingSec = if (!isUnlocking) 0 else ((item.pendingUnlockEndMillis - nowMillis) / 1000).toInt().coerceAtLeast(1)
        holder.status.text = if (isUnlocking) {
            holder.itemView.context.getString(R.string.countdown_unlocking, remainingSec)
        } else if (item.desiredAllowed && !item.isActuallyBlocked) {
            holder.itemView.context.getString(R.string.new_apps_item_allowed)
        } else {
            holder.itemView.context.getString(R.string.new_apps_item_blocked)
        }

        holder.switch.setOnCheckedChangeListener(null)
        holder.switch.isChecked = item.desiredAllowed
        holder.switch.isEnabled = true
        holder.switch.setOnCheckedChangeListener { _, isChecked ->
            onSwitchChanged(item, isChecked)
        }
    }

    override fun getItemCount(): Int = items.size
}
