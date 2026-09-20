package com.omnidocs.app.graph

import com.omnidocs.app.search.Tokenizer
import kotlin.math.ln

/**
 * BM25 scoring utility for text relevance supporting English, Malay, and CJK multilingual text.
 * Used by [GraphEngine] and vector/hybrid search to score candidate document relevance.
 * Pure Kotlin, no Android dependencies.
 *
 * Tokenization lives in [Tokenizer] (shared with all other retrieval paths).
 */
object Bm25Scorer {

    private const val K1 = 1.5f
    private const val B = 0.75f

    /**
     * Tokenize text into lowercase terms, supporting Latin words and CJK unigrams/bigrams.
     */
    fun tokenize(text: String): List<String> = Tokenizer.tokenize(text)

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
     */
    fun score(
        queryTerms: List<String>,
        docTermFreqs: Map<String, Int>,
        docLength: Int,
        avgDocLength: Float,
        idf: Map<String, Float>
    ): Float {
        var totalScore = 0f
        val safeAvgLength = if (avgDocLength > 0f) avgDocLength else 1f

        for (term in queryTerms) {
            val tf = docTermFreqs[term] ?: continue
            val termIdf = idf[term] ?: continue

            val tfNorm = (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * docLength / safeAvgLength))
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
