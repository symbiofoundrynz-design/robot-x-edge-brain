package nz.symbiofoundry.robotx

class VisionObservationCoordinator(
    private val defaultCamera: VisionCamera = VisionCamera.FRONT,
    private val minConfidence: Float = 0.50f,
    private val maxDetections: Int = 10
) {
    private val lock = Any()
    private var activeRequestId: String? = null

    init {
        require(defaultCamera != VisionCamera.DEFAULT)
        require(minConfidence in 0.0f..1.0f)
        require(maxDetections > 0)
    }

    fun resolveCamera(requestedCamera: VisionCamera): VisionCamera =
        if (requestedCamera == VisionCamera.DEFAULT) defaultCamera else requestedCamera

    fun begin(request: VisionRequest): VisionRequestStart = synchronized(lock) {
        val current = activeRequestId
        if (current != null) {
            return@synchronized VisionRequestStart.Busy(request, current)
        }

        val actualCamera = resolveCamera(request.camera)
        activeRequestId = request.requestId
        VisionRequestStart.Accepted(request, actualCamera)
    }

    fun completeFromInference(
        start: VisionRequestStart.Accepted,
        inference: VisionInferenceResult,
        capturedAtEpochMs: Long
    ): VisionObservation {
        require(capturedAtEpochMs >= 0L)

        val validation = VisionObservationValidator.validateDetections(
            detections = inference.detections,
            sourceWidth = inference.sourceWidth,
            sourceHeight = inference.sourceHeight,
            minConfidence = minConfidence,
            maxDetections = maxDetections
        )

        val observation = VisionObservation(
            requestId = start.request.requestId,
            status = if (validation.accepted.isEmpty()) {
                VisionObservationStatus.EMPTY
            } else {
                VisionObservationStatus.DETECTED
            },
            requestedCamera = start.request.camera,
            actualCamera = start.actualCamera,
            capturedAtEpochMs = capturedAtEpochMs,
            imagePath = inference.sourceImagePath,
            sourceWidth = inference.sourceWidth,
            sourceHeight = inference.sourceHeight,
            sourceBytes = inference.sourceBytes,
            inferenceMs = inference.inferenceMs,
            detections = validation.accepted
        )

        finish(start.request.requestId)
        return observation
    }

    fun completeFailure(
        start: VisionRequestStart.Accepted,
        status: VisionObservationStatus,
        errorCode: String
    ): VisionObservation {
        require(
            status != VisionObservationStatus.DETECTED &&
                status != VisionObservationStatus.EMPTY &&
                status != VisionObservationStatus.BUSY
        )
        require(errorCode.isNotBlank())

        val observation = VisionObservation(
            requestId = start.request.requestId,
            status = status,
            requestedCamera = start.request.camera,
            actualCamera = start.actualCamera,
            capturedAtEpochMs = null,
            imagePath = null,
            sourceWidth = null,
            sourceHeight = null,
            sourceBytes = null,
            inferenceMs = null,
            detections = emptyList(),
            errorCode = errorCode
        )

        finish(start.request.requestId)
        return observation
    }

    fun cancel(requestId: String): Boolean = synchronized(lock) {
        if (activeRequestId == requestId) {
            activeRequestId = null
            true
        } else {
            false
        }
    }

    fun isBusy(): Boolean = synchronized(lock) {
        activeRequestId != null
    }

    private fun finish(requestId: String) {
        synchronized(lock) {
            check(activeRequestId == requestId) {
                "request $requestId is not the active vision request"
            }
            activeRequestId = null
        }
    }
}
