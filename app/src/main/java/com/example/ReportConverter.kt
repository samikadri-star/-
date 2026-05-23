package com.example

import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFColor
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportConverter {

    // Common Arabic report titles and their normalized safe Arabic names for filenames.
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
     * Incorporates a dynamic timestamp to ensure unique saves without repeating the filename.
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

        val baseName = parts.joinToString("_")
            .replace(Regex("[\\\\/:*?\"<>|]"), "") // Sanitize filesystem chars
            .trim()
        
        val finalName = if (baseName.isEmpty()) "تقرير_مستخرج" else baseName
        
        // Append a precise unique timestamp (Hours, Minutes, Seconds) to prevent filename conflicts on subsequent saves.
        val timestampSuffix = SimpleDateFormat("HHmmss", Locale.ENGLISH).format(Date())
        return "${finalName}_$timestampSuffix"
    }

    /**
     * Parses strategy: Splits lines, preserving blank spacing and textual hierarchy perfectly.
     */
    fun parseWithSpaceSplitter(content: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        val lines = content.lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                // Preserve blank rows to maintain report aesthetics
                result.add(listOf(""))
                continue
            }
            
            // Keep division separators but label them for customized visual treatment
            if (trimmed.matches(Regex("^[-=*_+.\\s]+$"))) {
                result.add(listOf(trimmed))
                continue
            }

            // Detect metadata statements or titles to keep them centered and avoid slicing words
            val isSingleSentence = !line.contains("\t") && !trimmed.contains(Regex("\\s{2,}"))
            if (isSingleSentence) {
                result.add(listOf(trimmed))
                continue
            }
            
            val cells = if (trimmed.contains("\t")) {
                trimmed.split("\t").map { it.trim() }
            } else {
                trimmed.split(Regex("\\s{2,}")).map { it.trim() }
            }
            
            if (cells.isNotEmpty()) {
                result.add(cells)
            }
        }
        return result
    }

    /**
     * Advanced smart parsing strategy: Detects column boundaries dynamically by counting character presence.
     * Preserves paragraph titles, informational text cards, and blank rows perfectly.
     */
    fun parseWithSmartValleyDetector(content: String): List<List<String>> {
        val rawLines = content.lines()
        
        // Find columns based on lines that actually have tabular dividers
        val activeLines = rawLines.map { it.trimEnd() }.filter { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.matches(Regex("^[-=*_+.\\s]+$")) && (line.contains("\t") || trimmed.contains(Regex("\\s{2,}")))
        }
        
        if (activeLines.isEmpty()) {
            return parseWithSpaceSplitter(content)
        }
        
        val maxLen = activeLines.maxOf { it.length }
        if (maxLen == 0) return parseWithSpaceSplitter(content)

        // Count character presence
        val presenceCounts = IntArray(maxLen)
        for (line in activeLines) {
            for (i in line.indices) {
                if (line[i] != ' ' && line[i] != '\t') {
                    presenceCounts[i]++
                }
            }
        }

        // Trace boundary ranges
        val boundaries = mutableListOf<IntRange>()
        var inContent = false
        var startIdx = 0
        
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

        // Process all lines while preserving empty space and simple titles
        val parsedRows = mutableListOf<List<String>>()
        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                parsedRows.add(listOf("")) // Keep empty rows
                continue
            }
            if (trimmed.matches(Regex("^[-=*_+.\\s]+$"))) {
                parsedRows.add(listOf(trimmed)) // Keep dividers
                continue
            }

            // Single line non-tabular sentences (Metadata titles, credits, info banners)
            val isSingleSentence = !line.contains("\t") && !trimmed.contains(Regex("\\s{2,}"))
            if (isSingleSentence) {
                parsedRows.add(listOf(trimmed))
                continue
            }

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
            parsedRows.add(rowCells)
        }
        
        return parsedRows
    }

    /**
     * Generates a beautifully-designed, bold Excel workbook byte array.
     * Preserves the basic structure and layout of the report perfectly, styled matching "Editorial Aesthetic".
     */
    fun generateExcel(data: List<List<String>>): ByteArray {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("تقرير_مستخرج")

        sheet.setRightToLeft(true)

        // DEFINING PALETTE COLORS (Editorial Aesthetic)
        // Primary Purplebrand: XSSFColor with RGB #6750A4
        val primaryPurple = XSSFColor(java.awt.Color(103, 80, 164), null)
        // Deep Warm Violet for Text: RGB #21005D
        val deepWarmViolet = XSSFColor(java.awt.Color(33, 0, 93), null)
        // Alternating Soft Violet background: RGB #F3EDF7
        val softVioletStrip = XSSFColor(java.awt.Color(243, 237, 247), null)
        // Divider light gray: RGB #EADDFF
        val lightDividerColor = XSSFColor(java.awt.Color(234, 221, 255), null)

        // Fonts
        val headerFont = (workbook.createFont() as XSSFFont).apply {
            bold = true
            fontHeightInPoints = 11
            setColor(XSSFColor(java.awt.Color.WHITE, null))
        }

        val dataBoldFont = (workbook.createFont() as XSSFFont).apply {
            bold = true
            fontHeightInPoints = 10
            setColor(deepWarmViolet)
        }

        val titleFont = (workbook.createFont() as XSSFFont).apply {
            bold = true
            fontHeightInPoints = 12
            setColor(primaryPurple)
        }

        val faintFont = (workbook.createFont() as XSSFFont).apply {
            bold = false
            fontHeightInPoints = 9
            setColor(XSSFColor(java.awt.Color(100, 100, 110), null))
        }

        // Styles
        val headerStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            setFont(headerFont)
            setFillForegroundColor(primaryPurple)
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
        }

        val dataStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            setFont(dataBoldFont)
            alignment = HorizontalAlignment.GENERAL
            verticalAlignment = VerticalAlignment.CENTER
        }

        val alternateStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            setFont(dataBoldFont)
            setFillForegroundColor(softVioletStrip)
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.GENERAL
            verticalAlignment = VerticalAlignment.CENTER
        }

        val metadataStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            setFont(titleFont)
            alignment = HorizontalAlignment.RIGHT
            verticalAlignment = VerticalAlignment.CENTER
        }

        val dividerStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            setFont(faintFont)
            setFillForegroundColor(lightDividerColor)
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
        }

        var maxCols = 0
        var tableDataRowIndex = 0

        for ((rIdx, rowData) in data.withIndex()) {
            val row = sheet.createRow(rIdx)

            if (rowData.size > maxCols) {
                maxCols = rowData.size
            }

            // Recognize empty / separator / metadata rows
            val isBlank = rowData.isEmpty() || (rowData.size == 1 && rowData[0].isEmpty())
            val isDivider = rowData.size == 1 && rowData[0].trim().matches(Regex("^[-=*_+.\\s]+$"))
            val isMetadata = rowData.size == 1 && !isDivider && !isBlank
            val isHeader = (rIdx == 0)

            if (isBlank) {
                row.heightInPoints = 14f // Faint empty spacing height
                continue
            } else if (isDivider) {
                row.heightInPoints = 8f  // Custom neat height for decorative lines
                val cell = row.createCell(0)
                cell.setCellValue("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                cell.cellStyle = dividerStyle
                continue
            }

            row.heightInPoints = 26f // Comfortable padded rows

            // Standardize cells
            for ((cIdx, value) in rowData.withIndex()) {
                val cell = row.createCell(cIdx)
                cell.setCellValue(value)

                when {
                    isHeader -> {
                        cell.cellStyle = headerStyle
                    }
                    isMetadata -> {
                        cell.cellStyle = metadataStyle
                    }
                    else -> {
                        // Table row elements with alternating colors
                        cell.cellStyle = if (tableDataRowIndex % 2 == 1) alternateStyle else dataStyle
                    }
                }
            }

            // Only increment alternating zebra count on actual table rows
            if (!isHeader && !isMetadata && !isDivider && !isBlank) {
                tableDataRowIndex++
            }
        }

        // Adjust column widths gracefully (except 1st column)
        for (i in 0 until maxCols) {
            if (i == 0) {
                sheet.setColumnWidth(0, 16 * 256) // Perfect default padding
            } else {
                try {
                    sheet.autoSizeColumn(i)
                } catch (e: Exception) {
                    sheet.setColumnWidth(i, 18 * 256)
                }
            }
        }

        // Write to stream
        val outputStream = ByteArrayOutputStream()
        workbook.use { wb ->
            wb.write(outputStream)
        }
        return outputStream.toByteArray()
    }
}
