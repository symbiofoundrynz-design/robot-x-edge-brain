package nz.symbiofoundry.robotx

enum class VerificationVerdict {
ACCEPT,
REJECT,
UNCERTAIN
}

enum class VerificationReason {
SUPPORTED_FACT,
CONTRADICTED_FACT,
UNSUPPORTED_ATTRIBUTE,
UNSUPPORTED_SUBJECT,
AMBIGUOUS_REFERENCE,
COMPOUND_UNVERIFIABLE,
UNRECOGNISED_CLAIM,
INVALID_OBSERVATION,
OBSERVATION_MISMATCH,
EMPTY_RESPONSE
}

data class GroundedVerificationResult(
val verdict: VerificationVerdict,
val reason: VerificationReason,
val observationId: String,
val approvedText: String?
)

/**
* Package C: bounded verification of visual responses.
* Recognised complete statements only; unknown language fails closed.
* Does not acquire camera evidence, invoke an LLM, persist text, or move hardware.
*/
object GroundedResponseVerifier {
private const val MAX_CANDIDATE_LENGTH = 500
private const val MAX_CLASSES = 10

private val singleDetection = Regex(
"^(?:Yes, )?I detected (?:a |an )([A-Za-z][A-Za-z0-9 -]{0,79})\\.$"
)
private val detectionList = Regex(
"^I detected: ([A-Za-z0-9 ,&-]+)\\.$"
)
private val notDetected = Regex(
"^I did not detect (?:a |an )([A-Za-z][A-Za-z0-9 -]{0,79}) in this observation\\.$"
)

fun verify(
candidate: String,
expectedObservationId: String,
evidence: VisualEvidenceContract
): GroundedVerificationResult {
fun result(
verdict: VerificationVerdict,
reason: VerificationReason
) = GroundedVerificationResult(
verdict = verdict,
reason = reason,
observationId = expectedObservationId,
approvedText = if (verdict == VerificationVerdict.ACCEPT) candidate else null
)

if (expectedObservationId.isBlank() ||
evidence.observationId != expectedObservationId) {
return result(VerificationVerdict.REJECT, VerificationReason.OBSERVATION_MISMATCH)
}

if (candidate.isBlank()) {
return result(VerificationVerdict.UNCERTAIN, VerificationReason.EMPTY_RESPONSE)
}

if (candidate.length > MAX_CANDIDATE_LENGTH ||
candidate.any { it.isISOControl() } ||
candidate != candidate.trim()) {
return result(VerificationVerdict.UNCERTAIN, VerificationReason.UNRECOGNISED_CLAIM)
}

val unavailableAnswer = when (evidence.status) {
VisionObservationStatus.EMPTY ->
"I did not detect any objects in this observation."
VisionObservationStatus.CAMERA_UNAVAILABLE ->
"The camera was unavailable, so I cannot assess the scene."
VisionObservationStatus.PERMISSION_DENIED ->
"Camera permission was denied, so I cannot assess the scene."
VisionObservationStatus.BUSY ->
"The camera was busy, so I cannot assess the scene."
else -> "I do not have reliable visual evidence to answer that."
}

val supported = evidence.status == VisionObservationStatus.DETECTED &&
evidence.supportsObjectClaims &&
evidence.verifiedFacts.isNotEmpty()

if (!supported) {
return if (candidate == unavailableAnswer) {
result(VerificationVerdict.ACCEPT, VerificationReason.SUPPORTED_FACT)
} else if (candidate.startsWith("I detected") ||
candidate.startsWith("Yes, I detected")) {
result(VerificationVerdict.REJECT, VerificationReason.INVALID_OBSERVATION)
} else {
result(VerificationVerdict.UNCERTAIN, VerificationReason.INVALID_OBSERVATION)
}
}

val facts = evidence.verifiedFacts
if (facts.size > MAX_CLASSES ||
facts.any {
it.property != VisualProperty.OBJECT_CLASS ||
it.subjectId.isBlank() ||
it.value.isBlank()
} ||
facts.map { it.subjectId }.toSet().size != facts.size) {
return result(VerificationVerdict.UNCERTAIN, VerificationReason.INVALID_OBSERVATION)
}

val classes = facts.map { it.value.trim() }
fun hasClass(label: String): Boolean =
classes.any { it.equals(label.trim(), ignoreCase = true) }

fun claimsUnknownAttribute(label: String): Boolean =
classes.any { known ->
label.endsWith(" " + known, ignoreCase = true)
}

fun checkClass(label: String): GroundedVerificationResult =
when {
hasClass(label) ->
result(VerificationVerdict.ACCEPT, VerificationReason.SUPPORTED_FACT)
claimsUnknownAttribute(label) ->
result(VerificationVerdict.UNCERTAIN, VerificationReason.UNSUPPORTED_ATTRIBUTE)
else ->
result(VerificationVerdict.REJECT, VerificationReason.CONTRADICTED_FACT)
}

singleDetection.matchEntire(candidate)?.let {
return checkClass(it.groupValues[1])
}

detectionList.matchEntire(candidate)?.let { match ->
val requested = match.groupValues[1].split(",").map { it.trim() }
if (requested.isEmpty() || requested.size > MAX_CLASSES ||
requested.any { it.isEmpty() }) {
return result(VerificationVerdict.UNCERTAIN, VerificationReason.COMPOUND_UNVERIFIABLE)
}
val outcomes = requested.map { checkClass(it) }
return when {
outcomes.any { it.verdict == VerificationVerdict.REJECT } ->
result(VerificationVerdict.REJECT, VerificationReason.CONTRADICTED_FACT)
outcomes.any { it.verdict != VerificationVerdict.ACCEPT } ->
result(VerificationVerdict.UNCERTAIN, VerificationReason.COMPOUND_UNVERIFIABLE)
else ->
result(VerificationVerdict.ACCEPT, VerificationReason.SUPPORTED_FACT)
}
}

notDetected.matchEntire(candidate)?.let {
return if (hasClass(it.groupValues[1])) {
result(VerificationVerdict.REJECT, VerificationReason.CONTRADICTED_FACT)
} else {
result(VerificationVerdict.ACCEPT, VerificationReason.SUPPORTED_FACT)
}
}

if (candidate == "I detected a " + classes.singleOrNull() + ".") {
return result(VerificationVerdict.ACCEPT, VerificationReason.SUPPORTED_FACT)
}

if (facts.size == 1) {
val unknowns = evidence.unknownProperties[facts.single().subjectId].orEmpty()
if (candidate == "I cannot determine its colour from this observation." &&
VisualProperty.COLOUR in unknowns) {
return result(VerificationVerdict.ACCEPT, VerificationReason.SUPPORTED_FACT)
}
if (candidate == "I cannot identify who that is from this observation." &&
VisualProperty.IDENTITY in unknowns) {
return result(VerificationVerdict.ACCEPT, VerificationReason.SUPPORTED_FACT)
}
if (candidate == "I do not have verified colour evidence." ||
candidate == "I do not have verified identity evidence.") {
return result(VerificationVerdict.ACCEPT, VerificationReason.SUPPORTED_FACT)
}
}

if (candidate == "I do not have reliable object-class evidence." &&
classes.isEmpty()) {
return result(VerificationVerdict.ACCEPT, VerificationReason.SUPPORTED_FACT)
}

return result(VerificationVerdict.UNCERTAIN, VerificationReason.UNRECOGNISED_CLAIM)
}
}
