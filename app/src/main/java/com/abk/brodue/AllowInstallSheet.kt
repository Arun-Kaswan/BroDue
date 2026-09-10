package com.abk.brodue

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast

// App-themed replacement for the system's allow-installs dialog.
class AllowInstallSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_allow_install, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnInstallDeny).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dismiss()
        }
        view.findViewById<View>(R.id.btnInstallAllow).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dismiss()
            try {
                val ctx = requireContext()
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                })
            } catch (_: Exception) {}
            Toast.makeText(requireContext(), R.string.installs_hint, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val TAG = "AllowInstallSheet"

        fun newInstance(): AllowInstallSheet = AllowInstallSheet()
    }
}
