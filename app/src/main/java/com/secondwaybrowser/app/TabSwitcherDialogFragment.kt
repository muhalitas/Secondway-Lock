package com.secondwaybrowser.app

import app.secondway.lock.R

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class TabSwitcherDialogFragment : DialogFragment() {

    interface Callback {
        fun getTabs(): List<TabItem>
        fun getTabPreview(tabId: String): android.graphics.Bitmap?
        fun onTabSelected(position: Int)
        fun onTabClosed(position: Int)
        fun onNewTabRequested()
    }

    private var tabAdapter: TabSwitcherAdapter? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_tab_switcher, null)
        view.findViewById<View>(R.id.btn_new_tab).setOnClickListener {
            (requireActivity() as? Callback)?.onNewTabRequested()
            dismiss()
        }
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_tabs)
        val spanCount = calculateSpanCount()
        recycler.layoutManager = GridLayoutManager(requireContext(), spanCount)
        if (recycler.itemDecorationCount == 0) {
            val spacingPx = (resources.displayMetrics.density * 8).toInt()
            recycler.addItemDecoration(GridSpacingItemDecoration(spanCount, spacingPx))
        }
        val tabs = (requireActivity() as? Callback)?.getTabs() ?: emptyList()
        val previewProvider = (requireActivity() as? Callback)
        tabAdapter = TabSwitcherAdapter(
            tabs.toList(),
            { tabId -> previewProvider?.getTabPreview(tabId) },
            { position ->
                (requireActivity() as? Callback)?.onTabSelected(position)
                dismiss()
            },
            { position ->
                (requireActivity() as? Callback)?.onTabClosed(position)
            }
        )
        recycler.adapter = tabAdapter

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .create()
    }

    private fun calculateSpanCount(): Int {
        val metrics = resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        val minCardWidth = 170f
        val span = (widthDp / minCardWidth).toInt().coerceAtLeast(2)
        return span
    }

    private class GridSpacingItemDecoration(
        private val spanCount: Int,
        private val spacing: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            val column = position % spanCount
            val half = spacing / 2
            outRect.left = if (column == 0) spacing else half
            outRect.right = if (column == spanCount - 1) spacing else half
            outRect.top = if (position < spanCount) spacing else half
            outRect.bottom = half
        }
    }

    fun updateTabs() {
        val tabs = (activity as? Callback)?.getTabs() ?: emptyList()
        tabAdapter?.updateTabs(tabs)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
}
