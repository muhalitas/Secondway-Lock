package com.secondwaybrowser.app

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class TabsAdapter(
    activity: FragmentActivity,
    private val tabs: MutableList<TabItem>
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = tabs.size

    override fun createFragment(position: Int): Fragment {
        val tab = tabs[position]
        return TabFragment.newInstance(tab.id, tab.url)
    }

    override fun getItemId(position: Int): Long =
        tabs.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    override fun containsItem(itemId: Long): Boolean =
        tabs.any { it.id.hashCode().toLong() == itemId }
}
