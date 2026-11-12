package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentUserMemoryWriteUpdateTest {
    @Test
    fun writesAllApprovedKeysWithStableCanonicalIds() {
        val store = storeOf()
        val memory = PersistentUserMemory(store)
        val cases = linkedMapOf(
            "name" to ("Alex" to "user.fact.name"),
            "preferred_name" to ("Taylor" to "user.fact.preferred_name"),
            "favorite_color" to ("dark green" to "user.fact.favorite_color"),
            "location" to ("Wellington" to "user.fact.location")
        )

        cases.entries.forEachIndexed { index, (key, pair) ->
            val (value, expectedId) = pair
            val written = memory.remember(key, value, nowEpochMs = 1_000L + index)
            assertEquals(expectedId, written.id)
            assertEquals(KnowledgeCategory.USER_FACT, written.category)
            assertEquals(KnowledgeAuthority.USER_SUPPLIED, written.authority)
            assertEquals(PersistentUserMemory.SOURCE_EXPLICIT_USER_FACT, written.source)
            assertEquals(value, written.body)
            assertTrue(written.tags.contains("user_fact"))
            assertTrue(written.tags.contains(key))
        }

        assertEquals(4, store.count())
        assertEquals(
            cases.values.map { it.second }.sorted(),
            memory.all().map { it.id }.sorted()
        )
        store.close()
    }

    @Test
    fun updateReusesIdAndPreservesCreatedTimestamp() {
        val store = storeOf()
        val memory = PersistentUserMemory(store)

        val first = memory.remember("favorite_color", "teal", nowEpochMs = 1_000L)
        val updated = memory.remember("favorite_color", "green", nowEpochMs = 2_000L)

        assertEquals("user.fact.favorite_color", first.id)
        assertEquals(first.id, updated.id)
        assertEquals(1_000L, updated.createdAtEpochMs)
        assertEquals(2_000L, updated.updatedAtEpochMs)
        assertEquals("green", memory.get(first.id)?.body)
        assertEquals(1, store.count())
        store.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedKeyIsRejected() {
        val store = storeOf()
        try {
            PersistentUserMemory(store).remember("red_book_location", "desk", 1_000L)
        } finally {
            store.close()
        }
    }

    @Test
    fun nonUserMemoryCollisionIsNotOverwritten() {
        val protected = KnowledgeRecord(
            id = "user.fact.name",
            category = KnowledgeCategory.IDENTITY,
            title = "Protected record",
            body = "do not replace",
            authority = KnowledgeAuthority.AUTHORITATIVE,
            source = "test"
        )
        val store = storeOf(protected)
        val memory = PersistentUserMemory(store)

        var rejected = false
        try {
            memory.remember("name", "Alex", 1_000L)
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(protected, store.getExact("user.fact.name"))
        assertFalse(memory.all().any { it.id == "user.fact.name" })
        store.close()
    }

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
