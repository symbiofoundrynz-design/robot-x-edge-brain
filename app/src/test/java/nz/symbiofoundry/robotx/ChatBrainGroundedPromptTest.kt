package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBrainGroundedPromptTest {
    @Test
    fun groundedPromptUsesAcceptedB3RMinimalDialogueFraming() {
        val world =
            GroundedWorldContext(
                currentQuestion = "What is this?",
                factualCore = "The observed object is a person.",
                verifiedFacts = listOf("person (87% confidence)"),
                unknownFacts = listOf("identity", "residence", "occupation", "colour"),
                observationStatus = VisionObservationStatus.DETECTED,
                supportsObjectClaims = true
            )

        val prompt = ChatBrain.buildGroundedPrompt(world)

        assertTrue(prompt.startsWith("Visual fact: The observed object is a person."))
        assertTrue(prompt.contains("Unknown: identity, residence, occupation, colour"))
        assertTrue(prompt.contains("User: What is this?"))
        assertTrue(prompt.endsWith("Robot:"))
        assertFalse(prompt.contains("The user asked:"))
        assertFalse(prompt.contains("The safe factual answer is:"))
        assertFalse(prompt.contains("Session context:"))
        assertFalse(prompt.contains("Recent conversation:"))
    }

    @Test
    fun groundedCleanupUsesAcceptedB2RoleAndLeakBoundaries() {
        assertEquals(
            "I'm not sure.",
            ChatBrain.cleanGroundedResponse("User: Ruth forward.")
        )
        assertEquals(
            "It looks like a person.",
            ChatBrain.cleanGroundedResponse("Assistant: It looks like a person.")
        )
        assertEquals(
            "This is a person.",
            ChatBrain.cleanGroundedResponse("This is a person.")
        )
    }
}
