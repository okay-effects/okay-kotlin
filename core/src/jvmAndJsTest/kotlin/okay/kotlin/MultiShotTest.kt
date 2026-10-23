package okay.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

context(_: Choose)
suspend fun triple(n: Int): Triple<Int, Int, Int> {
    val a = among((1..n).toList())
    val b = among((a..n).toList())
    val c = among((b..n).toList())
    if (a * a + b * b != c * c) none()
    return Triple(a, b, c)
}

/** n queens, a row at a time: `placed` a local of the body, a value — each copy of the rest has its own */
context(_: Choose)
suspend fun queens(n: Int): List<Int> {
    var placed = emptyList<Int>()
    for (row in 0..<n) {
        val col = among((0..<n).toList())
        if (placed.withIndex().any { (r, c) -> c == col || row - r == kotlin.math.abs(col - c) }) none()
        placed = placed + col
    }
    return placed
}

/** the same with a mutable list: one object on the heap, shared by every copy — the ways spoil each other */
context(_: Choose)
suspend fun queensSharing(n: Int): List<Int> {
    val placed = ArrayList<Int>()
    for (row in 0..<n) {
        val col = among((0..<n).toList())
        if (placed.withIndex().any { (r, c) -> c == col || row - r == kotlin.math.abs(col - c) }) none()
        placed += col
    }
    return placed.toList()
}

/** the paper's k used any number of times: the rest copied, each copy resumed once */
class MultiShotTest {
    @Test
    fun everyWayOfTwoFlips() {
        assertEquals(listOf(true to true, true to false, false to true, false to false),
            runEffects { all { flip() to flip() } })
    }

    @Test
    fun pythagoreanTriples() {
        assertEquals(listOf(Triple(3, 4, 5), Triple(5, 12, 13), Triple(6, 8, 10), Triple(8, 15, 17),
            Triple(9, 12, 15), Triple(12, 16, 20)), runEffects { all { triple(20) } })
        assertEquals(Triple(3, 4, 5), runEffects { first { triple(20) } })
        assertNull(runEffects { first { triple(4) } })
    }

    @Test
    fun aLocalOfTheBodyIsEachWaysOwn() {
        assertEquals(listOf(listOf(1, 3, 0, 2), listOf(2, 0, 3, 1)), runEffects { all { queens(4) } })
        assertEquals(92, runEffects { all { queens(8) } }.size)
        assertEquals(listOf(0, 4, 7, 5, 2, 6, 1, 3), runEffects { first { queens(8) } })
        // a mutable object is shared: not the queens
        assertEquals(false, runEffects { all { queensSharing(4) } } == listOf(listOf(1, 3, 0, 2), listOf(2, 0, 3, 1)))
    }

    @Test
    fun aLoopGoesOnFromWhereItWasInEachCopy() {
        // the loop's iterator is copied with the frame: a range's, a list's (on the JVM, the JDK's when opened to it)
        val every = listOf(listOf(true, true), listOf(true, false), listOf(false, true), listOf(false, false))
        suspend fun overRange(c: Choose): List<Boolean> {
            var acc = emptyList<Boolean>()
            for (i in 0..<2) acc = acc + c.among(listOf(true, false))
            return acc
        }
        suspend fun overRepeat(c: Choose): List<Boolean> {
            var acc = emptyList<Boolean>()
            repeat(2) { acc = acc + c.among(listOf(true, false)) }
            return acc
        }
        suspend fun overList(c: Choose): List<Boolean> {
            var acc = emptyList<Boolean>()
            for (x in listOf(1, 2)) acc = acc + c.among(listOf(true, false))
            return acc
        }
        assertEquals(every, runEffects { all { overRange(contextOf<Choose>()) } })
        assertEquals(every, runEffects { all { overRepeat(contextOf<Choose>()) } })
        assertEquals(every, runEffects { all { overList(contextOf<Choose>()) } })
        assertEquals(every, runEffects {
            all {
                var acc = emptyList<Boolean>()
                var i = 0
                while (i < 2) {
                    acc = acc + flip()
                    i++
                }
                acc
            }
        })
    }

    @Test
    fun choicesObservedEachCopyGoingOnThroughTheObserver() {
        // the observer's frame is in the rest: copied with it, each way's answer seen
        val seen = mutableListOf<String>()
        val watch = observer { op, outcome -> seen += "$op=${outcome.getOrNull()}" }
        val r = runEffects { all { context(contextOf<Choose>().intercepted(watch)) { flip() to flip() } } }
        assertEquals(4, r.size)
        assertEquals(listOf("Choose.among([true, false])=true", "Choose.among([true, false])=true",
            "Choose.among([true, false])=false", "Choose.among([true, false])=false",
            "Choose.among([true, false])=true", "Choose.among([true, false])=false"), seen)
    }

    @Test
    fun aHandlerInPlaceIsOneForEveryWay() {
        // state outside the choice, answered in place: one counter for both ways
        assertEquals(2 to listOf(1, 2), runEffects { state(0) { all { flip(); modify<Int> { it + 1 }; get<Int>() } } })
    }

    @Test
    fun copyingAcrossADelimitingHandlerIsRefused() {
        val e = assertFailsWith<IllegalStateException> {
            runEffects { all { either<String, _> { flip() } } }
        }
        assertEquals("a continuation cannot be copied across a handler that delimits its body: " +
            "put the copying handler inside it", e.message)
        // inside it, it works
        assertEquals(Either.Right(listOf(true, false)), runEffects { either<String, _> { all { flip() } } })
    }

    @Test
    fun aCopyIsTakenBeforeTheRestIsResumed() {
        val e = assertFailsWith<IllegalStateException> {
            runEffects { handle<Int> { p -> context(Ask { p.shift { k -> k(1) + k.copy()(2) } }) { askFor() } } }
        }
        assertEquals("a continuation copied after it was resumed: copy it first", e.message)
    }
}
