package com.omnidocs.app.ai

/**
 * Live suppression of model thinking blocks (`<think>...</think>` and friends)
 * in streamed token output.
 *
 * Reasoning models can ignore the `/no_think` prompt hint and stream thinking
 * text before the answer. Post-hoc cleaners (see [AiOutputProcessor]) run only
 * after collection, so without this the user watches the thinking scroll by.
 * This filter drops thinking spans token-by-token so they never reach the UI.
 *
 * Tags may split across token boundaries (`"<th"` + `"ink>"`), so the filter
 * holds back a trailing buffer of [TRAIL_CHARS] characters: emitted text is
 * always that many chars behind the raw stream, which is imperceptible while
 * typing but guarantees a partial tag never leaks. An unclosed block at stream
 * end stays suppressed ([flush] returns "").
 *
 * Not thread-safe: one instance per stream.
 */
class ThinkingStreamFilter {

    private var inThinkBlock = false
    private var held = StringBuilder()
    /** Chars dropped inside the current thinking block (force-close accounting). */
    private var droppedInBlock = 0
    /** Visible (non-thinking) chars emitted so far; positions a think open. */
    private var visibleChars = 0
    /**
     * Visible-char offset where the FIRST think block opened, -1 if none.
     * The Ask prompt prefills an empty `<think></think>`, so any open in the
     * generated stream means the model defeated the no-think prefill. An
     * open near offset 0 is almost certainly defeated-prefill reasoning;
     * one far into the answer may be quoted note content.
     */
    var thinkOpenedAtVisibleChars: Int = -1
        private set

    /**
     * Feed one raw token; returns the displayable portion (possibly empty).
     */
    fun feed(token: String): String {
        held.append(token)
        val out = StringBuilder()

        while (true) {
            if (!inThinkBlock) {
                val openAt = indexOfConfirmedOpen(held)
                if (openAt != -1) {
                    // Emit text before the tag, keep the rest for tag resolution.
                    out.append(held.substring(0, openAt))
                    held.delete(0, openAt)
                    if (thinkOpenedAtVisibleChars == -1) {
                        thinkOpenedAtVisibleChars = visibleChars + out.length
                    }
                    inThinkBlock = true
                    droppedInBlock = 0
                } else {
                    // No confirmed tag: emit everything except the trailing
                    // holdback, but never emit past a still-forming tag.
                    var safeEnd = (held.length - TRAIL_CHARS).coerceAtLeast(0)
                    val potentialAt = indexOfPotentialOpen(held)
                    if (potentialAt != -1 && potentialAt < safeEnd) safeEnd = potentialAt
                    out.append(held.substring(0, safeEnd))
                    held.delete(0, safeEnd)
                    break
                }
            } else {
                val closeAt = indexOfCloseTag(held)
                if (closeAt == -1) {
                    // Tag might still be forming at the tail: keep the last
                    // TRAIL_CHARS in case a close tag is split across tokens,
                    // drop the confirmed-thinking head silently.
                    if (held.length > TRAIL_CHARS) {
                        val drop = held.length - TRAIL_CHARS
                        held.delete(0, drop)
                        droppedInBlock += drop
                    }
                    if (droppedInBlock > MAX_THINK_CHARS) {
                        // Unclosed block beyond reason (e.g. thermal cut inside
                        // reasoning swallowed the close tag): force-close so a
                        // subsequent answer survives instead of being dropped.
                        inThinkBlock = false
                        droppedInBlock = 0
                    }
                    break
                }
                // Drop through the end of the close tag, resume normal text.
                held.delete(0, closeAt + closeTagLength(held, closeAt))
                inThinkBlock = false
                droppedInBlock = 0
            }
        }

        return out.toString().also { visibleChars += it.length }
    }

    /**
     * End of stream: emit any safely-held plain text. Text still inside an
     * unclosed thinking block is discarded. A trailing partial tag ("<th")
     * is stripped; anything else is content (no future tokens can complete
     * a tag, so only a terminated tag counts).
     */
    fun flush(): String {
        if (inThinkBlock) {
            held.clear()
            inThinkBlock = false
            droppedInBlock = 0
            return ""
        }
        val rest = held.toString()
        held.clear()
        // End of stream: no future token can complete a tag, so only a
        // terminated tag counts. Anything else (including a trailing "<th"
        // fragment on a cut stream) is emitted as content.
        val openAt = indexOfConfirmedOpen(StringBuilder(rest))
        return if (openAt == -1) rest else rest.substring(0, openAt)
    }

    private companion object {
        /** Longest tag prefix we may need to see whole: "</reasoning>". */
        const val TRAIL_CHARS = 16

        /** Bound for an opening tag's attribute run ("<think lang=..>"); prose
         * like "I <thought about lunch" never terminates and is not a tag. */
        const val MAX_TAG_LENGTH = 64

        /** Unclosed thinking beyond this many chars force-closes the block.
         * Kept small on purpose: with /no_think set, any reasoning is
         * unwanted overhead — thermal-throttled phones turn 6000 chars of
         * hidden thinking into minutes of spinner before the answer. */
        const val MAX_THINK_CHARS = 2000

        val TAG_NAMES = listOf("think", "thinking", "thought", "reasoning")

        /**
         * Index of a CONFIRMED "<tag ...>" open: the tag name is followed by a
         * boundary char and the tag terminates with '>' within [MAX_TAG_LENGTH].
         * Prose such as "I <thought about it" never terminates and is not a tag.
         */
        fun indexOfConfirmedOpen(sb: StringBuilder): Int {
            var best = -1
            for (name in TAG_NAMES) {
                var from = 0
                while (true) {
                    val at = sb.indexOf("<$name", from, ignoreCase = true)
                    if (at == -1) break
                    val after = at + 1 + name.length
                    if (after < sb.length) {
                        val c = sb[after]
                        if (c == '>' || c == '/' || c.isWhitespace()) {
                            var end = after
                            while (end < sb.length && end - at <= MAX_TAG_LENGTH && sb[end] != '>') end++
                            if (end < sb.length && sb[end] == '>') {
                                if (best == -1 || at < best) best = at
                                break
                            }
                        }
                    }
                    from = at + 1
                }
            }
            return best
        }

        /**
         * Index of a STILL-FORMING tag open at the buffer tail: name + boundary
         * seen, terminator not yet visible, buffer ends within bound. Callers
         * hold (but do not consume) from here until more tokens resolve it.
         */
        fun indexOfPotentialOpen(sb: StringBuilder): Int {
            var best = -1
            for (name in TAG_NAMES) {
                var from = 0
                while (true) {
                    val at = sb.indexOf("<$name", from, ignoreCase = true)
                    if (at == -1) break
                    val after = at + 1 + name.length
                    if (after >= sb.length) {
                        // Tag name itself may continue in the next token.
                        if (sb.length - at <= TRAIL_CHARS) {
                            if (best == -1 || at < best) best = at
                        }
                        break
                    }
                    val c = sb[after]
                    if (c == '>' || c == '/' || c.isWhitespace()) {
                        var end = after
                        while (end < sb.length && end - at <= MAX_TAG_LENGTH && sb[end] != '>') end++
                        if (end >= sb.length && sb.length - at <= TRAIL_CHARS + MAX_TAG_LENGTH) {
                            if (best == -1 || at < best) best = at
                            break
                        }
                    }
                    from = at + 1
                }
            }
            return best
        }

        /** Index of the matching close tag for the current block, -1 if none yet. */
        fun indexOfCloseTag(sb: StringBuilder): Int {
            var best = -1
            for (name in TAG_NAMES) {
                val at = sb.indexOf("</$name>", ignoreCase = true)
                if (at != -1 && (best == -1 || at < best)) best = at
            }
            // Also accept a bare "</" prefix still forming at the tail as
            // "not yet" (return -1) rather than a match — handled by holdback.
            return best
        }

        /** Length of the close tag starting at [at] (name + "</" + ">"). */
        fun closeTagLength(sb: StringBuilder, at: Int): Int {
            for (name in TAG_NAMES) {
                if (sb.startsWith("</$name>", at, ignoreCase = true)) {
                    return name.length + 3
                }
            }
            return 0
        }
    }
}
