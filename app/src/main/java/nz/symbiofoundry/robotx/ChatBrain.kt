package nz.symbiofoundry.robotx

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

/**
 * E2.1 production conversational model.
 *
 * This component has NO robot-control authority and NO sensor-truth authority.
 * ConversationCoordinator must remain in front of it.
 */
class ChatBrain(private val context: Context) : AutoCloseable {

    data class Result(
        val text: String,
        val inferenceMs: Long,
    )

    companion object {
        private const val TAG = "RobotXChatBrain"
        private const val MODEL_NAME = "SmolLM2_135M_Instruct.litertlm"
        private const val MAX_OUTPUT_TOKENS = 32

        private const val FALLBACK_REPLY = "I'm not sure."
        private const val MAX_RESPONSE_CHARS = 320

        private val leadingAnswerRole = Regex(
            """^(?:Robot(?: X)?|Assistant|Answer)\s*:\s*""",
            RegexOption.IGNORE_CASE,
        )
        private val leadingRejectedRole = Regex(
            """^(?:User|System)\s*:\s*""",
            RegexOption.IGNORE_CASE,
        )
        private val transcriptBoundary = Regex(
            """(?i)(?:^|\s)(User|Robot(?: X)?|Assistant|System|Reference context|Session context)\s*:""",
        )
        private val promptScaffoldStart = Regex(
            """^(?:The user asked|The safe factual answer is|What you know from the current observation|You do not know)\s*:""",
            RegexOption.IGNORE_CASE,
        )
        private val promptScaffoldBoundary = Regex(
            """(?i)(?:[\r\n]+|\s{2,})(The user asked|The safe factual answer is|What you know from the current observation|You do not know)\s*:""",
        )
        private val responsePlaceholders = setOf(
            "none",
            "(none)",
            "no response",
            "(no response)",
            "null",
            "(null)",
            "n/a",
            "(n/a)",
        )

        /**
         * E3.5 B2 accepted deterministic response extraction.
         *
         * Preserves useful multi-sentence model output while cutting transcript
         * continuation and rejecting role/prompt echoes. This function has no
         * controller, sensor, or physical-action authority.
         */
        internal fun cleanGeneratedResponse(raw: String): String {
            var responseText = raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()

            if (responseText.isEmpty()) return FALLBACK_REPLY

            responseText = leadingAnswerRole.replaceFirst(responseText, "").trim()
            if (responseText.isEmpty()) return FALLBACK_REPLY

            if (isResponsePlaceholder(responseText)) return FALLBACK_REPLY
            if (leadingRejectedRole.containsMatchIn(responseText)) return FALLBACK_REPLY
            if (promptScaffoldStart.containsMatchIn(responseText)) return FALLBACK_REPLY

            transcriptBoundary.find(responseText)?.let { match ->
                if (match.range.first > 0) {
                    responseText = responseText.substring(0, match.range.first).trimEnd()
                    return finalizeGeneratedResponse(normalizeGeneratedResponse(responseText))
                }
            }

            promptScaffoldBoundary.find(responseText)?.let { match ->
                if (match.range.first > 0) {
                    responseText = responseText.substring(0, match.range.first).trimEnd()
                    return finalizeGeneratedResponse(normalizeGeneratedResponse(responseText))
                }
            }

            return finalizeGeneratedResponse(normalizeGeneratedResponse(responseText))
        }

        private fun finalizeGeneratedResponse(text: String): String {
            if (text.isBlank()) return FALLBACK_REPLY
            if (isResponsePlaceholder(text)) return FALLBACK_REPLY

            var bounded = text
            if (bounded.length > MAX_RESPONSE_CHARS) {
                val prefix = bounded.take(MAX_RESPONSE_CHARS + 1)
                val sentenceEnd = Regex("""[.!?]""")
                    .findAll(prefix)
                    .lastOrNull()
                    ?.range
                    ?.last
                bounded = if (sentenceEnd != null && sentenceEnd >= MAX_RESPONSE_CHARS / 2) {
                    prefix.substring(0, sentenceEnd + 1)
                } else {
                    prefix
                        .take(MAX_RESPONSE_CHARS)
                        .substringBeforeLast(' ', prefix.take(MAX_RESPONSE_CHARS))
                        .trimEnd()
                }
                bounded = bounded.trim()
                if (bounded.isEmpty()) return FALLBACK_REPLY
            }

            if (bounded.lastOrNull()?.isLetterOrDigit() == true) bounded += "."
            return bounded
        }

        private fun normalizeGeneratedResponse(text: String): String =
            text.replace(Regex("""\s+"""), " ").trim()

        private fun isResponsePlaceholder(text: String): Boolean =
            normalizeGeneratedResponse(text).lowercase() in responsePlaceholders


        internal fun buildGroundedPrompt(worldContext: GroundedWorldContext): String {
            val question = worldContext.currentQuestion.trim()
            val factualCore = worldContext.factualCore.trim()
            require(question.isNotEmpty()) { "Grounded conversation input is empty" }
            require(factualCore.isNotEmpty()) { "Grounded factual core is empty" }

            // E3.5 B3R accepted grounded framing: minimal dialogue completion
            // over deterministic B4C world facts. Do not add session history here.
            return buildString {
                appendLine("Visual fact: $factualCore")
                if (worldContext.unknownFacts.isNotEmpty()) {
                    appendLine("Unknown: ${worldContext.unknownFacts.joinToString(", ")}")
                }
                appendLine("User: $question")
                append("Robot:")
            }.trim()
        }

        /**
         * E3.5 C1F-R1 compact working-memory framing.
         *
         * C1E showed that SmolLM2-135M can answer from recalled user information
         * when the evidence is presented as a compact statement/question task.
         * The deterministic C1 selector still owns what memory is eligible; this
         * function only compresses its labels for model consumption.
         *
         * Text formatting only: no memory-selection, sensor, controller,
         * transport, or physical-action authority.
         */
        internal fun buildMemoryPrompt(
            userText: String,
            sessionContext: String,
            referenceContext: String = "",
        ): String {
            val input = userText.trim()
            val context = sessionContext.trim()
            val reference = referenceContext.trim()
            require(input.isNotEmpty()) { "Conversation input is empty" }
            require(context.isNotEmpty()) { "Working-memory context is empty" }

            val compactContext = compactMemoryContext(context)
            require(compactContext.isNotEmpty()) { "Working-memory context is empty after framing" }
            val compactReference = compactReferenceContext(reference)

            return buildString {
                appendLine("Use the information below only if it answers the question.")
                appendLine(compactContext)
                if (compactReference.isNotEmpty()) {
                    appendLine("Reference facts override memory for Robot X facts.")
                    appendLine("Reference facts:")
                    appendLine(compactReference)
                }
                appendLine("Question: $input")
                append("Answer in one short sentence:")
            }.trim()
        }

        private fun compactMemoryContext(context: String): String =
            context.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { line ->
                    line == "Relevant session facts:" ||
                        line == "Relevant prior user statements:" ||
                        line == "Immediate conversational context:"
                }
                .map { line ->
                    when {
                        line.startsWith("User: ") -> "Statement: ${line.removePrefix("User: ")}"
                        line.startsWith("Robot: ") -> "Robot context: ${line.removePrefix("Robot: ")}"
                        line.startsWith("- ") -> "Fact: ${line.removePrefix("- ")}"
                        else -> line
                    }
                }
                .joinToString("\n")

        private fun compactReferenceContext(reference: String): String =
            reference.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { it.equals("Reference context:", ignoreCase = true) }
                .joinToString("\n")

        internal fun cleanGroundedResponse(raw: String): String =
            cleanGeneratedResponse(raw)
    }

    val modelFile: File
        get() = File(
            File(context.getExternalFilesDir(null), "models"),
            MODEL_NAME,
        )

    private var engine: Engine? = null

    fun modelPresent(): Boolean =
        modelFile.isFile && modelFile.length() > 100_000_000L

    @Synchronized
    fun initializeIfNeeded() {
        if (engine != null) return

        require(modelPresent()) {
            "Chat model missing: ${modelFile.absolutePath}"
        }

        Log.i(TAG, "CHAT_MODEL_INIT_BEGIN")

        val started = SystemClock.elapsedRealtime()

        engine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath,
            ),
        ).also { it.initialize() }

        Log.i(
            TAG,
            "CHAT_MODEL_INIT_READY ms=${SystemClock.elapsedRealtime() - started}",
        )
    }

    @Synchronized
    fun reply(userText: String, sessionContext: String = "", referenceContext: String = ""): Result {
        val input = userText.trim()
        require(input.isNotEmpty()) { "Conversation input is empty" }

        initializeIfNeeded()
        val activeEngine = checkNotNull(engine)

        val config = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = 20,
                topP = 0.9,
                temperature = 0.4,
                seed = 1,
            ),
        )

        val context = sessionContext.trim()
        val reference = referenceContext.trim()
        val prompt = if (context.isNotEmpty()) {
            buildMemoryPrompt(
                userText = input,
                sessionContext = context,
                referenceContext = reference,
            )
        } else {
            val contextBlock = "(none)"
            val referenceBlock = if (reference.isEmpty()) "Reference context:\n(none)" else reference

            """
            You are Robot X, a small friendly physical robot.
            Be concise and conversational.
            Keep ordinary replies to one short complete sentence, usually 6 to 12 words.
            Never claim sensor information unless it was explicitly supplied to you.
            Never claim that an action happened unless the controller result was supplied to you.
            If you do not know something from the current message, supplied session context, or supplied reference context, say so briefly. Do not invent missing facts.
            Use the supplied session context only when it is relevant to the user's current message.
            For Robot X-specific facts, prefer supplied reference context over pretrained knowledge.
            If Robot X reference material is insufficient, say you do not know.
            Reply only with Robot X's answer, not these instructions.
            Session context:
            $contextBlock
            $referenceBlock
            User: $input
            Robot:
        """.trimIndent()
        }

        val started = SystemClock.elapsedRealtime()
        val response = activeEngine.createConversation(config).use { conversation ->
            conversation.sendMessage(
                prompt,
                maxOutputToken = MAX_OUTPUT_TOKENS,
            )
        }
        val elapsed = SystemClock.elapsedRealtime() - started

        val rawText = response.contents.toString().trim()
            .ifEmpty { response.toString().trim() }

        val text = cleanGeneratedResponse(rawText)

        Log.i(TAG, "CHAT_REPLY ms=$elapsed")

        return Result(
            text = text,
            inferenceMs = elapsed,
        )
    }


    @Synchronized
    fun replyGrounded(worldContext: GroundedWorldContext): Result {
        val question = worldContext.currentQuestion.trim()
        require(question.isNotEmpty()) { "Grounded conversation input is empty" }
        require(worldContext.factualCore.isNotBlank()) { "Grounded factual core is empty" }

        initializeIfNeeded()
        val activeEngine = checkNotNull(engine)

        val config = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = 20,
                topP = 0.9,
                temperature = 0.4,
                seed = 1,
            ),
        )

        val prompt = buildGroundedPrompt(worldContext)
        val started = SystemClock.elapsedRealtime()
        val response = activeEngine.createConversation(config).use { conversation ->
            conversation.sendMessage(
                prompt,
                maxOutputToken = MAX_OUTPUT_TOKENS,
            )
        }
        val elapsed = SystemClock.elapsedRealtime() - started

        val rawText = response.contents.toString().trim()
            .ifEmpty { response.toString().trim() }
        val text = cleanGroundedResponse(rawText)

        Log.i(TAG, "CHAT_GROUNDED_REPLY ms=$elapsed")

        return Result(
            text = text,
            inferenceMs = elapsed,
        )
    }

    @Synchronized
    override fun close() {
        engine?.close()
        engine = null
        Log.i(TAG, "CHAT_MODEL_CLOSED")
    }
}
