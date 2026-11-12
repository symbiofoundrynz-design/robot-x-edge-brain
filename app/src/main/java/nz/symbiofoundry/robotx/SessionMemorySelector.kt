package nz.symbiofoundry.robotx

/**
 * Deterministic, bounded selector for volatile conversation memory.
 *
 * E3.5C1 rules:
 * - Stored history may be wider than rendered history.
 * - Only query-relevant session facts and prior USER statements are eligible as
 *   durable conversational evidence.
 * - ROBOT replies are never promoted to factual memory. They may appear only in
 *   a tightly bounded immediate-context block for explicit follow-up language.
 * - The rendered context has a hard character ceiling.
 * - This class has no model, sensor, transport, RobotController, or persistence
 *   authority.
 */
class SessionMemorySelector(
    private val maxChars: Int = 640,
    private val maxRelevantUserTurns: Int = 4,
    private val immediateTurnLimit: Int = 2
) {
    init {
        require(maxChars in 200..1200) { "maxChars must be between 200 and 1200" }
        require(maxRelevantUserTurns in 1..8) {
            "maxRelevantUserTurns must be between 1 and 8"
        }
        require(immediateTurnLimit in 1..4) {
            "immediateTurnLimit must be between 1 and 4"
        }
    }

    fun render(
        userText: String,
        snapshot: ConversationSession.Snapshot
    ): String {
        val queryTerms = tokenize(userText)
        val builder = StringBuilder()

        val relevantFacts = snapshot.facts.entries
            .mapNotNull { entry ->
                val terms = tokenize(entry.key.replace('_', ' ') + " " + entry.value)
                val overlap = terms.intersect(queryTerms).size
                if (overlap > 0) FactCandidate(entry.key, entry.value, overlap) else null
            }
            .sortedWith(
                compareByDescending<FactCandidate> { it.overlap }
                    .thenBy { it.key }
            )

        if (relevantFacts.isNotEmpty()) {
            appendSectionHeader(builder, "Relevant session facts:")
            relevantFacts.forEach { fact ->
                appendBoundedLine(builder, "- ${fact.key}: ${fact.value}")
            }
        }

        val relevantUserTurns = snapshot.turns
            .mapIndexedNotNull { index, turn ->
                if (turn.speaker != ConversationSession.Speaker.USER) {
                    return@mapIndexedNotNull null
                }
                val overlap = tokenize(turn.text).intersect(queryTerms).size
                if (overlap >= MIN_USER_TURN_OVERLAP) {
                    TurnCandidate(index, turn, overlap)
                } else {
                    null
                }
            }
            .sortedWith(
                compareByDescending<TurnCandidate> { it.overlap }
                    .thenByDescending { it.index }
            )
            .take(maxRelevantUserTurns)
            .sortedBy { it.index }

        if (relevantUserTurns.isNotEmpty()) {
            appendSectionHeader(builder, "Relevant prior user statements:")
            relevantUserTurns.forEach { candidate ->
                appendBoundedLine(builder, "User: ${candidate.turn.text}")
            }
        }

        if (isExplicitFollowUp(userText)) {
            val selectedIndices = relevantUserTurns.mapTo(mutableSetOf()) { it.index }
            val immediate = snapshot.turns
                .mapIndexed { index, turn -> index to turn }
                .takeLast(immediateTurnLimit)
                .filterNot { (index, _) -> index in selectedIndices }

            if (immediate.isNotEmpty()) {
                appendSectionHeader(builder, "Immediate conversational context:")
                immediate.forEach { (_, turn) ->
                    val speaker = when (turn.speaker) {
                        ConversationSession.Speaker.USER -> "User"
                        ConversationSession.Speaker.ROBOT -> "Robot"
                    }
                    appendBoundedLine(builder, "$speaker: ${turn.text}")
                }
            }
        }

        return builder.toString().trimEnd()
    }

    private fun appendSectionHeader(builder: StringBuilder, header: String) {
        if (builder.isNotEmpty()) {
            appendBoundedLine(builder, "")
        }
        appendBoundedLine(builder, header)
    }

    private fun appendBoundedLine(builder: StringBuilder, line: String) {
        if (builder.length >= maxChars) return

        val prefix = if (builder.isEmpty()) "" else "\n"
        val remaining = maxChars - builder.length
        if (prefix.length >= remaining) return

        val full = prefix + line
        if (full.length <= remaining) {
            builder.append(full)
            return
        }

        val ellipsis = "…"
        val availableForText = remaining - prefix.length - ellipsis.length
        if (availableForText <= 0) return
        builder.append(prefix).append(line.take(availableForText)).append(ellipsis)
    }

    private fun isExplicitFollowUp(text: String): Boolean {
        val normalized = text.lowercase().trim().replace(WHITESPACE, " ")
        if (FOLLOW_UP_PREFIXES.any { normalized.startsWith(it) }) return true

        val rawTokens = TOKEN_REGEX.findAll(normalized).map { it.value }.toList()
        if (rawTokens.size > 8) return false
        return rawTokens.any { it in FOLLOW_UP_REFERENTS }
    }

    private fun tokenize(text: String): Set<String> =
        TOKEN_REGEX
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it !in STOP_WORDS }
            .map(::normalizeToken)
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    private fun normalizeToken(token: String): String = when {
        token.length > 5 && token.endsWith("ies") -> token.dropLast(3) + "y"
        token.length > 5 && token.endsWith("ing") -> token.dropLast(3)
        token.length > 4 && token.endsWith("es") -> token.dropLast(2)
        token.length > 3 && token.endsWith("s") -> token.dropLast(1)
        else -> token
    }

    private data class FactCandidate(
        val key: String,
        val value: String,
        val overlap: Int
    )

    private data class TurnCandidate(
        val index: Int,
        val turn: ConversationSession.Turn,
        val overlap: Int
    )

    private companion object {
        const val MIN_USER_TURN_OVERLAP = 2

        val TOKEN_REGEX = Regex("[a-z0-9]+")
        val WHITESPACE = Regex("\\s+")

        val STOP_WORDS = setOf(
            "a", "an", "and", "are", "can", "did", "do", "does", "for",
            "from", "how", "i", "in", "is", "it", "me", "my", "of", "on",
            "or", "the", "to", "was", "what", "when", "where", "which",
            "who", "with", "you", "your"
        )

        val FOLLOW_UP_PREFIXES = listOf(
            "and ",
            "and what ",
            "how about ",
            "what about ",
            "tell me more",
            "why is that",
            "what did you mean",
            "which one"
        )

        val FOLLOW_UP_REFERENTS = setOf(
            "that", "this", "those", "these", "them", "they", "he", "she"
        )
    }
}
