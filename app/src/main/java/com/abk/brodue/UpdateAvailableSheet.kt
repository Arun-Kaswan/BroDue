package com.abk.brodue

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

// Startup update popup (modal, app theme): version line only, no changelog.
// Detail -> System Update page, Update -> downloads inline with progress,
// Install (when already downloaded) -> installer. Only Remind me later closes.
class UpdateAvailableSheet : BasePopup() {

    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var installMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Modal: outside tap / back never closes it
        isCancelable = false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_update_available, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val tag = args.getString(ARG_TAG).orEmpty()
        val apkUrl = args.getString(ARG_APK_URL).orEmpty()
        val latest = tag.trim().removePrefix("v")
        val current = UpdateManager.currentVersionName(requireContext())
        view.findViewById<TextView>(R.id.tvUpdateLatestBig).text = "v$latest"
        view.findViewById<TextView>(R.id.tvUpdateCurrentLine).text =
            "You're on v$current · Tap Update to download"

        val actions = view.findViewById<View>(R.id.updatePopupActions)
        val progressWrap = view.findViewById<View>(R.id.updatePopupProgress)
        val progressBar = view.findViewById<LinearProgressIndicator>(R.id.progressUpdatePopup)
        val tvPct = view.findViewById<TextView>(R.id.tvUpdatePopupPct)
        val btnAction = view.findViewById<MaterialButton>(R.id.btnUpdateNow)

        fun showProgress(pct: Int) {
            actions.visibility = View.GONE
            progressWrap.visibility = View.VISIBLE
            progressBar.progress = pct
            tvPct.text = "Downloading... $pct%"
        }

        fun showInstall() {
            stopPolling()
            progressWrap.visibility = View.GONE
            actions.visibility = View.VISIBLE
            installMode = true
            btnAction.text = getString(R.string.install)
        }

        // Connected to the System Update page: already downloaded -> Install
        val downloaded = UpdateManager.getDownloaded(requireContext())
        if (downloaded != null && downloaded.first.trim().removePrefix("v")
                .equals(latest.trim(), ignoreCase = true)
        ) {
            showInstall()
        }

        view.findViewById<View>(R.id.btnUpdateDetail).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dismiss()
            (activity as? MainActivity)?.openSystemUpdate()
        }
        btnAction.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (installMode) {
                (activity as? MainActivity)?.installDownloadedApk()
                return@setOnClickListener
            }
            if (!apkUrl.endsWith(".apk")) {
                try {
                    startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(args.getString(ARG_HTML_URL).orEmpty())
                        )
                    )
                } catch (_: Exception) {}
                dismiss()
                return@setOnClickListener
            }
            showProgress(0)
            val act = activity as? MainActivity
            if (act == null) {
                Toast.makeText(requireContext(), R.string.downloading_update, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            act.startPopupDownload(apkUrl, tag)
            // Follow the shared download state (same source the page uses)
            pollRunnable = object : Runnable {
                override fun run() {
                    if (!isAdded) return
                    val a = activity as? MainActivity ?: return
                    val pct = a.updateDownloadProgress()
                    showProgress(pct)
                    if (!a.isUpdateDownloading()) {
                        val done = UpdateManager.getDownloaded(requireContext())
                        if (done != null) {
                            showInstall()
                        } else {
                            Toast.makeText(requireContext(), R.string.save_error, Toast.LENGTH_SHORT).show()
                            progressWrap.visibility = View.GONE
                            actions.visibility = View.VISIBLE
                        }
                        return
                    }
                    pollHandler.postDelayed(this, 400)
                }
            }
            pollHandler.postDelayed(pollRunnable!!, 400)
        }
        view.findViewById<View>(R.id.btnRemindLater).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dismiss()
        }
    }

    private fun stopPolling() {
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    override fun onDestroyView() {
        stopPolling()
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TAG = "tag"
        private const val ARG_APK_URL = "apkUrl"
        private const val ARG_HTML_URL = "htmlUrl"
        const val TAG = "UpdateAvailableSheet"

        fun newInstance(info: UpdateManager.ReleaseInfo): UpdateAvailableSheet =
            UpdateAvailableSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAG, info.tagName)
                    putString(ARG_APK_URL, info.apkUrl)
                    putString(ARG_HTML_URL, info.htmlUrl)
                }
            }
    }
}
