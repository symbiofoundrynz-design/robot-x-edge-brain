package nz.symbiofoundry.robotx

import org.junit.Assert.*
import org.junit.Test

/**
* I2 coordinator memory-boundary tests.
* ChatBrain is allocated without initialising its Android/LiteRT engine;
* the guarded method uses only the injected candidate callback.
*/
class ConversationCoordinatorGroundedVerificationTest {
private fun coordinator(): ConversationCoordinator {
val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
field.isAccessible = true
val unsafe = field.get(null) as sun.misc.Unsafe
val inertBrain = unsafe.allocateInstance(ChatBrain::class.java) as ChatBrain
return ConversationCoordinator(inertBrain)
}

private fun evidence(
id: String = "I2-observation",
classes: List<String> = listOf("chair")
): VisualEvidenceContract {
val facts = classes.mapIndexed { index, label ->
VerifiedVisualFact("subject-$index", VisualProperty.OBJECT_CLASS, label)
}
return VisualEvidenceContract(
observationId = id,
capturedAtEpochMs = 12345L,
status = VisionObservationStatus.DETECTED,
supportsObjectClaims = true,
verifiedFacts = facts,
unknownProperties = facts.associate {
it.subjectId to setOf(
VisualProperty.IDENTITY,
VisualProperty.COLOUR,
VisualProperty.AGE,
VisualProperty.TEXT,
VisualProperty.MATERIAL
)
}
)
}

private fun context(question: String) = GroundedWorldContext(
currentQuestion = question,
factualCore = "Validated observation evidence.",
verifiedFacts = listOf("Detected: chair"),
unknownFacts = listOf("Colour unknown"),
observationStatus = VisionObservationStatus.DETECTED,
supportsObjectClaims = true
)

private fun robotTurns(coordinator: ConversationCoordinator) =
coordinator.sessionSnapshot().turns.filter {
it.speaker == ConversationSession.Speaker.ROBOT
}.map { it.text }

@Test fun verifiedCandidateIsCommittedToMemory() {
val coordinator = coordinator()
val result = coordinator.replyGroundedVerifiedWithCandidate(
context("Describe the scene"), evidence()
) { ChatBrain.Result("I detected a chair.", 17L) }
assertEquals("I detected a chair.", result.text)
assertEquals(17L, result.inferenceMs)
assertEquals(listOf("I detected a chair."), robotTurns(coordinator))
}

@Test fun rejectedCandidateNeverEntersMemory() {
val coordinator = coordinator()
val result = coordinator.replyGroundedVerifiedWithCandidate(
context("Describe the scene"), evidence()
) { ChatBrain.Result("I detected a dog.", 13L) }
assertEquals(
"I cannot verify a reliable answer from this observation.",
result.text
)
assertEquals(listOf(result.text), robotTurns(coordinator))
assertFalse(coordinator.sessionSnapshot().turns.any { "dog" in it.text })
assertEquals(0L, result.inferenceMs)
}

@Test fun unsupportedAdditionalSentenceIsNotRemembered() {
val coordinator = coordinator()
val result = coordinator.replyGroundedVerifiedWithCandidate(
context("Describe the scene"), evidence()
) { ChatBrain.Result("I detected a chair. It belongs to John.", 5L) }
assertFalse(result.text.contains("John"))
assertFalse(robotTurns(coordinator).any { it.contains("John") })
}

@Test fun directAnswerBypassesCandidateGenerator() {
val coordinator = coordinator()
var called = false
val result = coordinator.replyGroundedVerifiedWithCandidate(
context("What is that?"), evidence()
) {
called = true
error("Direct response must bypass generation")
}
assertFalse(called)
assertEquals("I detected a chair.", result.text)
assertEquals(0L, result.inferenceMs)
assertEquals(listOf(result.text), robotTurns(coordinator))
}

@Test fun candidateGeneratorExceptionProducesRememberedFallback() {
val coordinator = coordinator()
val result = coordinator.replyGroundedVerifiedWithCandidate(
context("Describe the scene"), evidence()
) { throw IllegalStateException("Private inference failure") }
assertEquals(
"I cannot verify a reliable answer from this observation.",
result.text
)
assertEquals(listOf(result.text), robotTurns(coordinator))
assertFalse(robotTurns(coordinator).any { it.contains("Private inference failure") })
}

@Test fun userAndRobotTurnsAreCommittedInOrder() {
val coordinator = coordinator()
coordinator.replyGroundedVerifiedWithCandidate(
context("What is that?"), evidence()
) { error("Unexpected generation") }
val turns = coordinator.sessionSnapshot().turns
assertEquals(2, turns.size)
assertEquals(ConversationSession.Speaker.USER, turns[0].speaker)
assertEquals("What is that?", turns[0].text)
assertEquals(ConversationSession.Speaker.ROBOT, turns[1].speaker)
assertEquals("I detected a chair.", turns[1].text)
}

@Test fun differentCoordinatorSessionsDoNotShareCandidateText() {
val first = coordinator()
val second = coordinator()
first.replyGroundedVerifiedWithCandidate(
context("Describe the scene"), evidence(id = "first")
) { ChatBrain.Result("I detected a dog.", 1L) }
second.replyGroundedVerifiedWithCandidate(
context("What is that?"), evidence(id = "second")
) { error("Unexpected generation") }
assertEquals("I detected a chair.", robotTurns(second).single())
assertFalse(robotTurns(second).any { it.contains("dog") })
}
}
