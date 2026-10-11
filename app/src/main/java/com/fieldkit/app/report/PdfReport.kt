package com.fieldkit.app.report

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.OutputStream

/**
 * Renders the report text (see [ReportText] for its markup) plus spectrogram
 * snapshots into an A4 PDF using the platform PdfDocument. No library needed.
 */
object PdfReport {
    private const val PAGE_W = 595 // A4 in PostScript points
    private const val PAGE_H = 842
    private const val MARGIN = 42f
    private const val BOTTOM = PAGE_H - 48f

    fun write(out: OutputStream, reportText: String, images: List<Pair<String, File>>) {
        val titlePaint = paint(17f, bold = true)
        val headingPaint = paint(12.5f, bold = true)
        val bodyPaint = paint(9.5f)
        val tablePaint = paint(7.6f).apply { typeface = Typeface.MONOSPACE }
        val footerPaint = paint(8f).apply { color = Color.GRAY }
        val maxWidth = PAGE_W - 2 * MARGIN

        val doc = PdfDocument()
        var page: PdfDocument.Page? = null
        var pageNumber = 0
        var y = 0f

        fun finishPage() {
            val current = page ?: return
            current.canvas.drawText("Field Kit report  -  page $pageNumber", MARGIN, PAGE_H - 26f, footerPaint)
            doc.finishPage(current)
            page = null
        }

        fun newPage() {
            finishPage()
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            y = MARGIN + 8f
        }

        fun ensureRoom(height: Float) {
            if (page == null || y + height > BOTTOM) newPage()
        }

        fun drawWrapped(text: String, p: Paint, indent: Float = 0f) {
            val lineHeight = p.textSize * 1.38f
            for (piece in wrap(text, p, maxWidth - indent)) {
                ensureRoom(lineHeight)
                page!!.canvas.drawText(piece, MARGIN + indent, y, p)
                y += lineHeight
            }
        }

        try {
            newPage()
            for (raw in reportText.lines()) {
                when {
                    raw.isBlank() -> y += 5f
                    raw.startsWith("# ") -> {
                        y += 6f
                        drawWrapped(raw.removePrefix("# "), titlePaint)
                        y += 4f
                    }
                    raw.startsWith("## ") -> {
                        ensureRoom(headingPaint.textSize * 3.5f) // keep a heading with its first lines
                        y += 8f
                        drawWrapped(raw.removePrefix("## "), headingPaint)
                        y += 2f
                    }
                    raw.startsWith("| ") -> drawWrapped(raw.removePrefix("| "), tablePaint)
                    else -> {
                        // Preserve leading indentation of detail lines.
                        val indentChars = raw.length - raw.trimStart().length
                        drawWrapped(raw.trimStart(), bodyPaint, indentChars * 3.2f)
                    }
                }
            }

            for ((caption, file) in images) {
                val bitmap = try {
                    BitmapFactory.decodeFile(file.absolutePath)
                } catch (e: Exception) {
                    null
                } ?: continue
                val drawWidth = maxWidth
                val drawHeight = drawWidth * bitmap.height / bitmap.width
                ensureRoom(drawHeight + 34f)
                y += 10f
                drawWrapped(caption, bodyPaint)
                y += 2f
                page!!.canvas.drawBitmap(bitmap, null, RectF(MARGIN, y, MARGIN + drawWidth, y + drawHeight), null)
                y += drawHeight + 8f
                bitmap.recycle()
            }

            finishPage()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun paint(size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Greedy word wrap using the paint's own text measurement. */
    private fun wrap(text: String, p: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            val fits = p.breakText(rest, true, maxWidth, null)
            if (fits >= rest.length) {
                lines.add(rest)
                break
            }
            var cut = rest.lastIndexOf(' ', fits)
            if (cut <= 0) cut = maxOf(1, fits) // one very long word: hard break
            lines.add(rest.substring(0, cut))
            rest = rest.substring(cut).trimStart()
        }
        return lines
    }
}
