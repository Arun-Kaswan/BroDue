package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast

class StatementSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_statement, container, false)

    @Suppress("UNCHECKED_CAST")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        view.findViewById<TextView>(R.id.stTitle).text = args.getString(ARG_TITLE)
        val data = StatementExporter.StatementData(
            personName = args.getString(ARG_NAME).orEmpty(),
            mobile = args.getString(ARG_MOBILE).orEmpty(),
            net = args.getLong(ARG_NET),
            transactions = args.getSerializable(ARG_TX) as? ArrayList<Transaction> ?: emptyList()
        )
        val swShowNote = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swShowNote)
        val swFromLastZero = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swFromLastZero)
        val whatsApp = args.getBoolean(ARG_WHATSAPP, false)
        val saveOnly = args.getBoolean(ARG_SAVE, false)
        val ctx = requireContext()
        view.findViewById<View>(R.id.btnShareImage).setOnClickListener {
            val d = data.copy(showNotes = swShowNote.isChecked, fromLastZero = swFromLastZero.isChecked)
            if (saveOnly) {
                StatementExporter.saveAsync(ctx, d, asPdf = false) { saved ->
                    if (saved) Toast.makeText(ctx, "Saved to Download/BroDue/JPG", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(ctx, ctx.getString(R.string.save_error), Toast.LENGTH_SHORT).show()
                }
            } else {
                StatementExporter.shareAsync(ctx, d, asPdf = false, whatsApp = whatsApp)
            }
        }
        view.findViewById<View>(R.id.btnSharePdf).setOnClickListener {
            val d = data.copy(showNotes = swShowNote.isChecked, fromLastZero = swFromLastZero.isChecked)
            if (saveOnly) {
                StatementExporter.saveAsync(ctx, d, asPdf = true) { saved ->
                    if (saved) Toast.makeText(ctx, "Saved to Download/BroDue/PDF", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(ctx, ctx.getString(R.string.save_error), Toast.LENGTH_SHORT).show()
                }
            } else {
                StatementExporter.shareAsync(ctx, d, asPdf = true, whatsApp = whatsApp)
            }
        }
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_NAME = "name"
        private const val ARG_MOBILE = "mobile"
        private const val ARG_NET = "net"
        private const val ARG_TX = "transactions"
        private const val ARG_WHATSAPP = "whatsApp"
        private const val ARG_SAVE = "save"
        const val TAG = "StatementSheet"

        fun newInstance(
            title: String,
            personName: String,
            mobile: String,
            net: Long,
            transactions: List<Transaction>,
            whatsApp: Boolean = false,
            saveOnly: Boolean = false
        ): StatementSheet = StatementSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_NAME, personName)
                putString(ARG_MOBILE, mobile)
                putLong(ARG_NET, net)
                putSerializable(ARG_TX, ArrayList(transactions))
                putBoolean(ARG_WHATSAPP, whatsApp)
                putBoolean(ARG_SAVE, saveOnly)
            }
        }
    }
}