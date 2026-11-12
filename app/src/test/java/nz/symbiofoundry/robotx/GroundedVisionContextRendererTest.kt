package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedVisionContextRendererTest {
    @Test
    fun detectedRendersOnlyValidatedDetectionFacts() {
        val detections =
            listOf(
                VisionDetection(
                    label = "banana",
                    confidence = 0.77f,
                    left = 1f,
                    top = 2f,
                    right = 30f,
                    bottom = 40f
                ),
                VisionDetection(
                    label = "mouse",
                    confidence = 0.844f,
                    left = 10f,
                    top = 20f,
                    right = 50f,
                    bottom = 60f
                )
            )

        val result =
            GroundedVisionContextRenderer.render(
                status = VisionObservationStatus.DETECTED,
                detections = detections,
                errorCode = null
            )

        assertEquals(VisionObservationStatus.DETECTED, result.status)
        assertEquals(2, result.detectionCount)
        assertTrue(result.supportsObjectClaims)
        assertTrue(result.text.contains("banana (77% confidence)"))
        assertTrue(result.text.contains("mouse (84% confidence)"))
        assertTrue(result.text.contains("Only these validated detections"))
        assertTrue(result.text.contains("Do not infer colour"))
    }

    @Test
    fun emptyProducesBoundedUncertainty() {
        val result =
            GroundedVisionContextRenderer.render(
                status = VisionObservationStatus.EMPTY,
                detections = emptyList(),
                errorCode = null
            )

        assertEquals(VisionObservationStatus.EMPTY, result.status)
        assertFalse(result.supportsObjectClaims)
        assertEquals(0, result.detectionCount)
        assertTrue(result.text.contains("no objects were identified"))
        assertTrue(result.text.contains("Do not claim"))
    }

    @Test
    fun analysisFailureNeverSupportsVisualClaims() {
        val result =
            GroundedVisionContextRenderer.render(
                status = VisionObservationStatus.ANALYSIS_FAILED,
                detections = emptyList(),
                errorCode = "LOCAL_VISION_ANALYSIS_FAILED"
            )

        assertEquals(VisionObservationStatus.ANALYSIS_FAILED, result.status)
        assertFalse(result.supportsObjectClaims)
        assertEquals(0, result.detectionCount)
        assertTrue(result.text.contains("local visual analysis failed"))
        assertTrue(result.text.contains("LOCAL_VISION_ANALYSIS_FAILED"))
        assertTrue(result.text.contains("Do not make a visual claim"))
    }

    @Test
    fun busyNeverSupportsVisualClaims() {
        val result =
            GroundedVisionContextRenderer.render(
                status = VisionObservationStatus.BUSY,
                detections = emptyList(),
                errorCode = null
            )

        assertEquals(VisionObservationStatus.BUSY, result.status)
        assertFalse(result.supportsObjectClaims)
        assertEquals(0, result.detectionCount)
    }

    @Test
    fun detectedWithNoDetectionsFailsClosed() {
        val result =
            GroundedVisionContextRenderer.render(
                status = VisionObservationStatus.DETECTED,
                detections = emptyList(),
                errorCode = null
            )

        assertEquals(VisionObservationStatus.DETECTED, result.status)
        assertFalse(result.supportsObjectClaims)
        assertEquals(0, result.detectionCount)
        assertTrue(result.text.contains("Treat the visual evidence as unavailable"))
    }

    @Test
    fun detectedOutputIsBoundedToTenDetections() {
        val detections =
            (1..12).map { index ->
                VisionDetection(
                    label = "item-$index",
                    confidence = 0.75f,
                    left = index.toFloat(),
                    top = index.toFloat(),
                    right = index.toFloat() + 1f,
                    bottom = index.toFloat() + 1f
                )
            }

        val result =
            GroundedVisionContextRenderer.render(
                status = VisionObservationStatus.DETECTED,
                detections = detections,
                errorCode = null
            )

        assertTrue(result.supportsObjectClaims)
        assertEquals(10, result.detectionCount)
        assertTrue(result.text.contains("item-10"))
        assertFalse(result.text.contains("item-11"))
        assertFalse(result.text.contains("item-12"))
    }
}
