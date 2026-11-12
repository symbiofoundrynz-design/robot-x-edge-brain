package nz.symbiofoundry.robotx

import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

class ConversationCoordinatorLifecycleCommitTest {
private fun coordinator(): ConversationCoordinator {
val field = Unsafe::class.java.getDeclaredField("theUnsafe")
field.isAccessible = true
val unsafe = field.get(null) as Unsafe
val inertBrain = unsafe.allocateInstance(ChatBrain::class.java) as ChatBrain
return ConversationCoordinator(inertBrain)
}

private fun prepared(
observationId: String = "observation-a",
question: String = "What is visible?",
answer: String = "I can see a chair.",
route: ConversationCoordinator.Route = ConversationCoordinator.Route.VISION_REQUIRED
) = ConversationCoordinator.PreparedGroundedReply(
question = question,
observationId = observationId,
result = ConversationCoordinator.Result(route, answer, 0L)
)

@Test fun currentMatchingTokenCommitsApprovedExchange() {
val coordinator = coordinator()
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
val result = coordinator.commitPreparedGroundedReply(prepared(), owner, token)
assertNotNull(result)
assertEquals("I can see a chair.", result!!.text)
val turns = coordinator.sessionSnapshot().turns
assertEquals(2, turns.size)
assertEquals(ConversationSession.Speaker.USER, turns[0].speaker)
assertEquals("What is visible?", turns[0].text)
assertEquals(ConversationSession.Speaker.ROBOT, turns[1].speaker)
assertEquals("I can see a chair.", turns[1].text)
}

@Test fun uncommittedPreparedDataDoesNotEnterMemory() {
val coordinator = coordinator()
val pending = prepared()
assertEquals("observation-a", pending.observationId)
assertTrue(coordinator.sessionSnapshot().turns.isEmpty())
}

@Test fun supersededTokenCannotCommit() {
val coordinator = coordinator()
val owner = VisualResponseLifecycle()
val old = owner.begin("observation-a")
owner.begin("observation-b")
assertNull(coordinator.commitPreparedGroundedReply(prepared(), owner, old))
assertTrue(coordinator.sessionSnapshot().turns.isEmpty())
}

@Test fun invalidatedTokenCannotCommit() {
val coordinator = coordinator()
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
owner.invalidate()
assertNull(coordinator.commitPreparedGroundedReply(prepared(), owner, token))
assertTrue(coordinator.sessionSnapshot().turns.isEmpty())
}

@Test fun mismatchedObservationCannotCommitOrConsumeToken() {
val coordinator = coordinator()
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
assertNull(coordinator.commitPreparedGroundedReply(prepared(observationId = "observation-b"), owner, token))
assertTrue(coordinator.sessionSnapshot().turns.isEmpty())
assertNotNull(coordinator.commitPreparedGroundedReply(prepared(), owner, token))
}

@Test fun duplicateCommitDoesNotDuplicateHistory() {
val coordinator = coordinator()
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
assertNotNull(coordinator.commitPreparedGroundedReply(prepared(), owner, token))
assertNull(coordinator.commitPreparedGroundedReply(prepared(), owner, token))
assertEquals(2, coordinator.sessionSnapshot().turns.size)
}

@Test fun blankContentRejectsWithoutConsumingToken() {
val coordinator = coordinator()
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
assertNull(coordinator.commitPreparedGroundedReply(prepared(question = " "), owner, token))
assertNull(coordinator.commitPreparedGroundedReply(prepared(answer = " "), owner, token))
assertTrue(coordinator.sessionSnapshot().turns.isEmpty())
assertNotNull(coordinator.commitPreparedGroundedReply(prepared(), owner, token))
}

@Test fun nonVisualRouteIsRejected() {
val coordinator = coordinator()
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
assertNull(coordinator.commitPreparedGroundedReply(prepared(route = ConversationCoordinator.Route.CHAT_MODEL), owner, token))
assertTrue(coordinator.sessionSnapshot().turns.isEmpty())
}

@Test fun committedResultPermitsExactlyOnePublicationClaim() {
val coordinator = coordinator()
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
assertFalse(owner.claimPublication(token))
assertNotNull(coordinator.commitPreparedGroundedReply(prepared(), owner, token))
assertTrue(owner.claimPublication(token))
assertFalse(owner.claimPublication(token))
}
}
