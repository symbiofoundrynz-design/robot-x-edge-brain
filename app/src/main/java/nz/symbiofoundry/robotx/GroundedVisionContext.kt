package nz.symbiofoundry.robotx

import kotlin.math.roundToInt

/**
 * E3.4 B2A — deterministic adapter from a validated VisionObservation to
 * bounded textual context suitable for later grounded conversation.
 *
 * This adapter does not request a camera, perform inference, call ChatBrain,
 * or grant any robot-control authority.
 */
data class GroundedVisionContext(
    val status: VisionObservationStatus,
    val text: String,
    val supportsObjectClaims: Boolean,
    val detectionCount: Int
)

object GroundedVisionContextRenderer {
    private const val UNSUPPORTED_PROPERTIES =
        "Do not infer colour, identity, text, material, or any unlisted property."

    fun render(observation: VisionObservation): GroundedVisionContext =
        render(
            status = observation.status,
            detections = observation.detections,
            errorCode = observation.errorCode
        )

    internal fun render(
        status: VisionObservationStatus,
        detections: List<VisionDetection>,
        errorCode: String?
    ): GroundedVisionContext =
        when (status) {
            VisionObservationStatus.DETECTED -> renderDetected(detections)

            VisionObservationStatus.EMPTY ->
                GroundedVisionContext(
                    status = status,
                    text =
                        "Fresh visual observation completed, but no objects were " +
                            "identified with sufficient confidence. " +
                            "Do not claim that a specific object was seen. " +
                            UNSUPPORTED_PROPERTIES,
                    supportsObjectClaims = false,
                    detectionCount = 0
                )

            VisionObservationStatus.BUSY ->
                unavailable(
                    status = status,
                    reason = "another visual observation is already active",
                    errorCode = errorCode
                )

            VisionObservationStatus.CAMERA_UNAVAILABLE ->
                unavailable(
                    status = status,
                    reason = "the camera is unavailable",
                    errorCode = errorCode
                )

            VisionObservationStatus.PERMISSION_DENIED ->
                unavailable(
                    status = status,
                    reason = "camera permission was denied",
                    errorCode = errorCode
                )

            VisionObservationStatus.CAPTURE_FAILED ->
                unavailable(
                    status = status,
                    reason = "image capture failed",
                    errorCode = errorCode
                )

            VisionObservationStatus.INVALID_CAPTURE ->
                unavailable(
                    status = status,
                    reason = "the captured image was invalid",
                    errorCode = errorCode
                )

            VisionObservationStatus.ANALYSIS_FAILED ->
                unavailable(
                    status = status,
                    reason = "local visual analysis failed",
                    errorCode = errorCode
                )
        }

    private fun renderDetected(
        detections: List<VisionDetection>
    ): GroundedVisionContext {
        if (detections.isEmpty()) {
            return GroundedVisionContext(
                status = VisionObservationStatus.DETECTED,
                text =
                    "Visual observation reported DETECTED but contained no validated " +
                        "detections. Treat the visual evidence as unavailable. " +
                        UNSUPPORTED_PROPERTIES,
                supportsObjectClaims = false,
                detectionCount = 0
            )
        }

        val boundedDetections = detections.take(10)
        val facts = boundedDetections.joinToString(separator = "; ") { detection ->
            val percent = (detection.confidence * 100.0f).roundToInt().coerceIn(0, 100)
            "${detection.label} (${percent}% confidence)"
        }

        return GroundedVisionContext(
            status = VisionObservationStatus.DETECTED,
            text =
                "Fresh visual observation. The local detector identified: $facts. " +
                    "Only these validated detections are supported visual facts. " +
                    UNSUPPORTED_PROPERTIES,
            supportsObjectClaims = true,
            detectionCount = boundedDetections.size
        )
    }

    private fun unavailable(
        status: VisionObservationStatus,
        reason: String,
        errorCode: String?
    ): GroundedVisionContext {
        val code =
            errorCode
                ?.takeIf { it.isNotBlank() }
                ?.let { " Error code: $it." }
                .orEmpty()

        return GroundedVisionContext(
            status = status,
            text =
                "Fresh visual evidence is unavailable because $reason.$code " +
                    "Do not make a visual claim.",
            supportsObjectClaims = false,
            detectionCount = 0
        )
    }
}
