package nz.symbiofoundry.robotx

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class VisualResponseLifecycleTest {
@Test fun newTokenIsCurrent() {
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
assertEquals("observation-a", token.observationId)
assertTrue(owner.isCurrent(token))
}

@Test fun newerRequestSupersedesOlderRequest() {
val owner = VisualResponseLifecycle()
val old = owner.begin("observation-a")
val current = owner.begin("observation-b")
assertFalse(owner.isCurrent(old))
assertTrue(owner.isCurrent(current))
assertFalse(owner.commitOnce(old) { fail("stale commit executed") })
assertFalse(owner.claimPublication(old))
}

@Test fun invalidationRevokesCommitAndPublication() {
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
owner.invalidate()
assertFalse(owner.isCurrent(token))
assertFalse(owner.commitOnce(token) { fail("invalid commit executed") })
assertFalse(owner.claimPublication(token))
}

@Test fun commitIsPerformedAtMostOnce() {
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
var commits = 0
assertTrue(owner.commitOnce(token) { commits++ })
assertFalse(owner.commitOnce(token) { commits++ })
assertEquals(1, commits)
}

@Test fun publicationRequiresSuccessfulCommitClaim() {
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
assertFalse(owner.claimPublication(token))
assertTrue(owner.commitOnce(token) {})
assertTrue(owner.claimPublication(token))
}

@Test fun publicationCanBeClaimedOnlyOnce() {
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
assertTrue(owner.commitOnce(token) {})
assertTrue(owner.claimPublication(token))
assertFalse(owner.claimPublication(token))
}

@Test fun failedCommitCallbackConsumesClaimWithoutPublishing() {
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
try {
owner.commitOnce(token) { throw IllegalStateException("simulated commit failure") }
fail("expected callback exception")
} catch (expected: IllegalStateException) {
assertEquals("simulated commit failure", expected.message)
}
assertFalse(owner.commitOnce(token) { fail("retry executed") })
assertFalse(owner.claimPublication(token))
}

@Test fun blankObservationIdIsRejected() {
val owner = VisualResponseLifecycle()
try {
owner.begin(" ")
fail("expected invalid observation ID")
} catch (expected: IllegalArgumentException) {
assertFalse(expected.message.isNullOrBlank())
}
}

@Test fun concurrentCommitAttemptsAllowExactlyOneCallback() {
val owner = VisualResponseLifecycle()
val token = owner.begin("observation-a")
val ready = CountDownLatch(2)
val go = CountDownLatch(1)
val commits = AtomicInteger(0)
val approvals = AtomicInteger(0)
val workers = (1..2).map {
Thread {
ready.countDown()
go.await()
if (owner.commitOnce(token) { commits.incrementAndGet() }) {
approvals.incrementAndGet()
}
}
}
workers.forEach { it.start() }
assertTrue("workers did not start", ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
go.countDown()
workers.forEach { it.join(5000) }
assertTrue("worker still running", workers.none { it.isAlive })
assertEquals(1, commits.get())
assertEquals(1, approvals.get())
}

@Test fun newGenerationAllowsFreshCommitAndPublication() {
val owner = VisualResponseLifecycle()
val first = owner.begin("observation-a")
assertTrue(owner.commitOnce(first) {})
assertTrue(owner.claimPublication(first))
val second = owner.begin("observation-b")
assertFalse(owner.claimPublication(first))
assertTrue(owner.commitOnce(second) {})
assertTrue(owner.claimPublication(second))
}
}
