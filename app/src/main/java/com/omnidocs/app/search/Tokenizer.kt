package com.omnidocs.app.search

/**
 * Single shared tokenizer for all retrieval paths (BM25, FTS gate, snippet
 * extraction, block scoring). Previously four divergent implementations
 * (~120 / ~60 / 16 / 0 stopwords) caused inconsistent recall across paths.
 * Pure Kotlin, no Android dependencies.
 */
object Tokenizer {

    /**
     * True function words only: articles, prepositions, conjunctions,
     * pronouns, auxiliaries, quantifiers. Content verbs ("call", "find",
     * "need") and descriptors ("new", "first") are deliberately NOT stopped —
     * queries like "call Alice" or "new hires" lose their head term otherwise.
     * Negations (not/no/nor) stay stopped: lexical retrieval has no exclusion
     * semantics, so they would only add noise.
     */
    val STOP_WORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "shall", "can", "must", "to", "of", "in",
        "for", "on", "with", "at", "by", "from", "as", "into", "through",
        "during", "before", "after", "and", "but", "or", "nor", "not", "so",
        "yet", "both", "either", "neither", "each", "every", "all", "any",
        "few", "more", "most", "other", "some", "such", "no", "only", "own",
        "than", "too", "that", "this", "these", "those",
        "i", "me", "my", "we", "our", "you", "your", "he", "him", "his",
        "she", "her", "it", "its", "they", "them", "their", "what", "which",
        "who", "whom", "when", "where", "why", "how", "if", "then", "else",
        "because", "about", "also", "like", "over", "one", "two", "am", "up", "us", "go"
    )

    private val CJK_TERM_REGEX = Regex("[\u4E00-\u9FFF\u3040-\u30FF\uAC00-\uD7AF]")

    fun isCjk(text: String): Boolean = CJK_TERM_REGEX.containsMatchIn(text)

    fun isStopWord(term: String): Boolean = term.lowercase() in STOP_WORDS

    /**
     * Light symmetric suffix-fold for Latin terms (Porter-lite, no dictionary).
     * Only folds tokens long enough that the stem stays meaningful; short
     * tokens ("day", "bus", "red") pass through untouched. Applied to both
     * documents and queries, so variants always meet in the middle.
     */
    fun stemTerm(term: String): String {
        var w = term
        // -ies → -y (economies → economy), -es/-s plurals (boxes → box, notes → note)
        if (w.length > 5 && w.endsWith("ies")) {
            w = w.dropLast(3) + "y"
        } else if (w.length > 5 && (w.endsWith("ses") || w.endsWith("xes") || w.endsWith("zes") ||
                    w.endsWith("ches") || w.endsWith("shes"))
        ) {
            w = w.dropLast(2)
        } else if (w.length > 4 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")) {
            w = w.dropLast(1)
        }
        // -ing / -ed (skateboarding → skateboard, indexed → index)
        if (w.length > 6 && w.endsWith("ing")) {
            w = w.dropLast(3)
        } else if (w.length > 5 && w.endsWith("ed") && !w.endsWith("eed")) {
            w = w.dropLast(2)
        }
        // -ical / -ic adjectives (economical/economic → econom)
        if (w.length > 6 && w.endsWith("ical")) {
            w = w.dropLast(4)
        } else if (w.length > 5 && w.endsWith("ic")) {
            w = w.dropLast(2)
        }
        // Trailing -y after a consonant (economy → econom); guarded so
        // short words ("day") and vowel endings ("boy") survive.
        if (w.length > 5 && w.endsWith("y") && w[w.length - 2] !in "aeiou") {
            w = w.dropLast(1)
        }
        return w
    }

    /**
     * Tokenize text into lowercase terms, supporting Latin words and CJK unigrams/bigrams.
     * Latin terms are suffix-folded ([stemTerm]) so inflections converge
     * ("economic"/"economy"/"economics" → "econom", "skateboarding" → "skateboard").
     * Folding is symmetric (docs and queries alike), so it can only add matches.
     */
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val cleaned = text.lowercase()

        // 1. Extract space-separated Latin/alphanumeric words (preserves 2-letter tokens like "ai", "ml", "ui", "db")
        val latinWords = cleaned.replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .map { stemTerm(it) }
            .filter { it.length >= 2 && it !in STOP_WORDS }
        tokens.addAll(latinWords)

        // 2. Extract CJK unigrams and bigrams for Chinese, Japanese, and Korean
        val cjkChars = StringBuilder()
        for (char in cleaned) {
            val code = char.code
            val isCjk = code in 0x4E00..0x9FFF || code in 0x3040..0x30FF || code in 0xAC00..0xD7AF
            if (isCjk) {
                tokens.add(char.toString())
                cjkChars.append(char)
            }
        }

        // CJK Bigrams
        if (cjkChars.length >= 2) {
            for (i in 0 until cjkChars.length - 1) {
                tokens.add(cjkChars.substring(i, i + 2))
            }
        }

        return tokens
    }

    /**
     * Normalize already-lowercased free text for [matchesToken]: folds every
     * Latin word run through [stemTerm] so a folded query term ("econom")
     * matches its inflections in situ ("economy"). CJK runs pass through.
     * Callers must apply this to the TEXT side whenever the TERM side came
     * from [tokenize] (which folds).
     */
    fun normalizeForMatch(lowerText: String): String {
        return Regex("[a-z0-9]+").replace(lowerText) { m ->
            val w = m.value
            if (w.length > 2) stemTerm(w) else w
        }
    }
    /**
     * Check whether [term] occurs in [text] (both assumed lowercase).
     * Latin terms use word boundaries ("cat" no longer matches "category");
     * CJK terms use substring matching because `\b` has no boundaries
     * between adjacent CJK characters.
     *
     * Folded terms (from [tokenize]) only match folded text: run the text
     * through [normalizeForMatch] first, or inflections ("economy") will
     * never match their stem ("econom").
     */
    fun matchesToken(term: String, text: String): Boolean {
        return if (CJK_TERM_REGEX.containsMatchIn(term)) {
            text.contains(term)
        } else {
            Regex("""\b${Regex.escape(term)}\b""").containsMatchIn(text)
        }
    }
}
