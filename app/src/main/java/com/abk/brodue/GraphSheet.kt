package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible

class GraphSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_graph, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val values = args.getLongArray(ARG_VALUES) ?: return
        val graph = view.findViewById<NetGraphView>(R.id.netGraph)
        val days = view.findViewById<TextView>(R.id.tvDays)
        val empty = view.findViewById<TextView>(R.id.tvGraphEmpty)

        val mode = args.getString(ARG_MODE, "net") ?: "net"
        graph.displayMode = mode

        if (values.isEmpty()) {
            graph.isVisible = false
            days.isVisible = false
            empty.isVisible = true
        } else {
            graph.setValues(values)
            days.text = Formatters.spanFrom(args.getLong(ARG_LAST_DATE, 0L))
        }
    }

    companion object {
        private const val ARG_VALUES = "values"
        private const val ARG_LAST_DATE = "lastDate"
        private const val ARG_MODE = "mode"
        const val TAG = "GraphSheet"

        fun newInstance(values: LongArray, lastDate: Long, mode: String = "net"): GraphSheet = GraphSheet().apply {
            arguments = Bundle().apply {
                putLongArray(ARG_VALUES, values)
                putLong(ARG_LAST_DATE, lastDate)
                putString(ARG_MODE, mode)
            }
        }
    }
}