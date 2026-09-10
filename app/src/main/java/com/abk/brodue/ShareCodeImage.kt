package com.abk.brodue

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

// Builds + shares the "share code" image: app name/logo, date, person
// name, QR of the ACTIVE joining code with the code under it.
object ShareCodeImage {

    private const val W = 1080

    fun makeQrBitmap(content: String, size: Int): Bitmap? {
        if (content.isBlank()) return null
        return try {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: Exception) { null }
    }

    private fun textPaint(color: Int, size: Float, bold: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun ellipsize(paint: Paint, text: String, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var cut = text.length
        var t = text
        while (cut > 1 && paint.measureText("$t…") > maxWidth) {
            cut--
            t = text.substring(0, cut)
        }
        return "$t…"
    }

    private fun centerY(top: Float, height: Float, paint: Paint): Float =
        top + height / 2f - (paint.descent() + paint.ascent()) / 2f

    // Full share image. Returns null on failure.
    fun build(ctx: Context, personName: String, joinLink: String, code: String): Bitmap? {
        try {
            val navy = Color.parseColor("#2A3342")
            val grey = Color.parseColor("#6A7085")
            val primary = Color.parseColor("#5B6BA3")
            val primarySoft = Color.parseColor("#DEE2F6")
            val border = Color.parseColor("#E2E6EE")
            val gradTop = Color.WHITE
            val gradBottom = Color.parseColor("#E7EBF7")

            val pad = 72f
            val topPad = 72f
            val tile = 160f
            val logoSize = 118f
            val cardPad = 64f
            val qrSize = (W - pad * 2 - cardPad * 2).toInt()
            val pillH = 112f

            val appPaint = textPaint(navy, 60f, true)
            val datePaint = textPaint(grey, 38f, false)
            val namePaint = textPaint(navy, 78f, true)
            val codePaint = textPaint(navy, 64f, true).apply { letterSpacing = 0.3f }
            val hintPaint = textPaint(grey, 36f, false).apply { textAlign = Paint.Align.CENTER }

            // Adaptive/volume-safe logo: render the drawable, not the resource bytes
            val logo = runCatching {
                val d = AppCompatResources.getDrawable(ctx, R.mipmap.ic_launcher) ?: return@runCatching null
                val s = 256
                d.setBounds(0, 0, s, s)
                val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                d.draw(Canvas(bmp))
                bmp
            }.getOrNull()

            val name = ellipsize(namePaint, personName.ifBlank { "–" }, W - pad * 2)
            val date = Formatters.date(System.currentTimeMillis())
            val appName = ctx.getString(R.string.app_name)
            val hint = ctx.getString(R.string.share_image_hint)

            // ---- layout ----
            val nameTop = topPad + tile + 56f
            val nameH = 110f
            val cardTop = nameTop + nameH + 56f
            val qrTop = cardTop + cardPad
            val pillTop = qrTop + qrSize + 48f
            val cardH = cardPad + qrSize + 48f + pillH + cardPad
            val hintTop = cardTop + cardH + 44f
            val hintH = 56f
            val h = (hintTop + hintH + 88f).toInt()

            val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            // gradient background
            val bgPaint = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(), gradTop, gradBottom, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, W.toFloat(), h.toFloat(), bgPaint)

            // ---- header: logo tile + app name (left), date pill (right) ----
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                setShadowLayer(26f, 0f, 12f, Color.argb(38, 42, 51, 66))
            }
            val tileRect = RectF(pad, topPad, pad + tile, topPad + tile)
            canvas.drawRoundRect(tileRect, 40f, 40f, shadow)
            logo?.let {
                val scaled = Bitmap.createScaledBitmap(it, logoSize.toInt(), logoSize.toInt(), true)
                canvas.drawBitmap(scaled, pad + (tile - logoSize) / 2f, topPad + (tile - logoSize) / 2f, null)
            }
            canvas.drawText(
                appName, pad + tile + 32f,
                centerY(topPad, tile, appPaint), appPaint
            )
            // date pill
            val dateW = datePaint.measureText(date)
            val pillPadH = 30f
            val pillPadV = 16f
            val dateH = 38f + pillPadV * 2
            val dateTop = topPad + (tile - dateH) / 2f
            val dateRect = RectF(W - pad - dateW - pillPadH * 2, dateTop, W - pad, dateTop + dateH)
            val dateFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRoundRect(dateRect, dateH / 2f, dateH / 2f, dateFill)
            val dateStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = border
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawRoundRect(dateRect, dateH / 2f, dateH / 2f, dateStroke)
            canvas.drawText(date, W - pad - pillPadH, centerY(dateTop, dateH, datePaint), datePaint.apply {
                textAlign = Paint.Align.RIGHT
            })

            // ---- person name ----
            canvas.drawText(name, pad, centerY(nameTop, nameH, namePaint), namePaint)

            // ---- QR card ----
            val cardRect = RectF(pad, cardTop, W - pad, cardTop + cardH)
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                setShadowLayer(30f, 0f, 14f, Color.argb(42, 42, 51, 66))
            }
            canvas.drawRoundRect(cardRect, 56f, 56f, cardPaint)
            val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = border
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRoundRect(cardRect, 56f, 56f, cardStroke)
            val qr = makeQrBitmap(joinLink, qrSize) ?: return null
            canvas.drawBitmap(qr, (W - qrSize) / 2f, qrTop, null)

            // code pill (tinted) centered in card
            val codeW = codePaint.measureText(code)
            val codePillW = codeW + 96f
            val codeRect = RectF(
                (W - codePillW) / 2f, pillTop,
                (W + codePillW) / 2f, pillTop + pillH
            )
            val codeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primarySoft }
            canvas.drawRoundRect(codeRect, pillH / 2f, pillH / 2f, codeFill)
            val codeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = primary
                style = Paint.Style.STROKE
                strokeWidth = 2f
                alpha = 90
            }
            canvas.drawRoundRect(codeRect, pillH / 2f, pillH / 2f, codeStroke)
            canvas.drawText(code, W / 2f, centerY(pillTop, pillH, codePaint.apply {
                textAlign = Paint.Align.CENTER
            }), codePaint)

            // ---- hint ----
            canvas.drawText(hint, W / 2f, centerY(hintTop, hintH, hintPaint), hintPaint)
            return bmp
        } catch (_: Exception) {
            return null
        }
    }

    fun share(ctx: Context, bmp: Bitmap): Boolean {
        return try {
            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "share_code.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, null))
            true
        } catch (_: Exception) {
            false
        }
    }
}
