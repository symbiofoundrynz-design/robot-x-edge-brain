package nz.symbiofoundry.robotx

enum class VisualProperty {
OBJECT_CLASS,
IDENTITY,
COLOUR,
AGE,
TEXT,
MATERIAL
}

data class VerifiedVisualFact(
val subjectId: String,
val property: VisualProperty,
val value: String
)

data class VisualEvidenceContract(
val observationId: String,
val capturedAtEpochMs: Long?,
val status: VisionObservationStatus,
val supportsObjectClaims: Boolean,
val verifiedFacts: List<VerifiedVisualFact>,
val unknownProperties: Map<String, Set<VisualProperty>>
)

/**
* Package A: deterministic, observation-scoped evidence mapping.
* No LLM, camera acquisition, persistence, or robot-control authority.
* Revalidates detections and fails closed when source dimensions or
* grounded-adapter evidence do not agree.
*/
object VisualEvidenceContractMapper {
private val UNKNOWN_ATTRIBUTES = setOf(
VisualProperty.IDENTITY,
VisualProperty.COLOUR,
VisualProperty.AGE,
VisualProperty.TEXT,
VisualProperty.MATERIAL
)

fun map(
observation: VisionObservation,
grounded: GroundedVisionContext
): VisualEvidenceContract {
val width = observation.sourceWidth
val height = observation.sourceHeight
val accepted = if (
observation.status == VisionObservationStatus.DETECTED &&
width != null && width > 0 &&
height != null && height > 0
) {
VisionObservationValidator.validateDetections(
detections = observation.detections,
sourceWidth = width,
sourceHeight = height
).accepted
} else {
emptyList()
}

val canClaimObjects =
observation.status == VisionObservationStatus.DETECTED &&
grounded.status == observation.status &&
grounded.supportsObjectClaims &&
grounded.detectionCount == accepted.size &&
accepted.isNotEmpty()

val facts = if (canClaimObjects) {
accepted.mapIndexed { index, detection ->
VerifiedVisualFact(
subjectId = "subject-$index",
property = VisualProperty.OBJECT_CLASS,
value = detection.label.trim().replace("\n", " ").replace("\r", " ").take(80)
)
}
} else {
emptyList()
}

val unknowns = facts.associate { fact ->
fact.subjectId to UNKNOWN_ATTRIBUTES.toSet()
}

return VisualEvidenceContract(
observationId = observation.requestId,
capturedAtEpochMs = observation.capturedAtEpochMs,
status = observation.status,
supportsObjectClaims = canClaimObjects,
verifiedFacts = facts.toList(),
unknownProperties = unknowns.toMap()
)
}
}
