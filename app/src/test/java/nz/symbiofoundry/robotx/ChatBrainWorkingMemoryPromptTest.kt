package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBrainWorkingMemoryPromptTest {

    @Test
    fun selectedMemoryUsesCompactStatementQuestionFraming() {
        val memory = """
            Relevant prior user statements:
            User: I put the red book on the desk.
        """.trimIndent()

        val prompt = ChatBrain.buildMemoryPrompt(
            userText = "Where did I put the red book?",
            sessionContext = memory,
            referenceContext = "",
        )

        assertEquals(
            """
            Use the information below only if it answers the question.
            Statement: I put the red book on the desk.
            Question: Where did I put the red book?
            Answer in one short sentence:
            """.trimIndent(),
            prompt,
        )
        assertTrue(prompt.length < 200)
        assertFalse(prompt.contains("Relevant prior user statements:"))
        assertFalse(prompt.contains("You are Robot X"))
    }

    @Test
    fun sessionFactsAreCompactedWithoutChangingTheirContent() {
        val prompt = ChatBrain.buildMemoryPrompt(
            userText = "What is my favourite colour?",
            sessionContext = "Relevant session facts:\n- favourite_colour: green",
            referenceContext = "",
        )

        assertTrue(prompt.contains("Fact: favourite_colour: green"))
        assertFalse(prompt.contains("Relevant session facts:"))
    }

    @Test
    fun immediateRobotReplyIsClearlyLabelledAsContext() {
        val prompt = ChatBrain.buildMemoryPrompt(
            userText = "What did you mean by that?",
            sessionContext = """
                Immediate conversational context:
                User: Tell me about the moon.
                Robot: It has phases.
            """.trimIndent(),
            referenceContext = "",
        )

        assertTrue(prompt.contains("Statement: Tell me about the moon."))
        assertTrue(prompt.contains("Robot context: It has phases."))
        assertFalse(prompt.contains("Immediate conversational context:"))
    }

    @Test
    fun referenceFactsRemainExplicitlyHigherPriority() {
        val prompt = ChatBrain.buildMemoryPrompt(
            userText = "What did I say about your camera?",
            sessionContext = "Relevant prior user statements:\nUser: We talked about the camera.",
            referenceContext = "Reference context:\nRobot X camera capability is bounded.",
        )

        assertTrue(prompt.contains("Reference facts override memory for Robot X facts."))
        assertTrue(prompt.contains("Reference facts:\nRobot X camera capability is bounded."))
        assertFalse(prompt.contains("Reference context:"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyWorkingMemoryCannotEnterMemoryPrompt() {
        ChatBrain.buildMemoryPrompt(
            userText = "Where did I put the red book?",
            sessionContext = "   ",
            referenceContext = "",
        )
    }
}
