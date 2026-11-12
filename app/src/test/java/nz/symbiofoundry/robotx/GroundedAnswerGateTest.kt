package nz.symbiofoundry.robotx

import org.junit.Assert.*
import org.junit.Test

class GroundedAnswerGateTest {
private fun evidence(
id: String = "observation-B1",
status: VisionObservationStatus = VisionObservationStatus.DETECTED,
classes: List<String> = listOf("person"),
supports: Boolean = true,
includeUnknowns: Boolean = true
): VisualEvidenceContract {
val facts = classes.mapIndexed { index, label ->
VerifiedVisualFact("subject-$index", VisualProperty.OBJECT_CLASS, label)
}
val unknowns = if (includeUnknowns) {
facts.associate { fact ->
fact.subjectId to setOf(
VisualProperty.IDENTITY,
VisualProperty.COLOUR,
VisualProperty.AGE,
VisualProperty.TEXT,
VisualProperty.MATERIAL
)
}
} else {
emptyMap()
}
return VisualEvidenceContract(
observationId = id,
capturedAtEpochMs = 12345L,
status = status,
supportsObjectClaims = supports,
verifiedFacts = facts,
unknownProperties = unknowns
)
}

private fun direct(
question: String,
evidence: VisualEvidenceContract = evidence()
): GroundedAnswerDecision.Direct {
val result = GroundedAnswerGate.decide(question, evidence)
assertTrue("Expected Direct, got $result", result is GroundedAnswerDecision.Direct)
return result as GroundedAnswerDecision.Direct
}

private fun generates(
question: String,
reason: Reason,
evidence: VisualEvidenceContract = evidence()
) {
assertEquals(
GroundedAnswerDecision.Generate(reason),
GroundedAnswerGate.decide(question, evidence)
)
}

@Test fun listsOnlyVerifiedObjectClasses() {
val result = direct("What can you see?", evidence(classes = listOf("person", "chair")))
assertEquals("I detected: person, chair.", result.answer)
assertEquals("observation-B1", result.observationId)
}

@Test fun singularObjectQuestionUsesExactlyOneSubject() {
assertEquals("I detected a person.", direct("What is that?").answer)
generates("What is that?", Reason.AMBIGUOUS_SUBJECT, evidence(classes = listOf("person", "chair")))
}

@Test fun presenceQuestionsDistinguishDetectedFromNotDetected() {
assertEquals("Yes, I detected a chair.", direct("Can you see a chair?", evidence(classes = listOf("chair"))).answer)
assertEquals(
"I did not detect a chair in this observation.",
direct("Can you see a chair?", evidence(classes = listOf("person"))).answer
)
}

@Test fun unknownColourAndIdentityAreNotGuessed() {
assertEquals(
"I cannot determine its colour from this observation.",
direct("What colour is it?").answer
)
assertEquals(
"I cannot identify who that is from this observation.",
direct("Who is that?").answer
)
assertEquals(
"I do not have verified colour evidence.",
direct("What colour is it?", evidence(includeUnknowns = false)).answer
)
}

@Test fun unavailableStatusesDoNotMakePositiveClaims() {
val cases = mapOf(
VisionObservationStatus.EMPTY to "I did not detect any objects in this observation.",
VisionObservationStatus.CAMERA_UNAVAILABLE to "The camera was unavailable, so I cannot assess the scene.",
VisionObservationStatus.PERMISSION_DENIED to "Camera permission was denied, so I cannot assess the scene.",
VisionObservationStatus.BUSY to "The camera was busy, so I cannot assess the scene.",
VisionObservationStatus.ANALYSIS_FAILED to "I do not have reliable visual evidence to answer that."
)
for ((status, expected) in cases) {
assertEquals(expected, direct("What can you see?", evidence(status = status)).answer)
}
}

@Test fun unsupportedAndEmptyEvidenceFailClosed() {
assertEquals(
"I do not have reliable visual evidence to answer that.",
direct("What can you see?", evidence(supports = false)).answer
)
assertEquals(
"I do not have reliable visual evidence to answer that.",
direct("What can you see?", evidence(classes = emptyList())).answer
)
}

@Test fun compoundQuestionsCannotBePartiallyAnswered() {
generates("What is that and what colour is it?", Reason.COMPOUND_QUESTION)
generates("Can you see a chair, and a person?", Reason.COMPOUND_QUESTION)
}

@Test fun unsupportedQuestionReturnsGenerate() {
generates("Describe the room", Reason.UNSUPPORTED_QUESTION)
generates("", Reason.UNSUPPORTED_QUESTION)
generates("Surely that is John?", Reason.UNSUPPORTED_QUESTION)
}

@Test fun ambiguousUnknownAttributeDoesNotSelectSubject() {
val multiple = evidence(classes = listOf("person", "chair"))
generates("What colour is it?", Reason.AMBIGUOUS_SUBJECT, multiple)
generates("Who is that?", Reason.AMBIGUOUS_SUBJECT, multiple)
}

@Test fun decisionIsDeterministicAndObservationScoped() {
val first = evidence(id = "first", classes = listOf("chair"))
val second = evidence(id = "second", classes = listOf("person"))
val original = GroundedAnswerGate.decide("What do you see?", first)
assertEquals(original, GroundedAnswerGate.decide("What do you see?", first))
assertEquals("first", (original as GroundedAnswerDecision.Direct).observationId)
val next = direct("What do you see?", second)
assertEquals("second", next.observationId)
assertEquals("I detected: person.", next.answer)
}

@Test fun questionNormalisationIsBounded() {
assertEquals(
"I detected a person.",
direct(" WHAT IS THAT?! ").answer
)
generates("What colour is it or is it red?", Reason.COMPOUND_QUESTION)
}
}
