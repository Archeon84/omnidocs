package com.omnidocs.app.util

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/**
 * HTML sanitizer using Jsoup to prevent XSS in WebView content.
 * Allows only safe tags and attributes for rich text editing.
 */
object HtmlSanitizer {

    // Safelist for rich text content - allows formatting but blocks scripts/events
    private val RICH_TEXT_SAFELIST = Safelist.none()
        .addTags(
            "p", "br", "b", "strong", "i", "em", "u", "strike", "del",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li",
            "blockquote", "cite",
            "code", "pre",
            "hr",
            "table", "thead", "tbody", "tr", "th", "td",
            "div", "span",
            "img", "audio", "source",
            "a"
        )
        .addAttributes(
            "p", "style", "class", "dir",
            "div", "style", "class", "id", "contenteditable", "dir", "data-attachment-id",
            "span", "style", "class", "data-attachment-id", "role", "tabindex", "dir",
            "blockquote", "style", "class", "dir",
            "code", "style", "class",
            "pre", "style", "class",
            "h1", "style", "class",
            "h2", "style", "class",
            "h3", "style", "class",
            "h4", "style", "class",
            "h5", "style", "class",
            "h6", "style", "class",
            "ul", "style", "class",
            "ol", "style", "class",
            "li", "style", "class",
            "table", "style", "class",
            "thead", "style", "class",
            "tbody", "style", "class",
            "tr", "style", "class",
            "th", "style", "class", "colspan", "rowspan",
            "td", "style", "class", "colspan", "rowspan",
            "img", "src", "alt", "style", "class", "width", "height",
            "audio", "controls", "src", "style", "class",
            "source", "src", "type",
            "a", "href", "style", "class",
            "hr", "style", "class"
        )
        .addProtocols("a", "href", "http", "https", "mailto", "tel")
        .addProtocols("img", "src", "http", "https", "data", "content")
        .addProtocols("audio", "src", "http", "https", "content")
        .addProtocols("source", "src", "http", "https", "content")
        .preserveRelativeLinks(true)

    // Minimal safelist for plain text with basic formatting
    private val PLAIN_TEXT_SAFELIST = Safelist.simpleText()
        .addTags("p", "br", "b", "strong", "i", "em", "u")

    /**
     * Sanitize HTML content for safe display in WebView.
     * Removes all scripts, event handlers, and dangerous attributes.
     */
    fun sanitize(html: String?): String {
        if (html.isNullOrEmpty()) return ""
        return Jsoup.clean(html, RICH_TEXT_SAFELIST)
    }

    /**
     * Sanitize HTML with minimal tags for plain text display.
     */
    fun sanitizePlain(html: String?): String {
        if (html.isNullOrEmpty()) return ""
        return Jsoup.clean(html, PLAIN_TEXT_SAFELIST)
    }

    /**
     * Check if HTML contains potentially dangerous content.
     * Returns true if sanitization would modify the content.
     */
    fun hasDangerousContent(html: String?): Boolean {
        if (html.isNullOrEmpty()) return false
        val sanitized = Jsoup.clean(html, RICH_TEXT_SAFELIST)
        return sanitized != html
    }

    /**
     * Extract plain text from HTML (strips all tags).
     */
    fun toPlainText(html: String?): String {
        if (html.isNullOrEmpty()) return ""
        return Jsoup.parse(html).text()
    }
}

/**
 * Escape HTML special characters for safe embedding in HTML content.
 * Use when building HTML from user-provided plain text.
 */
fun sanitizeForHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;")
}