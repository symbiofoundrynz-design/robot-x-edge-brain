package nz.symbiofoundry.robotx

enum class KnowledgeCategory {
    IDENTITY,
    HARDWARE,
    SOFTWARE,
    CAPABILITY,
    LIMITATION,
    SAFETY,
    PROCEDURE,
    USER_FACT,
    OBSERVATION
}

enum class KnowledgeAuthority {
    AUTHORITATIVE,
    USER_SUPPLIED,
    SYSTEM_STATE,
    OBSERVED,
    REFERENCE
}

data class KnowledgeRecord(
    val id: String,
    val category: KnowledgeCategory,
    val title: String,
    val body: String,
    val authority: KnowledgeAuthority,
    val source: String,
    val tags: Set<String> = emptySet(),
    val createdAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val expiresAtEpochMs: Long? = null
) {
    init {
        require(id.isNotBlank()) { "Knowledge record id must not be blank" }
        require(title.isNotBlank()) { "Knowledge record title must not be blank" }
        require(body.isNotBlank()) { "Knowledge record body must not be blank" }
        require(source.isNotBlank()) { "Knowledge record source must not be blank" }
    }
}
