package com.abk.brodue

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object StatementExporter {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class StatementData(
        val personName: String,
        val mobile: String,
        val net: Long,
        val transactions: List<Transaction>,
        val showNotes: Boolean = false,
        val fromLastZero: Boolean = false
    )

    private const val W = 1080
    private const val PAD = 80
    private const val CONTENT_W = W - PAD * 2
    private const val SN_W = 80
    private const val AMOUNT_W = 300
    private val MID_W = CONTENT_W - SN_W - AMOUNT_W
    private const val CARD_RADIUS = 32f

    private sealed class Item {
        class Brand(val wordmark: String, val date: String) : Item()
        class Name(val text: String) : Item()
        class Mobile(val text: String) : Item()
        class NetCard(val label: String, val text: String, val color: Int) : Item()
        class TableHeader(val sn: String, val left: String, val right: String) : Item()
        class Divider : Item()
        class Entry(
            val sn: String,
            val date: String,
            val line: String,
            val amount: String,
            val amountColor: Int
        ) : Item()
        class Footer(val text: String, val color: Int) : Item()
    }

    private class Paints(
        val brand: TextPaint,
        val date: TextPaint,
        val name: TextPaint,
        val mobile: TextPaint,
        val cardLabel: TextPaint,
        val cardAmount: TextPaint,
        val cardFill: Paint,
        val header: TextPaint,
        val entryDate: TextPaint,
        val line: TextPaint,
        val amount: TextPaint,
        val footer: TextPaint,
        val divider: Paint
    )

    fun shareImage(context: Context, data: StatementData) {
        val file = writeImage(context, data) ?: return
        share(context, file, "image/png")
    }

    fun sharePdf(context: Context, data: StatementData) {
        val file = writePdf(context, data) ?: return
        share(context, file, "application/pdf")
    }

    fun shareImageToWhatsApp(context: Context, data: StatementData) {
        val file = writeImage(context, data) ?: return
        shareToWhatsApp(context, file, "image/png", data.mobile)
    }

    fun sharePdfToWhatsApp(context: Context, data: StatementData) {
        val file = writePdf(context, data) ?: return
        shareToWhatsApp(context, file, "application/pdf", data.mobile)
    }

    fun shareAsync(context: Context, data: StatementData, asPdf: Boolean, whatsApp: Boolean) {
        executor.execute {
            val file = if (asPdf) writePdf(context, data) else writeImage(context, data)
            mainHandler.post {
                if (file == null) return@post
                val mime = if (asPdf) "application/pdf" else "image/png"
                if (whatsApp) shareToWhatsApp(context, file, mime, data.mobile)
                else share(context, file, mime)
            }
        }
    }

    // Save into PUBLIC storage: Download/BroDue/JPG or Download/BroDue/PDF
    // (MediaStore on API 29+, public Downloads dir on older devices)
    fun saveAsync(context: Context, data: StatementData, asPdf: Boolean, onDone: (Boolean) -> Unit) {
        executor.execute {
            val ok = if (asPdf) savePdf(context, data, folder = "PDF") else saveImage(context, data, folder = "JPG")
            mainHandler.post { onDone(ok) }
        }
    }

    private fun saveImage(context: Context, data: StatementData, folder: String): Boolean {
        val paints = paints(context)
        val items = items(context, data)
        val height = measureHeight(paints, items)
        val bitmap = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        render(canvas, paints, items)
        val ok = openOutputStreamForSave(context, data, folder, "png", "image/png") { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return ok
    }

    private fun savePdf(context: Context, data: StatementData, folder: String): Boolean {
        val ok = openOutputStreamForSave(context, data, folder, "pdf", "application/pdf") { out ->
            val paints = paints(context)
            val items = items(context, data)
            val height = measureHeight(paints, items)
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(W, height, 1).create())
                render(page.canvas, paints, items)
                document.finishPage(page)
                document.writeTo(out)
            } finally {
                document.close()
            }
        }
        return ok
    }

    private fun openOutputStreamForSave(
        context: Context,
        data: StatementData,
        folder: String,
        ext: String,
        mime: String,
        write: (OutputStream) -> Unit
    ): Boolean {
        val name = fileName(context, data.personName, ext)
        val relPath = "Download/BroDue/$folder"
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = context.contentResolver.insert(collection, values) ?: return false
                try {
                    context.contentResolver.openOutputStream(uri)?.use { write(it) } ?: return false
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
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BroDue/$folder")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { write(it) }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun shareToWhatsApp(context: Context, file: File, mime: String, mobile: String) {
        val number = waNumber(mobile)
        if (number.isNotBlank()) {
            try {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://api.whatsapp.com/send?phone=$number")
                )
                intent.setPackage("com.whatsapp")
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number"))
                    )
                } catch (_: Exception) {
                }
                return
            }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.whatsapp")
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(intent).apply { setPackage(null) }
            context.startActivity(Intent.createChooser(fallback, context.getString(R.string.share)))
        }
    }

    private fun waNumber(mobile: String): String {
        val digits = mobile.replace(Regex("\\D"), "")
        return when {
            digits.length == 10 -> "91$digits"
            digits.length == 12 && digits.startsWith("91") -> digits
            digits.length == 11 && digits.startsWith("0") -> "91" + digits.drop(1)
            else -> digits
        }
    }

    private fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
    }

    private fun writeImage(context: Context, data: StatementData): File? {
        val paints = paints(context)
        val items = items(context, data)
        val height = measureHeight(paints, items)
        val bitmap = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        render(canvas, paints, items)
        return try {
            val file = File(exportDir(context), fileName(context, data.personName, "png"))
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            file
        } catch (_: Exception) {
            null
        }
    }

    private fun writePdf(context: Context, data: StatementData): File? {
        val paints = paints(context)
        val items = items(context, data)
        val height = measureHeight(paints, items)
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(W, height, 1).create())
            render(page.canvas, paints, items)
            document.finishPage(page)
            val file = File(exportDir(context), fileName(context, data.personName, "pdf"))
            FileOutputStream(file).use { out -> document.writeTo(out) }
            return file
        } catch (_: Exception) {
            return null
        } finally {
            document.close()
        }
    }

    private fun exportDir(context: Context): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    private fun fileName(context: Context, personName: String, ext: String): String {
        val safe = personName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "statement" }
        val date = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
        return "$safe - $date.$ext"
    }

    private fun dateStr(): String =
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())

    private fun items(context: Context, data: StatementData): List<Item> {
        val date = dateStr()
        val result = mutableListOf<Item>()
        result.add(Item.Brand(context.getString(R.string.app_name).uppercase(), date))
        result.add(Item.Name(data.personName))
        result.add(
            Item.NetCard(
                label = context.getString(R.string.net_balance).uppercase(),
                text = (if (data.net > 0) "+" + Formatters.amount(data.net) else Formatters.amount(data.net)).toString(),
                color = if (data.net > 0) color(context, R.color.positive) else if (data.net < 0) color(context, R.color.negative) else color(context, R.color.navy_text)
            )
        )
        result.add(
            Item.TableHeader(
                sn = "S.N.",
                left = context.getString(R.string.date_category),
                right = context.getString(R.string.amount).uppercase()
            )
        )
        result.add(Item.Divider())
        val sorted = filterTransactions(data.transactions, data.fromLastZero)
        if (sorted.isEmpty()) {
            result.add(Item.Entry("", "—", "", "", color(context, R.color.grey_soft)))
        } else {
            sorted.forEachIndexed { i, tx ->
                val isReceived = tx.type == "received"
                val amountColor = if (isReceived) color(context, R.color.positive) else color(context, R.color.negative)
                val sign = if (isReceived) "+" else "-"
                val note = if (data.showNotes) tx.note.trim().ifBlank { null } else null
                val line = if (note != null) tx.category + " · " + note else tx.category
                result.add(
                    Item.Entry(
                        sn = "${i + 1}.",
                        date = Formatters.date(tx.createdAt),
                        line = line,
                        amount = (sign + Formatters.amount(tx.amount)).toString(),
                        amountColor = amountColor
                    )
                )
                if (i < sorted.lastIndex) result.add(Item.Divider())
            }
        }
        result.add(Item.Footer(context.getString(R.string.app_name), color(context, R.color.primary)))
        return result
    }

    private fun filterTransactions(txs: List<Transaction>, fromLastZero: Boolean): List<Transaction> {
        val chronological = txs.sortedWith(compareBy<Transaction> { it.createdAt }.thenBy { it.id })
        if (!fromLastZero) return chronological.asReversed()
        var running = 0L
        var lastZero = -1
        chronological.forEachIndexed { i, tx ->
            running += if (tx.type == "received") tx.amount else -tx.amount
            if (running == 0L) lastZero = i
        }
        return chronological.drop(lastZero + 1).asReversed()
    }

    private fun paints(context: Context): Paints {
        val navy = color(context, R.color.navy_text)
        val grey = color(context, R.color.grey_soft)
        val primary = color(context, R.color.primary)
        val cardFill = color(context, R.color.net_card_bg)
        fun paint(size: Float, style: Int, color: Int, letterSpacing: Float = 0f): TextPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                typeface = Typeface.create(Typeface.DEFAULT, style)
                this.color = color
                this.letterSpacing = letterSpacing
            }
        return Paints(
            brand = paint(34f, Typeface.BOLD, primary, 0.2f),
            date = paint(28f, Typeface.NORMAL, grey),
            name = paint(80f, Typeface.BOLD, navy),
            mobile = paint(36f, Typeface.NORMAL, grey),
            cardLabel = paint(30f, Typeface.BOLD, primary, 0.2f),
            cardAmount = paint(108f, Typeface.BOLD, navy),
            cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardFill },
            header = paint(28f, Typeface.BOLD, grey),
            entryDate = paint(40f, Typeface.BOLD, navy),
            line = paint(30f, Typeface.NORMAL, grey),
            amount = paint(44f, Typeface.BOLD, navy),
            footer = paint(28f, Typeface.BOLD, primary),
            divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = color(context, R.color.border_light)
                strokeWidth = 2f
            }
        )
    }

    private fun color(context: Context, res: Int): Int = ContextCompat.getColor(context, res)

    private fun lineHeight(paint: TextPaint): Int {
        val fm = paint.fontMetrics
        return (fm.descent - fm.ascent).toInt() + 1
    }

    private fun wrappedHeight(paint: TextPaint, text: String, width: Int): Int {
        if (text.isEmpty()) return 0
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build().height
    }

    private const val CARD_PAD_V = 48
    private const val CARD_GAP = 8

    private fun cardHeight(paints: Paints): Int =
        CARD_PAD_V + lineHeight(paints.cardLabel) + CARD_GAP + lineHeight(paints.cardAmount) + CARD_PAD_V

    private fun measureHeight(paints: Paints, items: List<Item>): Int {
        var total = PAD
        items.forEach { item ->
            total += when (item) {
                is Item.Brand -> lineHeight(paints.brand) + 72
                is Item.Name -> wrappedHeight(paints.name, item.text, CONTENT_W) + 8
                is Item.Mobile -> if (item.text.isEmpty()) 0 else lineHeight(paints.mobile) + 6
                is Item.NetCard -> cardHeight(paints) + 64
                is Item.TableHeader -> lineHeight(paints.header) + 12
                is Item.Divider -> 2 + 26
                is Item.Entry ->
                    lineHeight(paints.entryDate) +
                        wrappedHeight(paints.line, item.line, MID_W) +
                        36
                is Item.Footer -> 2 + 24 + lineHeight(paints.footer) + 16
            }
        }
        return total + PAD
    }

    private fun render(canvas: Canvas, paints: Paints, items: List<Item>) {
        canvas.drawColor(Color.WHITE)
        var y = PAD
        items.forEach { item ->
            when (item) {
                is Item.Brand -> {
                    val base = y - paints.brand.fontMetrics.ascent
                    canvas.drawText(item.wordmark, PAD.toFloat(), base, paints.brand)
                    paints.date.textAlign = Paint.Align.RIGHT
                    canvas.drawText(item.date, (W - PAD).toFloat(), base, paints.date)
                    paints.date.textAlign = Paint.Align.LEFT
                    y += lineHeight(paints.brand) + 72
                }
                is Item.Name -> {
                    paints.name.textAlign = Paint.Align.CENTER
                    canvas.drawText(item.text, W / 2f, y - paints.name.fontMetrics.ascent, paints.name)
                    paints.name.textAlign = Paint.Align.LEFT
                    y += wrappedHeight(paints.name, item.text, CONTENT_W) + 8
                }
                is Item.Mobile -> {
                    if (item.text.isNotEmpty()) {
                        paints.mobile.textAlign = Paint.Align.CENTER
                        canvas.drawText(item.text, W / 2f, y - paints.mobile.fontMetrics.ascent, paints.mobile)
                        paints.mobile.textAlign = Paint.Align.LEFT
                        y += lineHeight(paints.mobile) + 6
                    }
                }
                is Item.NetCard -> {
                    val cardH = cardHeight(paints)
                    val rect = RectF(PAD.toFloat(), y.toFloat(), (W - PAD).toFloat(), (y + cardH).toFloat())
                    canvas.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, paints.cardFill)
                    paints.cardLabel.textAlign = Paint.Align.CENTER
                    val labelBase = y + CARD_PAD_V - paints.cardLabel.fontMetrics.ascent
                    canvas.drawText(item.label, W / 2f, labelBase, paints.cardLabel)
                    paints.cardLabel.textAlign = Paint.Align.LEFT
                    paints.cardAmount.color = item.color
                    paints.cardAmount.textAlign = Paint.Align.CENTER
                    val amountBase = y + CARD_PAD_V + lineHeight(paints.cardLabel) + CARD_GAP - paints.cardAmount.fontMetrics.ascent
                    canvas.drawText(item.text, W / 2f, amountBase, paints.cardAmount)
                    paints.cardAmount.textAlign = Paint.Align.LEFT
                    y += cardH + 64
                }
                                is Item.TableHeader -> {
                    paints.header.textAlign = Paint.Align.CENTER
                    canvas.drawText(item.sn, (PAD + SN_W / 2).toFloat(), y - paints.header.fontMetrics.ascent, paints.header)
                    paints.header.textAlign = Paint.Align.LEFT
                    canvas.drawText(item.left, (PAD + SN_W).toFloat(), y - paints.header.fontMetrics.ascent, paints.header)
                    paints.header.textAlign = Paint.Align.RIGHT
                    canvas.drawText(item.right, (W - PAD).toFloat(), y - paints.header.fontMetrics.ascent, paints.header)
                    paints.header.textAlign = Paint.Align.LEFT
                    y += lineHeight(paints.header) + 12
                }
                is Item.Divider -> {
                    canvas.drawLine(PAD.toFloat(), y.toFloat(), (W - PAD).toFloat(), y.toFloat(), paints.divider)
                    y += 2 + 26
                }
                is Item.Entry -> {
                    val xText = PAD + SN_W
                    val contentH = lineHeight(paints.entryDate) + wrappedHeight(paints.line, item.line, MID_W)
                    val centerY = y + contentH / 2f
                    if (item.sn.isNotEmpty()) {
                        paints.line.textAlign = Paint.Align.CENTER
                        val snBase = centerY - (paints.line.fontMetrics.ascent + paints.line.fontMetrics.descent) / 2f
                        canvas.drawText(item.sn, (PAD + SN_W / 2).toFloat(), snBase, paints.line)
                        paints.line.textAlign = Paint.Align.LEFT
                    }
                    if (item.amount.isNotEmpty()) {
                        paints.amount.color = item.amountColor
                        paints.amount.textAlign = Paint.Align.RIGHT
                        val amountBase = centerY - (paints.amount.fontMetrics.ascent + paints.amount.fontMetrics.descent) / 2f
                        canvas.drawText(item.amount, (W - PAD).toFloat(), amountBase, paints.amount)
                        paints.amount.textAlign = Paint.Align.LEFT
                    }
                    val base1 = y - paints.entryDate.fontMetrics.ascent
                    canvas.drawText(item.date, xText.toFloat(), base1, paints.entryDate)
                    y += lineHeight(paints.entryDate)
                    drawWrapped(canvas, item.line, paints.line, MID_W, xText, y)
                    y += wrappedHeight(paints.line, item.line, MID_W)
                    y += 36
                }
                is Item.Footer -> {
                    canvas.drawLine(PAD.toFloat(), y.toFloat(), (W - PAD).toFloat(), y.toFloat(), paints.divider)
                    y += 2 + 24
                    paints.footer.color = item.color
                    paints.footer.textAlign = Paint.Align.CENTER
                    canvas.drawText(item.text, W / 2f, y - paints.footer.fontMetrics.ascent, paints.footer)
                    paints.footer.textAlign = Paint.Align.LEFT
                    y += lineHeight(paints.footer) + 16
                }
            }
        }
    }

    private fun drawWrapped(canvas: Canvas, text: String, paint: TextPaint, width: Int, x: Int, y: Int) {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build()
        canvas.save()
        canvas.translate(x.toFloat(), y.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }
}