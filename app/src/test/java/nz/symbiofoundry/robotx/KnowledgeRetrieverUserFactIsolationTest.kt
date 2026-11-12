package nz.symbiofoundry.robotx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRetrieverUserFactIsolationTest {
    @Test
    fun userFactNeverEntersGeneralReferenceRetrieval() {
        val store = storeOf(
            record(
                id = "user.fact.favorite_color",
                category = KnowledgeCategory.USER_FACT,
                authority = KnowledgeAuthority.USER_SUPPLIED,
                title = "Favourite colour",
                body = "The user's favourite colour is green.",
                tags = setOf("favourite", "color", "green")
            )
        )

        val results = KnowledgeRetriever(store)
            .retrieve("What is my favourite color green?")

        assertTrue(results.isEmpty())
        store.close()
    }

    @Test
    fun normalReferenceRetrievalStillWorksBesideUserFacts() {
        val store = storeOf(
            record(
                id = "user.fact.phone_preference",
                category = KnowledgeCategory.USER_FACT,
                authority = KnowledgeAuthority.USER_SUPPLIED,
                title = "Phone preference",
                body = "The user likes a phone.",
                tags = setOf("phone")
            ),
            record(
                id = "hardware.phone",
                category = KnowledgeCategory.HARDWARE,
                authority = KnowledgeAuthority.AUTHORITATIVE,
                title = "Robot X phone",
                body = "Robot X uses a Samsung Galaxy A03s.",
                tags = setOf("phone", "android")
            )
        )

        val results = KnowledgeRetriever(store).retrieve("What phone are you using?")

        assertEquals("hardware.phone", results.firstOrNull()?.id)
        assertTrue(results.none { it.category == KnowledgeCategory.USER_FACT })
        store.close()
    }

    private fun record(
        id: String,
        category: KnowledgeCategory,
        authority: KnowledgeAuthority,
        title: String,
        body: String,
        tags: Set<String>
    ) = KnowledgeRecord(
        id = id,
        category = category,
        title = title,
        body = body,
        authority = authority,
        source = "C2A test",
        tags = tags
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
