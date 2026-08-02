package com.omnidocs.app.ocr

/**
 * Deduplicated HTML generation for OCR results.
 * Replaces visionTextToHtml() in OcrUtils.kt and inline HTML builders in OcrViewModel.
 */
object OcrHtmlBuilder {

    /**
     * Grouping strategy for plain text conversion.
     */
    enum class Grouping {
        /** One <p> per line */
        LINE,
        /** Merge consecutive non-empty lines into paragraphs, separated by blank lines */
        PARAGRAPH
    }

    /**
     * Generate HTML from an OcrResult. Uses the pre-built HTML from the result.
     */
    fun fromOcrResult(result: OcrResult): String = result.html

    /**
     * Generate HTML from plain text with the specified grouping.
     *
     * @param text Plain text to convert
     * @param grouping How to group lines into paragraphs
     * @return HTML string with <p> tags
     */
    fun fromPlainText(text: String, grouping: Grouping = Grouping.PARAGRAPH): String {
        if (text.isBlank()) return ""

        return when (grouping) {
            Grouping.LINE -> {
                text.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n") { "<p>$it</p>" }
            }
            Grouping.PARAGRAPH -> {
                val paragraphs = mutableListOf<MutableList<String>>()
                var current = mutableListOf<String>()

                for (line in text.lines()) {
                    if (line.isBlank()) {
                        if (current.isNotEmpty()) {
                            paragraphs.add(current)
                            current = mutableListOf()
                        }
                    } else {
                        current.add(line.trim())
                    }
                }
                if (current.isNotEmpty()) paragraphs.add(current)

                paragraphs.joinToString("\n") { "<p>${it.joinToString(" ")}</p>" }
            }
        }
    }

    /**
     * Generate HTML from a list of OcrBlocks (for live camera results).
     *
     * @param blocks List of OcrBlock with text and confidence
     * @return HTML string with <p> tags
     */
    fun fromBlocks(blocks: List<OcrBlock>): String {
        return blocks
            .filter { it.text.isNotBlank() }
            .joinToString("\n") { "<p>${it.text.trim()}</p>" }
    }
}
