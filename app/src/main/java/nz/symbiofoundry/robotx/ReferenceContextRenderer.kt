package nz.symbiofoundry.robotx

class ReferenceContextRenderer(
    private val maxRecords: Int = 3,
    private val maxChars: Int = 600
) {
    init {
        require(maxRecords in 1..3) { "maxRecords must be between 1 and 3" }
        require(maxChars >= 100) { "maxChars must be at least 100" }
    }

    fun render(records: List<KnowledgeRecord>): String {
        if (records.isEmpty()) return ""

        val header = "Reference context:\n"
        val builder = StringBuilder(header)

        records.take(maxRecords).forEach { record ->
            val line = "- ${record.title}: ${record.body}\n"
            val remaining = maxChars - builder.length
            if (remaining <= 0) return@forEach

            if (line.length <= remaining) {
                builder.append(line)
            } else {
                val ellipsis = "…"
                val take = (remaining - ellipsis.length).coerceAtLeast(0)
                builder.append(line.take(take)).append(ellipsis)
            }
        }

        return builder.toString().trimEnd()
    }
}
