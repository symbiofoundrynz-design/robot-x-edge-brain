package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PersistentUserMemoryRehydrationTest {
    @Test
    fun sessionFactsExposeOnlyApprovedCanonicalUserFacts() {
        val store = storeOf(
            userFact("user.fact.name", "Alex", "name"),
            userFact("user.fact.favorite_color", "  dark   green  ", "favorite_color"),
            KnowledgeRecord(
                id = "user.fact.unapproved",
                category = KnowledgeCategory.USER_FACT,
                title = "Unapproved user fact",
                body = "must not hydrate",
                authority = KnowledgeAuthority.USER_SUPPLIED,
                source = PersistentUserMemory.SOURCE_EXPLICIT_USER_FACT
            ),
            KnowledgeRecord(
                id = "user.fact.location",
                category = KnowledgeCategory.USER_FACT,
                title = "Wrong authority",
                body = "Auckland",
                authority = KnowledgeAuthority.REFERENCE,
                source = "test"
            )
        )

        val facts = PersistentUserMemory(store).sessionFacts()

        assertEquals(
            linkedMapOf(
                "name" to "Alex",
                "favorite_color" to "dark green"
            ),
            facts
        )
        assertFalse(facts.containsKey("unapproved"))
        assertFalse(facts.containsKey("location"))
        store.close()
    }

    @Test
    fun coordinatorRehydrationPolicyLoadsFactsWithoutTurnsOrClarification() {
        val store = storeOf(
            userFact("user.fact.preferred_name", "Taylor", "preferred_name"),
            userFact("user.fact.location", "Wellington", "location")
        )
        val session = ConversationSession()

        val loaded = ConversationCoordinator.rehydrateSession(
            session,
            PersistentUserMemory(store)
        )
        val snapshot = session.snapshot()

        assertEquals(2, loaded)
        assertEquals("Taylor", snapshot.facts["preferred_name"])
        assertEquals("Wellington", snapshot.facts["location"])
        assertEquals(2, snapshot.facts.size)
        assertEquals(emptyList<ConversationSession.Turn>(), snapshot.turns)
        assertEquals(null, snapshot.pendingClarification)
        store.close()
    }

    private fun userFact(id: String, body: String, key: String): KnowledgeRecord =
        KnowledgeRecord(
            id = id,
            category = KnowledgeCategory.USER_FACT,
            title = "User fact",
            body = body,
            authority = KnowledgeAuthority.USER_SUPPLIED,
            source = PersistentUserMemory.SOURCE_EXPLICIT_USER_FACT,
            tags = setOf("user_fact", key),
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L
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
