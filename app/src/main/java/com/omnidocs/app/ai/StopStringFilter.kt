package com.omnidocs.app.ai

/**
 * Template-aware stop sequences for on-device generation.
 *
 * The native sampler only stops on single-token EOG. Multi-token turn
 * continuations the prompts explicitly forbid ("Question:", "User:", foreign
 * turn tags) loop until maxTokens, burning thermal budget and latency while
 * the streamed UI shows the loop live. This filter detects them at the
 * Kotlin collection layer: callers stop generation as soon as [stopped]
 * becomes true. [AiOutputProcessor.TURN_CUTOFFS] remains as a backstop.
 */
object StopStrings {
    val DEFAULT: List<String> = listOf(
        "<|im_start|>",
        "<|im_end|>",
        "<|eot_id|>",
        "<|end|>",
        "<|start_header_id|>",
        "<|end_header_id|>",
        "<end_of_turn>",
        "<start_of_turn>",
        "\n\nUser:",
        "\n\nHuman:",
        "\n\nAssistant:"
    )
}

/**
 * Stateful trailing-window matcher: holds back up to (maxStopLen - 1) chars
 * so a stop sequence split across tokens is detected before emission.
 *
 * Feed each (already think-filtered) token via [feed], which returns the
 * newly releasable visible text. When [stopped] is true the caller must stop
 * generation; [remainder] holds releasable text before the stop sequence.
 */
class StopStringFilter(stops: List<String> = StopStrings.DEFAULT) {
    private val sortedStops = stops.filter { it.isNotEmpty() }.sortedByDescending { it.length }
    private val holdBack = (sortedStops.maxOfOrNull { it.length } ?: 1) - 1
    private val pending = StringBuilder()
    private val seen = StringBuilder()
    /** Chars already returned to the caller (absolute positions [0, releasedTotal)). */
    private var releasedTotal = 0
    var stopped: Boolean = false
        private set

    fun feed(token: String): String {
        if (stopped || token.isEmpty()) return ""
        pending.append(token)
        seen.append(token)
        val matchLen = matchSuffix(seen)
        if (matchLen > 0) {
            stopped = true
            // Stop occupies absolute [seen.length - matchLen, seen.length).
            // Keep the pending prefix before it; anything already released
            // cannot be retracted (final answer is still correct).
            val stopStart = seen.length - matchLen
            val keep = (stopStart - releasedTotal).coerceAtLeast(0).coerceAtMost(pending.length)
            val out = pending.substring(0, keep)
            releasedTotal += pending.length
            pending.setLength(0)
            return out
        }
        // Release everything except the hold-back window.
        val releasableLen = (pending.length - holdBack).coerceAtLeast(0)
        val out = pending.substring(0, releasableLen)
        pending.delete(0, releasableLen)
        releasedTotal += releasableLen
        return out
    }

    /** Flush releasable text at natural end (no stop hit). Returns "". */
    fun flush(): String {
        if (stopped) return ""
        val out = pending.toString()
        releasedTotal += pending.length
        pending.setLength(0)
        return out
    }

    private fun matchSuffix(text: CharSequence): Int {
        for (stop in sortedStops) {
            if (text.length >= stop.length && text.endsWith(stop)) return stop.length
        }
        return 0
    }
}
