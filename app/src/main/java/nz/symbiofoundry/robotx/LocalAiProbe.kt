package nz.symbiofoundry.robotx

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import java.io.File

class LocalAiProbe(private val context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "RobotXEdgeBrain"
        const val MODEL_FILENAME = "functiongemma-270M-it.litertlm"

        private val PARSER_FALLBACK = Regex(
            """Failed to parse tool calls from code block:\s*(forward|reverse|turn_left|turn_right|stop)\{\}""",
            RegexOption.IGNORE_CASE
        )

        private val VISION_PARSER_FALLBACK = Regex(
            """Failed to parse tool calls from code block:\s*(answer[_-]?directly|vision[_-]?required)\{\}""",
            RegexOption.IGNORE_CASE
        )
    }

    data class Result(
        val accepted: Boolean,
        val command: RobotCommand?,
        val rawOutput: String,
        val message: String,
        val inferenceMs: Long
    )

    enum class VisionNeedDecision {
        ANSWER_DIRECTLY,
        VISION_REQUIRED
    }

    data class VisionNeedResult(
        val accepted: Boolean,
        val decision: VisionNeedDecision?,
        val rawOutput: String,
        val message: String,
        val inferenceMs: Long
    )

    private class RobotTools : ToolSet {
        @Tool(description = "Move the robot straight forward.")
        fun forward(): String = "EDGE_TOOL_NOT_AUTOMATICALLY_EXECUTED"

        @Tool(description = "Move the robot straight backward or reverse.")
        fun reverse(): String = "EDGE_TOOL_NOT_AUTOMATICALLY_EXECUTED"

        @Tool(description = "Turn or rotate the robot left.")
        fun turnLeft(): String = "EDGE_TOOL_NOT_AUTOMATICALLY_EXECUTED"

        @Tool(description = "Turn or rotate the robot right.")
        fun turnRight(): String = "EDGE_TOOL_NOT_AUTOMATICALLY_EXECUTED"

        @Tool(description = "Stop the robot immediately.")
        fun stop(): String = "EDGE_TOOL_NOT_AUTOMATICALLY_EXECUTED"
    }

    private class VisionNeedTools : ToolSet {
        @Tool(description = "Answer the user's request directly without using fresh robot camera evidence.")
        fun answerDirectly(): String = "VISION_DECISION_NOT_AUTOMATICALLY_EXECUTED"

        @Tool(description = "The user's request requires fresh visual evidence from the robot camera before it can be answered truthfully.")
        fun visionRequired(): String = "VISION_DECISION_NOT_AUTOMATICALLY_EXECUTED"
    }

    private var engine: Engine? = null

    val modelFile: File
        get() = File(File(context.getExternalFilesDir(null), "models"), MODEL_FILENAME)

    fun modelPresent(): Boolean =
        modelFile.isFile && modelFile.length() > 100_000_000L

    @Synchronized
    fun initializeIfNeeded() {
        if (engine != null) return
        require(modelPresent()) { "Model missing: ${modelFile.absolutePath}" }

        Log.i(TAG, "EDGE_AI_INIT_BEGIN bytes=${modelFile.length()}")

        engine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath
            )
        ).also { it.initialize() }

        Log.i(TAG, "EDGE_AI_INIT_PASS")
    }

    private fun actionFromName(name: String): RobotAction? =
        when (name.lowercase().replace("_", "").replace("-", "")) {
            "forward" -> RobotAction.FORWARD
            "reverse" -> RobotAction.REVERSE
            "turnleft" -> RobotAction.TURN_LEFT
            "turnright" -> RobotAction.TURN_RIGHT
            "stop" -> RobotAction.STOP
            else -> null
        }

    private fun visionDecisionFromName(name: String): VisionNeedDecision? =
        when (name.lowercase().replace("_", "").replace("-", "")) {
            "answerdirectly" -> VisionNeedDecision.ANSWER_DIRECTLY
            "visionrequired" -> VisionNeedDecision.VISION_REQUIRED
            else -> null
        }

    private fun resultForVisionDecision(
        decision: VisionNeedDecision,
        raw: String,
        elapsedMs: Long,
        source: String
    ): VisionNeedResult {
        Log.i(TAG, "E34B1_VISION_DECISION_PASS source=$source decision=$decision elapsedMs=$elapsedMs")
        return VisionNeedResult(
            accepted = true,
            decision = decision,
            rawOutput = raw,
            message = "Bounded vision-need decision accepted ($source)",
            inferenceMs = elapsedMs
        )
    }

    private fun resultForAction(
        action: RobotAction,
        raw: String,
        elapsedMs: Long,
        source: String
    ): Result {
        val command = if (action == RobotAction.STOP) {
            RobotCommand(action, 1, 100)
        } else {
            RobotCommand(action, 3, 500)
        }

        val validation = RobotCommandPolicy.validate(command)
        if (!validation.accepted) {
            return Result(false, null, raw, "Policy rejected: ${validation.reason}", elapsedMs)
        }

        Log.i(TAG, "EDGE_STRUCTURED_PASS source=$source command=$command elapsedMs=$elapsedMs")
        return Result(true, command, raw, "Edge Brain bounded command accepted ($source)", elapsedMs)
    }

    fun infer(userText: String): Result {
        val input = userText.trim()
        if (input.isEmpty()) return Result(false, null, "", "Enter a command first", 0L)

        val lower = input.lowercase()
        if (
            Regex("""\b(stop|halt|freeze)\b""", RegexOption.IGNORE_CASE).containsMatchIn(input) ||
            "don't move" in lower ||
            "do not move" in lower
        ) {
            val command = RobotCommand(RobotAction.STOP, 1, 100)
            Log.i(TAG, "EDGE_STOP_BYPASS")
            return Result(true, command, "DETERMINISTIC_STOP", "Safety-critical STOP bypassed AI", 0L)
        }

        /*
         * Conservative lexical normalization for common, unambiguous robot phrases.
         * This does not execute anything; it only presents a canonical intent to
         * FunctionGemma so the model still has to select one bounded robot tool.
         */
        val modelInput = when (lower) {
            "back up",
            "backward",
            "go backward",
            "move backward" -> "reverse"

            "go forward",
            "move forward",
            "go ahead",
            "move ahead" -> "forward"

            else -> input
        }

        if (modelInput != input) {
            Log.i(TAG, "EDGE_INPUT_NORMALIZED")
        }

        initializeIfNeeded()

        val config = ConversationConfig(
            tools = listOf(tool(RobotTools())),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 1),
            automaticToolCalling = false
        )

        val started = System.nanoTime()

        try {
            val response = checkNotNull(engine).createConversation(config).use {
                it.sendMessage(modelInput, maxOutputToken = 24)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            val calls = response.toolCalls

            val raw = calls.joinToString(";") { "${it.name}${it.arguments}" }
            Log.i(TAG, "EDGE_TOOLCALL_RAW_RECEIVED elapsedMs=$elapsedMs toolCalls=${calls.size}")

            if (calls.size != 1) {
                return Result(false, null, raw, "FunctionGemma did not select exactly one robot tool", elapsedMs)
            }

            val action = actionFromName(calls.single().name)
                ?: return Result(false, null, raw, "Unknown tool: ${calls.single().name}", elapsedMs)

            return resultForAction(action, raw, elapsedMs, "native")
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            val msg = e.message.orEmpty()
            val match = PARSER_FALLBACK.find(msg)

            if (match != null) {
                val toolName = match.groupValues[1]
                val action = actionFromName(toolName)
                if (action != null) {
                    val raw = "$toolName{}"
                    Log.i(TAG, "EDGE_TOOLCALL_COMPAT_PASS")
                    return resultForAction(action, raw, elapsedMs, "strict-parser-compat")
                }
            }

            Log.i(TAG, "EDGE_TOOLCALL_REJECT")
            return Result(false, null, msg, "No valid bounded robot function call", elapsedMs)
        }
    }

    fun inferVisionNeed(userText: String): VisionNeedResult {
        val input = userText.trim()
        if (input.isEmpty()) return VisionNeedResult(false, null, "", "Enter text first", 0L)

        /*
         * Conservative lexical normalization mirrors the accepted movement path.
         * It does not request the camera or answer the user; FunctionGemma still
         * has to select exactly one bounded vision-decision tool.
         */
        val lexical = input.lowercase().trimEnd('?', '.', '!')
        val modelInput = when (lexical) {
            "what is this",
            "what's this",
            "whats this",
            "what is that",
            "what's that",
            "whats that" -> "Identify what I am showing you now."

            "what do you see",
            "what can you see" -> "Answer using what the robot can currently see."

            else -> input
        }

        if (modelInput != input) {
            Log.i(TAG, "E34B1_VISION_INPUT_NORMALIZED")
        }

        initializeIfNeeded()

        val config = ConversationConfig(
            tools = listOf(tool(VisionNeedTools())),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 1),
            automaticToolCalling = false
        )

        val decisionPrompt = """
            You are a binary routing classifier for Robot X.
            Choose exactly one available tool for the user request below.
            Use vision_required only when answering truthfully requires fresh current visual evidence from the robot camera.
            Use answer_directly when the request can be answered without fresh camera evidence.
            Call exactly one tool. Do not answer the user request yourself.

            User request:
            $modelInput
        """.trimIndent()

        val started = System.nanoTime()

        try {
            val response = checkNotNull(engine).createConversation(config).use {
                it.sendMessage(decisionPrompt, maxOutputToken = 16)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            val calls = response.toolCalls
            val raw = calls.joinToString(";") { "${it.name}${it.arguments}" }

            Log.i(TAG, "E34B1_VISION_TOOLCALL_RAW_RECEIVED elapsedMs=$elapsedMs toolCalls=${calls.size}")

            if (calls.size != 1) {
                return VisionNeedResult(
                    false, null, raw,
                    "FunctionGemma did not select exactly one vision-decision tool",
                    elapsedMs
                )
            }

            val decision = visionDecisionFromName(calls.single().name)
                ?: return VisionNeedResult(
                    false, null, raw,
                    "Unknown vision-decision tool: ${calls.single().name}",
                    elapsedMs
                )

            return resultForVisionDecision(decision, raw, elapsedMs, "native")
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            val msg = e.message.orEmpty()
            val match = VISION_PARSER_FALLBACK.find(msg)

            if (match != null) {
                val toolName = match.groupValues[1]
                val decision = visionDecisionFromName(toolName)
                if (decision != null) {
                    val raw = "$toolName{}"
                    Log.i(TAG, "E34B1_VISION_TOOLCALL_COMPAT_PASS")
                    return resultForVisionDecision(decision, raw, elapsedMs, "strict-parser-compat")
                }
            }

            Log.i(TAG, "E34B1_VISION_TOOLCALL_REJECT")
            return VisionNeedResult(
                false, null, msg,
                "No valid bounded vision-decision function call",
                elapsedMs
            )
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
