package okay.kotlin

import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine

/**
 * WHERE A HANDLER DELIMITS ITS BODY: made by [handle], given to the handler, which closes over it in the effect's
 * implementation. [shift] takes the rest of the body, up to here, as a [Resume].
 */
class Prompt<R> internal constructor() {
    internal lateinit var machine: Machine<R>
}

/** the rest of a handled body from an operation on: resumed once — a Kotlin continuation is one-shot — it gives
 * what the handler gives for the rest */
interface Resume<in X, out R> {
    suspend operator fun invoke(x: X): R
}

/** thrown at the operation whose handler dropped the rest of its body: the body unwinds, its `finally` blocks run and
 * its `use` closes what it opened; caught by the handler that dropped it. Control, not an error: on the JVM it has no
 * stack trace, which would cost more than the rest of a failure together */
open class Abandoned internal constructor() : CancellationException("the rest of the body dropped by its handler")

/** an [Abandoned] made as cheaply as the platform can */
internal expect fun abandoned(): Abandoned

/**
 * THE BODY, DELIMITED: `body` given its prompt, run; what it gives, or what a [shift] to the prompt makes of the rest.
 * Handlers are made of this: an effect's implementation closing over the prompt, given to the body as its context.
 */
suspend fun <R> handle(body: suspend (Prompt<R>) -> R): R {
    val prompt = Prompt<R>()
    val machine = Machine(prompt, coroutineContext)
    prompt.machine = machine
    val run = body.createCoroutineUnintercepted(prompt, Continuation(machine.outer + machine.body) { machine.done(it) })
    return machine.drive { run.resume(Unit) }
}

/**
 * THE REST OF THE BODY, TAKEN: up to the prompt, as `k`; what `f` gives is what the body's handler gives. `k(x)` goes
 * on with `x` as this call's value; not resumed, the rest is dropped — [Abandoned] unwinds it. `f` runs outside the
 * body, where the handler is: an operation of its own effect there goes to the handler further out.
 */
suspend fun <X, R> Prompt<R>.shift(f: suspend (k: Resume<X, R>) -> R): X =
    suspendCoroutineUninterceptedOrReturn { k ->
        val inner = k.context[Running] ?: error("shift outside its handler's body")
        if (inner === machine.body) machine.operation(Op(f, k)) else inner.relay(Relay({ shift(f) }, k))
        COROUTINE_SUSPENDED
    }

/** the body ended here: `value` is what its handler gives, the rest dropped */
suspend fun <R> Prompt<R>.abort(value: R): Nothing = shift { _ -> value }

/**
 * A PROGRAM OF NO EFFECT LEFT, RUN: every effect handled inside `body`. It may not wait for anything outside — a
 * timer, another thread — for that, run it in a coroutine: the handlers are suspend functions, `runBlocking { ... }`.
 */
fun <A> runEffects(body: suspend () -> A): A {
    var out: Result<A>? = null
    body.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
    return (out ?: error("the program waits for something outside its handlers: run it in a coroutine")).getOrThrow()
}

// ---- THE MACHINE: one per handled body, a loop driving the body and its handler's clauses as coroutines

/** an operation for this prompt: the clause `f`, the body's continuation `k` */
internal class Op<X, R>(val f: suspend (Resume<X, R>) -> R, val k: Continuation<X>)

/** a suspension aimed further out than the loop it reached: done again by that loop, in its own frame, where it
 * reaches the next loop out; its answer, or its failure, brought back */
internal class Relay<X>(private val again: suspend () -> X, private val k: Continuation<X>) {
    suspend fun relay(): () -> Unit {
        val x = runCatching { again() }
        return { k.resumeWith(x) }
    }
}

/** the coroutines a machine drives, known by their context: its body's, or its clauses' */
internal class Running(private val machine: Machine<*>) : AbstractCoroutineContextElement(Running) {
    companion object Key : CoroutineContext.Key<Running>

    var relay: Relay<*>? = null

    fun relay(r: Relay<*>) {
        relay = r
        machine.signal()
    }
}

/** what wakes a loop waiting for its body: a signal before it waits, or the loop itself, waiting */
internal sealed class Waker {
    object Signal : Waker()
    class Parked(val loop: Continuation<Unit>) : Waker()
}

/**
 * The body and every clause run as coroutines resumed by [drive], so a clause's `k(x)` does not nest: the clause
 * waits as data in [frames] for what the rest gives — a deep handler in constant stack. Whatever a coroutine of this
 * machine suspends for comes back here as one of the events below; a suspension for something outside (a timer,
 * another thread) leaves none, and the loop waits for the signal of the next.
 */
internal class Machine<R>(private val prompt: Prompt<R>, val outer: CoroutineContext) {
    val body = Running(this)
    private val clauses = Running(this)
    private var result: Result<R>? = null
    private var op: Op<*, R>? = null
    private var next: (() -> Unit)? = null
    private val frames = ArrayList<Continuation<R>>()
    private val wake = AtomicReference<Waker?>(null)

    fun done(r: Result<R>) {
        result = r
        signal()
    }

    fun operation(o: Op<*, R>) {
        op = o
        signal()
    }

    suspend fun drive(first: () -> Unit): R {
        var action: (() -> Unit)? = first
        while (true) {
            wake.store(null)
            action?.invoke()
            action = null
            val r = result
            val o = op
            val n = next
            when {
                r != null -> {
                    result = null
                    val waiting = frames.removeLastOrNull() ?: return r.getOrThrow()
                    action = { waiting.resumeWith(r) }
                }
                o != null -> {
                    op = null
                    action = { start(o) }
                }
                n != null -> {
                    next = null
                    action = n
                }
                else -> {
                    val relay = body.relay ?: clauses.relay
                    if (relay != null) {
                        body.relay = null
                        clauses.relay = null
                        action = relay.relay()
                    } else park()
                }
            }
        }
    }

    private suspend fun park() = suspendCoroutineUninterceptedOrReturn { loop ->
        if (wake.compareAndSet(null, Waker.Parked(loop.intercepted()))) COROUTINE_SUSPENDED else Unit
    }

    fun signal() {
        while (true) {
            when (val w = wake.load()) {
                null -> if (wake.compareAndSet(null, Waker.Signal)) return
                Waker.Signal -> return
                is Waker.Parked -> if (wake.compareAndSet(w, null)) return w.loop.resume(Unit)
            }
        }
    }

    private fun <X> start(o: Op<X, R>) {
        val k = Rest(o.k)
        o.f.createCoroutineUnintercepted(k, Continuation(outer + clauses) { given ->
            if (k.used) done(given)
            else {
                // the rest dropped: unwound, its `finally`s run, and then the clause's value
                k.used = true
                frames.add(Continuation(EmptyCoroutineContext) { done(given) })
                next = { o.k.resumeWithException(abandoned()) }
                signal()
            }
        }).resume(Unit)
    }

    /** `k`: the clause waits in [frames], the body goes on with `x` */
    private inner class Rest<X>(private val k: Continuation<X>) : Resume<X, R> {
        var used = false

        override suspend fun invoke(x: X): R = suspendCoroutineUninterceptedOrReturn { clause ->
            val inner = clause.context[Running] ?: error("a continuation resumed outside its handler")
            if (inner === clauses) {
                check(!used) { "a continuation resumed twice, or after it was dropped: it is one-shot" }
                used = true
                frames.add(clause)
                next = { k.resume(x) }
                signal()
            } else inner.relay(Relay({ invoke(x) }, clause))
            COROUTINE_SUSPENDED
        }
    }
}
