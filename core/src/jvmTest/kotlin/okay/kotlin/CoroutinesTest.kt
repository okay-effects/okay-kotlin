package okay.kotlin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/** the effects inside kotlinx.coroutines: a handled body waits for a timer, another thread, children of its own */
class CoroutinesTest {
    context(a: Ask, f: Fail<String>, w: Writer<String>)
    suspend fun slow(): Int {
        delay(20)
        val x = a.ask()
        w.tell("asked $x")
        val y = withContext(Dispatchers.Default) {
            delay(10)
            x * 2
        }
        ensure(y < 100) { "too big: $y" }
        return y
    }

    @Test
    fun aBodyWaitsForATimerAndAnotherThread() = runBlocking {
        assertEquals(Either.Right(listOf("asked 3") to 6), either<String, _> { writer { answering(3) { slow() } } })
        assertEquals(Either.Left("too big: 100"), either<String, _> { writer<String, _> { answering(50) { slow() } } })
    }

    @Test
    fun aGeneralHandlerAcrossThreads() = runBlocking(Dispatchers.Default) {
        // the clause waits, the rest goes on on another thread, many times over
        val sum = answering(1) {
            var s = 0
            repeat(200) {
                s += askFor()
                if (it % 50 == 0) withContext(Dispatchers.IO) { delay(1) }
            }
            s
        }
        assertEquals(200, sum)
    }

    @Test
    fun childrenOfABodyAskItsHandler() = runBlocking {
        // children run in the body's scope, each asking the handler outside it: answered in place
        val r = reader(7) {
            state(0) {
                coroutineScope {
                    (1..5).map { i -> async(Dispatchers.Default) { delay(5); ask<Int>() * i } }.awaitAll().sum()
                }
            }
        }
        assertEquals(0 to 105, r)
    }

    @Test
    fun raisedOnAnotherThread() = runBlocking {
        // the README's: the failure raised inside withContext, on another thread, while the body waits for it
        val r = either<String, _> {
            delay(20)
            withContext(Dispatchers.Default) { raise("stopped on another thread") }
        }
        assertEquals(Either.Left("stopped on another thread"), r)
    }

    @Test
    fun theRestDroppedWhileWaiting() = runBlocking {
        val log = mutableListOf<String>()
        val r = either<String, Int> {
            try {
                delay(5)
                raise("stop")
            } finally {
                log += "released"
            }
        }
        assertEquals(Either.Left("stop"), r)
        assertEquals(listOf("released"), log)
    }
}
