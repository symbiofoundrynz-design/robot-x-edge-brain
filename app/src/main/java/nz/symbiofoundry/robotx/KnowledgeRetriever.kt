package nz.symbiofoundry.robotx

class KnowledgeRetriever(
    private val store: KnowledgeStore,
    private val maxResults: Int = 3
) {
    init {
        require(maxResults in 1..3) { "maxResults must be between 1 and 3" }
    }

    fun retrieve(query: String): List<KnowledgeRecord> {
        val queryTerms = expandTerms(tokenize(query))
        if (queryTerms.isEmpty()) return emptyList()

        return store.all()
            .asSequence()
            .filter { record -> record.category != KnowledgeCategory.USER_FACT }
            .mapNotNull { record ->
                val score = score(record, queryTerms)
                if (score <= 0) null else ScoredRecord(record, score)
            }
            .sortedWith(
                compareByDescending<ScoredRecord> { it.score }
                    .thenBy { authorityRank(it.record.authority) }
                    .thenBy { it.record.id }
            )
            .take(maxResults)
            .map { it.record }
            .toList()
    }

    private fun score(
        record: KnowledgeRecord,
        queryTerms: Set<String>
    ): Int {
        val tagTerms = record.tags.flatMap(::tokenize).toSet()
        val titleTerms = tokenize(record.title)
        val bodyTerms = tokenize(record.body)
        val categoryTerms = tokenize(record.category.name)

        return queryTerms.sumOf { term ->
            when {
                term in tagTerms -> 8
                term in titleTerms -> 5
                term in categoryTerms -> 3
                term in bodyTerms -> 2
                else -> 0
            }
        }
    }

    private fun expandTerms(terms: Set<String>): Set<String> = buildSet {
        terms.forEach { term ->
            add(term)
            SYNONYMS[term]?.let(::addAll)
        }
    }

    private fun tokenize(text: String): Set<String> =
        TOKEN_REGEX
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .map(::normalizeToken)
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    private fun normalizeToken(token: String): String = when {
        token.length > 5 && token.endsWith("ies") -> token.dropLast(3) + "y"
        token.length > 4 && token.endsWith("ing") -> token.dropLast(3)
        token.length > 4 && token.endsWith("es") -> token.dropLast(2)
        token.length > 3 && token.endsWith("s") -> token.dropLast(1)
        else -> token
    }

    private fun authorityRank(authority: KnowledgeAuthority): Int = when (authority) {
        KnowledgeAuthority.AUTHORITATIVE -> 0
        KnowledgeAuthority.SYSTEM_STATE -> 1
        KnowledgeAuthority.USER_SUPPLIED -> 2
        KnowledgeAuthority.OBSERVED -> 3
        KnowledgeAuthority.REFERENCE -> 4
    }

    private data class ScoredRecord(
        val record: KnowledgeRecord,
        val score: Int
    )

    private companion object {
        val TOKEN_REGEX = Regex("[a-z0-9]+")
        val STOP_WORDS = setOf(
            "a", "an", "and", "are", "can", "do", "does", "how", "i", "is",
            "it", "my", "of", "or", "the", "to", "what", "which", "with",
            "without", "you", "your"
        )
        val SYNONYMS = mapOf(
            "see" to setOf("vision", "camera"),
            "object" to setOf("vision", "camera"),
            "wifi" to setOf("offline", "network", "internet"),
            "internet" to setOf("offline", "network"),
            "control" to setOf("controller", "motor", "authority"),
            "motor" to setOf("controller", "authority"),
            "move" to setOf("movement", "action"),
            "voice" to setOf("stt", "tts")
        )
    }
}
