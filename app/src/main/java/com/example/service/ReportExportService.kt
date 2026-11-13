package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.data.model.VoiceNote
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReportExportService(private val context: Context) {

    private val dateFormatter = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    private val fileTimestampFormatter = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())

    private fun exportDir(): File {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun exportPdfReport(
        notes: List<VoiceNote>,
        reportTitle: String
    ): Result<File> {
        return try {
            val file = File(exportDir(), "VoiceLedger_Report_${fileTimestampFormatter.format(Date())}.pdf")
            val document = PdfDocument()
            val pageWidth = 595
            val pageHeight = 842
            val margin = 40f
            val contentWidth = pageWidth - margin * 2

            val titlePaint = Paint().apply {
                color = Color.parseColor("#1A1C1E")
                textSize = 20f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val subtitlePaint = Paint().apply {
                color = Color.parseColor("#5A5D62")
                textSize = 10.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val headerPaint = Paint().apply {
                color = Color.parseColor("#C8553D")
                textSize = 13f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val bodyPaint = Paint().apply {
                color = Color.parseColor("#1A1C1E")
                textSize = 10.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val metaPaint = Paint().apply {
                color = Color.parseColor("#1F6E78")
                textSize = 9.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val dividerPaint = Paint().apply {
                color = Color.parseColor("#DAD3C8")
                strokeWidth = 1f
            }

            var pageNumber = 1
            var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            var canvas: Canvas = page.canvas
            var y = 48f

            // Report Header
            canvas.drawText("VoiceLedger Executive Report", margin, y, titlePaint)
            y += 18f
            canvas.drawText("$reportTitle • Generated ${dateFormatter.format(Date())}", margin, y, subtitlePaint)
            y += 16f

            val nowMs = System.currentTimeMillis()
            val openCount = notes.count { !it.isClosed }
            val closedCount = notes.count { it.isClosed }
            val agingCount = notes.count { !it.isClosed && it.computeAging(nowMs).days >= 3 }
            canvas.drawText(
                "Total: ${notes.size}   |   Open: $openCount   |   Aging (>=3d): $agingCount   |   Closed: $closedCount",
                margin,
                y,
                metaPaint
            )
            y += 14f
            canvas.drawLine(margin, y, pageWidth - margin, y, dividerPaint)
            y += 22f

            fun ensureSpace(neededHeight: Float) {
                if (y + neededHeight > pageHeight - margin) {
                    document.finishPage(page)
                    pageNumber++
                    page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                    canvas = page.canvas
                    y = 48f
                }
            }

            notes.forEachIndexed { idx, note ->
                val aging = note.computeAging(nowMs)
                val summaryLines = wrapText(
                    note.aiSummary.ifBlank { note.transcript }.ifBlank { "No transcript captured." },
                    bodyPaint,
                    contentWidth
                ).take(4)
                val actionLines = if (note.actionItem.isNotBlank()) {
                    wrapText("Action: ${note.actionItem}", bodyPaint, contentWidth).take(2)
                } else emptyList()
                val resLines = if (note.isClosed && note.resolutionNote.isNotBlank()) {
                    wrapText("Resolution (${note.resolvedByName.ifBlank { "Team" }}): ${note.resolutionNote}", bodyPaint, contentWidth).take(2)
                } else emptyList()

                val blockHeight = 56f + (summaryLines.size + actionLines.size + resLines.size) * 14f + 16f
                ensureSpace(blockHeight)

                canvas.drawText("${idx + 1}. ${note.title}", margin, y, headerPaint)
                y += 15f

                val dueText = if (note.dueAtMs > 0L) "Due: ${dateFormatter.format(Date(note.dueAtMs))}" else "No deadline"
                val statusText = "${note.category} • ${note.noteStatus.label} • Age: ${aging.label} • Priority: ${note.notePriority.label} • $dueText"
                canvas.drawText(statusText, margin, y, metaPaint)
                y += 14f

                if (note.tags.isNotEmpty()) {
                    canvas.drawText("Tags: ${note.tags.joinToString(", ")}", margin, y, subtitlePaint)
                    y += 13f
                }

                summaryLines.forEach { line ->
                    canvas.drawText(line, margin, y, bodyPaint)
                    y += 14f
                }
                actionLines.forEach { line ->
                    canvas.drawText(line, margin, y, bodyPaint)
                    y += 14f
                }
                resLines.forEach { line ->
                    canvas.drawText(line, margin, y, bodyPaint)
                    y += 14f
                }

                y += 6f
                canvas.drawLine(margin, y, pageWidth - margin, y, dividerPaint)
                y += 16f
            }

            document.finishPage(page)
            FileOutputStream(file).use { out ->
                document.writeTo(out)
            }
            document.close()
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun exportExcelReport(
        notes: List<VoiceNote>,
        reportTitle: String
    ): Result<File> {
        return try {
            val file = File(exportDir(), "VoiceLedger_Report_${fileTimestampFormatter.format(Date())}.xlsx")
            val bytes = buildXlsxBytes(notes, reportTitle)
            FileOutputStream(file).use { it.write(bytes) }
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun shareFile(file: File, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun buildXlsxBytes(notes: List<VoiceNote>, reportTitle: String): ByteArray {
        val nowMs = System.currentTimeMillis()
        val headers = listOf(
            "Title", "Category", "Status", "Priority", "Aging",
            "Action Item", "Resolution Note", "Tags", "Keywords",
            "Transcript", "Owner", "Assignee", "Created At", "Due Date", "Resolved By"
        )

        val rows = mutableListOf<List<String>>()
        rows.add(listOf("VoiceLedger Export — $reportTitle", "Generated ${dateFormatter.format(Date())}"))
        rows.add(headers)

        notes.forEach { note ->
            val aging = note.computeAging(nowMs)
            rows.add(
                listOf(
                    note.title,
                    note.category,
                    note.noteStatus.label,
                    note.notePriority.label,
                    aging.label,
                    note.actionItem,
                    note.resolutionNote,
                    note.tags.joinToString(", "),
                    note.keywords.joinToString(", "),
                    note.transcript,
                    note.ownerName,
                    note.assigneeName,
                    note.createdAt?.toDate()?.let { dateFormatter.format(it) } ?: "",
                    if (note.dueAtMs > 0L) dateFormatter.format(Date(note.dueAtMs)) else "",
                    note.resolvedByName
                )
            )
        }

        val sheetXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            append("""<sheetData>""")
            rows.forEachIndexed { rIdx, cols ->
                val rowNum = rIdx + 1
                val styleAttr = if (rIdx == 1) """ s="1"""" else ""
                append("""<row r="$rowNum">""")
                cols.forEachIndexed { cIdx, value ->
                    val cellRef = "${colLetter(cIdx)}$rowNum"
                    append("""<c r="$cellRef" t="inlineStr"$styleAttr><is><t>${escapeXml(value)}</t></is></c>""")
                }
                append("""</row>""")
            }
            append("""</sheetData></worksheet>""")
        }

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>""".trimIndent()

        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".trimIndent()

        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="VoiceLedger" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>""".trimIndent()

        val workbookRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""".trimIndent()

        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><sz val="11"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
  </fonts>
  <fills count="3">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFC8553D"/></patternFill></fill>
  </fills>
  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="2">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
  </cellXfs>
</styleSheet>""".trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            fun addEntry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            addEntry("[Content_Types].xml", contentTypes)
            addEntry("_rels/.rels", rels)
            addEntry("xl/workbook.xml", workbook)
            addEntry("xl/_rels/workbook.xml.rels", workbookRels)
            addEntry("xl/styles.xml", styles)
            addEntry("xl/worksheets/sheet1.xml", sheetXml)
        }
        return baos.toByteArray()
    }

    private fun colLetter(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + (n % 26)))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    private fun escapeXml(input: String): String {
        return buildString(input.length) {
            for (ch in input) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> if (ch.code >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') append(ch)
                }
            }
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.replace("\n", " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}
