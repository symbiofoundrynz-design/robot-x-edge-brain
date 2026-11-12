package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRetrieverTest {
    @Test
    fun retrievesPhoneRecordForNaturalQuestion() {
        val store = seededStore()
        val results = KnowledgeRetriever(store).retrieve("What phone are you using?")
        assertEquals("hardware.phone", results.firstOrNull()?.id)
        store.close()
    }

    @Test
    fun retrievesVisualLimitationForCanYouSeeObjects() {
        val store = seededStore()
        val results = KnowledgeRetriever(store).retrieve("Can you see objects?")
        assertEquals("limitation.visual_interpretation", results.firstOrNull()?.id)
        store.close()
    }

    @Test
    fun retrievesOfflineCapabilityForWifiQuestion() {
        val store = seededStore()
        val results = KnowledgeRetriever(store).retrieve("Can you work without Wi-Fi?")
        assertEquals("capability.offline_operation", results.firstOrNull()?.id)
        store.close()
    }

    @Test
    fun retrievesMotorAuthorityForControlQuestion() {
        val store = seededStore()
        val results = KnowledgeRetriever(store).retrieve("What controls your motors?")
        assertEquals("safety.motor_authority", results.firstOrNull()?.id)
        store.close()
    }

    @Test
    fun unknownQuestionReturnsNoReference() {
        val store = seededStore()
        val results = KnowledgeRetriever(store).retrieve("What is the gearbox reduction ratio?")
        assertTrue(results.isEmpty())
        store.close()
    }

    @Test
    fun neverReturnsMoreThanThreeRecords() {
        val store = seededStore()
        val results = KnowledgeRetriever(store, maxResults = 3)
            .retrieve("voice offline robot capability safety")
        assertTrue(results.size <= 3)
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
