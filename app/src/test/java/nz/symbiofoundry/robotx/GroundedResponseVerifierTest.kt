package nz.symbiofoundry.robotx

import org.junit.Assert.*
import org.junit.Test

class GroundedResponseVerifierTest {
private fun evidence(
id: String = "observation-C1",
status: VisionObservationStatus = VisionObservationStatus.DETECTED,
classes: List<String> = listOf("chair"),
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

private fun verify(
text: String,
evidence: VisualEvidenceContract = evidence(),
expectedId: String = evidence.observationId
) = GroundedResponseVerifier.verify(text, expectedId, evidence)

private fun assertVerdict(
expected: VerificationVerdict,
result: GroundedVerificationResult
) {
assertEquals(expected, result.verdict)
if (expected == VerificationVerdict.ACCEPT) {
assertNotNull(result.approvedText)
} else {
assertNull("Unapproved text must not escape the verifier", result.approvedText)
}
}

@Test fun verifiedSingleDetectionIsAccepted() {
val result = verify("I detected a chair.")
assertVerdict(VerificationVerdict.ACCEPT, result)
assertEquals("I detected a chair.", result.approvedText)
assertEquals("observation-C1", result.observationId)
assertVerdict(VerificationVerdict.ACCEPT, verify("Yes, I detected a chair."))
}

@Test fun unsupportedClassClaimIsRejected() {
val result = verify("I detected a dog.")
assertVerdict(VerificationVerdict.REJECT, result)
assertEquals(VerificationReason.CONTRADICTED_FACT, result.reason)
}

@Test fun knownAndUnknownDetectionsAreNotPartiallyApproved() {
val scene = evidence(classes = listOf("chair", "person"))
assertVerdict(
VerificationVerdict.ACCEPT,
verify("I detected: chair, person.", scene)
)
assertVerdict(
VerificationVerdict.REJECT,
verify("I detected: chair, dog.", scene)
)
}

@Test fun colourAndMaterialClaimsRemainUnverified() {
val colour = verify("I detected a red chair.")
assertVerdict(VerificationVerdict.UNCERTAIN, colour)
assertEquals(VerificationReason.UNSUPPORTED_ATTRIBUTE, colour.reason)
assertVerdict(VerificationVerdict.UNCERTAIN, verify("The chair is wooden."))
assertVerdict(VerificationVerdict.UNCERTAIN, verify("The chair is old."))
}

@Test fun identityClaimsRemainUnverified() {
assertVerdict(VerificationVerdict.UNCERTAIN, verify("That is John."))
assertVerdict(VerificationVerdict.UNCERTAIN, verify("I can read the sign."))
val person = evidence(classes = listOf("person"))
assertVerdict(
VerificationVerdict.ACCEPT,
verify("I cannot identify who that is from this observation.", person)
)
}

@Test fun supportedUnknownPropertyTemplatesCanPass() {
assertVerdict(
VerificationVerdict.ACCEPT,
verify("I cannot determine its colour from this observation.")
)
assertVerdict(
VerificationVerdict.UNCERTAIN,
verify(
"I cannot determine its colour from this observation.",
evidence(includeUnknowns = false)
)
)
}

@Test fun observationMismatchRejectsEvenTrueClaim() {
val result = verify("I detected a chair.", expectedId = "different-observation")
assertVerdict(VerificationVerdict.REJECT, result)
assertEquals(VerificationReason.OBSERVATION_MISMATCH, result.reason)
assertVerdict(VerificationVerdict.REJECT, verify("I detected a chair.", expectedId = ""))
}

@Test fun cameraUnavailableBlocksPositiveClaims() {
val scene = evidence(status = VisionObservationStatus.CAMERA_UNAVAILABLE)
assertVerdict(VerificationVerdict.REJECT, verify("I detected a chair.", scene))
assertVerdict(
VerificationVerdict.ACCEPT,
verify("The camera was unavailable, so I cannot assess the scene.", scene)
)
assertVerdict(
VerificationVerdict.ACCEPT,
verify(
"I did not detect any objects in this observation.",
evidence(status = VisionObservationStatus.EMPTY, classes = emptyList())
)
)
assertVerdict(
VerificationVerdict.REJECT,
verify("I detected a chair.", evidence(supports = false))
)
}

@Test fun detectionDoesNotProvePhysicalAbsence() {
assertVerdict(
VerificationVerdict.UNCERTAIN,
verify("There is definitely no dog.")
)
assertVerdict(
VerificationVerdict.ACCEPT,
verify("I did not detect a dog in this observation.")
)
assertVerdict(
VerificationVerdict.REJECT,
verify("I did not detect a chair in this observation.")
)
}

@Test fun extraSentenceNeverGetsSubstringApproval() {
assertVerdict(
VerificationVerdict.UNCERTAIN,
verify("I detected a chair. It belongs to John.")
)
assertVerdict(
VerificationVerdict.UNCERTAIN,
verify("I detected a chair. I detected a dog.")
)
assertVerdict(
VerificationVerdict.UNCERTAIN,
verify("I think that might be a chair.")
)
}

@Test fun malformedEmptyAndOversizedInputsFailClosed() {
assertVerdict(VerificationVerdict.UNCERTAIN, verify(""))
assertVerdict(VerificationVerdict.UNCERTAIN, verify(" "))
assertVerdict(VerificationVerdict.UNCERTAIN, verify("I detected a chair!"))
assertVerdict(VerificationVerdict.UNCERTAIN, verify("I detected a chair.\n"))
assertVerdict(VerificationVerdict.UNCERTAIN, verify("x".repeat(501)))
}

@Test fun ambiguousReferencesRemainUnverified() {
val scene = evidence(classes = listOf("chair", "person"))
assertVerdict(VerificationVerdict.UNCERTAIN, verify("It is red.", scene))
assertVerdict(VerificationVerdict.UNCERTAIN, verify("That one is John.", scene))
}

@Test fun identicalInputsProduceIdenticalVerdicts() {
val scene = evidence()
val first = verify("I detected a chair.", scene)
val second = verify("I detected a chair.", scene)
assertEquals(first, second)
}
}
