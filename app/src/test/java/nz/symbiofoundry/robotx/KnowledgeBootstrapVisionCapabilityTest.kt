package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBootstrapVisionCapabilityTest {
    @Test
    fun visualInterpretationRecordReflectsAcceptedGroundedVisionCapability() {
        val matches =
            KnowledgeBootstrap.records().filter {
                it.id == "limitation.visual_interpretation"
            }

        assertEquals(1, matches.size)

        val record = matches.single()
        assertEquals(KnowledgeCategory.CAPABILITY, record.category)
        assertEquals("Local visual perception", record.title)
        assertEquals(KnowledgeAuthority.AUTHORITATIVE, record.authority)
        assertEquals("E3.4 B4C grounded vision acceptance", record.source)
        assertEquals(
            setOf("capability", "camera", "vision", "perception", "grounding"),
            record.tags
        )
        assertTrue(record.body.contains("local perception model"))
        assertTrue(record.body.contains("validated detections as grounded visual facts"))
        assertTrue(record.body.contains("must not infer identity, colour, text, material"))
        assertFalse(record.body.contains("cannot currently interpret camera images"))
    }
}
