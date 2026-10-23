package okay.kotlin.effects

// THE INTERFACE: programs of effects as data, written once and run on any engine — okay-simple's `Effects`, whose
// programs run on its machine, its Free, okay-scala's machine. Kotlin has no type of a type constructor, so an
// engine is named by a type `F` and its programs are `Prog<F, R, A>`; a program is generic in `F` and takes its
// engine as a context parameter — Scala's import, as a parameter:
//
//     context(fx: Effects<F>, st: In<State<Int>, R>) fun <F, R> tick(): Prog<F, R, Unit> = ...
//     with(Machine) { run(state(0) { tick() }) }       with(Direct) { run(state(0) { tick() }) }

/** a program of the engine `F`, performing the effects of the row `R`, giving an `A` */
abstract class Prog<F, R, out A>

/** what a handler does, by its shape: an engine runs each shape its own way */
sealed interface Handler<F, E, Rest, A, B>

/** a handler as it runs: a stateful one has its state made, fresh, each time its body is entered */
sealed interface Live<F, E, Rest, A, B> : Handler<F, E, Rest, A, B> {
    /** the return clause */
    fun value(a: A): Prog<F, Rest, B>
}

/** the rest of the body as `k`, resumed any number of times: deep, multi-shot */
abstract class General<F, E, Rest, A, B> : Live<F, E, Rest, A, B> {
    abstract fun <X> op(op: Op<E, X>, k: (X) -> Prog<F, Rest, B>): Prog<F, Rest, B>
}

/** each operation answered in place, the body going on: no continuation taken */
abstract class Tail<F, E, Rest, A, B> : Live<F, E, Rest, A, B> {
    abstract fun <X> answer(op: Op<E, X>): X
}

/** each operation ends the body: the rest dropped, the clause's program in its place */
abstract class Abort<F, E, Rest, A, B> : Live<F, E, Rest, A, B> {
    abstract fun <X> abort(op: Op<E, X>): Prog<F, Rest, B>
}

/** answered in place, with a state of its own, made fresh each time the body is entered */
abstract class Stateful<F, E, S, Rest, A, B> : Handler<F, E, Rest, A, B> {
    abstract fun init(): S
    abstract fun <X> answer(op: Op<E, X>, state: Cell<S>): X
    abstract fun value(a: A, state: S): Prog<F, Rest, B>
}

/** a stateful handler's state */
class Cell<S>(var value: S)

/** the handler as it runs, its state, if it has one, made now */
fun <F, E, Rest, A, B> Handler<F, E, Rest, A, B>.live(): Live<F, E, Rest, A, B> = when (this) {
    is Live -> this
    is Stateful<F, E, *, Rest, A, B> -> started(this)
}

private fun <F, E, S, Rest, A, B> started(h: Stateful<F, E, S, Rest, A, B>): Live<F, E, Rest, A, B> {
    val cell = Cell(h.init())
    return object : Tail<F, E, Rest, A, B>() {
        override fun <X> answer(op: Op<E, X>): X = h.answer(op, cell)
        override fun value(a: A): Prog<F, Rest, B> = h.value(a, cell.value)
    }
}

/** AN ENGINE: the words every program is made of, and its run */
interface Effects<F> {
    fun <R, A> pure(a: A): Prog<F, R, A>
    fun <R, A, B> bind(p: Prog<F, R, A>, f: (A) -> Prog<F, R, B>): Prog<F, R, B>
    /** the program made when the run gets to it */
    fun <R, A> defer(make: () -> Prog<F, R, A>): Prog<F, R, A>
    fun <E, R, X> perform(at: In<E, R>, op: Op<E, X>): Prog<F, R, X>
    /** `E` handled from its place in the body's row, deeply */
    fun <E, R, Rest, A, B> handle(body: Prog<F, R, A>, at: Place<E, R, Rest>, h: Handler<F, E, Rest, A, B>):
        Prog<F, Rest, B>
    /** a program of `R` where the row is `S`, its operations renamed on their way out */
    fun <R, S, A> embed(p: Prog<F, R, A>, sub: Sub<R, S>): Prog<F, S, A>
    /** a program of no effect left, run */
    fun <A> run(p: Prog<F, Nil, A>): A
}

// ---- THE WORDS, for every engine: its own from the context

context(fx: Effects<F>) fun <F, R, A> pure(a: A): Prog<F, R, A> = fx.pure(a)
context(fx: Effects<F>) fun <F, R> unit(): Prog<F, R, Unit> = fx.pure(Unit)
context(fx: Effects<F>) fun <F, R, A> defer(make: () -> Prog<F, R, A>): Prog<F, R, A> = fx.defer(make)
context(fx: Effects<F>) fun <F, A> run(p: Prog<F, Nil, A>): A = fx.run(p)

context(fx: Effects<F>)
fun <F, R, A, B> Prog<F, R, A>.flatMap(f: (A) -> Prog<F, R, B>): Prog<F, R, B> = fx.bind(this, f)

context(fx: Effects<F>)
fun <F, R, A, B> Prog<F, R, A>.map(f: (A) -> B): Prog<F, R, B> = fx.bind(this) { fx.pure(f(it)) }

/** this, then `next`, its value */
context(fx: Effects<F>)
fun <F, R, A, B> Prog<F, R, A>.then(next: Prog<F, R, B>): Prog<F, R, B> = fx.bind(this) { next }

/** this, its value `b` */
context(fx: Effects<F>)
fun <F, R, A, B> Prog<F, R, A>.with(b: B): Prog<F, R, B> = fx.bind(this) { fx.pure(b) }

/** `step` from `init` until it gives a value: a loop of the program, no stack */
context(fx: Effects<F>)
fun <F, R, S, A> loop(init: S, step: (S) -> Prog<F, R, Next<S, A>>): Prog<F, R, A> = fx.bind(step(init)) {
    when (it) {
        is Next.Again -> fx.defer { loop(it.state, step) }
        is Next.Done -> fx.pure(it.value)
    }
}

/** this program, where the row is `S` */
context(fx: Effects<F>)
fun <F, R, S, A> Prog<F, R, A>.into(sub: Sub<R, S>): Prog<F, S, A> = fx.embed(this, sub)

/** this program inside a handler of `G` it does not see: its operations past it */
context(fx: Effects<F>)
fun <F, G, R, A> Prog<F, R, A>.lifted(): Prog<F, With<G, R>, A> = fx.embed(this, Sub.Inner())

/**
 * `E`'s operations in `p` handled by `h`, in the row they are in: `h` sees each first, and passes it on — performs it
 * again, to the handler of `E` outside — or not. okay-scala's `intercept`: `p` with `E` once more inside it, handled.
 */
context(fx: Effects<F>)
fun <F, E, R, A, B> intercept(at: In<E, R>, p: Prog<F, R, A>, h: Handler<F, E, R, A, B>): Prog<F, R, B> =
    fx.handle(fx.embed(p, interposing(at)), Place.Last(), h)

private fun <E, R, Rest> interposing(at: Place<E, R, Rest>): Sub<R, With<E, R>> = Sub.Interposing(at)

/** a loop's next step: again from a state, or done with a value */
sealed interface Next<out S, out A> {
    class Again<S>(val state: S) : Next<S, Nothing>
    class Done<A>(val value: A) : Next<Nothing, A>
}

/** a handler's body: given its effect's place, as a context and as `it`, and the lifting of places from outside */
typealias Body<F, E, Rest, A> =
    context(Place<E, With<E, Rest>, Rest>, Under<E, Rest>) (Place<E, With<E, Rest>, Rest>) -> Prog<F, With<E, Rest>, A>

/** the body with its effect handled by `h` at the last place */
context(fx: Effects<F>)
fun <F, E, Rest, A, B> handled(h: Handler<F, E, Rest, A, B>, body: Body<F, E, Rest, A>): Prog<F, Rest, B> {
    val place = Place.Last<E, Rest>()
    return fx.handle(context(place, Under<E, Rest>()) { body(place) }, place, h)
}
