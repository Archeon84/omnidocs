package com.omnidocs.app.ocr

import com.google.mlkit.vision.text.Text

fun visionTextToHtml(visionText: Text): String {
    val sb = StringBuilder()
    for (block in visionText.textBlocks) {
        for (line in block.lines) {
            val lineText = line.text.trim()
            if (lineText.isNotEmpty()) {
                sb.appendLine("<p>$lineText</p>")
            }
        }
    }
    return sb.toString().trim()
}
