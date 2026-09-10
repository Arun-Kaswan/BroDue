package com.abk.brodue

import android.content.DialogInterface
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

// Tracks the currently displayed QR code so it can be deleted if the app
// goes to the background (minimize) before the sheet is closed.
object QrSession {
    var activeCode: String? = null
        private set

    fun started(code: String) {
        activeCode = code
    }

    fun finished() {
        activeCode = null
    }

    fun deleteActive() {
        val code = activeCode ?: return
        activeCode = null
        ShareSync.deleteQrCode(code)
    }
}

// One-time join QR with a live expiry timer. The code is deleted from the
// database on close, on expiry, and when the app goes to the background.
class QrSheet : BaseSheet() {

    private var timer: CountDownTimer? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_qr, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val code = requireArguments().getString(ARG_CODE).orEmpty()
        val createdAt = requireArguments().getLong(ARG_CREATED_AT, System.currentTimeMillis())
        QrSession.started(code)
        // Deep link: scanning opens the app and auto-joins (see MainActivity.handleJoinLink)
        val bitmap = ShareCodeImage.makeQrBitmap(joinLink(code), 512)
        if (bitmap == null) {
            dismiss()
            return
        }
        view.findViewById<ImageView>(R.id.qrImage).setImageBitmap(bitmap)
        val timerTv = view.findViewById<TextView>(R.id.qrTimer)
        val remaining = createdAt + ShareSync.QR_TTL_MS - System.currentTimeMillis()
        if (remaining <= 0) {
            dismiss()
            return
        }
        timer = object : CountDownTimer(remaining, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (!isAdded) return
                timerTv.text = formatTime(millisUntilFinished)
            }

            override fun onFinish() {
                dismiss()
            }
        }.also {
            timerTv.text = formatTime(remaining)
            it.start()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        timer?.cancel()
        timer = null
        ShareSync.deleteQrCode(arguments?.getString(ARG_CODE).orEmpty())
        QrSession.finished()
        super.onDismiss(dialog)
    }

    private fun formatTime(millis: Long): String {
        val totalSec = (millis / 1000).coerceAtLeast(0)
        return "${totalSec / 60}:${String.format("%02d", totalSec % 60)}"
    }

    companion object {
        private const val ARG_CODE = "code"
        private const val ARG_CREATED_AT = "createdAt"
        const val TAG = "QrSheet"
        // Custom scheme: no domain needed, Android opens the app directly.
        fun joinLink(code: String): String = "brodue://join?code=$code"

        fun newInstance(code: String, createdAt: Long): QrSheet = QrSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_CODE, code)
                putLong(ARG_CREATED_AT, createdAt)
            }
        }
    }
}
