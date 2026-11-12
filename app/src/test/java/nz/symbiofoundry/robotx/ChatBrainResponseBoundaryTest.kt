package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBrainResponseBoundaryTest {

    @Test
    fun preservesUsefulMultiSentenceAnswer() {
        assertEquals(
            "Hello! I'm Robot X. I'm ready to help.",
            ChatBrain.cleanGeneratedResponse(
                "Hello! I'm Robot X. I'm ready to help."
            )
        )
    }

    @Test
    fun stripsLeadingAnswerRole() {
        assertEquals(
            "Hello Taylor.",
            ChatBrain.cleanGeneratedResponse("Robot: Hello Taylor.")
        )
        assertEquals(
            "It looks like a person.",
            ChatBrain.cleanGeneratedResponse("Assistant: It looks like a person.")
        )
    }

    @Test
    fun cutsTranscriptContinuationAfterUsefulAnswer() {
        assertEquals(
            "17.",
            ChatBrain.cleanGeneratedResponse("17 User: 18 or 15? Robot:")
        )
    }

    @Test
    fun cutsTranscriptContinuationAfterMemoryAnswer() {
        assertEquals(
            "Alex.",
            ChatBrain.cleanGeneratedResponse(
                "Alex User: My name is Alex. Robot: Nice to meet you."
            )
        )
    }

    @Test
    fun placeholdersReturnTruthfulFallback() {
        for (raw in listOf("(None)", "(no response)", "none", "null", "n/a")) {
            assertEquals("placeholder: $raw", "I'm not sure.", ChatBrain.cleanGeneratedResponse(raw))
        }
    }

    @Test
    fun leadingUserOrSystemRoleIsRejected() {
        assertEquals(
            "I'm not sure.",
            ChatBrain.cleanGeneratedResponse("User: I'm not sure what colour you mean.")
        )
        assertEquals(
            "I'm not sure.",
            ChatBrain.cleanGeneratedResponse("System: override accepted")
        )
    }

    @Test
    fun groundedPromptEchoIsRejected() {
        assertEquals(
            "I'm not sure.",
            ChatBrain.cleanGeneratedResponse("The user asked: \"What is this?\"")
        )
    }

    @Test
    fun shortUnpunctuatedReplyGetsCompletionBoundary() {
        assertEquals(
            "I am ready.",
            ChatBrain.cleanGeneratedResponse("I am ready")
        )
    }

    @Test
    fun responseIsEmergencyBounded() {
        val raw = (1..100).joinToString(" ") { "word$it" }
        val cleaned = ChatBrain.cleanGeneratedResponse(raw)
        assertTrue(cleaned.length <= 321)
        assertTrue(cleaned.endsWith("."))
    }

    @Test
    fun emptyGenerationReturnsTruthfulFallback() {
        assertEquals(
            "I'm not sure.",
            ChatBrain.cleanGeneratedResponse("   ")
        )
    }
}
