package nz.symbiofoundry.robotx

import org.junit.Assert.*
import org.junit.Test

class VisualEvidenceContractTest {
private fun detection(
label: String = "person",
confidence: Float = 0.90f,
left: Float = 1f,
right: Float = 50f
) = VisionDetection(label, confidence, left, 1f, right, 50f)

private fun observation(
id: String = "observation-1",
status: VisionObservationStatus = VisionObservationStatus.DETECTED,
detections: List<VisionDetection> = listOf(detection()),
width: Int? = 100,
height: Int? = 100
) = VisionObservation(
requestId = id,
status = status,
requestedCamera = VisionCamera.DEFAULT,
actualCamera = VisionCamera.FRONT,
capturedAtEpochMs = 12345L,
imagePath = null,
sourceWidth = width,
sourceHeight = height,
sourceBytes = null,
inferenceMs = null,
detections = detections
)

private fun mapped(
observation: VisionObservation,
supports: Boolean = true,
count: Int = observation.detections.size,
groundedStatus: VisionObservationStatus = observation.status
) = VisualEvidenceContractMapper.map(
observation,
GroundedVisionContext(groundedStatus, "Test evidence", supports, count)
)

@Test fun acceptedDetectionCreatesOnlyObjectClassFact() {
val result = mapped(observation())
assertEquals("observation-1", result.observationId)
assertEquals(12345L, result.capturedAtEpochMs)
assertTrue(result.supportsObjectClaims)
assertEquals(listOf(VerifiedVisualFact("subject-0", VisualProperty.OBJECT_CLASS, "person")), result.verifiedFacts)
assertEquals(
setOf(VisualProperty.IDENTITY, VisualProperty.COLOUR, VisualProperty.AGE, VisualProperty.TEXT, VisualProperty.MATERIAL),
result.unknownProperties["subject-0"]
)
}

@Test fun confidenceBelowThresholdFailsClosed() {
val result = mapped(observation(detections = listOf(detection(confidence = 0.49f))), count = 0)
assertFalse(result.supportsObjectClaims)
assertTrue(result.verifiedFacts.isEmpty())
}

@Test fun blankLabelAndInvalidBoundingBoxFailClosed() {
for (bad in listOf(detection(label = " "), detection(left = -1f), detection(left = 60f, right = 50f))) {
val result = mapped(observation(detections = listOf(bad)), count = 0)
assertFalse(result.supportsObjectClaims)
assertTrue(result.verifiedFacts.isEmpty())
}
}

@Test fun nonFiniteConfidenceFailsClosed() {
val result = mapped(observation(detections = listOf(detection(confidence = Float.NaN))), count = 0)
assertFalse(result.supportsObjectClaims)
}

@Test fun detectionCountIsBoundedToTen() {
val result = mapped(observation(detections = List(11) { detection(label = "object$it") }), count = 10)
assertEquals(10, result.verifiedFacts.size)
assertEquals("subject-9", result.verifiedFacts.last().subjectId)
}

@Test fun unavailableEmptyAndInconsistentEvidenceFailClosed() {
for (status in listOf(VisionObservationStatus.EMPTY, VisionObservationStatus.CAMERA_UNAVAILABLE, VisionObservationStatus.ANALYSIS_FAILED)) {
val result = mapped(observation(status = status))
assertFalse(result.supportsObjectClaims)
assertTrue(result.verifiedFacts.isEmpty())
}
assertFalse(mapped(observation(), groundedStatus = VisionObservationStatus.EMPTY).supportsObjectClaims)
assertFalse(mapped(observation(), supports = false).supportsObjectClaims)
assertFalse(mapped(observation(), count = 0).supportsObjectClaims)
assertFalse(mapped(observation(width = null)).supportsObjectClaims)
assertFalse(mapped(observation(detections = emptyList()), count = 0).supportsObjectClaims)
}

@Test fun distinctDetectionsHaveSeparateSubjects() {
val result = mapped(observation(detections = listOf(detection("person"), detection("chair"))))
assertEquals(listOf("subject-0", "subject-1"), result.verifiedFacts.map { it.subjectId })
assertEquals(listOf("person", "chair"), result.verifiedFacts.map { it.value })
assertEquals(setOf("subject-0", "subject-1"), result.unknownProperties.keys)
}

@Test fun observationsRetainDifferentIdsAndDoNotShareFacts() {
val first = mapped(observation(id = "first", detections = listOf(detection("person"))))
val second = mapped(observation(id = "second", detections = listOf(detection("chair"))))
assertNotEquals(first.observationId, second.observationId)
assertEquals("person", first.verifiedFacts.single().value)
assertEquals("chair", second.verifiedFacts.single().value)
}
}
