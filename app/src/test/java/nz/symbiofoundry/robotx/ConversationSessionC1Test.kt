package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSessionC1Test {

    @Test
    fun defaultHistoryRetainsSixteenTurns() {
        val session = ConversationSession()
        repeat(20) { index ->
            session.rememberTurn(
                ConversationSession.Speaker.USER,
                "turn-$index"
            )
        }

        val turns = session.snapshot().turns
        assertEquals(16, turns.size)
        assertEquals("turn-4", turns.first().text)
        assertEquals("turn-19", turns.last().text)
    }

    @Test
    fun repeatedExplicitFactKeyStillRefreshesValueAndOrder() {
        val session = ConversationSession(maxFacts = 2)
        session.rememberFact("favorite_color", "blue")
        session.rememberFact("name", "Alex")
        session.rememberFact("favorite_color", "green")
        session.rememberFact("location", "Wellington")

        val facts = session.snapshot().facts
        assertEquals(2, facts.size)
        assertEquals("green", facts["favorite_color"])
        assertEquals("Wellington", facts["location"])
    }
}
