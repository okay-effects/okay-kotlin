package okay.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// the programs of the tests: plain suspend functions, their effects their context parameters

context(_: Writer<String>)
private suspend fun part(): Int {
    tell("a")
    tell("b")
    return 7
}

context(_: Emit<Int>)
private suspend fun count(n: Int): String {
    for (i in 1..n) emit(i)
    return "done"
}

context(random: Randomness)
private suspend fun five(): List<Int> = List(5) { random.nextInt(10) }

context(store: Store<String, Int>)
private suspend fun deposit(who: String, n: Int) = store.save(who, (store.load(who) ?: 0) + n)

context(clock: Clock)
private suspend fun aDay(): Pair<Long, Long> {
    val start = clock.now()
    clock.sleep(86_400_000)
    return start to clock.now()
}

context(_: Log)
private suspend fun work(): Int {
    debug("d")
    info("i")
    error("e")
    return 1
}

class StdTest {
    @Test
    fun stateTwoOfThemToldApartByType() {
        val r = runEffects {
            state(1) {
                state("a") {
                    put(get<Int>() + 1)
                    modify<String> { it + "b" }
                    gets<Int, Int> { it * 10 }
                }
            }
        }
        assertEquals(2 to ("ab" to 20), r)
    }

    @Test
    fun readerLocal() {
        val r = runEffects { reader(10) { Triple(ask<Int>(), local<Int, Int>({ it * 2 }) { ask() }, ask<Int>()) } }
        assertEquals(Triple(10, 20, 10), r)
    }

    @Test
    fun writerListenAndCensor() {
        assertEquals(listOf("a", "b") to (listOf("a", "b") to 7),
            runEffects { writer<String, _> { listen { part() } } })
        assertEquals(listOf("A", "B") to 7,
            runEffects { writer<String, _> { censor({ ws -> ws.map { it.uppercase() } }) { part() } } })
    }

    @Test
    fun failBindAndRecover() {
        val parsed: Either<String, Int> = Either.Right(4)
        assertEquals(Either.Right(5), runEffects { either<String, _> { parsed.bind() + 1 } })
        assertEquals(4, runEffects { recover<String, Int>({ raise("four") }) { it.length } })
    }

    @Test
    fun optionalOfNullables() {
        val prices = mapOf("tea" to 30, "cake" to 50)
        suspend fun total(items: List<String>) = optional { items.sumOf { prices[it].bind() } }
        assertEquals(80, runEffects { total(listOf("tea", "cake")) })
        assertNull(runEffects { total(listOf("tea", "jam")) })
    }

    @Test
    fun emitCollectTakeForEach() {
        assertEquals(listOf(1, 2, 3) to "done", runEffects { collect<Int, _> { count(3) } })
        assertEquals(listOf(1, 2) to null, runEffects { take<Int, _>(2) { count(5) } })
        assertEquals(listOf(1, 2, 3) to "done", runEffects { take<Int, _>(9) { count(3) } })
        val seen = mutableListOf<Int>()
        assertEquals("done", runEffects { forEach<Int, _>({ count(2) }) { seen += it * 10 } })
        assertEquals(listOf(10, 20), seen)
    }

    @Test
    fun freshAndSeededRandomness() {
        assertEquals(100L to 101L, runEffects { numbered(100) { fresh() to fresh() } })
        val first = runEffects { seeded(42) { five() } }
        assertEquals(first, runEffects { seeded(42) { five() } })
        assertNotEquals(first, runEffects { seeded(43) { five() } })
        assertTrue(first.all { it in 0..<10 })
        val d = runEffects { seeded(1) { contextOf<Randomness>().nextDouble() } }
        assertTrue(d >= 0 && d < 1)
    }

    @Test
    fun storeInMemory() {
        val r = runEffects {
            inMemory<String, Int, _> {
                deposit("a", 1)
                deposit("b", 2)
                val store = contextOf<Store<String, Int>>()
                val a = store.load("a")
                store.remove("a")
                a to store.load("a")
            }
        }
        assertEquals(mapOf("b" to 2) to (1 to null), r)
    }

    @Test
    fun virtualTime() {
        assertEquals(86_401_000L to (1000L to 86_401_000L), runEffects { virtualTime(1000) { aDay() } })
    }

    @Test
    fun logFromALevel() {
        assertEquals(listOf(Level.Info to "i", Level.Error to "e") to 1, runEffects { logged(Level.Info) { work() } })
        val seen = mutableListOf<String>()
        assertEquals(1, runEffects { logTo({ level, m -> seen += "$level $m" }, Level.Warn) { work() } })
        assertEquals(listOf("Error e"), seen)
    }

    @Test
    fun aHandlerWrappedByDelegation() {
        // a journal before the store: the store's handler, wrapped by delegation — the program does not change
        val journal = mutableListOf<String>()
        val r = runEffects {
            inMemory<String, Int, _> {
                val store = contextOf<Store<String, Int>>()
                val journaled = object : Store<String, Int> by store {
                    override suspend fun save(key: String, value: Int) {
                        journal += "$key=$value"
                        store.save(key, value)
                    }
                }
                context(journaled) {
                    deposit("ann", 100)
                    deposit("ann", 50)
                }
            }
        }
        assertEquals(mapOf("ann" to 150), r.first)
        assertEquals(listOf("ann=100", "ann=150"), journal)
    }

    @Test
    fun theStdlibErrorIsNotShadowed() {
        assertEquals("plain", runCatching { error("plain") }.exceptionOrNull()?.message)
    }
}
