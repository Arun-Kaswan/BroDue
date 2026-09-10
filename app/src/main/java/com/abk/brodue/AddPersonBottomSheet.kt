package com.abk.brodue

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputLayout

class AddPersonBottomSheet : BaseSheet() {

    private lateinit var tilName: TextInputLayout
    private lateinit var tilMobile: TextInputLayout
    private lateinit var etName: EditText
    private lateinit var etMobile: EditText
    private var isSaving = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchContactPicker()
        else Toast.makeText(requireContext(), "Contacts permission denied", Toast.LENGTH_SHORT).show()
    }

    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                var cursor: Cursor? = null
                try {
                    cursor = requireContext().contentResolver.query(
                        uri,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                        ),
                        null, null, null
                    )
                    if (cursor != null && cursor.moveToFirst()) {
                        val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val number = if (numIdx >= 0) cursor.getString(numIdx) else null
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                        if (!name.isNullOrBlank()) {
                            etName.setText(name)
                        }
                        if (!number.isNullOrBlank()) {
                            // Keep raw number as from contacts (may include + and spaces)
                            // Limit to max 16 digits
                            val trimmed = number.trim()
                            etMobile.setText(trimmed)
                            etMobile.setSelection(trimmed.length)
                        }
                    }
                } finally {
                    cursor?.close()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_add_person, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tilName = view.findViewById(R.id.tilName)
        tilMobile = view.findViewById(R.id.tilMobile)
        etName = view.findViewById(R.id.etName)
        etMobile = view.findViewById(R.id.etMobile)

        // Polish formatting: country code grey + spaces in number
        etMobile.addTextChangedListener(object : TextWatcher {
            private var selfChange = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (selfChange) return
                val raw = s?.toString() ?: return
                if (raw.isEmpty()) return
                val trimmed = raw.trim()
                val hasPlus = trimmed.startsWith("+")
                var digitsAfterPlus = ""
                var countryCode: String? = null
                var numberDigits = ""
                if (hasPlus) {
                    val afterPlus = trimmed.substring(1).replace(Regex("\\D"), "")
                    if (afterPlus.isEmpty()) {
                        // Just "+" keep as is
                        return
                    }
                    // Find longest matching country code
                    val sorted = CountryCodes.list.sortedByDescending { it.code.length }
                    for (c in sorted) {
                        val codeDigits = c.code.substring(1)
                        if (afterPlus.startsWith(codeDigits)) {
                            countryCode = c.code
                            numberDigits = afterPlus.substring(codeDigits.length)
                            break
                        }
                    }
                    if (countryCode == null) {
                        // No known code yet, treat whole as digits, don't grey
                        val allDigits = afterPlus.take(16)
                        val formatted = formatNumberDigits(allDigits)
                        val newText = "+$formatted"
                        if (newText != raw) {
                            selfChange = true
                            s.replace(0, s.length, newText)
                            selfChange = false
                        }
                        return
                    }
                    digitsAfterPlus = afterPlus
                    // Limit total digits to 16
                    val totalDigits = countryCode!!.substring(1).length + numberDigits.length
                    if (totalDigits > 16) {
                        numberDigits = numberDigits.take(16 - countryCode!!.substring(1).length)
                    }
                } else {
                    numberDigits = raw.replace(Regex("\\D"), "").take(16)
                }

                val formattedNumber = formatNumberDigits(numberDigits)
                val finalText = if (countryCode != null) {
                    if (formattedNumber.isEmpty()) countryCode else "$countryCode $formattedNumber"
                } else {
                    if (hasPlus) "+$formattedNumber" else formattedNumber
                }

                if (finalText != raw) {
                    selfChange = true
                    val greyEnd = if (countryCode != null) countryCode!!.length else -1
                    if (greyEnd > 0 && hasPlus) {
                        val ssb = android.text.SpannableStringBuilder(finalText)
                        ssb.setSpan(
                            android.text.style.ForegroundColorSpan(
                                ContextCompat.getColor(requireContext(), R.color.grey_soft)
                            ), 0, greyEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        s.replace(0, s.length, ssb)
                    } else {
                        s.replace(0, s.length, finalText)
                    }
                    try { etMobile.setSelection(s.length) } catch (_: Exception) {}
                    selfChange = false
                } else if (countryCode != null && hasPlus) {
                    // Apply grey span even if text unchanged but not yet spanned
                    val existing = s.getSpans(0, s.length, android.text.style.ForegroundColorSpan::class.java)
                    if (existing.isEmpty()) {
                        selfChange = true
                        val ssb = android.text.SpannableStringBuilder(finalText)
                        ssb.setSpan(
                            android.text.style.ForegroundColorSpan(
                                ContextCompat.getColor(requireContext(), R.color.grey_soft)
                            ), 0, countryCode!!.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        s.replace(0, s.length, ssb)
                        try { etMobile.setSelection(s.length) } catch (_: Exception) {}
                        selfChange = false
                    }
                }
            }

            private fun formatNumberDigits(digits: String): String {
                if (digits.isEmpty()) return ""
                if (digits.length <= 5) return digits
                if (digits.length <= 10) return digits.substring(0, 5) + " " + digits.substring(5)
                // >10: groups of 3 for polish
                val sb = StringBuilder()
                var i = 0
                while (i < digits.length) {
                    val end = minOf(i + 3, digits.length)
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append(digits.substring(i, end))
                    i = end
                }
                return sb.toString()
            }
        })

        // Paste button - raw paste, no special formatting
        tilMobile.endIconScaleType = ImageView.ScaleType.CENTER
        tilMobile.setEndIconOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty()
            if (text.isNotBlank()) {
                etMobile.setText(text)
                etMobile.setSelection(etMobile.length())
            }
        }

        view.findViewById<View>(R.id.btnSelectContact).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            checkAndPickContact()
        }

        val args = arguments
        val personId = args?.getString(ARG_PERSON_ID)
        if (personId != null) {
            view.findViewById<TextView>(R.id.tvAddTitle).setText(R.string.edit_person)
            etName.setText(args.getString(ARG_NAME))
            etMobile.setText(args.getString(ARG_MOBILE))
        }
        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            save(personId)
        }

        // ---------- Join a shared person via code (6-box popup) ----------
        // Offline builds can't join - the button is hidden, not just dead.
        if (BuildConfig.OFFLINE_MODE) {
            view.findViewById<View>(R.id.btnJoin).visibility = View.GONE
        }
        view.findViewById<View>(R.id.btnJoin).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            JoinCodeSheet.newInstance().show(parentFragmentManager, JoinCodeSheet.TAG)
        }

        etName.requestFocus()
    }

    private fun checkAndPickContact() {
        val perm = Manifest.permission.READ_CONTACTS
        if (ContextCompat.checkSelfPermission(requireContext(), perm) == PackageManager.PERMISSION_GRANTED) {
            launchContactPicker()
        } else {
            requestPermissionLauncher.launch(perm)
        }
    }

    private fun launchContactPicker() {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_PICK,
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        )
        pickContactLauncher.launch(intent)
    }

    private fun save(personId: String?) {
        if (isSaving) return
        tilName.error = null
        tilMobile.error = null

        // Names are capped at 20 characters (field also enforces maxLength)
        val name = etName.text?.toString()?.trim().orEmpty().take(20)
        val rawMobile = etMobile.text?.toString()?.trim().orEmpty()

        var valid = true
        if (name.isEmpty()) {
            tilName.error = getString(R.string.name_required)
            shake(tilName)
            valid = false
        }
        // Mobile is optional - only validate if not empty, max 16 digits
        if (rawMobile.isNotEmpty()) {
            val digits = rawMobile.replace(Regex("\\D"), "")
            if (digits.length > 16) {
                tilMobile.error = getString(R.string.mobile_invalid)
                shake(tilMobile)
                valid = false
            } else if (digits.length < 7) {
                // Keep minimal check, but allow any if user wants very short? Use 7 as minimal for real numbers
                tilMobile.error = getString(R.string.mobile_invalid)
                shake(tilMobile)
                valid = false
            }
            // If starts with +, optionally validate country code exists - just accept any
        }
        if (!valid) return

        // New persons are always local: allowed offline. Only synced-person
        // edits touch the database.
        if (personId != null && ShareSync.isSynced(requireContext(), personId) &&
            !NetworkUtils.requireOnline(requireContext())
        ) return

        isSaving = true
        val fullMobile = rawMobile
        val ctx = requireContext()
        // Local-first storage
        try {
            val now = System.currentTimeMillis()
            if (personId != null) {
                LocalStore.updatePersonFields(
                    ctx, personId,
                    mapOf("name" to name, "mobile" to fullMobile, "updatedAt" to now)
                )
            } else {
                LocalStore.upsertPerson(
                    ctx, java.util.UUID.randomUUID().toString(),
                    mapOf(
                        "name" to name,
                        "mobile" to fullMobile,
                        "netAmount" to 0L,
                        "archived" to false,
                        "createdAt" to now,
                        "updatedAt" to now,
                        // Pin the current default: later default changes
                        // only affect upcoming new persons
                        "currency" to CurrencyManager.getSymbol(ctx)
                    )
                )
            }
            Toast.makeText(
                ctx,
                if (personId != null) R.string.person_updated else R.string.person_added,
                Toast.LENGTH_SHORT
            ).show()
            (requireActivity() as? MainActivity)?.let {
                it.refreshLocalData()
                // Edited person may be open: repaint its header instantly
                if (personId != null) it.refreshOpenPersonHeader(personId)
            }
            // An i-drawer may sit open underneath: repaint it live too
            if (personId != null) {
                try {
                    (parentFragmentManager.findFragmentByTag(NetBalanceSheet.TAG) as? NetBalanceSheet)
                        ?.refreshPersonInfo()
                } catch (_: Exception) {}
            }
            dismiss()
        } catch (_: Exception) {
            isSaving = false
            Toast.makeText(ctx, R.string.save_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shake(view: View) {
        ObjectAnimator.ofFloat(view, "translationX", 0f, -16f, 16f, -10f, 10f, -5f, 5f, 0f).apply {
            duration = 450
            interpolator = UiUtils.EASE_OUT
            start()
        }
    }

    companion object {
        private const val ARG_PERSON_ID = "personId"
        private const val ARG_NAME = "name"
        private const val ARG_MOBILE = "mobile"
        const val TAG = "AddPersonBottomSheet"

        fun newInstance(personId: String, name: String, mobile: String): AddPersonBottomSheet =
            AddPersonBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PERSON_ID, personId)
                    putString(ARG_NAME, name)
                    putString(ARG_MOBILE, mobile)
                }
            }
    }
}
