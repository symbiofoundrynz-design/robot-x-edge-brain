package nz.symbiofoundry.robotx

/**
 * C2 persistent-user-memory boundary over durable structured user facts.
 *
 * C2 persistent-memory boundary for the four approved structured user facts.
 * C2D adds deterministic delete/forget semantics while preserving the existing
 * C2B write/update and C2C structured rehydration boundaries.
 */
class PersistentUserMemory(
    private val store: KnowledgeStore
) {
    fun all(): List<KnowledgeRecord> =
        store.allByCategory(KnowledgeCategory.USER_FACT)
            .filter(::isPersistentUserFact)

    fun get(id: String): KnowledgeRecord? {
        require(id.isNotBlank()) { "Persistent user-memory id must not be blank" }
        return store.getExact(id)?.takeIf(::isPersistentUserFact)
    }

    /**
     * C2C deterministic rehydration surface.
     *
     * Only the four approved canonical IDs are eligible to enter a fresh
     * ConversationSession. Arbitrary USER_FACT rows remain durable data but
     * cannot silently expand the session-memory authority boundary.
     */
    fun sessionFacts(): Map<String, String> {
        val byId = all().associateBy { it.id }
        val facts = linkedMapOf<String, String>()

        for ((key, definition) in DEFINITIONS) {
            val value = byId[definition.id]
                ?.body
                ?.trim()
                ?.replace(Regex("""\s+"""), " ")
                .orEmpty()
            if (value.isNotEmpty()) {
                facts[key] = value
            }
        }

        return facts
    }

    fun remember(
        key: String,
        value: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): KnowledgeRecord {
        val definition = DEFINITIONS[key]
            ?: throw IllegalArgumentException("Unsupported persistent user-memory key: $key")
        val normalizedValue = value
            .trim()
            .replace(Regex("""\s+"""), " ")
        require(normalizedValue.isNotEmpty()) { "Persistent user-memory value must not be blank" }
        require(nowEpochMs >= 0L) { "Persistent user-memory timestamp must be non-negative" }

        val existing = store.getExact(definition.id)
        if (existing != null && !isPersistentUserFact(existing)) {
            throw IllegalStateException(
                "Refusing to overwrite non-user-memory record ${definition.id}"
            )
        }

        val record = KnowledgeRecord(
            id = definition.id,
            category = KnowledgeCategory.USER_FACT,
            title = definition.title,
            body = normalizedValue,
            authority = KnowledgeAuthority.USER_SUPPLIED,
            source = SOURCE_EXPLICIT_USER_FACT,
            tags = setOf("user_fact", key),
            createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = null
        )

        store.upsert(record)
        return record
    }


    /**
     * C2D deterministic durable forget operation.
     *
     * The caller supplies approved fact keys, never raw database IDs. All
     * resolved IDs are deleted together through KnowledgeStore's atomic delete
     * boundary. A canonical-ID collision with non-user-memory data is refused.
     */
    fun forget(keys: Set<String>): Int {
        if (keys.isEmpty()) return 0

        val definitions = keys.associateWith { key ->
            DEFINITIONS[key]
                ?: throw IllegalArgumentException(
                    "Unsupported persistent user-memory key: $key"
                )
        }

        val ids = definitions.values.mapTo(linkedSetOf()) { it.id }
        ids.forEach { id ->
            val existing = store.getExact(id) ?: return@forEach
            if (!isPersistentUserFact(existing)) {
                throw IllegalStateException(
                    "Refusing to delete non-user-memory record $id"
                )
            }
        }

        return store.deleteExact(ids)
    }

    private fun isPersistentUserFact(record: KnowledgeRecord): Boolean =
        record.category == KnowledgeCategory.USER_FACT &&
            record.authority == KnowledgeAuthority.USER_SUPPLIED

    private data class Definition(
        val id: String,
        val title: String
    )

    companion object {
        const val SOURCE_EXPLICIT_USER_FACT = "conversation.explicit_user_fact"

        private val DEFINITIONS = linkedMapOf(
            "name" to Definition("user.fact.name", "User name"),
            "preferred_name" to Definition("user.fact.preferred_name", "Preferred name"),
            "favorite_color" to Definition("user.fact.favorite_color", "Favourite colour"),
            "location" to Definition("user.fact.location", "Home location")
        )

        val supportedKeys: Set<String>
            get() = DEFINITIONS.keys.toSet()
    }
}
