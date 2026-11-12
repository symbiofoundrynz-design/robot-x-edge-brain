package nz.symbiofoundry.robotx

object GroundingGuard {
    fun shouldFailClosed(
        userText: String,
        hasReferenceContext: Boolean
    ): Boolean {
        if (hasReferenceContext) return false

        val normalized = userText
            .lowercase()
            .replace(Regex("[^a-z0-9' ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (normalized.isEmpty()) return false
        if (normalized == "how are you" || normalized.startsWith("how are you ")) return false
        if (normalized.startsWith("what do you think")) return false
        if (normalized.startsWith("do you think")) return false

        val hasPossessiveSelfReference =
            Regex("""\b(your|robot x's)\b""").containsMatchIn(normalized)

        val explicitRobotReference =
            Regex("""\brobot x\b""").containsMatchIn(normalized)

        val factualYouPattern =
            Regex(
                """^(what are you|what do you (have|use)|which .* you|where .* you|when .* you|who .* you|how (old|much|many) .* you)\b"""
            ).containsMatchIn(normalized)

        return hasPossessiveSelfReference || explicitRobotReference || factualYouPattern
    }
}
