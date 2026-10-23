package okay.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// THE LITERATURE'S CALCULI, AS OKAY-KOTLIN WRITES THEM: Forster, Kammar, Lindley, Pretnar, "On the Expressive Power of
// User-Defined Effects: Effect Handlers, Monadic Reflection, Delimited Control", ICFP 2017. Its examples, its
// reduction rules, its translations — each a test.

/** the boolean state of the paper's running example: `get : 1 → bit`, `put : bit → 1` */
interface Bits {
    suspend fun get(): Boolean
    suspend fun put(b: Boolean)
}

/** toggle = {x ← get(); y ← not! x; put y; x} — Fig. 4(a), the same in every calculus */
context(bits: Bits)
suspend fun toggle(): Boolean {
    val x = bits.get()
    bits.put(!x)
    return x
}

/** a run of state: the initial state in, the value and the final state out */
typealias Run<A> = suspend (Boolean) -> Pair<A, Boolean>

/**
 * EFF, Fig. 10: H_ST = {return x ↦ λs.return x} ⊎ {get _ k ↦ λs.k! s s} ⊎ {put s' k ↦ λ_.k! () s'}, runState c =
 * handle c! with H_ST. The handler's value is a function of the state; each clause returns a function that resumes k
 * when it is applied — after the clause, after `handle` has returned: k is a value. (The final state is kept by the
 * return clause too, to see it.)
 */
suspend fun <A> runStateEff(c: suspend context(Bits) () -> A): Run<A> = handle { prompt ->
    val bits = object : Bits {
        override suspend fun get(): Boolean = prompt.shift { k -> { s -> k(s)(s) } }
        override suspend fun put(b: Boolean) = prompt.shift { k -> { _ -> k(Unit)(b) } }
    }
    val x = context(bits) { c() }
    val ret: Run<A> = { s -> x to s }
    ret
}

/**
 * DEL, Fig. 18: get = S0 k.λs.k! s s, put s' = S0 k.λ_.k! () s', runState c = ⟨c! | x.λs.x⟩ — shift0 to the nearest
 * reset. Here the reset is `handle` and its prompt names it: get and put are shifts to it, nothing else.
 */
suspend fun <A> runStateDel(c: suspend (get: suspend () -> Boolean, put: suspend (Boolean) -> Unit) -> A): Run<A> =
    handle { p ->
        val x = c({ p.shift { k -> { s -> k(s)(s) } } }, { s1 -> p.shift { k -> { _ -> k(Unit)(s1) } } })
        val ret: Run<A> = { s -> x to s }
        ret
    }

/** MON, Fig. 14: the state monad, `return x = λs.(x, s)`, `f ≫= k = λs.(x, s') ← f s; k! x s'` */
typealias StateM<X> = suspend (Boolean) -> Pair<X, Boolean>

/** reflection of the state monad: μ(N) makes a computation of the monad a value of the program */
interface ReflectState {
    suspend fun <X> reflect(m: StateM<X>): X
}

/**
 * MON → EFF, §6.5: reflection an operation, reification its handler, the monad's unit the return clause and its bind
 * the operation clause: [M]^T := handle M with {return x ↦ N_u} ⊎ {reflect y f ↦ N_b}. The paper's translation is
 * not typeable — a single operation is monomorphic — and it conjectures that polymorphic operations make it so: an
 * interface's generic function is one, and this is typed.
 */
suspend fun <A> reifyState(c: suspend context(ReflectState) () -> A): StateM<A> = handle { p ->
    val r = object : ReflectState {
        override suspend fun <X> reflect(m: StateM<X>): X = p.shift { k ->
            { s ->
                val (x, s1) = m(s)
                k(x)(s1)
            }
        }
    }
    val x = context(r) { c() }
    val unit: StateM<A> = { s -> x to s }
    unit
}

class SpecTest {
    @Test
    fun effExample32() {
        // runState! toggle True ⇝* True, the state then False
        assertEquals(true to false, runEffects { runStateEff { toggle() }(true) })
        assertEquals(listOf(true, false, true) to false,
            runEffects { runStateEff { List(3) { toggle() } }(true) })
    }

    @Test
    fun delExample52() {
        val r = runEffects {
            runStateDel { get, put ->
                val x = get()
                put(!x)
                x
            }(true)
        }
        assertEquals(true to false, r)
    }

    @Test
    fun monExample42() {
        // get = μ(λs.(s, s)), put s' = μ(λ_.((), s')): toggle by reflection, run by reification
        val toggled = runEffects {
            reifyState {
                val r = contextOf<ReflectState>()
                val x = r.reflect { s -> s to s }
                r.reflect { _ -> Unit to !x }
                x
            }(true)
        }
        assertEquals(true to false, toggled)
    }

    @Test
    fun ruleRet() {
        // (ret) handle (return V) with H ⇝ H^ret[V/x]: no operation, the return clause alone
        assertEquals(Either.Right(5), runEffects { either<String, _> { 5 } })
    }

    @Test
    fun ruleOpIsDeep() {
        // (op) handle H[op V] with H ⇝ H^op[V/p, {λx.handle H[return x] with H}/k]: the continuation is handled by
        // the same handler again — every later operation goes to it
        var clauses = 0
        val r = runEffects {
            handle<Int> { p ->
                context(Ask { p.shift { k -> clauses++; k(clauses) } }) { askFor() + askFor() + askFor() }
            }
        }
        assertEquals(1 + 2 + 3 to 3, r to clauses)
    }

    @Test
    fun theClauseRunsOutsideItsHandler() {
        // shift0: the clause runs with the reset removed — an operation it performs goes to the handler further out
        val r = runEffects {
            answering(10) {
                val outer = contextOf<Ask>()
                handle<Int> { p -> context(Ask { p.shift { k -> k(outer.ask() + 1) } }) { askFor() } }
            }
        }
        assertEquals(11, r)
    }

    @Test
    fun aContinuationIsAValueResumedAfterHandleReturned() {
        // the rest kept by a handler that returns at once, resumed by its caller
        lateinit var rest: Resume<Int, String>
        val first = runEffects {
            handle<String> { p ->
                context(Ask { p.shift { k -> rest = k; "suspended" } }) { "got ${askFor()}" }
            }
        }
        assertEquals("suspended", first)
        assertEquals("got 42", runEffects { rest(42) })
        assertFailsWith<IllegalStateException> { runEffects { rest(43) } }
    }

    @Test
    fun aDroppedRestUnwinds() {
        val log = mutableListOf<String>()
        val r = runEffects {
            handle<String> { p ->
                context(Ask { p.shift { k -> k.drop(); "dropped" } }) {
                    try {
                        "got ${askFor()}"
                    } finally {
                        log += "finally"
                    }
                }
            }
        }
        assertEquals("dropped" to listOf("finally"), r to log)
    }

    @Test
    fun handlersAreChosenLexically() {
        // the operation goes to the handler whose capability it was given — not to the nearest one at run time, as in
        // EFF. A function made under `answering(1)` and run under `answering(2)` asks the first
        val r = runEffects {
            answering(1) {
                val askOne: suspend () -> Int = { askFor() }
                answering(2) { askOne() * 10 + askFor() }
            }
        }
        assertEquals(12, r)
    }

    @Test
    fun aCapabilityOutlivingItsHandlerIsAnError() {
        val e = assertFailsWith<IllegalStateException> {
            runEffects {
                val escaped = answering(1) { contextOf<Ask>() }
                escaped.ask()
            }
        }
        assertEquals("shift outside its handler's body", e.message)
    }
}
