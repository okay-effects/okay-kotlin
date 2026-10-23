package okay.kotlin.examples

import okay.kotlin.Either
import okay.kotlin.either
import okay.kotlin.guard
import okay.kotlin.observer
import okay.kotlin.raise
import okay.kotlin.runEffects
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedTest {
    private val catalog = object : Catalog {
        override suspend fun price(item: String) = mapOf("tea" to 30)[item]
        override suspend fun search(vararg words: String) = words.filter { it.startsWith("t") }
        override fun currency() = "EUR"
        override val name = "shop"
    }

    private val orders = object : Orders<Int, String> {
        val placed = mutableListOf<List<String>>()
        override suspend fun place(lines: List<String>): Int {
            placed += lines
            return placed.size
        }
        override suspend fun cancel(id: Int, reason: String?) = id <= placed.size
        override suspend fun <R> query(id: Int, view: suspend (Int) -> R): R = view(id)
        override suspend fun reject(why: String): Nothing = error(why)
        override suspend fun <T> label(t: T): String where T : CharSequence, T : Comparable<T> = "#$t"
    }

    @Test
    fun everySuspendFunctionSeenWithItsArguments() {
        val seen = mutableListOf<String>()
        val watch = observer { op, outcome -> seen += "$op -> ${outcome.getOrNull()}" }
        val c = catalog.intercepted(watch)
        val o = orders.intercepted(watch)
        runEffects {
            c.price("tea")
            c.search("tea", "jam", "toast")
            o.placeOne("tea")
            o.cancel(1, "late")
            o.query(1) { "order $it" }
            o.label("x")
        }
        assertEquals(listOf(
            "Catalog.price(tea) -> 30",
            "Catalog.search([tea, jam, toast]) -> [tea, toast]",
            // the default goes to the wrapped one's, whose own `place` is not the wrapper's
            "Orders.placeOne(tea) -> 1",
            "Orders.cancel(1, late) -> true",
        ), seen.take(4))
        // a function argument is seen as it is: a value, its text the platform's
        assert(seen[4].startsWith("Orders.query(1, ") && seen[4].endsWith(") -> order 1")) { seen[4] }
        assertEquals("Orders.label(x) -> #x", seen[5])
        assertEquals("EUR", c.currency())
        assertEquals("shop", c.name)
    }

    @Test
    fun aGuardRefusesByTheProgramsFailure() {
        val r = runEffects {
            either<String, _> {
                val noRejects = guard { op -> if (op.name == "reject") raise("no rejections: ${op.args}") }
                orders.intercepted(noRejects).reject("too late")
            }
        }
        assertEquals(Either.Left("no rejections: [too late]"), r)
    }

    @Test
    fun anInheritedEffectAndItsProperties() {
        val seen = mutableListOf<String>()
        val counter = object : Counter, Catalog by catalog {
            var n = 0
            override suspend fun count() = ++n
            override var limit = 3
        }
        val c = counter.intercepted(observer { op, _ -> seen += op.toString() })
        runEffects {
            c.count()
            c.price("jam")
        }
        c.limit = 5
        assertEquals(5, counter.limit)
        assertEquals(listOf("Counter.count()", "Counter.price(jam)"), seen)
    }
}
