package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Test

class PersistentUserMemoryCoordinatorContractTest {
    @Test
    fun existingDeterministicParserFeedsExactlyTheFourPersistentKeys() {
        val store = KnowledgeStore.forTesting(InMemoryKnowledgePersistence())
        val memory = PersistentUserMemory(store)
        val inputs = listOf(
            "My name is Alex.",
            "Call me Taylor.",
            "My favourite colour is dark green.",
            "I live in Wellington."
        )

        inputs.forEachIndexed { index, input ->
            val fact = ConversationCoordinator.extractExplicitSessionFact(input)
                ?: error("Expected deterministic explicit fact for $input")
            memory.remember(fact.first, fact.second, 10_000L + index)
        }

        assertEquals(
            setOf(
                "user.fact.name",
                "user.fact.preferred_name",
                "user.fact.favorite_color",
                "user.fact.location"
            ),
            memory.all().map { it.id }.toSet()
        )
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
