package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionObservationCoordinatorTest {
    @Test
    fun defaultCameraResolvesToFront() {
        val coordinator = VisionObservationCoordinator()
        assertEquals(VisionCamera.FRONT, coordinator.resolveCamera(VisionCamera.DEFAULT))
    }

    @Test
    fun explicitCameraSelectionIsPreserved() {
        val coordinator = VisionObservationCoordinator()
        assertEquals(VisionCamera.FRONT, coordinator.resolveCamera(VisionCamera.FRONT))
        assertEquals(VisionCamera.REAR, coordinator.resolveCamera(VisionCamera.REAR))
    }

    @Test
    fun validatorRejectsInvalidAndRetainsValidDetection() {
        val valid = VisionDetection("mouse", 0.84f, 100f, 120f, 500f, 460f)
        val invalid = VisionDetection("", 0.95f, -1f, 10f, 20f, 30f)

        val result = VisionObservationValidator.validateDetections(
            listOf(valid, invalid), 1000, 800
        )

        assertEquals(listOf(valid), result.accepted)
        assertEquals(1, result.rejectedCount)
    }

    @Test
    fun emptyInferenceProducesEmptyObservation() {
        val coordinator = VisionObservationCoordinator()
        val request = VisionRequest("empty-1", requestedAtEpochMs = 100L)
        val start = coordinator.begin(request) as VisionRequestStart.Accepted
        val observation = coordinator.completeFromInference(
            start,
            VisionInferenceResult(
                "/tmp/image.jpg", 640, 480, 1234L, 350L, emptyList()
            ),
            200L
        )

        assertEquals(VisionObservationStatus.EMPTY, observation.status)
        assertTrue(observation.detections.isEmpty())
        assertFalse(coordinator.isBusy())
    }

    @Test
    fun secondSimultaneousRequestReturnsBusy() {
        val coordinator = VisionObservationCoordinator()
        val first = VisionRequest("first", requestedAtEpochMs = 100L)
        val second = VisionRequest("second", requestedAtEpochMs = 101L)

        val firstStart = coordinator.begin(first)
        val secondStart = coordinator.begin(second)

        assertTrue(firstStart is VisionRequestStart.Accepted)
        assertTrue(secondStart is VisionRequestStart.Busy)
        assertEquals("first", (secondStart as VisionRequestStart.Busy).activeRequestId)
        assertTrue(coordinator.cancel("first"))
        assertFalse(coordinator.isBusy())
    }
}
