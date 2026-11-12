package nz.symbiofoundry.robotx

import android.util.Log

/**
 * Owns deterministic input routing and conversational capability truth.
 *
 * Authority boundary:
 * - This class has no direct path to RobotController.
 * - It may classify text as action-like, but cannot execute physical action.
 * - Sensor/capability truth is handled deterministically before ChatBrain.
 */
class ConversationCoordinator(
    private val chatBrain: ChatBrain,
    private val knowledgeRetriever: KnowledgeRetriever? = null,
    private val referenceContextRenderer: ReferenceContextRenderer = ReferenceContextRenderer(),
    private val sessionMemorySelector: SessionMemorySelector = SessionMemorySelector(),
    private val persistentUserMemory: PersistentUserMemory? = null
) {

    private val session = ConversationSession()

    init {
        try {
            rehydrateSession(session, persistentUserMemory)
        } catch (error: RuntimeException) {
            Log.w(
                MEMORY_TAG,
                "C2C persistent user-memory rehydration failed type=${error.javaClass.simpleName}"
            )
        }
    }

    enum class Route {
        STOP,
        COORDINATOR_GUARD,
        ACTION,
        ACTION_INCOMPLETE,
        VISION_REQUIRED,
        CHAT_MODEL
    }

    enum class CanonicalAction {
        FORWARD,
        REVERSE,
        TURN_LEFT,
        TURN_RIGHT
    }

    data class RouteDecision(
        val route: Route,
        val normalizedInput: String,
        val fixedReply: String? = null,
        val canonicalAction: CanonicalAction? = null
    )

    data class Result(
        val route: Route,
        val text: String,
        val inferenceMs: Long
    )

    /**
     * Pure deterministic classifier.
     *
     * Important: classification alone never causes physical execution.
     * Uncertain or unsupported language falls away from ACTION.
     */
    companion object {
        private const val MEMORY_TAG = "RobotXMemory"

        internal data class ForgetDirective(
            val keys: Set<String>,
            val confirmation: String,
            val missing: String
        )

        internal fun parseForgetDirective(userText: String): ForgetDirective? {
            var text = normalize(userText)
            if (text.startsWith("robot x ")) text = text.removePrefix("robot x ").trim()
            if (text.startsWith("please ")) text = text.removePrefix("please ").trim()

            return when (text) {
                "forget my name" -> ForgetDirective(
                    keys = linkedSetOf("name", "preferred_name"),
                    confirmation = "I've forgotten your name.",
                    missing = "I don't have your name saved."
                )

                "forget what i asked you to call me" -> ForgetDirective(
                    keys = linkedSetOf("preferred_name"),
                    confirmation = "I've forgotten what you asked me to call you.",
                    missing = "I don't have a preferred name saved for you."
                )

                "forget my favourite colour",
                "forget my favorite colour",
                "forget my favourite color",
                "forget my favorite color" -> ForgetDirective(
                    keys = linkedSetOf("favorite_color"),
                    confirmation = "I've forgotten your favourite colour.",
                    missing = "I don't have your favourite colour saved."
                )

                "forget where i live" -> ForgetDirective(
                    keys = linkedSetOf("location"),
                    confirmation = "I've forgotten where you live.",
                    missing = "I don't have your home location saved."
                )

                else -> null
            }
        }

        /**
         * C2D deletion ordering: durable storage first, then volatile session.
         * If durable deletion throws, the live session remains unchanged so a
         * failed forget operation cannot appear successful only until restart.
         */
        internal fun applyForgetDirective(
            session: ConversationSession,
            persistentUserMemory: PersistentUserMemory?,
            directive: ForgetDirective
        ): Int {
            val durableRemoved = persistentUserMemory?.forget(directive.keys) ?: 0
            val sessionRemoved = session.forgetFacts(directive.keys)
            return durableRemoved + sessionRemoved
        }

        internal fun rehydrateSession(
            session: ConversationSession,
            persistentUserMemory: PersistentUserMemory?
        ): Int {
            var loaded = 0
            persistentUserMemory
                ?.sessionFacts()
                .orEmpty()
                .forEach { (key, value) ->
                    session.rememberFact(key, value)
                    loaded += 1
                }
            return loaded
        }
        private val whitespace = Regex("\\s+")
        private val punctuation = Regex("[^a-z0-9' ]+")

        private val stopPatterns = listOf(
            Regex("""^(robot x\s+)?(?:stop|stopped)(\s+(now|moving|please))?$"""),
            Regex("""^(robot x\s+)?halt(\s+(now|please))?$"""),
            Regex("""^(robot x\s+)?freeze(\s+(now|please))?$""")
        )

        private val visionRequiredPatterns = listOf(
            Regex("""^(robot x\s+)?what (?:do|can) you see$"""),
            Regex("""^(robot x\s+)?describe what you see$"""),
            Regex("""^(robot x\s+)?what am i holding$"""),
            Regex("""^(robot x\s+)?(?:what is|what's|whats) (?:this|that)$"""),
            Regex("""^(robot x\s+)?(?:what is|what's|whats) (?:this|that) in front of you$"""),
            Regex("""^(robot x\s+)?what is in front of you$"""),
            Regex("""^(robot x\s+)?who is in front of you$"""),
            Regex("""^(robot x\s+)?is there .+ in front of you$"""),
            Regex("""^(robot x\s+)?what colou?r is (?:this|that)$"""),
            Regex("""^(robot x\s+)?look at (?:this|that)$"""),
            Regex("""^(robot x\s+)?can you see (?:this|that|me|.+ in front of you)$"""),
            Regex("""^(robot x\s+)?what (?:does|can) your camera see$""")
        )

        private val explanatoryPrefixes = listOf(
            "what ",
            "why ",
            "how ",
            "when ",
            "where ",
            "who ",
            "tell me ",
            "explain ",
            "describe "
        )

        private val actionVerbs = setOf(
            "move",
            "go",
            "drive",
            "forward",
            "advance",
            "reverse",
            "back",
            "backward",
            "backwards",
            "turn",
            "rotate",
            "left",
            "right"
        )

        private val incompleteActionPatterns = listOf(
            Regex("""^(robot x\s+)?(please\s+)?move(\s+please)?$"""),
            Regex("""^(robot x\s+)?(please\s+)?go(\s+please)?$"""),
            Regex("""^(robot x\s+)?(please\s+)?drive(\s+please)?$"""),
            Regex("""^(robot x\s+)?(please\s+)?turn(\s+please)?$"""),
            Regex("""^(robot x\s+)?(please\s+)?rotate(\s+please)?$""")
        )

        private val explicitActionPatterns = listOf(
            Regex("""^(robot x\s+)?(please\s+)?(move|go|drive|advance)\s+forward(s)?(\s+please)?$"""),
            Regex("""^(robot x\s+)?(please\s+)?(move|go|drive)\s+(back|backward|backwards)(\s+please)?$"""),
            Regex("""^(robot x\s+)?(please\s+)?reverse(\s+please)?$"""),
            Regex("""^(robot x\s+)?(please\s+)?(turn|rotate)\s+(to\s+the\s+)?left(\s+please)?$"""),
            Regex("""^(robot x\s+)?(please\s+)?(turn|rotate)\s+(to\s+the\s+)?right(\s+please)?$"""),
            Regex("""^(robot x\s+)?(please\s+)?(go|move)\s+left(\s+please)?$"""),
            Regex("""^(robot x\s+)?(please\s+)?(go|move)\s+right(\s+please)?$"""),
            Regex("""^(robot x\s+)?can you\s+(move|go|drive|advance)\s+forward(s)?(\s+please)?$"""),
            Regex("""^(robot x\s+)?could you\s+(move|go|drive|advance)\s+forward(s)?(\s+please)?$"""),
            Regex("""^(robot x\s+)?can you\s+(reverse|move backward|move backwards|go backward|go backwards)(\s+please)?$"""),
            Regex("""^(robot x\s+)?could you\s+(reverse|move backward|move backwards|go backward|go backwards)(\s+please)?$"""),
            Regex("""^(robot x\s+)?can you\s+(turn|rotate)\s+(to\s+the\s+)?(left|right)(\s+please)?$"""),
            Regex("""^(robot x\s+)?could you\s+(turn|rotate)\s+(to\s+the\s+)?(left|right)(\s+please)?$""")
        )

        private fun canonicalBasicAction(text: String): CanonicalAction? {
            val politePrefix = """(?:please\s+)?(?:can\s+you\s+|could\s+you\s+|would\s+you\s+)?"""
            val politeSuffix = """(?:\s+please)?"""

            val forward = Regex(
                """^$politePrefix(?:move|go|drive|advance)\s+forwards?$politeSuffix$"""
            )
            val reverse = Regex(
                """^$politePrefix(?:(?:move|go|drive)\s+backwards?|reverse|back\s+up)$politeSuffix$"""
            )
            val left = Regex(
                """^$politePrefix(?:turn|rotate)\s+left$politeSuffix$"""
            )
            val right = Regex(
                """^$politePrefix(?:turn|rotate)\s+right$politeSuffix$"""
            )

            return when {
                forward.matches(text) -> CanonicalAction.FORWARD
                reverse.matches(text) -> CanonicalAction.REVERSE
                left.matches(text) -> CanonicalAction.TURN_LEFT
                right.matches(text) -> CanonicalAction.TURN_RIGHT
                else -> null
            }
        }
        fun resolvePendingDirection(
            pending: ConversationSession.PendingClarification,
            userText: String
        ): CanonicalAction? {
            if (pending.kind != ConversationSession.ClarificationKind.DIRECTION) {
                return null
            }

            val reply = normalize(userText)
            val original = normalize(pending.originalInput)

            val left = reply in setOf("left", "to the left")
            val right = reply in setOf("right", "to the right")
            val forward = reply in setOf("forward", "forwards", "ahead")
            val reverse = reply in setOf(
                "back",
                "backward",
                "backwards",
                "reverse",
                "back up"
            )

            return when {
                original.matches(Regex("""^(?:please\s+)?(?:turn|rotate)(?:\s+please)?$""")) -> when {
                    left -> CanonicalAction.TURN_LEFT
                    right -> CanonicalAction.TURN_RIGHT
                    else -> null
                }

                original.matches(Regex("""^(?:please\s+)?(?:move|go|drive)(?:\s+please)?$""")) -> when {
                    forward -> CanonicalAction.FORWARD
                    reverse -> CanonicalAction.REVERSE
                    left -> CanonicalAction.TURN_LEFT
                    right -> CanonicalAction.TURN_RIGHT
                    else -> null
                }

                else -> null
            }
        }
        fun extractExplicitSessionFact(userText: String): Pair<String, String>? {
            val text = userText
                .trim()
                .replace(Regex("""\s+"""), " ")

            val patterns = listOf(
                Regex("""(?i)^my name is ([A-Za-z][A-Za-z .'-]{0,48})[.!?]?$""") to "name",
                Regex("""(?i)^call me ([A-Za-z][A-Za-z .'-]{0,48})[.!?]?$""") to "preferred_name",
                Regex("""(?i)^my favou?rite colou?r is ([A-Za-z][A-Za-z -]{0,32})[.!?]?$""") to "favorite_color",
                Regex("""(?i)^i live in ([A-Za-z0-9][A-Za-z0-9 ,.'-]{0,64})[.!?]?$""") to "location"
            )

            for ((pattern, key) in patterns) {
                val match = pattern.matchEntire(text) ?: continue
                val value = match.groupValues[1].trim().trimEnd('.', '!', '?')
                if (value.isNotEmpty()) {
                    return key to value
                }
            }

            return null
        }
        fun explicitSessionFactReply(
            userText: String,
            snapshot: ConversationSession.Snapshot
        ): String? {
            val text = normalize(userText)
            val facts = snapshot.facts

            val asksName = text.matches(
                Regex("""^(?:what(?:'s| is)|do you know) my name\??$""")
            )

            val asksColour = text.matches(
                Regex("""^(?:what(?:'s| is)|do you know) my favou?rite colou?r\??$""")
            )

            val asksLocation = text.matches(
                Regex("""^(?:where do i live|do you know where i live)\??$""")
            )

            return when {
                asksName -> {
                    val value = facts["preferred_name"] ?: facts["name"]
                    if (value != null) {
                        "Your name is $value."
                    } else {
                        "You haven't told me your name yet."
                    }
                }

                asksColour -> {
                    val value = facts["favorite_color"]
                    if (value != null) {
                        "Your favourite colour is $value."
                    } else {
                        "You haven't told me your favourite colour yet."
                    }
                }

                asksLocation -> {
                    val value = facts["location"]
                    if (value != null) {
                        "You live in $value."
                    } else {
                        "You haven't told me where you live yet."
                    }
                }

                else -> null
            }
        }



        fun renderSessionContext(
            snapshot: ConversationSession.Snapshot
        ): String {
            val lines = mutableListOf<String>()

            if (snapshot.facts.isNotEmpty()) {
                lines += "Session facts:"
                for ((key, value) in snapshot.facts) {
                    lines += "- $key: $value"
                }
            }

            if (snapshot.turns.isNotEmpty()) {
                if (lines.isNotEmpty()) lines += ""
                lines += "Recent conversation:"
                for (turn in snapshot.turns) {
                    val speaker = when (turn.speaker) {
                        ConversationSession.Speaker.USER -> "User"
                        ConversationSession.Speaker.ROBOT -> "Robot"
                    }
                    lines += "$speaker: ${turn.text}"
                }
            }

            return lines.joinToString("\n")
        }






        fun classifyText(userText: String): RouteDecision {
            val normalized = normalize(userText)

            if (normalized.isBlank()) {
                return RouteDecision(
                    Route.CHAT_MODEL,
                    normalized,
                    "Tell me what you'd like Robot X to do or talk about."
                )
            }

            if (stopPatterns.any { it.matches(normalized) }) {
                return RouteDecision(Route.STOP, normalized)
            }

            if (visionRequiredPatterns.any { it.matches(normalized) }) {
                return RouteDecision(
                    route = Route.VISION_REQUIRED,
                    normalizedInput = normalized
                )
            }

            if (incompleteActionPatterns.any { it.matches(normalized) }) {
                val reply = if (
                    normalized.contains("turn") || normalized.contains("rotate")
                ) {
                    "Left or right?"
                } else {
                    "Which direction?"
                }
                return RouteDecision(Route.ACTION_INCOMPLETE, normalized, reply)
            }

            // Questions/explanations that merely mention movement must remain dialogue.
            if (explanatoryPrefixes.any { normalized.startsWith(it) }) {
                return RouteDecision(Route.CHAT_MODEL, normalized)
            }
            val canonicalAction = canonicalBasicAction(normalized)
            if (canonicalAction != null) {
                return RouteDecision(
                    route = Route.ACTION,
                    normalizedInput = normalized,
                    canonicalAction = canonicalAction
                )
            }



            if (explicitActionPatterns.any { it.matches(normalized) }) {
                return RouteDecision(Route.ACTION, normalized)
            }

            // Presence of an action word alone is deliberately insufficient.
            // Conservative fallback prevents accidental movement.
            val words = normalized.split(' ').filter { it.isNotBlank() }
            if (words.any { it in actionVerbs }) {
                return RouteDecision(Route.CHAT_MODEL, normalized)
            }

            return RouteDecision(Route.CHAT_MODEL, normalized)
        }

        private fun normalize(text: String): String =
            text.lowercase()
                .replace(punctuation, " ")
                .replace(whitespace, " ")
                .trim()
    }

    /**
     * E2.1-compatible conversational reply surface.
     *
     * E2.2B will orchestrate STOP/ACTION routes from MainActivity.
     */

    fun sessionSnapshot(): ConversationSession.Snapshot = session.snapshot()

    fun decide(userText: String): RouteDecision {
        val pending = session.snapshot().pendingClarification

        if (pending != null) {
            val resolved = resolvePendingDirection(pending, userText)

            if (resolved != null) {
                session.clearPendingClarification()
                return RouteDecision(
                    route = Route.ACTION,
                    normalizedInput = normalize(userText),
                    canonicalAction = resolved
                )
            }

            // Do not carry stale physical intent across unrelated input.
            session.clearPendingClarification()
        }

        val decision = classifyText(userText)

        if (decision.route == Route.ACTION_INCOMPLETE) {
            session.setPendingDirection(decision.normalizedInput)
        } else {
            session.clearPendingClarification()
        }

        return decision
    }





    /**
     * E3.4 B3 grounded visual reply surface.
     *
     * The caller owns sensor acquisition. This method receives bounded textual
     * evidence only, keeps ChatBrain non-authoritative, and records the completed
     * visual turn in normal conversation memory.
     */
    /**
* I2 guarded visual response: commit only pipeline-approved text.
* Legacy replyGrounded remains unchanged pending I3 cutover.
*/
/**
* I3-C1 preparation-only result. Not a memory-commit or publication permit.
* Request ownership must be checked separately before committing or publishing.
*/
internal data class PreparedGroundedReply(
val question: String,
val observationId: String,
val result: Result
)

/**
* Prepare a pipeline-approved visual reply without modifying conversation memory.
* The existing I2 entry points remain unchanged until the I3 cutover.
*/
internal fun prepareGroundedVerified(
worldContext: GroundedWorldContext,
evidence: VisualEvidenceContract
): PreparedGroundedReply {
val question = worldContext.currentQuestion.trim()
require(question.isNotEmpty()) { "Conversation input is empty" }
require(worldContext.factualCore.isNotBlank()) { "Grounded factual core is empty" }

var generationMs = 0L
val approved = GroundedResponsePipeline.resolve(
question = question,
expectedObservationId = evidence.observationId,
evidence = evidence,
generateCandidate = {
val candidate = chatBrain.replyGrounded(worldContext)
generationMs = candidate.inferenceMs
candidate.text
}
)
return PreparedGroundedReply(
question = question,
observationId = evidence.observationId,
result = Result(
route = Route.VISION_REQUIRED,
text = approved.text,
inferenceMs = if (approved.source == GroundedOutputSource.VERIFIED_GENERATION) generationMs else 0L
)
)
}

/** Commit a prepared visual exchange only while its observation owns the lifecycle token. */
internal fun commitPreparedGroundedReply(
 prepared: PreparedGroundedReply,
 lifecycle: VisualResponseLifecycle,
 token: VisualResponseLifecycle.Token
): Result? {
 if (prepared.observationId != token.observationId) return null
 if (prepared.question.isBlank() || prepared.result.text.isBlank()) return null
 if (prepared.result.route != Route.VISION_REQUIRED) return null
 val committed = lifecycle.commitOnce(token) {
 session.rememberExchange(prepared.question, prepared.result.text)
 }
 return if (committed) prepared.result else null
}

fun replyGroundedVerified(
 worldContext: GroundedWorldContext,
 evidence: VisualEvidenceContract
): Result = replyGroundedVerifiedWithCandidate(worldContext, evidence) {
 chatBrain.replyGrounded(worldContext)
}

internal fun replyGroundedVerifiedWithCandidate(
 worldContext: GroundedWorldContext,
 evidence: VisualEvidenceContract,
 generateCandidate: () -> ChatBrain.Result
): Result {
val question = worldContext.currentQuestion.trim()
require(question.isNotEmpty()) { "Conversation input is empty" }
require(worldContext.factualCore.isNotBlank()) { "Grounded factual core is empty" }

var generationMs = 0L
val approved = GroundedResponsePipeline.resolve(
question = question,
expectedObservationId = evidence.observationId,
evidence = evidence,
generateCandidate = {
val candidate = generateCandidate()
generationMs = candidate.inferenceMs
candidate.text
}
)

session.rememberTurn(ConversationSession.Speaker.USER, question)
session.rememberTurn(ConversationSession.Speaker.ROBOT, approved.text)

return Result(
route = Route.VISION_REQUIRED,
text = approved.text,
inferenceMs = if (approved.source == GroundedOutputSource.VERIFIED_GENERATION) generationMs else 0L
)
}

 fun replyGrounded(worldContext: GroundedWorldContext): Result {
        val question = worldContext.currentQuestion.trim()
        require(question.isNotEmpty()) { "Conversation input is empty" }
        require(worldContext.factualCore.isNotBlank()) { "Grounded factual core is empty" }

        val reply = chatBrain.replyGrounded(worldContext)

        session.rememberTurn(ConversationSession.Speaker.USER, question)
        session.rememberTurn(ConversationSession.Speaker.ROBOT, reply.text)

        return Result(
            route = Route.VISION_REQUIRED,
            text = reply.text,
            inferenceMs = reply.inferenceMs
        )
    }

    fun reply(userText: String): Result {
        val decision = decide(userText)

        if (decision.fixedReply != null) {
            return Result(
                route = decision.route,
                text = decision.fixedReply,
                inferenceMs = 0L
            )
        }

        if (decision.route == Route.VISION_REQUIRED) {
            return Result(
                route = Route.VISION_REQUIRED,
                text = "I need fresh visual evidence to answer that.",
                inferenceMs = 0L
            )
        }

        parseForgetDirective(userText)?.let { directive ->
            val removed = try {
                applyForgetDirective(session, persistentUserMemory, directive)
            } catch (error: RuntimeException) {
                Log.w(TAG, "C2D")
                return Result(
                    route = Route.CHAT_MODEL,
                    text = "I couldn't forget that safely.",
                    inferenceMs = 0L
                )
            }

            return Result(
                route = Route.CHAT_MODEL,
                text = if (removed > 0) directive.confirmation else directive.missing,
                inferenceMs = 0L
            )
        }

        explicitSessionFactReply(userText, session.snapshot())?.let { factReply ->
            return Result(
                route = Route.CHAT_MODEL,
                text = factReply,
                inferenceMs = 0L
            )
        }

        extractExplicitSessionFact(userText)?.let { (key, value) ->
            // Keep the live C1 fact even if durable storage is unavailable.
            session.rememberFact(key, value)
            try {
                persistentUserMemory?.remember(key, value)
            } catch (error: RuntimeException) {
                Log.w(TAG, "C2B")
            }
        }

        val sessionContext = sessionMemorySelector.render(userText, session.snapshot())
        val referenceRecords = knowledgeRetriever
            ?.retrieve(userText)
            .orEmpty()
        val referenceContext = referenceContextRenderer.render(referenceRecords)

        if (
            knowledgeRetriever != null &&
            GroundingGuard.shouldFailClosed(
                userText = userText,
                hasReferenceContext = referenceRecords.isNotEmpty()
            )
        ) {
            val unknownReply = "I don't know that yet."
            session.rememberTurn(ConversationSession.Speaker.USER, userText)
            session.rememberTurn(ConversationSession.Speaker.ROBOT, unknownReply)
            return Result(
                route = Route.COORDINATOR_GUARD,
                text = unknownReply,
                inferenceMs = 0L
            )
        }

        val reply = chatBrain.reply(userText, sessionContext, referenceContext)

        session.rememberTurn(ConversationSession.Speaker.USER, userText)
        session.rememberTurn(ConversationSession.Speaker.ROBOT, reply.text)
        return Result(
            route = Route.CHAT_MODEL,
            text = reply.text,
            inferenceMs = reply.inferenceMs
        )
    }
}
