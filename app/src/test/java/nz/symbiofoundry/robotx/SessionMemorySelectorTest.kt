package nz.symbiofoundry.robotx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMemorySelectorTest {

    @Test
    fun relevantOlderUserStatementSurvivesBeyondLegacySixTurnWindow() {
        val session = ConversationSession()
        session.rememberTurn(ConversationSession.Speaker.USER, "I put the red book on the desk.")
        repeat(10) { index ->
            session.rememberTurn(ConversationSession.Speaker.USER, "Unrelated topic $index")
        }

        val rendered = SessionMemorySelector().render(
            "Where did I put the red book?",
            session.snapshot()
        )

        assertTrue(rendered.contains("I put the red book on the desk."))
    }

    @Test
    fun dogFactCanBeRecoveredFromRelevantUserTurnWithoutFactPromotion() {
        val session = ConversationSession()
        session.rememberTurn(ConversationSession.Speaker.USER, "My dog is called Max.")
        repeat(8) { index ->
            session.rememberTurn(ConversationSession.Speaker.USER, "We discussed topic $index.")
        }

        val rendered = SessionMemorySelector().render(
            "What is my dog called?",
            session.snapshot()
        )

        assertTrue(rendered.contains("My dog is called Max."))
        assertTrue(session.snapshot().facts.isEmpty())
    }

    @Test
    fun unrelatedSessionFactDoesNotContaminateRainQuestion() {
        val session = ConversationSession()
        session.rememberFact("favorite_color", "green")
        session.rememberTurn(ConversationSession.Speaker.USER, "My favourite colour is green.")

        val rendered = SessionMemorySelector().render(
            "Why does rain fall from clouds?",
            session.snapshot()
        )

        assertFalse(rendered.contains("favorite_color"))
        assertFalse(rendered.contains("green"))
    }

    @Test
    fun explicitRelevantFactIsPreferredWhenQuestionMatches() {
        val session = ConversationSession()
        session.rememberFact("favorite_color", "green")

        val rendered = SessionMemorySelector().render(
            "Tell me about my favorite color.",
            session.snapshot()
        )

        assertTrue(rendered.contains("favorite_color: green"))
    }

    @Test
    fun oldRobotReplyIsNotSelectedAsRelevantFactualMemory() {
        val session = ConversationSession()
        session.rememberTurn(
            ConversationSession.Speaker.ROBOT,
            "Your dog is called WrongName."
        )
        session.rememberTurn(
            ConversationSession.Speaker.USER,
            "My dog is called Max."
        )
        session.rememberTurn(
            ConversationSession.Speaker.ROBOT,
            "Okay."
        )
        session.rememberTurn(
            ConversationSession.Speaker.USER,
            "We changed topic."
        )

        val rendered = SessionMemorySelector().render(
            "What is my dog called?",
            session.snapshot()
        )

        assertTrue(rendered.contains("My dog is called Max."))
        assertFalse(rendered.contains("WrongName"))
    }

    @Test
    fun robotReplyAppearsOnlyForExplicitImmediateFollowUp() {
        val session = ConversationSession()
        session.rememberTurn(ConversationSession.Speaker.USER, "The battery is low.")
        session.rememberTurn(ConversationSession.Speaker.ROBOT, "We should charge it soon.")

        val followUp = SessionMemorySelector().render(
            "Why is that?",
            session.snapshot()
        )
        val unrelated = SessionMemorySelector().render(
            "Explain rainbows.",
            session.snapshot()
        )

        assertTrue(followUp.contains("Robot: We should charge it soon."))
        assertFalse(unrelated.contains("We should charge it soon."))
    }

    @Test
    fun renderedContextNeverExceedsHardBudget() {
        val session = ConversationSession(maxTurns = 16, maxTextChars = 160)
        repeat(16) { index ->
            session.rememberTurn(
                ConversationSession.Speaker.USER,
                "red book topic $index " + "detail ".repeat(30)
            )
        }
        session.rememberFact("book_location", "desk " + "detail ".repeat(30))

        val rendered = SessionMemorySelector(maxChars = 640).render(
            "Where is the red book and book location?",
            session.snapshot()
        )

        assertTrue(rendered.length <= 640)
    }
}
