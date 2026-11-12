package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentUserMemoryForgetDeleteTest {
    @Test
    fun forgetDeletesApprovedFactsByCanonicalId() {
        val persistence = InMemoryKnowledgePersistence()
        val store = KnowledgeStore.forTesting(persistence)
        val memory = PersistentUserMemory(store)
        memory.remember("favorite_color", "teal", 1_000L)
        memory.remember("location", "Wellington", 1_001L)

        val deleted = memory.forget(setOf("favorite_color", "location"))

        assertEquals(2, deleted)
        assertNull(memory.get("user.fact.favorite_color"))
        assertNull(memory.get("user.fact.location"))
        assertEquals(0, store.count())
        assertEquals(1, persistence.deleteCalls)
        store.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedForgetKeyIsRejected() {
        val store = KnowledgeStore.forTesting(InMemoryKnowledgePersistence())
        try {
            PersistentUserMemory(store).forget(setOf("red_book_location"))
        } finally {
            store.close()
        }
    }

    @Test
    fun canonicalCollisionWithNonUserMemoryIsNotDeleted() {
        val protected = KnowledgeRecord(
            id = "user.fact.name",
            category = KnowledgeCategory.IDENTITY,
            title = "Protected",
            body = "authoritative",
            authority = KnowledgeAuthority.AUTHORITATIVE,
            source = "test"
        )
        val persistence = InMemoryKnowledgePersistence()
        val store = KnowledgeStore.forTesting(persistence).also { it.bootstrap(listOf(protected)) }
        val memory = PersistentUserMemory(store)

        var rejected = false
        try {
            memory.forget(setOf("name"))
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(protected, store.getExact("user.fact.name"))
        assertFalse(persistence.deletedIds.contains("user.fact.name"))
        store.close()
    }

    private class InMemoryKnowledgePersistence : KnowledgePersistence {
        private val records = linkedMapOf<String, KnowledgeRecord>()
        var deleteCalls = 0
            private set
        val deletedIds = linkedSetOf<String>()

        override fun upsertAll(records: List<KnowledgeRecord>) {
            records.forEach { record -> this.records[record.id] = record }
        }

        override fun findById(id: String): KnowledgeRecord? = records[id]
        override fun all(): List<KnowledgeRecord> = records.values.sortedBy { it.id }

        override fun deleteByIds(ids: Set<String>): Int {
            deleteCalls += 1
            var deleted = 0
            ids.forEach { id ->
                if (records.remove(id) != null) {
                    deleted += 1
                    deletedIds += id
                }
            }
            return deleted
        }

        override fun count(): Int = records.size
        override fun close() = Unit
    }
}
