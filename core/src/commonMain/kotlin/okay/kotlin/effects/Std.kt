package okay.kotlin.effects

import okay.kotlin.Either

// THE STANDARD EFFECTS AS DATA, their words and their handlers written once, for every engine. An operation whose
// answer's type its class fixes carries `answer`, the witness that turns it into the operation's `X`: Kotlin does not
// refine `X` when a `when` finds the case, so the operation says how — no cast.

/** the branch that cannot be: an effect's operations are open (`Op`), so a `when` over them has an `else` */
fun foreign(op: Any?): Nothing = error("not an operation of this effect: $op")

/** a state of type `S` */
class State<S> private constructor() {
    class Get<S, X>(val answer: (S) -> X) : Op<State<S>, X>
    class Put<S, X>(val value: S, val answer: (Unit) -> X) : Op<State<S>, X>
}

context(fx: Effects<F>, at: In<State<S>, R>) fun <F, S, R> get(): Prog<F, R, S> = fx.perform(at, State.Get { it })
context(fx: Effects<F>, at: In<State<S>, R>) fun <F, S, R> put(s: S): Prog<F, R, Unit> =
    fx.perform(at, State.Put(s) { it })
context(fx: Effects<F>, at: In<State<S>, R>) fun <F, S, R> modify(f: (S) -> S): Prog<F, R, Unit> =
    fx.bind(get()) { put(f(it)) }

/** the body from `init`: the state it ends with, and its value */
context(fx: Effects<F>)
fun <F, S, Rest, A> state(init: S, body: Body<F, State<S>, Rest, A>): Prog<F, Rest, Pair<S, A>> =
    handled(object : Stateful<F, State<S>, S, Rest, A, Pair<S, A>>() {
        override fun init(): S = init
        override fun <X> answer(op: Op<State<S>, X>, state: Cell<S>): X = when (op) {
            is State.Get -> op.answer(state.value)
            is State.Put -> {
                state.value = op.value
                op.answer(Unit)
            }
            else -> foreign(op)
        }
        override fun value(a: A, state: S): Prog<F, Rest, Pair<S, A>> = fx.pure(state to a)
    }, body)

/** an environment of type `E` */
class Reader<E> private constructor() {
    class Ask<E, X>(val answer: (E) -> X) : Op<Reader<E>, X>
}

context(fx: Effects<F>, at: In<Reader<E>, R>) fun <F, E, R> ask(): Prog<F, R, E> = fx.perform(at, Reader.Ask { it })

/** the body with `env` */
context(fx: Effects<F>)
fun <F, E, Rest, A> reader(env: E, body: Body<F, Reader<E>, Rest, A>): Prog<F, Rest, A> =
    handled(object : Tail<F, Reader<E>, Rest, A, A>() {
        override fun <X> answer(op: Op<Reader<E>, X>): X = when (op) {
            is Reader.Ask -> op.answer(env)
            else -> foreign(op)
        }
        override fun value(a: A): Prog<F, Rest, A> = fx.pure(a)
    }, body)

/** outputs of type `W` */
class Writer<W> private constructor() {
    class Tell<W, X>(val w: W, val answer: (Unit) -> X) : Op<Writer<W>, X>
}

context(fx: Effects<F>, at: In<Writer<W>, R>) fun <F, W, R> tell(w: W): Prog<F, R, Unit> =
    fx.perform(at, Writer.Tell(w) { it })

/** what the body wrote, in order, and its value */
context(fx: Effects<F>)
fun <F, W, Rest, A> writer(body: Body<F, Writer<W>, Rest, A>): Prog<F, Rest, Pair<List<W>, A>> =
    handled(object : Stateful<F, Writer<W>, MutableList<W>, Rest, A, Pair<List<W>, A>>() {
        override fun init(): MutableList<W> = ArrayList()
        override fun <X> answer(op: Op<Writer<W>, X>, state: Cell<MutableList<W>>): X = when (op) {
            is Writer.Tell -> {
                state.value.add(op.w)
                op.answer(Unit)
            }
            else -> foreign(op)
        }
        override fun value(a: A, state: MutableList<W>): Prog<F, Rest, Pair<List<W>, A>> = fx.pure(state.toList() to a)
    }, body)

/** failure with an error of type `E`: the rest dropped */
class Fail<E> private constructor() {
    class Raise<E, X>(val error: E) : Op<Fail<E>, X>
}

context(fx: Effects<F>, at: In<Fail<E>, R>) fun <F, E, R> raise(e: E): Prog<F, R, Nothing> =
    fx.perform(at, Fail.Raise(e))

/** the body's value, or the error it raised */
context(fx: Effects<F>)
fun <F, E, Rest, A> either(body: Body<F, Fail<E>, Rest, A>): Prog<F, Rest, Either<E, A>> =
    handled(object : Abort<F, Fail<E>, Rest, A, Either<E, A>>() {
        override fun <X> abort(op: Op<Fail<E>, X>): Prog<F, Rest, Either<E, A>> = when (op) {
            is Fail.Raise -> fx.pure(Either.Left(op.error))
            else -> foreign(op)
        }
        override fun value(a: A): Prog<F, Rest, Either<E, A>> = fx.pure(Either.Right(a))
    }, body)

/** a choice among values: MULTI-SHOT — its handlers resume the rest once for each */
class Choose private constructor() {
    /** its answer's type is its values': no witness needed */
    class Among<X>(val xs: List<X>) : Op<Choose, X>
}

context(fx: Effects<F>, at: In<Choose, R>) fun <F, X, R> among(xs: List<X>): Prog<F, R, X> =
    fx.perform(at, Choose.Among(xs))
context(fx: Effects<F>, at: In<Choose, R>) fun <F, R> flip(): Prog<F, R, Boolean> = among(listOf(true, false))
context(fx: Effects<F>, at: In<Choose, R>) fun <F, R> none(): Prog<F, R, Nothing> = among(emptyList())

/** every value of every way the choices can go, in order */
context(fx: Effects<F>)
fun <F, Rest, A> all(body: Body<F, Choose, Rest, A>): Prog<F, Rest, List<A>> =
    handled(object : General<F, Choose, Rest, A, List<A>>() {
        override fun <X> op(op: Op<Choose, X>, k: (X) -> Prog<F, Rest, List<A>>): Prog<F, Rest, List<A>> =
            when (op) {
                is Choose.Among -> op.xs.fold(fx.pure(emptyList())) { found, x ->
                    fx.bind(found) { before -> fx.bind(k(x)) { fx.pure(before + it) } }
                }
                else -> foreign(op)
            }
        override fun value(a: A): Prog<F, Rest, List<A>> = fx.pure(listOf(a))
    }, body)

/** the first way the choices go to a value, the ways after it not tried; none, `null` */
context(fx: Effects<F>)
fun <F, Rest, A : Any> first(body: Body<F, Choose, Rest, A>): Prog<F, Rest, A?> =
    handled(object : General<F, Choose, Rest, A, A?>() {
        override fun <X> op(op: Op<Choose, X>, k: (X) -> Prog<F, Rest, A?>): Prog<F, Rest, A?> = when (op) {
            is Choose.Among -> tryEach(op.xs, k)
            else -> foreign(op)
        }
        override fun value(a: A): Prog<F, Rest, A?> = fx.pure(a)
    }, body)

context(fx: Effects<F>)
private fun <F, Rest, X, A : Any> tryEach(xs: List<X>, k: (X) -> Prog<F, Rest, A?>): Prog<F, Rest, A?> =
    if (xs.isEmpty()) fx.pure(null)
    else fx.bind(k(xs.first())) { found -> found?.let { fx.pure(it) } ?: fx.defer { tryEach(xs.drop(1), k) } }

/** values of type `O`, given out */
class Emit<O> private constructor() {
    class Out<O, X>(val o: O, val answer: (Unit) -> X) : Op<Emit<O>, X>
}

context(fx: Effects<F>, at: In<Emit<O>, R>) fun <F, O, R> emit(o: O): Prog<F, R, Unit> =
    fx.perform(at, Emit.Out(o) { it })

/** the first `n` values, and the body's value if it ended by then */
context(fx: Effects<F>)
fun <F, O, Rest, A> take(n: Int, body: Body<F, Emit<O>, Rest, A>): Prog<F, Rest, Pair<List<O>, A?>> =
    fx.bind(handled(object : General<F, Emit<O>, Rest, A, (List<O>) -> Prog<F, Rest, Pair<List<O>, A?>>>() {
        override fun <X> op(op: Op<Emit<O>, X>, k: (X) -> Prog<F, Rest, (List<O>) -> Prog<F, Rest, Pair<List<O>, A?>>>):
            Prog<F, Rest, (List<O>) -> Prog<F, Rest, Pair<List<O>, A?>>> = when (op) {
            is Emit.Out -> fx.pure { taken ->
                if (taken.size == n) fx.pure(taken to null)
                else fx.bind(k(op.answer(Unit))) { go -> go(taken + op.o) }
            }
            else -> foreign(op)
        }
        override fun value(a: A): Prog<F, Rest, (List<O>) -> Prog<F, Rest, Pair<List<O>, A?>>> =
            fx.pure { taken -> fx.pure(taken to a) }
    }, body)) { go -> go(emptyList()) }
