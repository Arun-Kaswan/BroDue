package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// Single-choice drawer: fixed-height scrollable list like the Currency
// drawer. Selected rows get their own tinted style.
class SingleChoiceSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_single_choice, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val requestKey = args.getString(ARG_REQUEST_KEY).orEmpty()
        view.findViewById<TextView>(R.id.choiceTitle).text = args.getString(ARG_TITLE).orEmpty()
        val options = args.getStringArrayList(ARG_OPTIONS) ?: arrayListOf()
        val selected = args.getInt(ARG_SELECTED, -1)

        val rv = view.findViewById<RecyclerView>(R.id.choiceRecycler)
        rv.layoutManager = LinearLayoutManager(requireContext())
        val adapter = ChoiceAdapter(options, selected) { index ->
            parentFragmentManager.setFragmentResult(
                requestKey, Bundle().apply { putInt(EXTRA_INDEX, index) }
            )
            dismiss()
        }
        rv.adapter = adapter
        if (selected in options.indices) rv.scrollToPosition(selected)
    }

    private class ChoiceAdapter(
        private val options: List<String>,
        private var selected: Int,
        private val onPick: (Int) -> Unit
    ) : RecyclerView.Adapter<ChoiceAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.choiceLabel)
            val check: ImageView = view.findViewById(R.id.choiceCheck)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_single_choice, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = options.size

        private fun bgFor(position: Int, isSelected: Boolean): Int {
            val n = options.size
            val base = when {
                n == 1 -> R.drawable.bg_choice_single to R.drawable.bg_choice_single_selected
                position == 0 -> R.drawable.bg_choice_top to R.drawable.bg_choice_top_selected
                position == n - 1 -> R.drawable.bg_choice_bottom to R.drawable.bg_choice_bottom_selected
                else -> R.drawable.bg_choice_middle to R.drawable.bg_choice_middle_selected
            }
            return if (isSelected) base.second else base.first
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ctx = holder.itemView.context
            val den = ctx.resources.displayMetrics.density
            val isSelected = position == selected
            holder.label.text = options[position]
            holder.label.setTextColor(
                ContextCompat.getColor(
                    ctx, if (isSelected) R.color.primary else R.color.navy_text
                )
            )
            holder.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.itemView.background = ContextCompat.getDrawable(ctx, bgFor(position, isSelected))
            val lp = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
            lp.topMargin = if (position == 0) 0 else (5 * den).toInt()
            holder.itemView.layoutParams = lp
            holder.itemView.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onPick(holder.bindingAdapterPosition)
            }
        }
    }

    companion object {
        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_TITLE = "title"
        private const val ARG_OPTIONS = "options"
        private const val ARG_SELECTED = "selected"
        const val EXTRA_INDEX = "index"
        const val TAG = "SingleChoiceSheet"

        fun newInstance(
            requestKey: String,
            title: String,
            options: List<String>,
            selected: Int
        ): SingleChoiceSheet = SingleChoiceSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putString(ARG_TITLE, title)
                putStringArrayList(ARG_OPTIONS, ArrayList(options))
                putInt(ARG_SELECTED, selected)
            }
        }
    }
}
