package com.omnidocs.app.util

object HtmlToMarkdown {
    fun convert(html: String): String {
        var md = html
        // Headers
        md = md.replace(Regex("<h1[^>]*>(.*?)</h1>"), "# $1\n")
        md = md.replace(Regex("<h2[^>]*>(.*?)</h2>"), "## $1\n")
        md = md.replace(Regex("<h3[^>]*>(.*?)</h3>"), "### $1\n")
        // Bold and italic
        md = md.replace(Regex("<strong[^>]*>(.*?)</strong>"), "**$1**")
        md = md.replace(Regex("<b[^>]*>(.*?)</b>"), "**$1**")
        md = md.replace(Regex("<em[^>]*>(.*?)</em>"), "*$1*")
        md = md.replace(Regex("<i[^>]*>(.*?)</i>"), "*$1*")
        // Lists
        md = md.replace(Regex("<li[^>]*>(.*?)</li>"), "- $1\n")
        md = md.replace(Regex("<ul[^>]*>|</ul>"), "")
        md = md.replace(Regex("<ol[^>]*>|</ol>"), "")
        // Links and images
        md = md.replace(Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>"), "[$2]($1)")
        md = md.replace(Regex("<img[^>]*src=\"([^\"]+)\"[^>]*/?>"), "![]($1)")
        // Blockquotes
        md = md.replace(Regex("<blockquote[^>]*>(.*?)</blockquote>"), "> $1\n")
        // Code
        md = md.replace(Regex("<code[^>]*>(.*?)</code>"), "`$1`")
        md = md.replace(Regex("<pre[^>]*>(.*?)</pre>"), "```\n$1\n```")
        // Horizontal rule
        md = md.replace(Regex("<hr[^>]*/?>"), "\n---\n")
        // Line breaks and paragraphs
        md = md.replace(Regex("<br\\s*/?>"), "\n")
        md = md.replace(Regex("<p[^>]*>(.*?)</p>"), "$1\n\n")
        // Remove remaining HTML tags
        md = md.replace(Regex("<[^>]+>"), "")
        // Clean up whitespace
        md = md.replace(Regex("\n{3,}"), "\n\n")
        return md.trim()
    }
}