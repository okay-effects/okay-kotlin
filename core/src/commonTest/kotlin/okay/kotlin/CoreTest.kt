package okay.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** an effect of the tests: a number asked for, its handlers general — they take the rest of the body */
fun interface Ask {
    suspend fun ask(): Int
}

context(a: Ask) suspend fun askFor(): Int = a.ask()

/** each ask answered with `n`, the rest resumed in the clause: a general handler in tail position */
suspend fun <A> answering(n: Int, body: suspend context(Ask) () -> A): A = handle { prompt ->
    context(Ask { prompt.shift { k -> k(n) } }) { body() }
}

/** each ask answered twice over, the rests' values added: needs the rest twice — refused, it is one-shot */
suspend fun bothWays(body: suspend context(Ask) () -> Int): Int = handle { prompt ->
    context(Ask { prompt.shift { k -> k(1) + k(2) } }) { body() }
}

class CoreTest {
    context(a: Ask, f: Fail<String>)
    suspend fun checked(): Int {
        val x = a.ask()
        ensure(x <= 5) { "big $x" }
        return x * 2
    }

    @Test
    fun handlersComposeInEitherOrder() {
        assertEquals(Either.Right(6), runEffects { either<String, _> { answering(3) { checked() } } })
        assertEquals(Either.Right(6), runEffects { answering(3) { either<String, _> { checked() } } })
        assertEquals(Either.Left("big 9"), runEffects { answering(9) { either<String, _> { checked() } } })
    }

    @Test
    fun theRestDroppedRunsItsFinallyBlocks() {
        val log = mutableListOf<String>()
        val r = runEffects {
            either<String, _> {
                answering(9) {
                    try {
                        checked()
                    } finally {
                        log += "released"
                    }
                }
            }
        }
        assertEquals(Either.Left("big 9"), r)
        assertEquals(listOf("released"), log)
    }

    @Test
    fun aDeepHandlerRunsInConstantStack() {
        val sum = runEffects {
            answering(1) {
                var s = 0L
                repeat(1_000_000) { s += askFor() }
                s
            }
        }
        assertEquals(1_000_000L, sum)
    }

    @Test
    fun theClauseSeesWhatTheRestGives() {
        // the clause runs after the rest: what it does with the rest's value is the handler's value
        val r = runEffects {
            handle<Int> { prompt ->
                context(Ask { prompt.shift { k -> k(10) * 2 } }) { askFor() + 1 }
            }
        }
        assertEquals(22, r)
    }

    @Test
    fun aContinuationIsOneShot() {
        val e = assertFailsWith<IllegalStateException> { runEffects { bothWays { askFor() } } }
        assertEquals("a continuation resumed twice, or after it was dropped: it is one-shot", e.message)
    }

    @Test
    fun aClauseUsesTheHandlersOutsideIt() {
        // the clause of the inner Ask asks the outer one, and resumes the rest from inside another handler
        val r = runEffects {
            answering(7) {
                handle<Int> { prompt ->
                    context(Ask { prompt.shift { k -> answering(100) { k(askFor() * askFor()) } } }) { askFor() }
                }
            }
        }
        assertEquals(10_000, r)
    }

    @Test
    fun anOperationCrossesTheHandlersBetween() {
        val r = runEffects {
            either<String, Int> {
                answering(2) {
                    state(0) {
                        reader("env") {
                            writer<String, Int> {
                                tell(ask<String>())
                                put(askFor())
                                if (get<Int>() > 1) raise("two")
                                0
                            }.second
                        }
                    }.second
                }
            }
        }
        assertEquals(Either.Left("two"), r)
    }

    @Test
    fun abortGivesTheHandlersValue() {
        val r = runEffects { handle<String> { prompt -> prompt.abort("early"); @Suppress("UNREACHABLE_CODE") "late" } }
        assertEquals("early", r)
    }

    @Test
    fun anExceptionGoesThroughTheHandlers() {
        val e = assertFailsWith<IllegalArgumentException> {
            runEffects { answering(1) { either<String, Int> { require(askFor() > 1) { "small" }; 0 } } }
        }
        assertEquals("small", e.message)
    }
}
