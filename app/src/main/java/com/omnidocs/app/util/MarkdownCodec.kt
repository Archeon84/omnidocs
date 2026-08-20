package com.omnidocs.app.util

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser

/**
 * Single conversion point between markdown source and rich HTML.
 * markdownToHtml always sanitizes its output so it is safe to inject
 * into a WebView. htmlToMarkdown delegates to the existing regex-based
 * HtmlToMarkdown (used by export); it is inherently lossy for complex
 * embeds, which the editor's snapshot guard protects against.
 */
object MarkdownCodec {
    private val parser: Parser = Parser.builder().build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().build()

    fun htmlToMarkdown(html: String): String =
        HtmlToMarkdown.convert(html)

    fun markdownToHtml(md: String): String {
        if (md.isBlank()) return ""
        return try {
            val doc = parser.parse(md)
            HtmlSanitizer.sanitize(renderer.render(doc))
        } catch (_: Exception) {
            // Never let a parse failure escape; fall back to escaped plain text.
            sanitizeForHtml(md)
        }
    }
}
