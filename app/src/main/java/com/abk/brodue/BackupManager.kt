package com.abk.brodue

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object BackupManager {

    const val FILE_EXT = ".brodue"
    // Legacy flat folder (still listed for auto-cleanup, no longer written to)
    const val FOLDER = "Download/BroDue/_backups"

    // Per-account folders: manual -> Download/BroDue/_backup/<email>/,
    // auto -> .../<email>/autobackup/
    fun accountDir(): String {
        val email = try {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email
        } catch (_: Exception) {
            null
        }
        return email?.trim()?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^a-zA-Z0-9@._-]"), "_")
            ?: "account"
    }

    fun manualFolder(): String = "Download/BroDue/_backup/${accountDir()}"
    fun autoFolder(): String = "${manualFolder()}/autobackup"

    // Retention eligibility: my account folder or the legacy flat folder.
    // Never another account's folder. The legacy flat folder is grandfathered.
    fun isEligibleAutoPath(relativePath: String?, account: String): Boolean {
        val path = relativePath.orEmpty()
        if (path.isBlank()) return false
        if (account.isNotBlank() && path.contains("/_backup/$account/")) return true
        return path.contains("BroDue/_backups")
    }
    private const val IV_SIZE = 12
    private val MAGIC = byteArrayOf(0x42, 0x52, 0x44, 0x55) // "BRDU"
    const val FLAG_ACCOUNT = 1
    const val FLAG_PIN = 2

    // Builds the key from the enabled encryption bases
    private fun cipherKey(flags: Int, pin: String?): ByteArray {
        val uid = UserDb.uid() ?: "anon"
        val md = MessageDigest.getInstance("SHA-256")
        val base = when (flags and (FLAG_ACCOUNT or FLAG_PIN)) {
            FLAG_ACCOUNT or FLAG_PIN -> "$uid::brodue-backup-v1::$pin"
            FLAG_ACCOUNT -> "$uid::brodue-backup-v1"
            FLAG_PIN -> "brodue-pin-v1::$pin"
            else -> "$uid::brodue-backup-v1"
        }
        return md.digest(base.toByteArray(Charsets.UTF_8))
    }

    private fun encryptBytes(flags: Int, pin: String?, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cipherKey(flags, pin), "AES"))
        val iv = cipher.iv
        val enc = cipher.doFinal(plain)
        return iv + enc
    }

    private fun decryptBytes(flags: Int, pin: String?, data: ByteArray): ByteArray? {
        return try {
            if (data.size <= IV_SIZE) return null
            val iv = data.copyOfRange(0, IV_SIZE)
            val enc = data.copyOfRange(IV_SIZE, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(cipherKey(flags, pin), "AES"),
                GCMParameterSpec(128, iv)
            )
            cipher.doFinal(enc)
        } catch (e: Exception) {
            null
        }
    }

    const val AUTO_PREFIX = "auto_"
    const val UPDATE_PREFIX = "update_protection_"
    // Update-protection backups live in the same autobackup folder but under
    // their own cap, so normal retention never eats them (and vice versa).
    const val UPDATE_PROTECTION_KEEP = 5
    private const val MANUAL_PREFIX = "backup_"
    private const val LEGACY_AUTO_PREFIX = "brodue-auto-"
    private const val TS_FORMAT = "yyyy-MM-dd_HH.mm.ss"

    // Takes the local data snapshot, encrypts it (account and/or PIN based),
    // saves as .brodue in the account's manual folder
    fun createBackup(context: Context, flags: Int, pin: String?, onDone: (Boolean, String?) -> Unit) {
        val name = MANUAL_PREFIX +
            SimpleDateFormat(TS_FORMAT, Locale.getDefault()).format(Date()) +
            FILE_EXT
        writeBackup(context, manualFolder(), flags, pin, name, onDone)
    }

    // Automatic backup: same vault, account's autobackup folder + distinct
    // name, so retention never touches manual backups (and vice versa).
    fun createAutoBackup(context: Context, flags: Int, pin: String?, onDone: (Boolean, String?) -> Unit) {
        val name = AUTO_PREFIX +
            SimpleDateFormat(TS_FORMAT, Locale.getDefault()).format(Date()) +
            FILE_EXT
        writeBackup(context, autoFolder(), flags, pin, name, onDone)
    }

    // Update-protection backup: pre-install safety copy in the autobackup
    // folder under its own name (own retention cap, see enforceAutoRetention).
    fun createUpdateProtectionBackup(context: Context, flags: Int, pin: String?, onDone: (Boolean, String?) -> Unit) {
        val name = UPDATE_PREFIX +
            SimpleDateFormat(TS_FORMAT, Locale.getDefault()).format(Date()) +
            FILE_EXT
        writeBackup(context, autoFolder(), flags, pin, name, onDone)
    }

    private fun writeBackup(
        context: Context,
        folder: String,
        flags: Int,
        pin: String?,
        name: String,
        onDone: (Boolean, String?) -> Unit
    ) {
        try {
            val value = try {
                JSONObject(LocalStore.rootJson(context))
            } catch (e: Exception) {
                null
            }
            if (value == null) {
                onDone(false, null)
                return
            }
            try {
                // Stamp the creator account (cross-account restores detect it)
                try {
                    UserDb.uid()?.takeIf { it.isNotBlank() }?.let { value.put("backupUid", it) }
                } catch (_: Exception) {}
                // Never backup the login profile (name/email/photoUrl goes with the account)
                value.optJSONObject("settings")?.remove("profile")
                val jsonPlain = value.toString(2).toByteArray(Charsets.UTF_8)
                val enc = encryptBytes(flags, pin, jsonPlain)
                val body = MAGIC + byteArrayOf(flags.toByte()) + enc
                // Verify what we wrote (in-memory: no MediaStore indexing race)
                val verified = try {
                    body.isValidVault() &&
                        decryptBytes(flags, pin, body.encBody())?.let {
                            JSONObject(String(it, Charsets.UTF_8)); true
                        } == true
                } catch (_: Exception) {
                    false
                }
                if (!verified) {
                    onDone(false, null)
                    return
                }
                val ok = writeToDownloads(context, folder, name, body)
                onDone(ok, if (ok) name else null)
            } catch (e: Exception) {
                onDone(false, null)
            }
        } catch (e: Exception) {
            onDone(false, null)
        }
    }

    data class AutoFile(val name: String, val uri: Uri? = null, val modified: Long, val size: Long = 0L, val account: String = "")

    // Pure helper (unit-tested): newest-first ordering, everything beyond
    // keep is condemned. Operates on one account folder at a time.
    fun selectCondemned(files: List<AutoFile>, keep: Int): List<AutoFile> =
        files.sortedWith(compareByDescending<AutoFile> { it.modified }.thenByDescending { it.name })
            .drop(keep.coerceAtLeast(0))

    // Every *.brodue file inside any <email>/autobackup/ folder (any prefix:
    // current auto_*, legacy brodue-auto-*, strays, partials). Manual folders
    // and non-.brodue files are never listed here.
    fun listAutoFolderFiles(context: Context): List<AutoFile> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val out = mutableListOf<AutoFile>()
                val collection =
                    android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val projection = arrayOf(
                    android.provider.MediaStore.MediaColumns._ID,
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                    android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
                    android.provider.MediaStore.MediaColumns.SIZE,
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH
                )
                context.contentResolver.query(
                    collection, projection,
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    arrayOf("%$FILE_EXT"), null
                )?.use { c ->
                    val idIdx = c.getColumnIndex(android.provider.MediaStore.MediaColumns._ID)
                    val nameIdx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                    val modIdx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
                    val sizeIdx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.SIZE)
                    val pathIdx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        val id = if (idIdx >= 0) c.getLong(idIdx) else continue
                        val name = if (nameIdx >= 0) c.getString(nameIdx) else continue
                        val relPath = if (pathIdx >= 0) c.getString(pathIdx) else null
                        val account = autoFolderAccount(relPath) ?: continue
                        val mod = if (modIdx >= 0) c.getLong(modIdx) * 1000 else 0L
                        val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                        out.add(
                            AutoFile(
                                name,
                                android.content.ContentUris.withAppendedId(collection, id),
                                mod,
                                size,
                                account
                            )
                        )
                    }
                }
                out
            } else {
                @Suppress("DEPRECATION")
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                @Suppress("DEPRECATION")
                val root = File(base, "BroDue/_backup")
                (root.listFiles { f -> f.isDirectory } ?: emptyArray()).flatMap { acctDir ->
                    val auto = File(acctDir, "autobackup")
                    auto.listFiles { f -> f.isFile && f.name.endsWith(FILE_EXT) }
                        ?.map { AutoFile(it.name, Uri.fromFile(it), it.lastModified(), it.length(), acctDir.name) }
                        ?: emptyList()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // "Download/BroDue/_backup/<account>/autobackup/" -> <account>, else null.
    // Manual folders (_backup/<account>/) and the legacy flat folder never match.
    private fun autoFolderAccount(relativePath: String?): String? {
        val path = relativePath.orEmpty()
        val marker = "/_backup/"
        val mi = path.indexOf(marker)
        if (mi < 0) return null
        val rest = path.substring(mi + marker.length)
        if (!rest.endsWith("/autobackup/") && !rest.endsWith("/autobackup")) return null
        val account = rest.substringBefore("/autobackup").trim('/').trim()
        return account.takeIf { it.isNotEmpty() }
    }

    // Pure helper (unit-tested): split update-protection files from the rest.
    fun partitionProtection(files: List<AutoFile>): Pair<List<AutoFile>, List<AutoFile>> {
        val protection = mutableListOf<AutoFile>()
        val rest = mutableListOf<AutoFile>()
        files.forEach { if (it.name.startsWith(UPDATE_PREFIX)) protection.add(it) else rest.add(it) }
        return rest to protection
    }

    // Strict enforcement: every account's autobackup folder holds at most
    // `keep` newest regular *.brodue files plus at most UPDATE_PROTECTION_KEEP
    // update-protection files; everything else found there is deleted.
    // Returns the number of files actually deleted. Never touches manual
    // folders or non-.brodue files.
    fun enforceAutoRetention(context: Context, keep: Int): Int {
        return try {
            var deleted = 0
            listAutoFolderFiles(context)
                .groupBy { it.account }
                .values
                .forEach { files ->
                    val (regular, protection) = partitionProtection(files)
                    (selectCondemned(regular, keep) + selectCondemned(protection, UPDATE_PROTECTION_KEEP))
                        .forEach {
                            if (deleteAutoBackup(context, it)) deleted++
                        }
                }
            deleted
        } catch (e: Exception) {
            0
        }
    }

    // Lists automatic backups only (manual ones never match the prefix).
    // Searches the per-account autobackup folder plus the legacy flat folder.
    fun listAutoBackups(context: Context): List<AutoFile> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val out = mutableListOf<AutoFile>()
                val collection =
                    android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val projection = arrayOf(
                    android.provider.MediaStore.MediaColumns._ID,
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                    android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
                    android.provider.MediaStore.MediaColumns.SIZE,
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH
                )
                // New auto_ names plus legacy brodue-auto- names (still pruned).
                // Path scoping happens client-side (OEM trailing-slash quirks).
                context.contentResolver.query(
                    collection, projection,
                    "(" + android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? OR " +
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?)",
                    arrayOf(
                        "$AUTO_PREFIX%$FILE_EXT",
                        "$LEGACY_AUTO_PREFIX%$FILE_EXT"
                    ), null
                )?.use { c ->
                    val idIdx = c.getColumnIndex(android.provider.MediaStore.MediaColumns._ID)
                    val nameIdx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                    val modIdx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
                    val sizeIdx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.SIZE)
                    val pathIdx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.RELATIVE_PATH)
                    val account = accountDir()
                    while (c.moveToNext()) {
                        val id = if (idIdx >= 0) c.getLong(idIdx) else continue
                        val name = if (nameIdx >= 0) c.getString(nameIdx) else continue
                        val relPath = if (pathIdx >= 0) c.getString(pathIdx) else null
                        if (!isEligibleAutoPath(relPath, account)) continue
                        val mod = if (modIdx >= 0) c.getLong(modIdx) * 1000 else 0L
                        val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                        out.add(
                            AutoFile(
                                name,
                                android.content.ContentUris.withAppendedId(collection, id),
                                mod,
                                size
                            )
                        )
                    }
                }
                out
            } else {
                @Suppress("DEPRECATION")
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dirs = listOf(
                    File(base, "BroDue/_backup/${accountDir()}/autobackup"),
                    File(base, "BroDue/_backups")
                )
                dirs.flatMap { dir ->
                    dir.listFiles { f ->
                        (f.name.startsWith(AUTO_PREFIX) || f.name.startsWith(LEGACY_AUTO_PREFIX)) &&
                            f.name.endsWith(FILE_EXT)
                    }
                        ?.map { AutoFile(it.name, Uri.fromFile(it), it.lastModified(), it.length()) }
                        ?: emptyList()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteAutoBackup(context: Context, file: AutoFile): Boolean {
        return try {
            val uri = file.uri ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.delete(uri, null, null) > 0
            } else if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() } == true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    // Full verification: magic + decrypt with the same key + JSON parse.
    fun verifyAutoBackup(context: Context, file: AutoFile, flags: Int, pin: String?): Boolean {
        return try {
            val uri = file.uri ?: return false
            val bytes = if (uri.scheme == "file") {
                uri.path?.let { File(it).takeIf { f -> f.exists() }?.readBytes() }
                    ?: return false
            } else {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return false
            }
            if (!bytes.isValidVault()) return false
            val plain = decryptBytes(flags, pin, bytes.encBody()) ?: return false
            JSONObject(String(plain, Charsets.UTF_8))
            true
        } catch (e: Exception) {
            false
        }
    }

    // Reads + decrypts an existing .brodue backup.
    // needsPin=true -> file is PIN protected, caller must ask for the PIN and
    // retry with loadWithPin()
    fun loadBackup(
        context: Context,
        uri: Uri,
        onDone: (data: Map<String, Any?>?, needsPin: Boolean, err: String?) -> Unit
    ) {
        try {
            val name = queryDisplayName(context, uri)
            if (name != null && !name.endsWith(FILE_EXT, ignoreCase = true)) {
                onDone(null, false, "Only .brodue backup files are supported")
                return
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                onDone(null, false, "Could not read file")
                return
            }
            if (!bytes.isValidVault()) {
                onDone(null, false, "Invalid or corrupted backup file")
                return
            }
            val flags = bytes[4].toInt() and 0xFF
            if (flags and FLAG_PIN != 0) {
                onDone(null, true, null)
                return
            }
            val plain = decryptBytes(flags, null, bytes.encBody())
            if (plain == null) {
                onDone(null, false, "This backup belongs to a different account")
                return
            }
            val json = JSONObject(String(plain, Charsets.UTF_8))
            onDone(jsonToMap(json), false, null)
        } catch (e: Exception) {
            onDone(null, false, "Invalid or corrupted backup file")
        }
    }

    // PIN-protected backup: decrypt after the user enters the correct PIN
    fun loadWithPin(
        context: Context,
        uri: Uri,
        pin: String,
        onDone: (data: Map<String, Any?>?, err: String?) -> Unit
    ) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || !bytes.isValidVault()) {
                onDone(null, "Invalid or corrupted backup file")
                return
            }
            val flags = bytes[4].toInt() and 0xFF
            val plain = decryptBytes(flags, pin, bytes.encBody())
            if (plain == null) {
                onDone(null, "Incorrect PIN")
                return
            }
            val json = JSONObject(String(plain, Charsets.UTF_8))
            onDone(jsonToMap(json), null)
        } catch (e: Exception) {
            onDone(null, "Invalid or corrupted backup file")
        }
    }

    private fun ByteArray.isValidVault(): Boolean =
        size > 5 + IV_SIZE && MAGIC.withIndex().all { (i, m) -> get(i) == m }

    // body = iv + ciphertext (skip magic(4) + flags(1))
    private fun ByteArray.encBody(): ByteArray = copyOfRange(5, size)

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) { null }
    }

    // Ruled restore:
    // - local-only persons, archived persons and settings/currency: normal.
    // - previously-synced persons: matched against the cloud by personId.
    //   Cloud has it    -> skip backup data (cloud refills via pull).
    //   Cloud missing   -> restore locally and mark leftGroup (+ reason),
    //                      so it behaves exactly like a left person.
    fun applyWithRules(context: Context, data: Map<String, Any?>, mode: String, onDone: (Boolean) -> Unit) {
        try {
            val root = JSONObject(data as Map<*, *>)
            val people = root.optJSONObject("people") ?: JSONObject()
            val txs = root.optJSONObject("transactions") ?: JSONObject()
            val settings = root.optJSONObject("settings") ?: JSONObject()
            val syncFlags = runCatching {
                JSONObject(settings.optString("sync_flags", ""))
            }.getOrNull() ?: JSONObject()
            val sharedInfo = runCatching {
                JSONObject(settings.optString("shared_info", ""))
            }.getOrNull() ?: JSONObject()
            val membersCache = runCatching {
                JSONObject(settings.optString("members_cache", ""))
            }.getOrNull()
            val me = try {
                UserDb.uid()
            } catch (_: Exception) {
                null
            }.orEmpty()
            val backupUid = root.optString("backupUid", "")
            val foreignBackup = backupUid.isNotBlank() && backupUid != me

            val checkIds = mutableListOf<String>()
            val pKeys = people.keys()
            while (pKeys.hasNext()) {
                val pid = pKeys.next()
                val p = people.optJSONObject(pid) ?: continue
                if (p.optBoolean("archived", false)) continue
                if (syncFlags.optBoolean(pid, false) ||
                    sharedInfo.optJSONObject(pid) != null ||
                    p.optString("dbOwner", "").isNotBlank()
                ) {
                    checkIds.add(pid)
                }
            }
            if (checkIds.isEmpty()) {
                finishRuledRestore(context, root, mode, onDone)
                return
            }
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val missing = mutableSetOf<String>()
            var remaining = checkIds.size
            fun onCheckDone() {
                remaining--
                if (remaining > 0) return
                try {
                    val now = System.currentTimeMillis()
                    // Cloud has it -> drop backup data (refilled by pull below)
                    val txIds = txs.keys()
                    val txToDrop = mutableListOf<String>()
                    while (txIds.hasNext()) {
                        val tid = txIds.next()
                        val t = txs.optJSONObject(tid) ?: continue
                        if (t.optString("personId", "") !in missing &&
                            checkIds.contains(t.optString("personId", ""))
                        ) {
                            txToDrop.add(tid)
                        }
                    }
                    txToDrop.forEach { txs.remove(it) }
                    checkIds.forEach { pid ->
                        if (pid in missing) {
                            // Cloud missing -> restore + mark like a left person
                            val p = people.optJSONObject(pid) ?: return@forEach
                            val role = sharedInfo.optJSONObject(pid)?.optString("role", "")
                            val dbOwner = p.optString("dbOwner", "")
                            val foreign = foreignBackup ||
                                (dbOwner.isNotBlank() && me.isNotBlank() && dbOwner != me)
                            p.put("leftGroup", true)
                            p.put(
                                "leftReason",
                                when {
                                    foreign -> "foreign"
                                    role == "owner" || dbOwner == me -> "disabled"
                                    else -> "removed"
                                }
                            )
                            p.put("leftAt", now)
                            syncFlags.remove(pid)
                            p.remove("dbOwner")
                            sharedInfo.remove(pid)
                            membersCache?.remove(pid)
                        } else {
                            people.remove(pid)
                        }
                    }
                    settings.put("sync_flags", syncFlags.toString())
                    settings.put("shared_info", sharedInfo.toString())
                    if (membersCache != null) settings.put("members_cache", membersCache.toString())
                    root.put("people", people)
                    root.put("transactions", txs)
                    root.put("settings", settings)
                } catch (_: Exception) {}
                finishRuledRestore(context, root, mode, onDone)
            }
            checkIds.forEach { pid ->
                try {
                    db.collection("people-shared").document(pid).get().timeout(ShareSync.T_ACTION)
                        .addOnSuccessListener { snap ->
                            if (!snap.exists()) missing.add(pid)
                            onCheckDone()
                        }
                        .addOnFailureListener {
                            // Fail-safe: restore locally (pulls converge once online)
                            missing.add(pid)
                            onCheckDone()
                        }
                } catch (_: Exception) {
                    missing.add(pid)
                    onCheckDone()
                }
            }
        } catch (_: Exception) {
            onDone(false)
        }
    }

    private fun finishRuledRestore(
        context: Context,
        root: JSONObject,
        mode: String,
        onDone: (Boolean) -> Unit
    ) {
        val apply: (Map<String, Any?>, (Boolean) -> Unit) -> Unit =
            if (mode == "merge") {
                { data, done -> applyMerge(context, data, done) }
            } else {
                { data, done -> applyReplace(context, data, done) }
            }
        try {
            apply(jsonToMap(root)) { ok ->
                if (!ok) {
                    onDone(false)
                    return@apply
                }
                // Refill cloud-skipped persons straight from the cloud
                try {
                    ShareSync.pullAllMine(context) { _, _ ->
                        try {
                            ShareSync.pullMyGrants(context) { onDone(true) }
                        } catch (_: Exception) {
                            onDone(true)
                        }
                    }
                } catch (_: Exception) {
                    onDone(true)
                }
            }
        } catch (_: Exception) {
            onDone(false)
        }
    }

    // REPLACE: overwrite the whole local data with the backup
    fun applyReplace(context: Context, data: Map<String, Any?>, onDone: (Boolean) -> Unit) {
        try {
            LocalStore.loadFromJson(context, JSONObject(data))
            onDone(true)
        } catch (e: Exception) { onDone(false) }
    }

    // MERGE: deep-merge backup into the existing local data
    // (backup wins on same keys, existing data not in the backup is kept)
    fun applyMerge(context: Context, data: Map<String, Any?>, onDone: (Boolean) -> Unit) {
        try {
            val current = JSONObject(LocalStore.rootJson(context))
            val currentMap = jsonToMap(current)
            val merged = deepMerge(currentMap, data)
            LocalStore.loadFromJson(context, JSONObject(merged as Map<*, *>))
            onDone(true)
        } catch (e: Exception) { onDone(false) }
    }

    // Keep the currently signed-in profile - backup never overrides name/email/photoUrl
    private fun sanitize(data: Any?): Map<String, Any?>? {
        val base = data as? Map<*, *> ?: return null
        @Suppress("UNCHECKED_CAST")
        val out = base.toMutableMap() as MutableMap<String, Any?>
        (out["settings"] as? MutableMap<String, Any?>)?.remove("profile")
        return out
    }

    private fun deepMerge(old: Any?, new: Any?): Any? {
        val oldMap = old as? Map<*, *>
        val newMap = new as? Map<*, *>
        if (oldMap != null && newMap != null) {
            val out = mutableMapOf<String, Any?>()
            (oldMap.keys + newMap.keys).forEach { key ->
                val k = key.toString()
                out[k] = deepMerge(oldMap[key], newMap[key])
            }
            return out
        }
        // New wins at leaves (or only one side has data)
        return new ?: old
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = convertValue(obj.opt(key))
        }
        return map
    }

    private fun convertValue(v: Any?): Any? = when (v) {
        is JSONObject -> jsonToMap(v)
        is JSONArray -> (0 until v.length()).map { convertValue(v.opt(it)) }
        else -> v
    }

    // folder is a Download-relative path (MediaStore) / subpath (pre-Q files)
    private fun writeToDownloads(context: Context, folder: String, name: String, bytes: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, folder)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = context.contentResolver.insert(collection, values) ?: return false
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
                    values.clear()
                    values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    true
                } catch (e: Exception) {
                    context.contentResolver.delete(uri, null, null)
                    false
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    folder.removePrefix("Download/")
                )
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, name)).use { it.write(bytes) }
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
