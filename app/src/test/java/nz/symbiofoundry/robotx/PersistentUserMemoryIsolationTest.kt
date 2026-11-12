package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistentUserMemoryIsolationTest {
    @Test
    fun exposesOnlyUserSuppliedUserFacts() {
        val store = storeOf(
            record(
                id = "user.fact.favorite_color",
                category = KnowledgeCategory.USER_FACT,
                authority = KnowledgeAuthority.USER_SUPPLIED,
                body = "green"
            ),
            record(
                id = "user.fact.invalid_authority",
                category = KnowledgeCategory.USER_FACT,
                authority = KnowledgeAuthority.AUTHORITATIVE,
                body = "must stay isolated"
            ),
            record(
                id = "capability.user_supplied",
                category = KnowledgeCategory.CAPABILITY,
                authority = KnowledgeAuthority.USER_SUPPLIED,
                body = "must not become user memory"
            )
        )

        val memory = PersistentUserMemory(store)

        assertEquals(
            listOf("user.fact.favorite_color"),
            memory.all().map { it.id }
        )
        assertEquals("green", memory.get("user.fact.favorite_color")?.body)
        assertNull(memory.get("user.fact.invalid_authority"))
        assertNull(memory.get("capability.user_supplied"))
        store.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankExactLookupIsRejected() {
        val store = storeOf()
        try {
            PersistentUserMemory(store).get("   ")
        } finally {
            store.close()
        }
    }

    private fun record(
        id: String,
        category: KnowledgeCategory,
        authority: KnowledgeAuthority,
        body: String
    ) = KnowledgeRecord(
        id = id,
        category = category,
        title = id,
        body = body,
        authority = authority,
        source = "C2A test"
    )

    private fun storeOf(vararg records: KnowledgeRecord): KnowledgeStore {
        val persistence = InMemoryKnowledgePersistence()
        return KnowledgeStore.forTesting(persistence).also {
            it.bootstrap(records.toList())
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
