package nz.symbiofoundry.robotx

enum class GroundedOutputSource {
DETERMINISTIC,
VERIFIED_GENERATION,
SAFE_FALLBACK
}

sealed interface GroundedOutput {
data class Approved(
val text: String,
val observationId: String,
val source: GroundedOutputSource
) : GroundedOutput
}

/**
* Integration Stage I1: isolated, deterministic approval boundary.
*
* The caller supplies an observation-scoped evidence contract and a generation
* callback. Only approved output is returned. No conversation memory, camera,
* UI, speech, persistence, or robot-control operations occur here.
*
* Generate is not permission to publish an unverified candidate. All candidate
* text must pass Package C; exceptions and uncertainty yield a fixed fallback.
*/
object GroundedResponsePipeline {
private const val MAX_QUESTION_LENGTH = 1000
private const val MAX_DIRECT_LENGTH = 500
private const val FALLBACK_TEXT =
"I cannot verify a reliable answer from this observation."

fun resolve(
question: String,
expectedObservationId: String,
evidence: VisualEvidenceContract,
generateCandidate: () -> String
): GroundedOutput.Approved {
fun fallback(): GroundedOutput.Approved = GroundedOutput.Approved(
text = FALLBACK_TEXT,
observationId = expectedObservationId,
source = GroundedOutputSource.SAFE_FALLBACK
)

if (expectedObservationId.isBlank() ||
evidence.observationId != expectedObservationId ||
question.isBlank() ||
question.length > MAX_QUESTION_LENGTH) {
return fallback()
}

val decision = runCatching {
GroundedAnswerGate.decide(question, evidence)
}.getOrElse {
return fallback()
}

when (decision) {
is GroundedAnswerDecision.Direct -> {
val text = decision.answer
if (decision.observationId != expectedObservationId ||
text.isBlank() ||
text.length > MAX_DIRECT_LENGTH ||
text != text.trim() ||
text.any { it.isISOControl() }) {
return fallback()
}

return GroundedOutput.Approved(
text = text,
observationId = expectedObservationId,
source = GroundedOutputSource.DETERMINISTIC
)
}

is GroundedAnswerDecision.Generate -> {
val candidate = runCatching {
generateCandidate()
}.getOrElse {
return fallback()
}

val verification = runCatching {
GroundedResponseVerifier.verify(
candidate = candidate,
expectedObservationId = expectedObservationId,
evidence = evidence
)
}.getOrElse {
return fallback()
}

if (verification.verdict != VerificationVerdict.ACCEPT ||
verification.observationId != expectedObservationId ||
verification.approvedText != candidate ||
candidate.isBlank()) {
return fallback()
}

return GroundedOutput.Approved(
text = candidate,
observationId = expectedObservationId,
source = GroundedOutputSource.VERIFIED_GENERATION
)
}
}
}
}
