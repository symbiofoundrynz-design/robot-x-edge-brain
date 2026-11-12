package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedWorldContextBuilderTest {
    @Test
    fun detectedPersonCreatesDeterministicFactualCore() {
        val detections = listOf(VisionDetection("person", 0.8671875f, 1f, 1f, 10f, 10f))
        val grounded =
            GroundedVisionContextRenderer.render(
                status = VisionObservationStatus.DETECTED,
                detections = detections,
                errorCode = null
            )

        val world =
            GroundedWorldContextBuilder.build(
                userText = "What is this?",
                status = VisionObservationStatus.DETECTED,
                detections = detections,
                grounded = grounded
            )

        assertEquals("What is this?", world.currentQuestion)
        assertEquals("The observed object is a person.", world.factualCore)
        assertTrue(world.verifiedFacts.contains("person (87% confidence)"))
        assertTrue(world.unknownFacts.contains("residence"))
        assertTrue(world.unknownFacts.contains("occupation"))
        assertTrue(world.supportsObjectClaims)
    }

    @Test
    fun emptyObservationFailsClosed() {
        val grounded =
            GroundedVisionContextRenderer.render(
                status = VisionObservationStatus.EMPTY,
                detections = emptyList(),
                errorCode = null
            )

        val world =
            GroundedWorldContextBuilder.build(
                userText = "What is this?",
                status = VisionObservationStatus.EMPTY,
                detections = emptyList(),
                grounded = grounded
            )

        assertEquals("No object was identified with sufficient confidence.", world.factualCore)
        assertFalse(world.supportsObjectClaims)
        assertTrue(world.unknownFacts.contains("specific object class"))
    }

    @Test
    fun detectedWithoutValidatedDetectionsFailsClosed() {
        val grounded =
            GroundedVisionContextRenderer.render(
                status = VisionObservationStatus.DETECTED,
                detections = emptyList(),
                errorCode = null
            )

        val world =
            GroundedWorldContextBuilder.build(
                userText = "What is this?",
                status = VisionObservationStatus.DETECTED,
                detections = emptyList(),
                grounded = grounded
            )

        assertTrue(world.factualCore.contains("inconclusive"))
        assertFalse(world.supportsObjectClaims)
    }
}
