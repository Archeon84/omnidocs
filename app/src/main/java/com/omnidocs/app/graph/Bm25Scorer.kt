package com.omnidocs.app.graph

import kotlin.math.ln

/**
 * BM25 scoring utility for text relevance.
 * Used by [GraphEngine] to find candidate note pairs for the knowledge graph.
 * Pure Kotlin, no Android dependencies.
 */
object Bm25Scorer {

    private const val K1 = 1.5f
    private const val B = 0.75f

    private val STOP_WORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "shall", "can", "to", "of", "in", "for",
        "on", "with", "at", "by", "from", "as", "into", "through", "during",
        "before", "after", "and", "but", "or", "nor", "not", "so", "yet",
        "both", "either", "neither", "each", "every", "all", "any", "few",
        "more", "most", "other", "some", "such", "no", "only", "own", "same",
        "than", "too", "very", "just", "that", "this", "these", "those",
        "i", "me", "my", "we", "our", "you", "your", "he", "him", "his",
        "she", "her", "it", "its", "they", "them", "their", "what", "which",
        "who", "whom", "when", "where", "why", "how", "if", "then", "else",
        "because", "about", "also", "like", "over", "such", "even", "new",
        "one", "two", "first", "now", "well", "back", "much", "go", "see",
        "know", "get", "make", "say", "think", "take", "come", "could",
        "want", "look", "use", "find", "give", "tell", "work", "call",
        "try", "ask", "need", "feel", "become", "leave", "put", "mean",
        "keep", "let", "begin", "seem", "help", "show", "hear", "play",
        "run", "move", "live", "believe", "bring", "happen", "must", "really"
    )

    /**
     * Tokenize text into lowercase terms, stripping punctuation and stop words.
     */
    fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
    }

    /**
     * Compute IDF (inverse document frequency) for all terms across a corpus.
     * IDF(t) = log((N - n(t) + 0.5) / (n(t) + 0.5) + 1)
     * where N = total documents, n(t) = documents containing term t.
     */
    fun computeIdf(documents: List<List<String>>): Map<String, Float> {
        val n = documents.size.toFloat()
        val docFrequency = mutableMapOf<String, Int>()

        for (doc in documents) {
            val uniqueTerms = doc.toSet()
            for (term in uniqueTerms) {
                docFrequency[term] = (docFrequency[term] ?: 0) + 1
            }
        }

        return docFrequency.mapValues { (_, df) ->
            val score = (n - df + 0.5f) / (df + 0.5f) + 1f
            ln(score)
        }
    }

    /**
     * Compute BM25 score of query terms against a document.
     *
     * @param queryTerms terms to score (typically top-N terms from a note)
     * @param docTermFreqs term frequencies in the target document
     * @param docLength number of tokens in the target document
     * @param avgDocLength average document length across the corpus
     * @param idf precomputed IDF values
     * @return BM25 score (higher = more relevant)
     */
    fun score(
        queryTerms: List<String>,
        docTermFreqs: Map<String, Int>,
        docLength: Int,
        avgDocLength: Float,
        idf: Map<String, Float>
    ): Float {
        var totalScore = 0f

        for (term in queryTerms) {
            val tf = docTermFreqs[term] ?: continue
            val termIdf = idf[term] ?: continue

            val tfNorm = (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * docLength / avgDocLength))
            totalScore += termIdf * tfNorm
        }

        return totalScore
    }

    /**
     * Extract top-N terms from a tokenized document by term frequency.
     */
    fun topTerms(tokens: List<String>, n: Int = 10): List<String> {
        return tokens.groupBy { it }
            .entries
            .sortedByDescending { it.value.size }
            .take(n)
            .map { it.key }
    }
}
