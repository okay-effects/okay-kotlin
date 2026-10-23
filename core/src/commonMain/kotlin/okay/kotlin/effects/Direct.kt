package okay.kotlin.effects

import okay.kotlin.Resume
import okay.kotlin.handle
import okay.kotlin.runEffects
import okay.kotlin.shift

/**
 * THE ENGINE OF CONTINUATIONS: the same programs run on okay-kotlin's delimited continuations. The binds of a body run
 * in a loop, its handlers are objects found by their places in a row of them ([Handlers]): one answering in place
 * (`Tail`, `Stateful`) is a call, no continuation taken; one that takes the rest (`Abort`, `General`) is a prompt, and
 * `k` the rest of the coroutine — resumed more than once by copying it, where a continuation can be copied (the JVM,
 * JavaScript; not across another handler that takes the rest: see `Resume.copy`).
 */
object Direct : Effects<Direct> {
    override fun <R, A> pure(a: A): Prog<Direct, R, A> = D.Value(a)
    override fun <R, A, B> bind(p: Prog<Direct, R, A>, f: (A) -> Prog<Direct, R, B>): Prog<Direct, R, B> =
        D.Bind(p.d(), f)
    override fun <R, A> defer(make: () -> Prog<Direct, R, A>): Prog<Direct, R, A> = D.Defer(make)
    override fun <E, R, X> perform(at: In<E, R>, op: Op<E, X>): Prog<Direct, R, X> = D.Perform(at, op)
    override fun <E, R, Rest, A, B> handle(body: Prog<Direct, R, A>, at: Place<E, R, Rest>,
                                           h: Handler<Direct, E, Rest, A, B>): Prog<Direct, Rest, B> =
        D.Handle(body.d(), at, h)
    override fun <R, S, A> embed(p: Prog<Direct, R, A>, sub: Sub<R, S>): Prog<Direct, S, A> = D.Remap(p.d(), sub)

    override fun <A> run(p: Prog<Direct, Nil, A>): A = runEffects { eval(p.d(), Handlers.None) }
}

private fun <R, A> Prog<Direct, R, A>.d(): D<R, A> = when (this) {
    is D -> this
    else -> error("a program of another engine, run on Direct: build it with Direct's words")
}

/** a body run with the handlers of its row: its binds in a loop */
private suspend fun <R, A> eval(p: D<R, A>, handlers: Handlers<R>): A {
    var state: DState<R, A> = DRunning(p, DFrames.End())
    while (state !is DDone) state = state.next(handlers)
    return state.value
}

private sealed class DState<R, Z> {
    abstract suspend fun next(handlers: Handlers<R>): DState<R, Z>
}

private class DDone<R, Z>(val value: Z) : DState<R, Z>() {
    override suspend fun next(handlers: Handlers<R>): DState<R, Z> = this
}

private class DRunning<R, X, Z>(val c: D<R, X>, val σ: DFrames<R, X, Z>) : DState<R, Z>() {
    override suspend fun next(handlers: Handlers<R>): DState<R, Z> = c.step(σ, handlers)
}

private sealed class DFrames<R, in X, Z> {
    abstract fun ret(x: X): DState<R, Z>

    class End<R, Z> : DFrames<R, Z, Z>() {
        override fun ret(x: Z): DState<R, Z> = DDone(x)
    }

    class Bind<R, X, Y, Z>(val f: (X) -> Prog<Direct, R, Y>, val rest: DFrames<R, Y, Z>) : DFrames<R, X, Z>() {
        override fun ret(x: X): DState<R, Z> = DRunning(f(x).d(), rest)
    }
}

private sealed class D<R, out A> : Prog<Direct, R, A>() {
    abstract suspend fun <Z> step(σ: DFrames<R, A, Z>, handlers: Handlers<R>): DState<R, Z>

    class Value<R, out A>(val a: A) : D<R, A>() {
        override suspend fun <Z> step(σ: DFrames<R, A, Z>, handlers: Handlers<R>): DState<R, Z> = σ.ret(a)
    }

    class Bind<R, X, out A>(val p: D<R, X>, val f: (X) -> Prog<Direct, R, A>) : D<R, A>() {
        override suspend fun <Z> step(σ: DFrames<R, A, Z>, handlers: Handlers<R>): DState<R, Z> =
            DRunning(p, DFrames.Bind(f, σ))
    }

    class Defer<R, out A>(val make: () -> Prog<Direct, R, A>) : D<R, A>() {
        override suspend fun <Z> step(σ: DFrames<R, A, Z>, handlers: Handlers<R>): DState<R, Z> =
            DRunning(make().d(), σ)
    }

    /** the handler at the operation's place, asked: a call, or a shift to its prompt */
    class Perform<E, R, A>(val at: In<E, R>, val op: Op<E, A>) : D<R, A>() {
        override suspend fun <Z> step(σ: DFrames<R, A, Z>, handlers: Handlers<R>): DState<R, Z> =
            σ.ret(at.find(handlers).perform(op))
    }

    class Handle<E, R0, A0, R, A>(val body: D<R0, A0>, val at: Place<E, R0, R>,
                                  val h: Handler<Direct, E, R, A0, A>) : D<R, A>() {
        override suspend fun <Z> step(σ: DFrames<R, A, Z>, handlers: Handlers<R>): DState<R, Z> =
            σ.ret(handled(body, at, h.live(), handlers))
    }

    /** a program of `R0` where the row is `R`: run with the handlers of `R`, seen as `R0`'s */
    class Remap<R0, R, A>(val p: D<R0, A>, val sub: Sub<R0, R>) : D<R, A>() {
        override suspend fun <Z> step(σ: DFrames<R, A, Z>, handlers: Handlers<R>): DState<R, Z> =
            σ.ret(eval(p, sub.handlers(handlers)))
    }

    /** `k` resumed: a copy of the rest where one can be made, so `k` can be resumed again; the rest itself where not */
    class Resumed<R, X, A>(val k: Resume<X, A>, val x: X) : D<R, A>() {
        override suspend fun <Z> step(σ: DFrames<R, A, Z>, handlers: Handlers<R>): DState<R, Z> {
            val once = try {
                k.copy()
            } catch (_: UnsupportedOperationException) {
                k
            } catch (_: IllegalStateException) {
                k
            }
            return σ.ret(once(x))
        }
    }
}

/** the body under its handler, the handlers outside it given: a handler in place is a call, one that takes the rest
 * a prompt */
private suspend fun <E, R0, A0, R, A> handled(body: D<R0, A0>, at: Place<E, R0, R>, h: Live<Direct, E, R, A0, A>,
                                              outside: Handlers<R>): A = when (h) {
    is Tail -> {
        val handling = object : Handling<E> {
            override suspend fun <X> perform(op: Op<E, X>): X = h.answer(op)
        }
        eval(h.value(eval(body, at.put(outside, handling))).d(), outside)
    }
    is Abort -> handle { prompt ->
        val handling = object : Handling<E> {
            override suspend fun <X> perform(op: Op<E, X>): X = prompt.shift { k ->
                k.drop()
                eval(h.abort(op).d(), outside)
            }
        }
        eval(h.value(eval(body, at.put(outside, handling))).d(), outside)
    }
    is General -> handle { prompt ->
        val handling = object : Handling<E> {
            override suspend fun <X> perform(op: Op<E, X>): X = prompt.shift { k ->
                eval(h.op(op) { x -> D.Resumed(k, x) }.d(), outside)
            }
        }
        eval(h.value(eval(body, at.put(outside, handling))).d(), outside)
    }
}
