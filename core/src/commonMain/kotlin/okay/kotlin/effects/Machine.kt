package okay.kotlin.effects

/**
 * THE REFERENCE ENGINE: a CEK machine with generalised continuations (Hillerström, Lindley, Atkey), okay-scala's.
 * C, the program in control, is a [Node]; E is in it, closures; K is two-level: [Frames], the binds of the body
 * running, and [Levels], the handlers around it, each with the frames outside it. An operation goes out level by
 * level, each crossed one kept in [Crossed]; at its handler, `k` is data — the frames and the levels crossed — put
 * back on whatever is outside when it is resumed: deep, and multi-shot, as many times as the clause likes, on every
 * platform. One step at a time, in a loop: no stack. No cast: each node, frame and level knows its own types, and the
 * machine asks it to take its step — the Kotlin spelling of okay-scala's matches on GADTs.
 */
object Machine : Effects<Machine> {
    override fun <R, A> pure(a: A): Prog<Machine, R, A> = Node.Value(a)
    override fun <R, A, B> bind(p: Prog<Machine, R, A>, f: (A) -> Prog<Machine, R, B>): Prog<Machine, R, B> =
        Node.Bind(p.node(), f)
    override fun <R, A> defer(make: () -> Prog<Machine, R, A>): Prog<Machine, R, A> = Node.Defer(make)
    override fun <E, R, X> perform(at: In<E, R>, op: Op<E, X>): Prog<Machine, R, X> = Node.Perform(at.inject(op))
    override fun <E, R, Rest, A, B> handle(body: Prog<Machine, R, A>, at: Place<E, R, Rest>,
                                           h: Handler<Machine, E, Rest, A, B>): Prog<Machine, Rest, B> =
        Node.Handle(body.node(), at, h)

    override fun <A> run(p: Prog<Machine, Nil, A>): A {
        var turn: Turn<A> = Running(p.node(), Frames.End(), Levels.Top())
        while (turn !is Done) turn = turn.next()
        return turn.value
    }
}

/** a program of this engine as its node: one of another engine's is refused */
private fun <R, A> Prog<Machine, R, A>.node(): Node<R, A> = when (this) {
    is Node -> this
    else -> error("a program of another engine, run on the machine: build it with the machine's words")
}

/** the machine between two steps: a turn of it */
private sealed class Turn<Z> {
    abstract fun next(): Turn<Z>
}

private class Done<Z>(val value: Z) : Turn<Z>() {
    override fun next(): Turn<Z> = this
}

private class Running<R, X, V, Z>(val c: Node<R, X>, val σ: Frames<R, X, V>, val κ: Levels<R, V, Z>) : Turn<Z>() {
    override fun next(): Turn<Z> = c.step(σ, κ)
}

/** C: the program in control */
private sealed class Node<R, out A> : Prog<Machine, R, A>() {
    abstract fun <V, Z> step(σ: Frames<R, A, V>, κ: Levels<R, V, Z>): Turn<Z>

    class Value<R, out A>(val a: A) : Node<R, A>() {
        override fun <V, Z> step(σ: Frames<R, A, V>, κ: Levels<R, V, Z>): Turn<Z> = σ.ret(a, κ)
    }

    class Bind<R, X, out A>(val p: Node<R, X>, val f: (X) -> Prog<Machine, R, A>) : Node<R, A>() {
        override fun <V, Z> step(σ: Frames<R, A, V>, κ: Levels<R, V, Z>): Turn<Z> =
            Running(p, Frames.Bind(f, σ), κ)
    }

    class Defer<R, out A>(val make: () -> Prog<Machine, R, A>) : Node<R, A>() {
        override fun <V, Z> step(σ: Frames<R, A, V>, κ: Levels<R, V, Z>): Turn<Z> = Running(make().node(), σ, κ)
    }

    /** an operation: out to its handler, level by level, the levels crossed kept */
    class Perform<R, A>(val at: At<R, A>) : Node<R, A>() {
        override fun <V, Z> step(σ: Frames<R, A, V>, κ: Levels<R, V, Z>): Turn<Z> =
            κ.perform(at, σ, Crossed.None())
    }

    /** a handled body: a level of its own, the frames outside it kept with it */
    class Handle<E, R0, A0, R, A>(val body: Node<R0, A0>, val at: Place<E, R0, R>,
                                  val h: Handler<Machine, E, R, A0, A>) : Node<R, A>() {
        override fun <V, Z> step(σ: Frames<R, A, V>, κ: Levels<R, V, Z>): Turn<Z> =
            Running(body, Frames.End(), Levels.Handled(at, h.live(), σ, κ))
    }

    /** `k` resumed with `x`: the frames inside and the levels crossed put back, its handler again around them, on
     * the frames and the levels outside it now */
    class Resume<X, R0, V0, E, R, Rest, A, B>(
        val x: X, val inner: Frames<R0, X, V0>, val crossed: Crossed<R0, V0, R, A>,
        val at: Place<E, R, Rest>, val h: Live<Machine, E, Rest, A, B>,
    ) : Node<Rest, B>() {
        override fun <V, Z> step(σ: Frames<Rest, B, V>, κ: Levels<Rest, V, Z>): Turn<Z> =
            Running(Value(x), inner, crossed.put(Levels.Handled(at, h, σ, κ)))
    }
}

/** K, inside a level: the binds of the body, from an `X` to the body's value `V` */
private sealed class Frames<R, in X, V> {
    abstract fun <Z> ret(x: X, κ: Levels<R, V, Z>): Turn<Z>

    class End<R, V> : Frames<R, V, V>() {
        override fun <Z> ret(x: V, κ: Levels<R, V, Z>): Turn<Z> = κ.ret(x)
    }

    class Bind<R, X, Y, V>(val f: (X) -> Prog<Machine, R, Y>, val rest: Frames<R, Y, V>) : Frames<R, X, V>() {
        override fun <Z> ret(x: X, κ: Levels<R, V, Z>): Turn<Z> = Running(f(x).node(), rest, κ)
    }
}

/** K, the levels: the handlers around a body of the row `R` whose value is a `V`, up to the run's `Z` */
private sealed class Levels<R, V, Z> {
    abstract fun ret(v: V): Turn<Z>
    abstract fun <X, R0, V0> perform(at: At<R, X>, inner: Frames<R0, X, V0>, crossed: Crossed<R0, V0, R, V>): Turn<Z>

    class Top<Z> : Levels<Nil, Z, Z>() {
        override fun ret(v: Z): Turn<Z> = Done(v)
        override fun <X, R0, V0> perform(at: At<Nil, X>, inner: Frames<R0, X, V0>,
                                         crossed: Crossed<R0, V0, Nil, Z>): Turn<Z> =
            error("an operation of no effect: the row is empty")
    }

    class Handled<E, R, Rest, A, B, W, Z>(
        val at: Place<E, R, Rest>, val h: Live<Machine, E, Rest, A, B>,
        val outside: Frames<Rest, B, W>, val up: Levels<Rest, W, Z>,
    ) : Levels<R, A, Z>() {
        override fun ret(v: A): Turn<Z> = Running(h.value(v).node(), outside, up)

        override fun <X, R0, V0> perform(at: At<R, X>, inner: Frames<R0, X, V0>,
                                         crossed: Crossed<R0, V0, R, A>): Turn<Z> =
            when (val taken = this.at.take(at)) {
                is Taken.Mine -> when (val h = h) {
                    is Tail -> Running(Node.Value(h.answer(taken.op)), inner, crossed.put(this))
                    is Abort -> Running(h.abort(taken.op).node(), outside, up)
                    is General -> {
                        val k = { x: X -> Node.Resume(x, inner, crossed, this.at, h) }
                        Running(h.op(taken.op, k).node(), outside, up)
                    }
                }
                is Taken.Theirs -> up.perform(taken.op, inner, Crossed.Level(crossed, this.at, h, outside))
            }
    }
}

/** the levels an operation crossed on its way out, from the body that performed it, of `R0` and `V0`, to the level
 * of `R` and `V` it has got to: data, put back by `put` wherever `k` is resumed */
private sealed class Crossed<R0, V0, R, V> {
    abstract fun <Z> put(κ: Levels<R, V, Z>): Levels<R0, V0, Z>

    class None<R, V> : Crossed<R, V, R, V>() {
        override fun <Z> put(κ: Levels<R, V, Z>): Levels<R, V, Z> = κ
    }

    class Level<R0, V0, E, R, Rest, A, B, W>(
        val inner: Crossed<R0, V0, R, A>, val at: Place<E, R, Rest>, val h: Live<Machine, E, Rest, A, B>,
        val outside: Frames<Rest, B, W>,
    ) : Crossed<R0, V0, Rest, W>() {
        override fun <Z> put(κ: Levels<Rest, W, Z>): Levels<R0, V0, Z> = inner.put(Levels.Handled(at, h, outside, κ))
    }
}
