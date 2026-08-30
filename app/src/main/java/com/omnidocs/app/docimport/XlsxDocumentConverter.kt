package com.omnidocs.app.docimport

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/**
 * Converts Excel XLSX files to HTML tables using Apache POI.
 */
class XlsxDocumentConverter : DocumentConverter {

    override val supportedMimeTypes = listOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel"
    )

    companion object {
        private const val MAX_ROWS_PER_SHEET = 3000
        private const val MAX_COLS_PER_ROW = 100
    }

    override suspend fun convert(context: Context, uri: Uri, fileName: String): ConversionOutcome {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ConversionOutcome.Failure("Couldn't open \"$fileName\"")

            val title = fileName.substringBeforeLast('.')
            val htmlBuilder = StringBuilder()
            val plainBuilder = StringBuilder()

            inputStream.use { stream ->
                XSSFWorkbook(stream).use { workbook ->
                    for (sheetIdx in 0 until workbook.numberOfSheets) {
                        val sheet = workbook.getSheetAt(sheetIdx)
                        val sheetName = sheet.sheetName

                        if (workbook.numberOfSheets > 1) {
                            htmlBuilder.appendLine("<h2>${escapeHtml(sheetName)}</h2>")
                            plainBuilder.appendLine("=== $sheetName ===")
                        }

                        htmlBuilder.appendLine("<table>")

                        var rowCount = 0
                        for (row in sheet) {
                            if (rowCount >= MAX_ROWS_PER_SHEET) {
                                htmlBuilder.appendLine("<tr><td colspan=\"3\"><em>[Sheet truncated: exceeded $MAX_ROWS_PER_SHEET rows]</em></td></tr>")
                                plainBuilder.appendLine("[Sheet truncated: exceeded $MAX_ROWS_PER_SHEET rows]")
                                break
                            }

                            htmlBuilder.append("<tr>")
                            val cells = mutableListOf<String>()

                            val lastCol = row.lastCellNum.toInt().coerceAtMost(MAX_COLS_PER_ROW)
                            for (cellIdx in 0 until lastCol) {
                                val cell = row.getCell(cellIdx)
                                val value = getCellValue(cell)
                                htmlBuilder.append("<td>${escapeHtml(value)}</td>")
                                cells.add(value)
                            }

                            htmlBuilder.appendLine("</tr>")
                            plainBuilder.appendLine(cells.joinToString(" | "))
                            rowCount++
                        }

                        htmlBuilder.appendLine("</table>")
                        htmlBuilder.appendLine()
                    }
                }
            }

            val html = htmlBuilder.toString().trim()
            val plain = plainBuilder.toString().trim()

            if (html.isEmpty()) return ConversionOutcome.Failure("No text content found in \"$fileName\"")

            ConversionOutcome.Success(
                ConversionResult(
                    title = title,
                    htmlContent = html,
                    plainText = plain
                )
            )
        } catch (e: Exception) {
            ConversionOutcome.Failure("Couldn't import \"$fileName\": ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun getCellValue(cell: org.apache.poi.ss.usermodel.Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                val num = cell.numericCellValue
                if (num == num.toLong().toDouble()) num.toLong().toString() else num.toString()
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> try {
                cell.stringCellValue
            } catch (e: Exception) {
                try { cell.numericCellValue.toString() } catch (e2: Exception) { "" }
            }
            else -> ""
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
