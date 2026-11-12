package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnowledgeStoreTest {
    @Test
    fun exactIdRetrievalReturnsBootstrappedRecord() {
        val persistence = InMemoryKnowledgePersistence()
        val store = KnowledgeStore.forTesting(persistence)
        store.bootstrap(KnowledgeBootstrap.records())

        assertEquals(10, store.count())
        val record = store.getExact("hardware.phone")
        assertEquals("Robot X phone", record?.title)
        assertEquals(
            "Robot X uses a Samsung Galaxy A03s, model SM-A037F, running Android 13.",
            record?.body
        )
        assertEquals(KnowledgeAuthority.AUTHORITATIVE, record?.authority)
        assertNull(store.getExact("hardware.unknown"))
        store.close()
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
