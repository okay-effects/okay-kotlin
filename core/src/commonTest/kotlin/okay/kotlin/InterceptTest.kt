package okay.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

context(store: Store<String, Int>)
private suspend fun move(from: String, to: String) {
    val n = store.load(from) ?: 0
    store.remove(from)
    store.save(to, n)
}

/** a sensitive effect: money moved */
interface Payments {
    suspend fun pay(to: String, amount: Int): String
}

/** its handler gives a vault, not the capability: the program pays only under an audit */
suspend fun <A> payments(body: suspend (Vault<Payments>) -> A): A {
    var n = 0
    val bank = object : Payments {
        override suspend fun pay(to: String, amount: Int) = "receipt ${++n}: $amount to $to"
    }
    return body(vault(bank) { p, i ->
        object : Payments {
            override suspend fun pay(to: String, amount: Int) =
                i.around(Operation("Payments", "pay", listOf(to, amount))) { p.pay(to, amount) }
        }
    })
}

context(p: Payments)
private suspend fun salaries(): List<String> = listOf(p.pay("ann", 100), p.pay("bob", 5000))

class InterceptTest {
    @Test
    fun anObserverSeesEachOperationAndItsAnswer() {
        val seen = mutableListOf<String>()
        val watch = observer { op, outcome -> seen += "$op = ${outcome.getOrNull()}" }
        val (db, _) = runEffects {
            inMemory(mapOf("a" to 1)) { context(contextOf<Store<String, Int>>().intercepted(watch)) { move("a", "b") } }
        }
        assertEquals(mapOf("b" to 1), db)
        assertEquals(listOf("Store.load(a) = 1", "Store.remove(a) = kotlin.Unit", "Store.save(b, 1) = kotlin.Unit"),
            seen)
    }

    @Test
    fun aGuardRefusesByTheProgramsOwnFailure() {
        val r = runEffects {
            inMemory(mapOf("a" to 1)) {
                either<String, _> {
                    val noRemoves = guard { op -> if (op.name == "remove") raise("no removes: $op") }
                    context(contextOf<Store<String, Int>>().intercepted(noRemoves)) { move("a", "b") }
                }
            }
        }
        assertEquals(mapOf("a" to 1) to Either.Left("no removes: Store.remove(a)"), r)
    }

    @Test
    fun interceptorsComposeOuterFirst() {
        val order = mutableListOf<String>()
        fun named(name: String) = object : Interceptor {
            override suspend fun <X> around(op: Operation, proceed: suspend () -> X): X {
                order += "$name before"
                return proceed().also { order += "$name after" }
            }
        }
        val both = named("outer") + named("inner")
        runEffects { state(0) { context(contextOf<State<Int>>().intercepted(both)) { get<Int>() } } }
        assertEquals(listOf("outer before", "inner before", "inner after", "outer after"), order)
    }

    @Test
    fun anOperationWhoseHandlerDropsTheRestIsSeenAbandoned() {
        val seen = mutableListOf<String>()
        val r = runEffects {
            either<String, Int> {
                context(contextOf<Fail<String>>().intercepted(observer { op, outcome ->
                    seen += "$op abandoned: ${outcome.exceptionOrNull() is Abandoned}"
                })) { raise("no") }
            }
        }
        assertEquals(Either.Left("no"), r)
        assertEquals(listOf("Fail.raise(no) abandoned: true"), seen)
    }

    @Test
    fun aSensitiveEffectOnlyUnderAnAudit() {
        val trail = mutableListOf<String>()
        val limit = guard { op -> if ((op.args[1] as? Int ?: 0) > 1000) throw Refused(op, "over the limit") }
        val auditor = observer { op, outcome ->
            trail += "$op: ${outcome.getOrNull() ?: outcome.exceptionOrNull()?.message}"
        }
        val e = assertFailsWith<Refused> {
            runEffects { payments { vault -> audit(vault, auditor + limit) { salaries() } } }
        }
        assertEquals("over the limit: Payments.pay(bob, 5000)", e.message)
        assertEquals(listOf("Payments.pay(ann, 100): receipt 1: 100 to ann",
            "Payments.pay(bob, 5000): over the limit: Payments.pay(bob, 5000)"), trail)
    }
}
