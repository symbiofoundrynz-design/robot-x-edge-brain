package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCoordinatorForgetContractTest {
    @Test
    fun deterministicForgetCommandsMapOnlyToApprovedKeys() {
        assertEquals(
            linkedSetOf("name", "preferred_name"),
            ConversationCoordinator.parseForgetDirective("Forget my name.")?.keys
        )
        assertEquals(
            linkedSetOf("preferred_name"),
            ConversationCoordinator.parseForgetDirective("Forget what I asked you to call me.")?.keys
        )
        assertEquals(
            linkedSetOf("favorite_color"),
            ConversationCoordinator.parseForgetDirective("Forget my favourite colour.")?.keys
        )
        assertEquals(
            linkedSetOf("favorite_color"),
            ConversationCoordinator.parseForgetDirective("Please forget my favorite color")?.keys
        )
        assertEquals(
            linkedSetOf("location"),
            ConversationCoordinator.parseForgetDirective("Robot X, forget where I live.")?.keys
        )
        assertNull(ConversationCoordinator.parseForgetDirective("Forget the red book."))
    }

    @Test
    fun forgetMyNameDeletesDurableAndLiveNameTogether() {
        val persistence = InMemoryKnowledgePersistence()
        val store = KnowledgeStore.forTesting(persistence)
        val memory = PersistentUserMemory(store)
        memory.remember("name", "Alex", 1_000L)
        memory.remember("preferred_name", "Taylor", 1_001L)
        val session = ConversationSession().apply {
            rememberFact("name", "Alex")
            rememberFact("preferred_name", "Taylor")
        }
        val directive = ConversationCoordinator.parseForgetDirective("Forget my name.")!!

        val removed = ConversationCoordinator.applyForgetDirective(session, memory, directive)
        val snapshot = session.snapshot()

        assertTrue(removed > 0)
        assertNull(memory.get("user.fact.name"))
        assertNull(memory.get("user.fact.preferred_name"))
        assertNull(snapshot.facts["name"])
        assertNull(snapshot.facts["preferred_name"])
        assertEquals(1, persistence.deleteCalls)
        store.close()
    }

    @Test
    fun durableDeleteFailureLeavesLiveFactUntouched() {
        val persistence = FailingDeletePersistence()
        val store = KnowledgeStore.forTesting(persistence)
        val memory = PersistentUserMemory(store)
        memory.remember("favorite_color", "teal", 1_000L)
        val session = ConversationSession().apply {
            rememberFact("favorite_color", "teal")
        }
        val directive = ConversationCoordinator.parseForgetDirective("Forget my favourite colour.")!!

        var failed = false
        try {
            ConversationCoordinator.applyForgetDirective(session, memory, directive)
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals("teal", session.snapshot().facts["favorite_color"])
        assertEquals("teal", memory.get("user.fact.favorite_color")?.body)
        store.close()
    }

    private open class InMemoryKnowledgePersistence : KnowledgePersistence {
        protected val records = linkedMapOf<String, KnowledgeRecord>()
        var deleteCalls = 0
            protected set

        override fun upsertAll(records: List<KnowledgeRecord>) {
            records.forEach { record -> this.records[record.id] = record }
        }

        override fun findById(id: String): KnowledgeRecord? = records[id]
        override fun all(): List<KnowledgeRecord> = records.values.sortedBy { it.id }

        override fun deleteByIds(ids: Set<String>): Int {
            deleteCalls += 1
            var deleted = 0
            ids.forEach { id -> if (records.remove(id) != null) deleted += 1 }
            return deleted
        }

        override fun count(): Int = records.size
        override fun close() = Unit
    }

    private class FailingDeletePersistence : InMemoryKnowledgePersistence() {
        override fun deleteByIds(ids: Set<String>): Int {
            throw IllegalStateException("simulated durable delete failure")
        }
    }
}
