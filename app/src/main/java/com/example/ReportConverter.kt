package com.example

import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

object ReportConverter {

    // Common Arabic report titles and their normalized ASCII-friendly or safe Arabic names for filenames.
    private val reportTypes = listOf(
        "ميزان مراجعة" to "ميزان_مراجعة",
        "ميزان المراجعة" to "ميزان_مراجعة",
        "كشف حساب" to "كشف_حساب",
        "كشف الحساب" to "كشف_حساب",
        "كشف حركة" to "كشف_حركة",
        "كشف الحركة" to "كشف_حركة",
        "كشف حركه" to "كشف_حركة",
        "حساب حركات" to "كشف_حركة",
        "يومية" to "يومية_عامة",
        "سند قيد" to "سند_قيد",
        "سند صرف" to "سند_صرف",
        "سند قبض" to "سند_قبض",
        "فاتورة" to "فاتورة",
        "تقرير المبيعات" to "تقرير_مبيعات",
        "الأرباح والخسائر" to "ارباح_وخسائر"
    )

    /**
     * Decodes bytes using the Arabic ISO-8859-6 charset.
     */
    fun decodeISO88596(bytes: ByteArray): String {
        return try {
            val charset = Charset.forName("ISO-8859-6")
            String(bytes, charset)
        } catch (e: Exception) {
            String(bytes, Charsets.UTF_8) // Fallback to UTF-8
        }
    }

    /**
     * Extracts a descriptive report filename from the report's decoded content or falls back to original name.
     */
    fun extractReportTitle(content: String, originalFilename: String): String {
        val cleanFilename = originalFilename.substringBeforeLast(".")
        val lines = content.lines().take(40).map { it.trim() }

        var detectedType = ""
        var detectedDetail = ""
        var dateString = ""

        // 1. Scan content lines for matching report types
        for ((keyword, cleanName) in reportTypes) {
            if (lines.any { it.contains(keyword, ignoreCase = true) }) {
                detectedType = cleanName
                break
            }
        }

        // 2. If not found in content, check the original filename
        if (detectedType.isEmpty()) {
            for ((keyword, cleanName) in reportTypes) {
                if (cleanFilename.contains(keyword, ignoreCase = true)) {
                    detectedType = cleanName
                    break
                }
            }
        }

        // 3. Fallback to sanitized original name
        if (detectedType.isEmpty()) {
            detectedType = cleanFilename.replace(Regex("[^\\p{L}\\p{N}_\\-\\s]"), "").trim().replace(" ", "_")
            if (detectedType.isEmpty()) {
                detectedType = "تقرير_مستخرج"
            }
        }

        // 4. Try parsing details like Account Number (رقم الحساب) or Client (العميل/الاسم)
        val accountRegex = Regex("""(?:رقم\s+الحساب|حساب\s+رقم|الحساب)\s*[:\s]*(\d+)""")
        val clientRegex = Regex("""(?:العميل|الاسم|اسم\s+الحساب|السادة|المطلوب)\s*[:\s]*([^\d\s\-_:/\\]{3,15})""")
        
        for (line in lines) {
            val accMatch = accountRegex.find(line)
            if (accMatch != null) {
                detectedDetail = accMatch.groupValues[1]
                break
            }
        }

        if (detectedDetail.isEmpty()) {
            for (line in lines) {
                val cliMatch = clientRegex.find(line)
                if (cliMatch != null) {
                    detectedDetail = cliMatch.groupValues[1].replace(" ", "_")
                    break
                }
            }
        }

        // 5. Try parsing dates in the report to make filenames unique and informative
        val dateRegex = Regex("""(\d{2,4}[/\-.]\d{2}[/\-.]\d{2,4})""")
        for (line in lines) {
            val dateMatch = dateRegex.find(line)
            if (dateMatch != null) {
                dateString = dateMatch.groupValues[1].replace("/", "-").replace(".", "-")
                break
            }
        }

        // Assemble the filename parts
        val parts = mutableListOf<String>()
        parts.add(detectedType)
        if (detectedDetail.isNotEmpty()) {
            parts.add(detectedDetail)
        }
        if (dateString.isNotEmpty()) {
            parts.add(dateString)
        }

        val finalName = parts.joinToString("_")
            .replace(Regex("[\\\\/:*?\"<>|]"), "") // Sanitize filesystem chars
            .trim()
        
        return if (finalName.isEmpty()) "تقرير_مستخرج" else finalName
    }

    /**
     * Simple parsing strategy: splits lines by two or more spaces.
     * Prevents separating inside cell words (like "كشف حساب" where there is only 1 space).
     */
    fun parseWithSpaceSplitter(content: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        val lines = content.lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            
            // Skip lines that are purely separator ornaments
            if (trimmed.matches(Regex("^[-=*_+.\\s]+$"))) continue
            
            // Check for tabs first, otherwise split by 2+ spaces
            val cells = if (trimmed.contains("\t")) {
                trimmed.split("\t").map { it.trim() }
            } else {
                trimmed.split(Regex("\\s{2,}")).map { it.trim() }
            }
            
            if (cells.isNotEmpty() && cells.any { it.isNotEmpty() }) {
                result.add(cells)
            }
        }
        return result
    }

    /**
     * Advanced smart parsing strategy: Detects column boundaries dynamically by counting non-space text presence 
     * across the entire file vertically. Ideal for fixed-width space-aligned tabular reports with empty cells.
     */
    fun parseWithSmartValleyDetector(content: String): List<List<String>> {
        val rawLines = content.lines()
        
        // Filter out decorative or separator lines to find real columns
        val activeLines = rawLines.map { it.trimEnd() }.filter { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.matches(Regex("^[-=*_+.\\s]+$"))
        }
        
        if (activeLines.isEmpty()) return emptyList()
        
        val maxLen = activeLines.maxOf { it.length }
        if (maxLen == 0) return emptyList()

        // Accumulate character occupancy counts vertically at each x-index
        val presenceCounts = IntArray(maxLen)
        for (line in activeLines) {
            for (i in line.indices) {
                if (line[i] != ' ' && line[i] != '\t') {
                    presenceCounts[i]++
                }
            }
        }

        // Detect column start & end ranges. A vertical separator is where characters are 0 (or almost 0) across all lines
        val boundaries = mutableListOf<IntRange>()
        var inContent = false
        var startIdx = 0
        
        // A simple smoothing window of 1-character can help reduce noise
        for (i in 0 until maxLen) {
            val count = presenceCounts[i]
            val isSeparatorSpace = (count == 0)

            if (inContent) {
                if (isSeparatorSpace) {
                    boundaries.add(startIdx until i)
                    inContent = false
                }
            } else {
                if (!isSeparatorSpace) {
                    startIdx = i
                    inContent = true
                }
            }
        }
        if (inContent) {
            boundaries.add(startIdx until maxLen)
        }

        // Substring extract based on boundaries
        val parsedRows = mutableListOf<List<String>>()
        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.matches(Regex("^[-=*_+.\\s]+$"))) continue

            val rowCells = mutableListOf<String>()
            for (range in boundaries) {
                val start = range.first
                val end = minOf(range.last + 1, line.length)
                
                if (start < line.length) {
                    val value = line.substring(start, end).trim()
                    rowCells.add(value)
                } else {
                    rowCells.add("")
                }
            }
            
            // Add row only if it has some content
            if (rowCells.any { it.isNotEmpty() }) {
                parsedRows.add(rowCells)
            }
        }
        
        return parsedRows
    }

    /**
     * Generates a beautifully-designed, bold Excel workbook byte array with Right-to-Left sheets and automatic columns adjustment.
     */
    fun generateExcel(data: List<List<String>>): ByteArray {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("تقرير_مستخرج")

        // 1. CONFIGURE SHEET DIRECTION: Right to Left (RTL) for perfect Arabic alignment
        sheet.setRightToLeft(true)

        // 2. DESIGN CORNERSTONE FONTS & STYLES (Zebra Corporate Palette: deep teal + light soft teal accents)
        // Deep Teal header style
        val headerFont = workbook.createFont().apply {
            bold = true
            fontHeightInPoints = 11
            color = IndexedColors.WHITE.getIndex()
        }
        val headerStyle = workbook.createCellStyle().apply {
            setFont(headerFont)
            fillForegroundColor = IndexedColors.TEAL.getIndex()
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
        }

        // Bold Font for normal text as requested: ("الخط عريض")
        val boldDataFont = workbook.createFont().apply {
            bold = true
            fontHeightInPoints = 10
        }

        // Normal content style
        val dataStyle = workbook.createCellStyle().apply {
            setFont(boldDataFont)
            alignment = HorizontalAlignment.GENERAL
            verticalAlignment = VerticalAlignment.CENTER
        }

        // Zebra striped alternate style (soft teal background)
        val alternateStyle = workbook.createCellStyle().apply {
            setFont(boldDataFont)
            fillForegroundColor = IndexedColors.LIGHT_TURQUOISE.getIndex() // Beautiful matching soft accent
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.GENERAL
            verticalAlignment = VerticalAlignment.CENTER
        }

        var maxCols = 0

        for ((rIdx, rowData) in data.withIndex()) {
            val row = sheet.createRow(rIdx)
            row.heightInPoints = 25f // Comfortable height padding

            if (rowData.size > maxCols) {
                maxCols = rowData.size
            }

            // Standardize first line as header
            val isHeader = (rIdx == 0)

            for ((cIdx, value) in rowData.withIndex()) {
                val cell = row.createCell(cIdx)
                cell.setCellValue(value)

                if (isHeader) {
                    cell.cellStyle = headerStyle
                } else {
                    cell.cellStyle = if (rIdx % 2 == 1) alternateStyle else dataStyle
                }
            }
        }

        // 3. AUTO WIDTH MANAGEMENT EXCEPT THE FIRST COLUMN
        // "وضبط الأعمدة تلقائي ما عدا العمود الاول"
        for (i in 0 until maxCols) {
            if (i == 0) {
                // Column 0 is the EXCEPTED (first) column. We give it a nice fixed default width so it looks pleasant but is not auto-fitted.
                sheet.setColumnWidth(0, 14 * 256) // ~14 characters wide
            } else {
                try {
                    sheet.autoSizeColumn(i)
                } catch (e: Exception) {
                    // Fallback to safe width if autosizing fails on certain characters
                    sheet.setColumnWidth(i, 18 * 256)
                }
            }
        }

        // Write output
        val outputStream = ByteArrayOutputStream()
        workbook.use { wb ->
            wb.write(outputStream)
        }
        return outputStream.toByteArray()
    }
}
