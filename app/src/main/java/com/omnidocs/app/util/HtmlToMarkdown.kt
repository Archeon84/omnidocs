package com.omnidocs.app.util

object HtmlToMarkdown {
    fun convert(html: String): String {
        var md = html
        val dotAll = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)

        // Clean attachment delete buttons before converting
        md = md.replace(Regex("<(button|span)[^>]*class=[\"'][^\"']*delete-btn[^\"']*[\"'][^>]*>.*?</\\1>", dotAll), "")

        // Tables
        md = md.replace(Regex("<table[^>]*>(.*?)</table>", dotAll)) { tableMatch ->
            val tableContent = tableMatch.groupValues[1]
            val rows = Regex("<tr[^>]*>(.*?)</tr>", dotAll).findAll(tableContent).toList()
            if (rows.isEmpty()) return@replace ""

            val sb = StringBuilder("\n")
            var isFirstRow = true
            var colCount = 0

            for (row in rows) {
                val rowHtml = row.groupValues[1]
                val ths = Regex("<th[^>]*>(.*?)</th>", dotAll).findAll(rowHtml).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.toList()
                val tds = Regex("<td[^>]*>(.*?)</td>", dotAll).findAll(rowHtml).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.toList()

                val cells = if (ths.isNotEmpty()) ths else tds
                if (cells.isNotEmpty()) {
                    if (isFirstRow) {
                        colCount = cells.size
                        sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
                        sb.append("| ").append((1..colCount).joinToString(" | ") { "---" }).append(" |\n")
                        isFirstRow = false
                    } else {
                        sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
                    }
                }
            }
            sb.append("\n")
            sb.toString()
        }

        // Headers (h1 - h6)
        md = md.replace(Regex("<h1[^>]*>(.*?)</h1>", dotAll), "# $1\n")
        md = md.replace(Regex("<h2[^>]*>(.*?)</h2>", dotAll), "## $1\n")
        md = md.replace(Regex("<h3[^>]*>(.*?)</h3>", dotAll), "### $1\n")
        md = md.replace(Regex("<h4[^>]*>(.*?)</h4>", dotAll), "#### $1\n")
        md = md.replace(Regex("<h5[^>]*>(.*?)</h5>", dotAll), "##### $1\n")
        md = md.replace(Regex("<h6[^>]*>(.*?)</h6>", dotAll), "###### $1\n")

        // Bold and italic
        md = md.replace(Regex("<(strong|b)[^>]*>(.*?)</\\1>", dotAll), "**$2**")
        md = md.replace(Regex("<(em|i)[^>]*>(.*?)</\\1>", dotAll), "*$2*")
        md = md.replace(Regex("<(strike|del|s)[^>]*>(.*?)</\\1>", dotAll), "~~$2~~")

        // Code and Pre
        md = md.replace(Regex("<pre[^>]*><code[^>]*>(.*?)</code></pre>", dotAll), "```\n$1\n```\n")
        md = md.replace(Regex("<pre[^>]*>(.*?)</pre>", dotAll), "```\n$1\n```\n")
        md = md.replace(Regex("<code[^>]*>(.*?)</code>", dotAll), "`$1`")

        // Blockquotes
        md = md.replace(Regex("<blockquote[^>]*>(.*?)</blockquote>", dotAll), "> $1\n")

        // Lists
        md = md.replace(Regex("<li[^>]*>(.*?)</li>", dotAll), "- $1\n")
        md = md.replace(Regex("</?(ul|ol)[^>]*>", RegexOption.IGNORE_CASE), "")

        // Links, images and audio
        md = md.replace(Regex("<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", dotAll), "[$2]($1)")
        md = md.replace(Regex("<img[^>]*src=[\"']([^\"']+)[\"'][^>]*/?>", RegexOption.IGNORE_CASE), "![]($1)")
        md = md.replace(Regex("<audio[^>]*src=[\"']([^\"']+)[\"'][^>]*>.*?</audio>", dotAll), "[Audio]($1)")

        // Horizontal rule
        md = md.replace(Regex("<hr[^>]*/?>", RegexOption.IGNORE_CASE), "\n---\n")

        // Line breaks and paragraphs
        md = md.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        md = md.replace(Regex("<p[^>]*>(.*?)</p>", dotAll), "$1\n\n")

        // Remove remaining HTML tags
        md = md.replace(Regex("<[^>]+>"), "")

        // Clean up whitespace
        md = md.replace(Regex("\n{3,}"), "\n\n")
        return md.trim()
    }
}