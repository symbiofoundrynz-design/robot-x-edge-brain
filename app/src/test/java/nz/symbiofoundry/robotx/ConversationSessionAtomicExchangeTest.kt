package nz.symbiofoundry.robotx

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ConversationSessionAtomicExchangeTest {
@Test fun successfulExchangeAppendsUserThenRobot() {
val session = ConversationSession()
session.rememberExchange("What is there?", "A chair.")
val turns = session.snapshot().turns
assertEquals(2, turns.size)
assertEquals(ConversationSession.Speaker.USER, turns[0].speaker)
assertEquals("What is there?", turns[0].text)
assertEquals(ConversationSession.Speaker.ROBOT, turns[1].speaker)
assertEquals("A chair.", turns[1].text)
}

@Test fun blankUserRejectsEntireExchange() {
val session = ConversationSession()
session.rememberExchange("first", "reply")
val before = session.snapshot().turns
session.rememberExchange(" ", "not stored")
assertEquals(before, session.snapshot().turns)
}

@Test fun blankRobotRejectsEntireExchange() {
val session = ConversationSession()
session.rememberExchange("first", "reply")
val before = session.snapshot().turns
session.rememberExchange("not stored", " ")
assertEquals(before, session.snapshot().turns)
}

@Test fun exchangeNormalizesBothTexts() {
val session = ConversationSession()
session.rememberExchange(" where is\n it? ", " right\t here ")
val turns = session.snapshot().turns
assertEquals("where is it?", turns[0].text)
assertEquals("right here", turns[1].text)
}

@Test fun exchangeRespectsBoundedHistory() {
val session = ConversationSession(maxTurns = 4)
(1..5).forEach { session.rememberExchange("user-$it", "robot-$it") }
val turns = session.snapshot().turns
assertEquals(4, turns.size)
assertEquals(listOf("user-4", "robot-4", "user-5", "robot-5"), turns.map { it.text })
}

@Test fun exchangeRespectsTextLengthBounds() {
val session = ConversationSession(maxTextChars = 5)
session.rememberExchange("abcdefgh", "12345678")
assertEquals(listOf("abcde", "12345"), session.snapshot().turns.map { it.text })
}

@Test fun concurrentExchangesRetainWholePairs() {
val session = ConversationSession(maxTurns = 200)
val ready = CountDownLatch(2)
val start = CountDownLatch(1)
val failure = AtomicReference<Throwable?>(null)
val workers = (1..2).map { workerId ->
Thread {
try {
ready.countDown()
check(start.await(5, TimeUnit.SECONDS))
(1..25).forEach { i ->
val key = "$workerId-$i"
session.rememberExchange("U:$key", "R:$key")
}
} catch (error: Throwable) {
failure.compareAndSet(null, error)
}
}
}
workers.forEach { it.start() }
assertTrue("workers failed to start", ready.await(5, TimeUnit.SECONDS))
start.countDown()
workers.forEach { it.join(5000) }
assertTrue("worker did not finish", workers.none { it.isAlive })
failure.get()?.let { throw AssertionError("worker failed", it) }
val turns = session.snapshot().turns
assertEquals(100, turns.size)
turns.chunked(2).forEach { pair ->
assertEquals(ConversationSession.Speaker.USER, pair[0].speaker)
assertEquals(ConversationSession.Speaker.ROBOT, pair[1].speaker)
assertEquals(pair[0].text.removePrefix("U:"), pair[1].text.removePrefix("R:"))
}
}

@Test fun concurrentSnapshotsNeverObserveHalfAnExchange() {
val session = ConversationSession(maxTurns = 200)
val ready = CountDownLatch(2)
val start = CountDownLatch(1)
val failure = AtomicReference<Throwable?>(null)
val writer = Thread {
try {
ready.countDown()
check(start.await(5, TimeUnit.SECONDS))
(1..50).forEach { session.rememberExchange("U:$it", "R:$it") }
} catch (error: Throwable) {
failure.compareAndSet(null, error)
}
}
val reader = Thread {
try {
ready.countDown()
check(start.await(5, TimeUnit.SECONDS))
repeat(500) {
val turns = session.snapshot().turns
check(turns.size % 2 == 0)
turns.chunked(2).forEach { pair ->
check(pair[0].speaker == ConversationSession.Speaker.USER)
check(pair[1].speaker == ConversationSession.Speaker.ROBOT)
}
}
} catch (error: Throwable) {
failure.compareAndSet(null, error)
}
}
listOf(writer, reader).forEach { it.start() }
assertTrue("threads failed to start", ready.await(5, TimeUnit.SECONDS))
start.countDown()
listOf(writer, reader).forEach { it.join(5000) }
assertFalse("writer still running", writer.isAlive)
assertFalse("reader still running", reader.isAlive)
failure.get()?.let { throw AssertionError("concurrent snapshot failed", it) }
assertEquals(100, session.snapshot().turns.size)
}
}
