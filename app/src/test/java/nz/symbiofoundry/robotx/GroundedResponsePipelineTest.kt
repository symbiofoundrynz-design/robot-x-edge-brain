package nz.symbiofoundry.robotx

import org.junit.Assert.*
import org.junit.Test

class GroundedResponsePipelineTest {
private fun evidence(
id: String = "observation-I1",
classes: List<String> = listOf("chair"),
status: VisionObservationStatus = VisionObservationStatus.DETECTED,
supports: Boolean = true
): VisualEvidenceContract {
val facts = classes.mapIndexed { index, label ->
VerifiedVisualFact("subject-$index", VisualProperty.OBJECT_CLASS, label)
}
return VisualEvidenceContract(
observationId = id,
capturedAtEpochMs = 12345L,
status = status,
supportsObjectClaims = supports,
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

private fun resolve(
question: String,
evidence: VisualEvidenceContract = evidence(),
expectedId: String = evidence.observationId,
generate: () -> String = { "I detected a chair." }
) = GroundedResponsePipeline.resolve(
question,
expectedId,
evidence,
generate
)

private fun assertFallback(result: GroundedOutput.Approved) {
assertEquals(GroundedOutputSource.SAFE_FALLBACK, result.source)
assertEquals(
"I cannot verify a reliable answer from this observation.",
result.text
)
}

@Test fun directAnswerDoesNotInvokeGenerator() {
var invocations = 0
val result = resolve("What is that?") {
invocations++
error("Generator must not run for a direct answer")
}
assertEquals(0, invocations)
assertEquals(GroundedOutputSource.DETERMINISTIC, result.source)
assertEquals("I detected a chair.", result.text)
assertEquals("observation-I1", result.observationId)
}

@Test fun unknownColourUsesDeterministicResponse() {
val result = resolve("What colour is it?") {
error("Unknown visual attributes should not require generation")
}
assertEquals(GroundedOutputSource.DETERMINISTIC, result.source)
assertEquals(
"I cannot determine its colour from this observation.",
result.text
)
}

@Test fun supportedGeneratedCandidateIsApproved() {
var calls = 0
val result = resolve("Describe the scene") {
calls++
"I detected a chair."
}
assertEquals(1, calls)
assertEquals(GroundedOutputSource.VERIFIED_GENERATION, result.source)
assertEquals("I detected a chair.", result.text)
}

@Test fun unsupportedGeneratedObjectNeverEscapes() {
val result = resolve("Describe the scene") {
"I detected a dog."
}
assertFallback(result)
assertFalse(result.text.contains("dog"))
}

@Test fun uncertainGeneratedAttributesNeverEscape() {
val result = resolve("Describe the scene") {
"I detected a red chair."
}
assertFallback(result)
assertFalse(result.text.contains("red chair"))
}

@Test fun unsupportedExtraSentenceBlocksEntireCandidate() {
val result = resolve("Describe the scene") {
"I detected a chair. It belongs to John."
}
assertFallback(result)
assertFalse(result.text.contains("John"))
}

@Test fun observationMismatchPreventsGeneration() {
var called = false
val result = resolve(
"Describe the scene",
evidence = evidence(id = "actual-observation"),
expectedId = "stale-observation"
) {
called = true
"I detected a chair."
}
assertFallback(result)
assertFalse(called)
assertEquals("stale-observation", result.observationId)
}

@Test fun blankObservationIdFailsClosed() {
val result = resolve(
"Describe the scene",
expectedId = ""
) {
error("Generator must not run for an invalid observation ID")
}
assertFallback(result)
}

@Test fun generatorExceptionProducesSafeFallback() {
val result = resolve("Describe the scene") {
throw IllegalStateException("Inference unavailable")
}
assertFallback(result)
assertFalse(result.text.contains("Inference unavailable"))
}

@Test fun emptyGeneratedResponseProducesSafeFallback() {
assertFallback(resolve("Describe the scene") { "" })
assertFallback(resolve("Describe the scene") { " " })
}

@Test fun unsupportedQuestionWithCameraFailureCannotLeakClaim() {
val result = resolve(
"Describe the scene",
evidence = evidence(
status = VisionObservationStatus.CAMERA_UNAVAILABLE,
supports = false
)
) {
"I detected a chair."
}
assertFallback(result)
}

@Test fun directCameraFailureResponseIsDeterministic() {
val result = resolve(
"What can you see?",
evidence = evidence(
status = VisionObservationStatus.CAMERA_UNAVAILABLE,
supports = false
)
) {
error("Generator must not run for a supported status question")
}
assertEquals(GroundedOutputSource.DETERMINISTIC, result.source)
assertEquals(
"The camera was unavailable, so I cannot assess the scene.",
result.text
)
}

@Test fun emptyAndOversizedQuestionsFailClosed() {
assertFallback(resolve("") { error("No generation expected") })
assertFallback(resolve("x".repeat(1001)) { error("No generation expected") })
}

@Test fun sameInputsProduceSameApprovedOutput() {
val first = resolve("Describe the scene") { "I detected a chair." }
val second = resolve("Describe the scene") { "I detected a chair." }
assertEquals(first, second)
}

@Test fun separateObservationsDoNotShareOutputIdentity() {
val first = resolve("What is that?", evidence(id = "first"))
val second = resolve("What is that?", evidence(id = "second"))
assertEquals("first", first.observationId)
assertEquals("second", second.observationId)
assertEquals(first.text, second.text)
}
}
