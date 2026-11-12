package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRetrieverStopWordNormalizationTest {
    @Test
    fun rainQuestionDoesNotRetrieveIrrelevantAuthorityRecord() {
        val store = seededStore()
        val results = KnowledgeRetriever(store).retrieve("Why does rain fall from clouds?")
        assertTrue(results.isEmpty())
        store.close()
    }

    @Test
    fun doesStopWordRemovalPreservesRelevantMotorRetrieval() {
        val store = seededStore()
        val results = KnowledgeRetriever(store).retrieve("What does Robot X use for motor control?")
        assertEquals("safety.motor_authority", results.firstOrNull()?.id)
        store.close()
    }

    @Test
    fun doesStopWordRemovalPreservesRelevantSafetyBoundaryRetrieval() {
        val store = seededStore()
        val results = KnowledgeRetriever(store).retrieve("Does AI own physical action authority?")
        assertEquals("safety.ai_boundary", results.firstOrNull()?.id)
        store.close()
    }

    @Test
    fun existingVisionAndPhoneRetrievalRemainAvailable() {
        val store = seededStore()
        assertEquals(
            "limitation.visual_interpretation",
            KnowledgeRetriever(store).retrieve("Can you see objects?").firstOrNull()?.id
        )
        assertEquals(
            "hardware.phone",
            KnowledgeRetriever(store).retrieve("What phone are you using?").firstOrNull()?.id
        )
        store.close()
    }

    private fun seededStore(): KnowledgeStore {
        val persistence = InMemoryKnowledgePersistence()
        return KnowledgeStore.forTesting(persistence).also {
            it.bootstrap(KnowledgeBootstrap.records())
        }
    }

    private class InMemoryKnowledgePersistence : KnowledgePersistence {
        private val records = linkedMapOf<String, KnowledgeRecord>()

        override fun upsertAll(records: List<KnowledgeRecord>) {
            records.forEach { record -> this.records[record.id] = record }
        }

        override fun findById(id: String): KnowledgeRecord? = records[id]
        override fun all(): List<KnowledgeRecord> = records.values.sortedBy { it.id }
        override fun count(): Int = records.size
        override fun close() = Unit
    }
}
