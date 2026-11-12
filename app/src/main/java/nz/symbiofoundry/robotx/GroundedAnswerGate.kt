package nz.symbiofoundry.robotx

sealed interface GroundedAnswerDecision {
data class Direct(
val answer: String,
val observationId: String
) : GroundedAnswerDecision

data class Generate(
val reason: Reason
) : GroundedAnswerDecision
}

enum class Reason {
UNSUPPORTED_QUESTION,
AMBIGUOUS_SUBJECT,
COMPOUND_QUESTION
}

/**
* Package B: deterministic, observation-scoped answers from Package A evidence.
*
* No LLM, camera, persistence, or actuator access.
* Generate means the question is outside this gate, NOT that a generated
* answer has been verified or authorised for display or speech.
*/
object GroundedAnswerGate {
private val listingQuestions = setOf(
"what can you see",
"what do you see",
"what objects can you see",
"what objects do you see"
)

private val singularQuestions = setOf(
"what is that",
"what is it",
"what is this",
"what object is that",
"what object is this"
)

private val colourQuestions = setOf(
"what colour is it",
"what color is it",
"what colour is that",
"what color is that",
"what colour is this",
"what color is this"
)

private val identityQuestions = setOf(
"who is that",
"who is this",
"who is it",
"who is the person",
"do you know who that is",
"can you identify that person"
)

private val presenceQuestion = Regex(
"^can you see (?:a |an |the )?([a-z][a-z0-9 -]{0,79})$"
)

fun decide(
question: String,
evidence: VisualEvidenceContract
): GroundedAnswerDecision {
val normalised = question.trim().lowercase()
.replace(Regex("[?!.]+$"), "")
.replace(Regex("\\s+"), " ")
.trim()

if (normalised.isEmpty()) {
return GroundedAnswerDecision.Generate(Reason.UNSUPPORTED_QUESTION)
}

// A compound question must not be reduced to a safe-looking fragment.
if (normalised.contains(" and ") ||
normalised.contains(" or ") ||
normalised.contains(";") ||
normalised.contains(",")) {
return GroundedAnswerDecision.Generate(Reason.COMPOUND_QUESTION)
}

val isListing = normalised in listingQuestions
val isSingular = normalised in singularQuestions
val isColour = normalised in colourQuestions
val isIdentity = normalised in identityQuestions
val requestedClass = presenceQuestion.matchEntire(normalised)
?.groupValues?.get(1)?.trim()

if (!isListing && !isSingular && !isColour &&
!isIdentity && requestedClass == null) {
return GroundedAnswerDecision.Generate(Reason.UNSUPPORTED_QUESTION)
}

fun direct(answer: String) = GroundedAnswerDecision.Direct(
answer = answer,
observationId = evidence.observationId
)

val available = evidence.status == VisionObservationStatus.DETECTED &&
evidence.supportsObjectClaims &&
evidence.verifiedFacts.isNotEmpty()

if (!available) {
val response = when (evidence.status) {
VisionObservationStatus.EMPTY ->
"I did not detect any objects in this observation."
VisionObservationStatus.CAMERA_UNAVAILABLE ->
"The camera was unavailable, so I cannot assess the scene."
VisionObservationStatus.PERMISSION_DENIED ->
"Camera permission was denied, so I cannot assess the scene."
VisionObservationStatus.BUSY ->
"The camera was busy, so I cannot assess the scene."
else ->
"I do not have reliable visual evidence to answer that."
}
return direct(response)
}

val classes = evidence.verifiedFacts
.filter { it.property == VisualProperty.OBJECT_CLASS }
.map { it.value.trim() }
.filter { it.isNotEmpty() }

// Fail closed if a purportedly supported contract has no class facts.
if (classes.isEmpty()) {
return direct("I do not have reliable object-class evidence.")
}

if (isListing) {
return direct("I detected: " + classes.joinToString(", ") + ".")
}

if (requestedClass != null) {
val found = classes.any { it.equals(requestedClass, ignoreCase = true) }
return if (found) {
direct("Yes, I detected a " + requestedClass + ".")
} else {
direct("I did not detect a " + requestedClass +
" in this observation.")
}
}

if (classes.size != 1) {
return GroundedAnswerDecision.Generate(Reason.AMBIGUOUS_SUBJECT)
}

val subjectId = evidence.verifiedFacts
.first { it.property == VisualProperty.OBJECT_CLASS }
.subjectId
val unknowns = evidence.unknownProperties[subjectId].orEmpty()

if (isSingular) {
return direct("I detected a " + classes.single() + ".")
}

if (isColour) {
return if (VisualProperty.COLOUR in unknowns) {
direct("I cannot determine its colour from this observation.")
} else {
direct("I do not have verified colour evidence.")
}
}

if (isIdentity) {
return if (VisualProperty.IDENTITY in unknowns) {
direct("I cannot identify who that is from this observation.")
} else {
direct("I do not have verified identity evidence.")
}
}

return GroundedAnswerDecision.Generate(Reason.UNSUPPORTED_QUESTION)
}
}
