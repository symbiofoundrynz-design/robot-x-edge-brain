package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSessionTest {

    @Test
    fun turnsAreBoundedAndOrdered() {
        val session = ConversationSession(maxTurns = 3)

        session.rememberTurn(ConversationSession.Speaker.USER, "one")
        session.rememberTurn(ConversationSession.Speaker.ROBOT, "two")
        session.rememberTurn(ConversationSession.Speaker.USER, "three")
        session.rememberTurn(ConversationSession.Speaker.ROBOT, "four")

        val turns = session.snapshot().turns

        assertEquals(3, turns.size)
        assertEquals("two", turns[0].text)
        assertEquals("three", turns[1].text)
        assertEquals("four", turns[2].text)
    }

    @Test
    fun factsAreBoundedAndReplacementRefreshesOrder() {
        val session = ConversationSession(maxFacts = 2)

        session.rememberFact("name", "Alex")
        session.rememberFact("colour", "green")
        session.rememberFact("name", "Taylor")
        session.rememberFact("project", "Robot X")

        val facts = session.snapshot().facts

        assertEquals(2, facts.size)
        assertEquals("Taylor", facts["name"])
        assertEquals("Robot X", facts["project"])
        assertTrue("colour" !in facts)
    }

    @Test
    fun pendingClarificationIsSingleSlotAndClearable() {
        val session = ConversationSession()

        session.setPendingDirection("Turn")
        assertEquals(
            ConversationSession.ClarificationKind.DIRECTION,
            session.snapshot().pendingClarification?.kind
        )
        assertEquals(
            "Turn",
            session.snapshot().pendingClarification?.originalInput
        )

        session.setPendingDirection("Move")
        assertEquals(
            "Move",
            session.snapshot().pendingClarification?.originalInput
        )

        session.clearPendingClarification()
        assertNull(session.snapshot().pendingClarification)
    }

    @Test
    fun textIsNormalizedAndLengthBounded() {
        val session = ConversationSession(maxTextChars = 8)

        session.rememberTurn(
            ConversationSession.Speaker.USER,
            "   hello      world   "
        )
        session.rememberFact("  Name  ", "  Alexander  ")

        val snapshot = session.snapshot()

        assertEquals("hello wo", snapshot.turns.single().text)
        assertEquals("Alexande", snapshot.facts["name"])
    }

    @Test
    fun clearRemovesAllVolatileState() {
        val session = ConversationSession()

        session.rememberTurn(ConversationSession.Speaker.USER, "hello")
        session.rememberFact("name", "Alex")
        session.setPendingDirection("Turn")

        session.clear()

        val snapshot = session.snapshot()

        assertTrue(snapshot.turns.isEmpty())
        assertTrue(snapshot.facts.isEmpty())
        assertNull(snapshot.pendingClarification)
    }
}
