package com.abk.brodue

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

// Lightweight markdown for GitHub release notes: headings, bold, italic,
// bullet/numbered lists, inline code, quotes. Parsing is pure (unit-tested);
// only span application touches Android classes.
object ReleaseNotes {

    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false
    )

    sealed interface Block {
        data class Heading(val level: Int, val runs: List<Run>) : Block
        data class Para(val runs: List<Run>) : Block
        data class Bullet(val runs: List<Run>) : Block
        data class Numbered(val text: String) : Block
        data class Quote(val runs: List<Run>) : Block
        data class Code(val text: String) : Block
        object Rule : Block
    }

    // ---- pure parsing ----

    fun inlineRuns(text: String): List<Run> {
        val runs = mutableListOf<Run>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                runs.add(Run(plain.toString()))
                plain.clear()
            }
        }
        var i = 0
        val n = text.length
        while (i < n) {
            // `code` (protected first)
            if (text[i] == '`') {
                val e = text.indexOf('`', i + 1)
                if (e > i + 1) {
                    flush()
                    runs.add(Run(text.substring(i + 1, e), code = true))
                    i = e + 1
                    continue
                }
            }
            // [text](url) -> text
            if (text[i] == '[') {
                val mid = text.indexOf("](", i + 1)
                if (mid > i + 1) {
                    val e = text.indexOf(')', mid + 2)
                    if (e > mid + 2) {
                        plain.append(text.substring(i + 1, mid))
                        i = e + 1
                        continue
                    }
                }
            }
            // **bold**
            if (i + 1 < n && text[i] == '*' && text[i + 1] == '*') {
                val e = text.indexOf("**", i + 2)
                if (e > i + 2) {
                    flush()
                    runs.add(Run(text.substring(i + 2, e), bold = true))
                    i = e + 2
                    continue
                }
            }
            // *italic* or _italic_
            val c = text[i]
            if (c == '*' || c == '_') {
                val e = text.indexOf(c, i + 1)
                if (e > i + 1) {
                    flush()
                    runs.add(Run(text.substring(i + 1, e), italic = true))
                    i = e + 1
                    continue
                }
            }
            plain.append(c)
            i++
        }
        flush()
        return runs
    }

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var inCodeBlock = false
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }
            if (inCodeBlock) {
                if (line.isNotBlank()) blocks.add(Block.Code(line))
                continue
            }
            val t = line.trim()
            if (t.isEmpty()) continue
            when {
                t.startsWith("### ") -> blocks.add(Block.Heading(3, inlineRuns(t.removePrefix("### ").trim())))
                t.startsWith("## ") -> blocks.add(Block.Heading(2, inlineRuns(t.removePrefix("## ").trim())))
                t.startsWith("# ") -> blocks.add(Block.Heading(1, inlineRuns(t.removePrefix("# ").trim())))
                t == "---" || t == "***" -> blocks.add(Block.Rule)
                t.startsWith("> ") -> blocks.add(Block.Quote(inlineRuns(t.removePrefix("> ").trim())))
                t.matches(Regex("^\\d+[.)]\\s+.*")) -> blocks.add(Block.Numbered(t))
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") ->
                    blocks.add(Block.Bullet(inlineRuns(t.substring(2).trim())))
                else -> blocks.add(Block.Para(inlineRuns(t)))
            }
        }
        return blocks
    }

    // ---- span application ----

    fun render(markdown: String, navy: Int, secondary: Int): CharSequence {
        val sb = SpannableStringBuilder()
        val blocks = parse(markdown)
        blocks.forEachIndexed { index, block ->
            if (index > 0) sb.append("\n")
            when (block) {
                is Block.Heading -> {
                    val start = sb.length
                    appendRuns(sb, block.runs)
                    val size = when (block.level) {
                        1 -> 1.45f
                        2 -> 1.32f
                        else -> 1.18f
                    }
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(size), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(navy), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Block.Para -> appendRuns(sb, block.runs)
                is Block.Bullet -> {
                    val start = sb.length
                    appendRuns(sb, block.runs)
                    sb.setSpan(BulletSpan(24, secondary), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Block.Numbered -> {
                    val start = sb.length
                    sb.append(block.text)
                    sb.setSpan(LeadingMarginSpan.Standard(48, 0), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Block.Quote -> {
                    val start = sb.length
                    appendRuns(sb, block.runs)
                    sb.setSpan(ForegroundColorSpan(secondary), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Block.Code -> {
                    val start = sb.length
                    sb.append(block.text)
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(secondary), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                Block.Rule -> {
                    val start = sb.length
                    sb.append("────────────────")
                    sb.setSpan(ForegroundColorSpan(secondary), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return sb
    }

    private fun appendRuns(sb: SpannableStringBuilder, runs: List<Run>) {
        runs.forEach { run ->
            if (run.text.isEmpty()) return@forEach
            val start = sb.length
            sb.append(run.text)
            if (run.bold) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (run.italic) {
                sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (run.code) {
                sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
