package nz.symbiofoundry.robotx

enum class VisionCamera {
    DEFAULT,
    FRONT,
    REAR
}

data class VisionRequest(
    val requestId: String,
    val camera: VisionCamera = VisionCamera.DEFAULT,
    val requestedAtEpochMs: Long
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(requestedAtEpochMs >= 0L) { "requestedAtEpochMs must be non-negative" }
    }
}

sealed class VisionRequestStart {
    data class Accepted(
        val request: VisionRequest,
        val actualCamera: VisionCamera
    ) : VisionRequestStart() {
        init {
            require(actualCamera != VisionCamera.DEFAULT) {
                "actualCamera must resolve to FRONT or REAR"
            }
        }
    }

    data class Busy(
        val request: VisionRequest,
        val activeRequestId: String
    ) : VisionRequestStart()
}
