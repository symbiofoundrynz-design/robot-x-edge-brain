package nz.symbiofoundry.robotx

/** Owns authority to commit and publish one visual conversation response. */
internal class VisualResponseLifecycle {
internal data class Token(val generation: Long, val observationId: String)

private var generation = 0L
private var current: Token? = null
private var commitClaimed = false
private var commitCompleted = false
private var publicationClaimed = false

@Synchronized
fun begin(observationId: String): Token {
require(observationId.isNotBlank()) { "Observation ID must not be blank" }
check(generation < Long.MAX_VALUE) { "Visual generation exhausted" }
generation += 1
return Token(generation, observationId).also {
current = it
commitClaimed = false
commitCompleted = false
publicationClaimed = false
}
}

@Synchronized
fun isCurrent(token: Token): Boolean = current == token

/** Revokes any outstanding visual response, including during destruction or STOP. */
@Synchronized
fun invalidate() {
current = null
commitClaimed = false
commitCompleted = false
publicationClaimed = false
}

/** Authorises one memory commit while holding the same lock as begin/invalidate. */
@Synchronized
fun commitOnce(token: Token, commitApprovedResponse: () -> Unit): Boolean {
if (current != token || commitClaimed) return false
commitClaimed = true
commitApprovedResponse()
commitCompleted = true
return true
}

/** Claims one publication after a completed commit; caller acts on its owner thread. */
@Synchronized
fun claimPublication(token: Token): Boolean {
if (current != token || !commitCompleted || publicationClaimed) return false
publicationClaimed = true
return true
}
}
