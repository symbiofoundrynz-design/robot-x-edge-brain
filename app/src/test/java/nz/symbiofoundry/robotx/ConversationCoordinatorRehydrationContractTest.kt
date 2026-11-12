package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationCoordinatorRehydrationContractTest {
    @Test
    fun hydratedStructuredFactFeedsExistingDeterministicFactReply() {
        val store = KnowledgeStore.forTesting(InMemoryKnowledgePersistence())
        val memory = PersistentUserMemory(store)
        memory.remember("favorite_color", "teal", nowEpochMs = 1_000L)

        val session = ConversationSession()
        val loaded = ConversationCoordinator.rehydrateSession(session, memory)
        val snapshot = session.snapshot()
        val reply = ConversationCoordinator.explicitSessionFactReply(
            "What is my favourite colour?",
            snapshot
        )

        assertEquals(1, loaded)
        assertEquals("teal", snapshot.facts["favorite_color"])
        assertEquals("Your favourite colour is teal.", reply)
        assertEquals(0, snapshot.turns.size)
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
