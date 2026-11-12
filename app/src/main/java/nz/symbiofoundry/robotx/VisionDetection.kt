package nz.symbiofoundry.robotx

data class VisionDetection(
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class VisionInferenceResult(
    val sourceImagePath: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceBytes: Long,
    val inferenceMs: Long,
    val detections: List<VisionDetection>
)

data class VisionModelInfo(
    val modelAssetName: String,
    val modelBytes: Long,
    val loadMs: Long,
    val delegate: String
)

enum class VisionErrorCode {
    CLOSED,
    MODEL_LOAD_FAILED,
    IMAGE_NOT_FOUND,
    IMAGE_EMPTY,
    IMAGE_DECODE_FAILED,
    INFERENCE_FAILED
}

class VisionDetectorException(
    val code: VisionErrorCode,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
