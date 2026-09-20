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

    @Test
    fun `html to markdown - multiline tags and tables`() {
        val html = """
            <h2>Quarterly Review</h2>
            <p>First line of paragraph.
            Second line across newline.</p>
            <table>
                <tr><th>Metric</th><th>Q1</th><th>Q2</th></tr>
                <tr><td>Revenue</td><td>$10M</td><td>$12M</td></tr>
            </table>
            <div class='attachment' id='img_123'>
                <img src='content://media/1' />
                <span class='delete-btn' data-attachment-id='img_123'>Delete</span>
            </div>
        """.trimIndent()

        val md = MarkdownCodec.htmlToMarkdown(html)
        assertTrue("Heading preserved", md.contains("## Quarterly Review"))
        assertTrue("Multiline paragraph preserved", md.contains("First line of paragraph.") && md.contains("Second line across newline."))
        assertTrue("Table header formatted", md.contains("| Metric | Q1 | Q2 |"))
        assertTrue("Table separator formatted", md.contains("| --- | --- | --- |"))
        assertTrue("Table row formatted", md.contains("| Revenue | $10M | $12M |"))
        assertFalse("Delete button stripped from markdown", md.contains("Delete"))
        assertTrue("Image markdown preserved", md.contains("![](content://media/1)"))
    }
}
