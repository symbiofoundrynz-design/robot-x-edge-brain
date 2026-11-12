package nz.symbiofoundry.robotx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingGuardTest {
    @Test
    fun unsupportedRobotSelfFactFailsClosed() {
        assertTrue(
            GroundingGuard.shouldFailClosed(
                userText = "What is your gearbox reduction ratio?",
                hasReferenceContext = false
            )
        )
    }

    @Test
    fun supportedRobotSelfFactDoesNotFailClosed() {
        assertFalse(
            GroundingGuard.shouldFailClosed(
                userText = "What phone are you using?",
                hasReferenceContext = true
            )
        )
    }

    @Test
    fun ordinaryConversationStillReachesChatModel() {
        assertFalse(
            GroundingGuard.shouldFailClosed(
                userText = "How are you?",
                hasReferenceContext = false
            )
        )
        assertFalse(
            GroundingGuard.shouldFailClosed(
                userText = "Tell me a joke.",
                hasReferenceContext = false
            )
        )
    }

    @Test
    fun unsupportedPossessiveFactsFailClosed() {
        assertTrue(
            GroundingGuard.shouldFailClosed(
                userText = "What is your favourite colour?",
                hasReferenceContext = false
            )
        )
    }
}
