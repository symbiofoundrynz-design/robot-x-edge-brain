package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCoordinatorRoutingTest {
    private fun route(text: String) =
        ConversationCoordinator.classifyText(text).route

    @Test
    fun routingMatrix() {
        val cases = linkedMapOf(
            "Stop" to ConversationCoordinator.Route.STOP,
            "Robot X stop now" to ConversationCoordinator.Route.STOP,
            "Robot X stopped now" to ConversationCoordinator.Route.STOP,
            "Can you see what's in front of you?" to ConversationCoordinator.Route.VISION_REQUIRED,

            "Move forward" to ConversationCoordinator.Route.ACTION,
            "Please turn left" to ConversationCoordinator.Route.ACTION,
            "Robot X turn right" to ConversationCoordinator.Route.ACTION,
            "Can you move forward please?" to ConversationCoordinator.Route.ACTION,
            "Could you reverse?" to ConversationCoordinator.Route.ACTION,

            "Move" to ConversationCoordinator.Route.ACTION_INCOMPLETE,
            "Turn" to ConversationCoordinator.Route.ACTION_INCOMPLETE,

            "Hello Robot X" to ConversationCoordinator.Route.CHAT_MODEL,
            "Tell me a joke" to ConversationCoordinator.Route.CHAT_MODEL,
            "What does forward mean?" to ConversationCoordinator.Route.CHAT_MODEL,
            "Tell me about turning left" to ConversationCoordinator.Route.CHAT_MODEL,
            "What happens if you move forward?" to ConversationCoordinator.Route.CHAT_MODEL,
            "Why would you reverse?" to ConversationCoordinator.Route.CHAT_MODEL
        )

        cases.forEach { (input, expected) ->
            assertEquals("Input: $input", expected, route(input))
        }
    }

    @Test
    fun incompleteActionNeverBecomesAction() {
        assertEquals(
            ConversationCoordinator.Route.ACTION_INCOMPLETE,
            route("move")
        )
        assertEquals(
            ConversationCoordinator.Route.ACTION_INCOMPLETE,
            route("turn")
        )
    }

    @Test
    fun explanatoryMovementLanguageNeverBecomesAction() {
        listOf(
            "What does forward mean?",
            "Tell me about turning left",
            "What happens if you move forward?",
            "Why would you reverse?"
        ).forEach {
            assertEquals(
                "Input: $it",
                ConversationCoordinator.Route.CHAT_MODEL,
                route(it)
            )
        }
    }


    @Test
    fun canonicalBasicDirectionsResolveDeterministically() {
        val cases = listOf(
            "Move forward" to ConversationCoordinator.CanonicalAction.FORWARD,
            "Go forwards" to ConversationCoordinator.CanonicalAction.FORWARD,
            "Can you move forward please" to ConversationCoordinator.CanonicalAction.FORWARD,

            "Reverse" to ConversationCoordinator.CanonicalAction.REVERSE,
            "Move backward" to ConversationCoordinator.CanonicalAction.REVERSE,
            "Move backwards" to ConversationCoordinator.CanonicalAction.REVERSE,
            "Go backward" to ConversationCoordinator.CanonicalAction.REVERSE,
            "Go backwards" to ConversationCoordinator.CanonicalAction.REVERSE,
            "Drive backward" to ConversationCoordinator.CanonicalAction.REVERSE,
            "Drive backwards" to ConversationCoordinator.CanonicalAction.REVERSE,
            "Back up" to ConversationCoordinator.CanonicalAction.REVERSE,
            "Please back up" to ConversationCoordinator.CanonicalAction.REVERSE,
            "Can you move backwards please" to ConversationCoordinator.CanonicalAction.REVERSE,

            "Turn left" to ConversationCoordinator.CanonicalAction.TURN_LEFT,
            "Please turn left" to ConversationCoordinator.CanonicalAction.TURN_LEFT,
            "Turn right" to ConversationCoordinator.CanonicalAction.TURN_RIGHT,
            "Could you turn right please" to ConversationCoordinator.CanonicalAction.TURN_RIGHT
        )

        for ((text, expected) in cases) {
            val decision = ConversationCoordinator.classifyText(text)
            assertEquals("route for: $text", ConversationCoordinator.Route.ACTION, decision.route)
            assertEquals("canonical action for: $text", expected, decision.canonicalAction)
        }
    }

    @Test
    fun explanatoryDirectionLanguageNeverGetsCanonicalAction() {
        val cases = listOf(
            "What does forward mean",
            "Tell me about turning left",
            "Why would you reverse",
            "What happens if you move backwards",
            "Explain how backing up works"
        )

        for (text in cases) {
            val decision = ConversationCoordinator.classifyText(text)
            assertEquals("canonical action for: $text", null, decision.canonicalAction)
        }
    }


    @Test
    fun pendingTurnResolvesOnlyLeftOrRight() {
        val pending = ConversationSession.PendingClarification(
            kind = ConversationSession.ClarificationKind.DIRECTION,
            originalInput = "Turn"
        )

        assertEquals(
            ConversationCoordinator.CanonicalAction.TURN_LEFT,
            ConversationCoordinator.resolvePendingDirection(pending, "Left")
        )
        assertEquals(
            ConversationCoordinator.CanonicalAction.TURN_RIGHT,
            ConversationCoordinator.resolvePendingDirection(pending, "right")
        )
        assertEquals(
            null,
            ConversationCoordinator.resolvePendingDirection(pending, "backwards")
        )
        assertEquals(
            null,
            ConversationCoordinator.resolvePendingDirection(pending, "hello")
        )
    }

    @Test
    fun pendingMoveResolvesBoundedDirections() {
        val pending = ConversationSession.PendingClarification(
            kind = ConversationSession.ClarificationKind.DIRECTION,
            originalInput = "Move"
        )

        val cases = mapOf(
            "forward" to ConversationCoordinator.CanonicalAction.FORWARD,
            "forwards" to ConversationCoordinator.CanonicalAction.FORWARD,
            "ahead" to ConversationCoordinator.CanonicalAction.FORWARD,
            "back" to ConversationCoordinator.CanonicalAction.REVERSE,
            "backward" to ConversationCoordinator.CanonicalAction.REVERSE,
            "backwards" to ConversationCoordinator.CanonicalAction.REVERSE,
            "reverse" to ConversationCoordinator.CanonicalAction.REVERSE,
            "back up" to ConversationCoordinator.CanonicalAction.REVERSE,
            "left" to ConversationCoordinator.CanonicalAction.TURN_LEFT,
            "right" to ConversationCoordinator.CanonicalAction.TURN_RIGHT
        )

        for ((text, expected) in cases) {
            assertEquals(
                "pending move follow-up: $text",
                expected,
                ConversationCoordinator.resolvePendingDirection(pending, text)
            )
        }

        assertEquals(
            null,
            ConversationCoordinator.resolvePendingDirection(pending, "tell me a joke")
        )
    }

    @Test
    fun bareDirectionWithoutPendingStateIsNotCanonicalAction() {
        for (text in listOf("Left", "Right", "Backwards", "Forward")) {
            val decision = ConversationCoordinator.classifyText(text)
            assertEquals(
                "bare direction must not become canonical action: $text",
                null,
                decision.canonicalAction
            )
        }
    }


    @Test
    fun explicitSessionFactsAreDeterministic() {
        assertEquals(
            "name" to "Alex",
            ConversationCoordinator.extractExplicitSessionFact("My name is Alex")
        )
        assertEquals(
            "preferred_name" to "Taylor",
            ConversationCoordinator.extractExplicitSessionFact("Call me Taylor.")
        )
        assertEquals(
            "favorite_color" to "dark green",
            ConversationCoordinator.extractExplicitSessionFact(
                "My favourite colour is dark green"
            )
        )
        assertEquals(
            "location" to "Wellington",
            ConversationCoordinator.extractExplicitSessionFact(
                "I live in Wellington."
            )
        )
    }

    @Test
    fun nonExplicitStatementsAreNotStoredAsFacts() {
        for (text in listOf(
            "What is my name?",
            "I like green",
            "Maybe call me later",
            "Where do I live?",
            "My robot is moving"
        )) {
            assertEquals(
                "must not infer a session fact: $text",
                null,
                ConversationCoordinator.extractExplicitSessionFact(text)
            )
        }
    }

    @Test
    fun renderedSessionContextContainsOnlySnapshotMaterial() {
        val snapshot = ConversationSession.Snapshot(
            turns = listOf(
                ConversationSession.Turn(
                    ConversationSession.Speaker.USER,
                    "Hello"
                ),
                ConversationSession.Turn(
                    ConversationSession.Speaker.ROBOT,
                    "Hi"
                )
            ),
            facts = linkedMapOf(
                "name" to "Alex",
                "favorite_color" to "green"
            ),
            pendingClarification = null
        )

        val rendered = ConversationCoordinator.renderSessionContext(snapshot)

        assertTrue(rendered.contains("Session facts:"))
        assertTrue(rendered.contains("- name: Alex"))
        assertTrue(rendered.contains("- favorite_color: green"))
        assertTrue(rendered.contains("Recent conversation:"))
        assertTrue(rendered.contains("User: Hello"))
        assertTrue(rendered.contains("Robot: Hi"))
    }


    @Test
    fun explicitSessionFactRecallIsDeterministic() {
        val snapshot = ConversationSession.Snapshot(
            turns = emptyList(),
            facts = linkedMapOf(
                "name" to "Alex",
                "preferred_name" to "Taylor",
                "favorite_color" to "green",
                "location" to "Wellington"
            ),
            pendingClarification = null
        )

        assertEquals(
            "Your name is Taylor.",
            ConversationCoordinator.explicitSessionFactReply(
                "What is my name?",
                snapshot
            )
        )

        assertEquals(
            "Your favourite colour is green.",
            ConversationCoordinator.explicitSessionFactReply(
                "What is my favourite colour?",
                snapshot
            )
        )

        assertEquals(
            "Your favourite colour is green.",
            ConversationCoordinator.explicitSessionFactReply(
                "What's my favorite color?",
                snapshot
            )
        )

        assertEquals(
            "You live in Wellington.",
            ConversationCoordinator.explicitSessionFactReply(
                "Where do I live?",
                snapshot
            )
        )
    }

    @Test
    fun unknownExplicitFactReturnsTruthfulUncertainty() {
        val snapshot = ConversationSession.Snapshot(
            turns = emptyList(),
            facts = emptyMap(),
            pendingClarification = null
        )

        assertEquals(
            "You haven't told me your name yet.",
            ConversationCoordinator.explicitSessionFactReply(
                "What is my name?",
                snapshot
            )
        )

        assertEquals(
            "You haven't told me your favourite colour yet.",
            ConversationCoordinator.explicitSessionFactReply(
                "What is my favourite colour?",
                snapshot
            )
        )

        assertEquals(
            "You haven't told me where you live yet.",
            ConversationCoordinator.explicitSessionFactReply(
                "Where do I live?",
                snapshot
            )
        )
    }

    @Test
    fun unrelatedConversationDoesNotTriggerFactRecall() {
        val snapshot = ConversationSession.Snapshot(
            turns = emptyList(),
            facts = mapOf("favorite_color" to "green"),
            pendingClarification = null
        )

        for (text in listOf(
            "Tell me about green",
            "Do you like colours?",
            "What is your name?",
            "Move forward"
        )) {
            assertEquals(
                "must remain outside deterministic user-fact recall: $text",
                null,
                ConversationCoordinator.explicitSessionFactReply(
                    text,
                    snapshot
                )
            )
        }
    }


    @Test
    fun visualPerceptionQuestionsUseDeterministicCapabilityGuard() {
        for (text in listOf(
            "What am I holding?",
            "What is in front of you?",
            "Who is in front of you?",
            "What colour is this?",
            "What color is this?",
            "Describe what you see"
        )) {
            val decision = ConversationCoordinator.classifyText(text)

            assertEquals(
                "visual perception must use coordinator guard: $text",
                ConversationCoordinator.Route.VISION_REQUIRED,
                decision.route
            )
 assertEquals(null, decision.fixedReply)
            assertEquals(
                null,
                decision.canonicalAction
            )
        }
    }

    @Test
    fun visualCapabilityGuardDoesNotAcquirePhysicalAuthority() {
        val decision = ConversationCoordinator.classifyText(
            "What am I holding?"
        )

        assertEquals(
            ConversationCoordinator.Route.VISION_REQUIRED,
            decision.route
        )
        assertEquals(null, decision.canonicalAction)
    }

    @Test
    fun ordinaryNonVisualConversationDoesNotUseNewPerceptionPhrases() {
        for (text in listOf(
            "Tell me what holding a conversation means",
            "My favourite colour is green",
            "Who are you?",
            "Describe yourself"
        )) {
            val decision = ConversationCoordinator.classifyText(text)

            assertEquals(
                "ordinary conversation should remain conversational: $text",
                ConversationCoordinator.Route.CHAT_MODEL,
                decision.route
            )
        }
    }
}
