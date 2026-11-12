package nz.symbiofoundry.robotx

import kotlin.math.roundToInt

/**
 * E3.4 B4C — deterministic world context for grounded conversational replies.
 *
 * Truth is derived only from the current validated VisionObservation and the
 * accepted GroundedVisionContext. ChatBrain may phrase this context, but does
 * not decide what is true and receives no robot-control authority.
 */
data class GroundedWorldContext(
    val currentQuestion: String,
    val factualCore: String,
    val verifiedFacts: List<String>,
    val unknownFacts: List<String>,
    val observationStatus: VisionObservationStatus,
    val supportsObjectClaims: Boolean
)

object GroundedWorldContextBuilder {
    private val DETECTED_UNKNOWN_FACTS =
        listOf(
            "identity",
            "residence",
            "occupation",
            "age",
            "relationship to the user",
            "colour",
            "text",
            "material",
            "other unreported properties"
        )

    fun build(
        userText: String,
        observation: VisionObservation,
        grounded: GroundedVisionContext
    ): GroundedWorldContext =
        build(
            userText = userText,
            status = observation.status,
            detections = observation.detections,
            grounded = grounded
        )

    internal fun build(
        userText: String,
        status: VisionObservationStatus,
        detections: List<VisionDetection>,
        grounded: GroundedVisionContext
    ): GroundedWorldContext {
        val question = userText.trim()
        require(question.isNotEmpty()) { "Grounded world question is empty" }

        val boundedDetections = detections.take(10)
        val canClaimObjects =
            status == VisionObservationStatus.DETECTED &&
                grounded.supportsObjectClaims &&
                boundedDetections.isNotEmpty()

        if (canClaimObjects) {
            val labels = boundedDetections.map { sanitizeLabel(it.label) }
            val factualCore =
                if (labels.size == 1) {
                    "The observed object is a ${labels.single()}."
                } else {
                    "The observed objects are ${labels.joinToString(separator = ", ")}."
                }
            val verifiedFacts =
                boundedDetections.map { detection ->
                    val percent =
                        (detection.confidence * 100.0f)
                            .roundToInt()
                            .coerceIn(0, 100)
                    "${sanitizeLabel(detection.label)} (${percent}% confidence)"
                }

            return GroundedWorldContext(
                currentQuestion = question,
                factualCore = factualCore,
                verifiedFacts = verifiedFacts,
                unknownFacts = DETECTED_UNKNOWN_FACTS,
                observationStatus = status,
                supportsObjectClaims = true
            )
        }

        val factualCore =
            when (status) {
                VisionObservationStatus.EMPTY ->
                    "No object was identified with sufficient confidence."
                VisionObservationStatus.DETECTED ->
                    "The visual evidence is inconclusive because no validated detection is available."
                else ->
                    "Fresh visual evidence is unavailable."
            }

        return GroundedWorldContext(
            currentQuestion = question,
            factualCore = factualCore,
            verifiedFacts = listOf("visual observation status: ${status.name}"),
            unknownFacts =
                listOf(
                    "specific object class",
                    "identity",
                    "colour",
                    "text",
                    "material",
                    "all other unreported visual properties"
                ),
            observationStatus = status,
            supportsObjectClaims = false
        )
    }

    private fun sanitizeLabel(label: String): String =
        label
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .ifBlank { "unknown object" }
            .take(80)
}
