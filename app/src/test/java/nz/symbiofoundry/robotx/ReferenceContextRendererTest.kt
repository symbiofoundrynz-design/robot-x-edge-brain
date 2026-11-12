package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceContextRendererTest {
    @Test
    fun emptyRecordsRenderEmptyString() {
        assertEquals("", ReferenceContextRenderer().render(emptyList()))
    }

    @Test
    fun rendersAtMostThreeRecords() {
        val rendered = ReferenceContextRenderer(maxRecords = 3).render(
            KnowledgeBootstrap.records().take(5)
        )
        assertTrue(rendered.startsWith("Reference context:"))
        assertEquals(3, rendered.lines().count { it.startsWith("- ") })
    }

    @Test
    fun renderNeverExceedsCharBudget() {
        val rendered = ReferenceContextRenderer(
            maxRecords = 3,
            maxChars = 120
        ).render(KnowledgeBootstrap.records().take(3))
        assertTrue(rendered.length <= 120)
    }
}
