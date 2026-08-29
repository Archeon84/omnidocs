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

    override suspend fun convert(context: Context, uri: Uri, fileName: String): ConversionOutcome {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ConversionOutcome.Failure("Couldn't open \"$fileName\"")
            val workbook = XSSFWorkbook(inputStream)

            val title = fileName.substringBeforeLast('.')
            val htmlBuilder = StringBuilder()
            val plainBuilder = StringBuilder()

            for (sheetIdx in 0 until workbook.numberOfSheets) {
                val sheet = workbook.getSheetAt(sheetIdx)
                val sheetName = sheet.sheetName

                if (workbook.numberOfSheets > 1) {
                    htmlBuilder.appendLine("<h2>${escapeHtml(sheetName)}</h2>")
                    plainBuilder.appendLine("=== $sheetName ===")
                }

                htmlBuilder.appendLine("<table>")

                for (row in sheet) {
                    htmlBuilder.append("<tr>")
                    val cells = mutableListOf<String>()

                    for (cellIdx in 0 until row.lastCellNum) {
                        val cell = row.getCell(cellIdx)
                        val value = getCellValue(cell)
                        htmlBuilder.append("<td>${escapeHtml(value)}</td>")
                        cells.add(value)
                    }

                    htmlBuilder.appendLine("</tr>")
                    plainBuilder.appendLine(cells.joinToString(" | "))
                }

                htmlBuilder.appendLine("</table>")
                htmlBuilder.appendLine()
            }

            workbook.close()

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
