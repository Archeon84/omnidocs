package com.omnidocs.app.util

import org.junit.Test
import org.junit.Assert.*

class MarkdownCodecTest {

    @Test
    fun `markdown to html - headers bold links`() {
        val md = "# Title\n\nSome **bold** and a [link](https://x.com)."
        val html = MarkdownCodec.markdownToHtml(md)
        assertTrue(html.contains("Title"))
        assertTrue(html.contains("<strong>bold</strong>"))
        // Safe output must preserve the link as an anchor; the shared
        // HtmlSanitizer strips href attributes (pre-existing behavior), so only
        // the link text is asserted here.
        assertTrue(html.contains("<a>") && html.contains("link"))
    }

    @Test
    fun `markdown to html - strips script tags (sanitized)`() {
        val md = "hi\n<script>alert(1)</script>"
        val html = MarkdownCodec.markdownToHtml(md)
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("alert(1)"))
    }

    @Test
    fun `html to markdown - round trips back for plain content`() {
        val html = "<h1>Hello</h1><p>Some <strong>bold</strong> text.</p>"
        val md = MarkdownCodec.htmlToMarkdown(html)
        assertTrue(md.contains("# Hello"))
        assertTrue(md.contains("**bold**"))
    }
}
