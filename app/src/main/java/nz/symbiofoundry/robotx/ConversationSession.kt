package nz.symbiofoundry.robotx

/**
 * Volatile, bounded conversation state for E2.3.
 *
 * Authority rule:
 * This object stores conversational/session information only. It has no
 * RobotController, RobotTransport, BLE, camera, or model-execution authority.
 *
 * Lifetime rule:
 * State is volatile and owned by the coordinator instance. Persistence is deliberately
 * out of scope for E3.5C1.
 */
class ConversationSession(
    private val maxTurns: Int = 16,
    private val maxFacts: Int = 6,
    private val maxTextChars: Int = 160
) {
    enum class Speaker {
        USER,
        ROBOT
    }

    enum class ClarificationKind {
        DIRECTION
    }

    data class Turn(
        val speaker: Speaker,
        val text: String
    )

    data class PendingClarification(
        val kind: ClarificationKind,
        val originalInput: String
    )

    data class Snapshot(
        val turns: List<Turn>,
        val facts: Map<String, String>,
        val pendingClarification: PendingClarification?
    )

    private var turns = ArrayDeque<Turn>()
    private val facts = LinkedHashMap<String, String>()

    private var pendingClarification: PendingClarification? = null

    @Synchronized
fun rememberTurn(speaker: Speaker, text: String) {
        val normalized = boundedText(text)
        if (normalized.isEmpty()) return

        turns.addLast(Turn(speaker, normalized))
        while (turns.size > maxTurns) {
            turns.removeFirst()
        }
    }

    /** Atomically publishes a bounded USER/ROBOT exchange; blanks leave history unchanged. */
@Synchronized
fun rememberExchange(userText: String, robotText: String) {
 val user = boundedText(userText)
 val robot = boundedText(robotText)
 if (user.isEmpty() || robot.isEmpty()) return
 val replacement = ArrayDeque<Turn>()
 replacement.addAll(turns)
 replacement.addLast(Turn(Speaker.USER, user))
 replacement.addLast(Turn(Speaker.ROBOT, robot))
 while (replacement.size > maxTurns) {
 replacement.removeFirst()
 }
 turns = replacement
}

@Synchronized
fun rememberFact(key: String, value: String) {
        val normalizedKey = boundedText(key).lowercase()
        val normalizedValue = boundedText(value)

        if (normalizedKey.isEmpty() || normalizedValue.isEmpty()) return

        // Refresh insertion order when replacing an existing fact.
        facts.remove(normalizedKey)
        facts[normalizedKey] = normalizedValue

        while (facts.size > maxFacts) {
            val oldest = facts.entries.firstOrNull()?.key ?: break
            facts.remove(oldest)
        }
    }


    /** C2D removes only exact normalized fact keys from the live session. */
    @Synchronized
fun forgetFacts(keys: Iterable<String>): Int {
        val normalizedKeys = keys
            .map { boundedText(it).lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        var removed = 0
        normalizedKeys.forEach { key ->
            if (facts.remove(key) != null) removed += 1
        }
        return removed
    }

    @Synchronized
fun setPendingDirection(originalInput: String) {
        pendingClarification = PendingClarification(
            kind = ClarificationKind.DIRECTION,
            originalInput = boundedText(originalInput)
        )
    }

    @Synchronized
fun clearPendingClarification() {
        pendingClarification = null
    }

    @Synchronized
fun snapshot(): Snapshot = Snapshot(
        turns = turns.toList(),
        facts = LinkedHashMap(facts),
        pendingClarification = pendingClarification
    )

    @Synchronized
fun clear() {
        turns.clear()
        facts.clear()
        pendingClarification = null
    }

    private fun boundedText(text: String): String =
        text
            .trim()
            .replace(Regex("""\s+"""), " ")
            .take(maxTextChars)
}
