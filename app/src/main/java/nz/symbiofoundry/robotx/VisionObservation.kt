package nz.symbiofoundry.robotx

enum class VisionObservationStatus {
    DETECTED,
    EMPTY,
    BUSY,
    CAMERA_UNAVAILABLE,
    PERMISSION_DENIED,
    CAPTURE_FAILED,
    INVALID_CAPTURE,
    ANALYSIS_FAILED
}

data class VisionObservation(
    val requestId: String,
    val status: VisionObservationStatus,
    val requestedCamera: VisionCamera,
    val actualCamera: VisionCamera?,
    val capturedAtEpochMs: Long?,
    val imagePath: String?,
    val sourceWidth: Int?,
    val sourceHeight: Int?,
    val sourceBytes: Long?,
    val inferenceMs: Long?,
    val detections: List<VisionDetection>,
    val errorCode: String? = null
)

data class VisionValidationResult(
    val accepted: List<VisionDetection>,
    val rejectedCount: Int
)

object VisionObservationValidator {
    fun validateDetections(
        detections: List<VisionDetection>,
        sourceWidth: Int,
        sourceHeight: Int,
        minConfidence: Float = 0.50f,
        maxDetections: Int = 10
    ): VisionValidationResult {
        require(sourceWidth > 0)
        require(sourceHeight > 0)
        require(minConfidence in 0.0f..1.0f)
        require(maxDetections > 0)

        val accepted = mutableListOf<VisionDetection>()
        var rejected = 0

        detections.forEach { detection ->
            val valid =
                detection.label.isNotBlank() &&
                detection.confidence.isFinite() &&
                detection.confidence >= minConfidence &&
                detection.confidence <= 1.0f &&
                detection.left.isFinite() &&
                detection.top.isFinite() &&
                detection.right.isFinite() &&
                detection.bottom.isFinite() &&
                detection.left >= 0.0f &&
                detection.top >= 0.0f &&
                detection.right > detection.left &&
                detection.bottom > detection.top &&
                detection.right <= sourceWidth.toFloat() &&
                detection.bottom <= sourceHeight.toFloat()

            if (valid && accepted.size < maxDetections) {
                accepted += detection
            } else {
                rejected += 1
            }
        }

        return VisionValidationResult(accepted.toList(), rejected)
    }
}
