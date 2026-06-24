package com.talkback.appprod.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView inside ScrollView with wrap_content height only measures a subset of rows
 * on some OEMs. Expand to full content height when height is AT_MOST/UNSPECIFIED.
 */
class FullyExpandedLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
    override fun onMeasure(
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
        widthSpec: Int,
        heightSpec: Int
    ) {
        val heightMode = View.MeasureSpec.getMode(heightSpec)
        if (heightMode == View.MeasureSpec.UNSPECIFIED || heightMode == View.MeasureSpec.AT_MOST) {
            val width = View.MeasureSpec.getSize(widthSpec)
            var totalHeight = paddingTop + paddingBottom
            val count = state.itemCount
            for (i in 0 until count) {
                val view = recycler.getViewForPosition(i)
                measureChildWithMargins(view, 0, 0)
                totalHeight += getDecoratedMeasuredHeight(view)
                recycler.recycleView(view)
            }
            setMeasuredDimension(width, totalHeight)
        } else {
            super.onMeasure(recycler, state, widthSpec, heightSpec)
        }
    }
}
