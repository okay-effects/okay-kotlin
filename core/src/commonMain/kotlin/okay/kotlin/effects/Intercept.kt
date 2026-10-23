package okay.kotlin.effects

// INTERCEPTION OF DATA: a program's operations of an effect handled in the row they are in — seen, changed, passed on
// to the handler outside. okay-scala's `observe`, `around`, and the standard effects' words built on `intercept`.

/** each operation of `E` in `p` seen by `see` first — a program of the row: a trail, a count — then passed on. The
 * effect is named by its place: a context could hold several, and the one meant must not be guessed */
context(fx: Effects<F>)
fun <F, E, R, A> observe(at: In<E, R>, p: Prog<F, R, A>, see: (Op<E, *>) -> Prog<F, R, Unit>): Prog<F, R, A> =
    intercept(at, p, object : General<F, E, R, A, A>() {
        override fun <X> op(op: Op<E, X>, k: (X) -> Prog<F, R, A>): Prog<F, R, A> =
            fx.bind(see(op)) { fx.bind(fx.perform(at, op), k) }
        override fun value(a: A): Prog<F, R, A> = fx.pure(a)
    })

/** what is done around an operation of `E`: given it and `proceed`, its way on as it is, the program in its place */
interface Around<F, E, R> {
    fun <X> around(op: Op<E, X>, proceed: Prog<F, R, X>): Prog<F, R, X>
}

/** each operation of `E` — at its place — in `p`, through `f` */
context(fx: Effects<F>)
fun <F, E, R, A> around(at: In<E, R>, p: Prog<F, R, A>, f: Around<F, E, R>): Prog<F, R, A> =
    intercept(at, p, object : General<F, E, R, A, A>() {
        override fun <X> op(op: Op<E, X>, k: (X) -> Prog<F, R, A>): Prog<F, R, A> =
            fx.bind(f.around(op, fx.perform(at, op)), k)
        override fun value(a: A): Prog<F, R, A> = fx.pure(a)
    })

/** `p` with the environment through `f`; the rest sees it as it was */
context(fx: Effects<F>, at: In<Reader<E>, R>)
fun <F, E, R, A> local(f: (E) -> E, p: Prog<F, R, A>): Prog<F, R, A> = fx.bind(ask()) { e ->
    val env = f(e)
    intercept(at, p, object : Tail<F, Reader<E>, R, A, A>() {
        override fun <X> answer(op: Op<Reader<E>, X>): X = when (op) {
            is Reader.Ask -> op.answer(env)
            else -> foreign(op)
        }
        override fun value(a: A): Prog<F, R, A> = fx.pure(a)
    })
}

/** what `p` wrote, seen here, and written outside too when it ends */
context(fx: Effects<F>, at: In<Writer<W>, R>)
fun <F, W, R, A> listen(p: Prog<F, R, A>): Prog<F, R, Pair<List<W>, A>> = written(p) { it }

/** what `p` wrote, through `f`, written outside when it ends */
context(fx: Effects<F>, at: In<Writer<W>, R>)
fun <F, W, R, A> censor(f: (List<W>) -> List<W>, p: Prog<F, R, A>): Prog<F, R, A> = fx.bind(written(p, f)) { (_, a) ->
    fx.pure(a)
}

context(fx: Effects<F>, at: In<Writer<W>, R>)
private fun <F, W, R, A> written(p: Prog<F, R, A>, f: (List<W>) -> List<W>): Prog<F, R, Pair<List<W>, A>> =
    intercept(at, p, object : Stateful<F, Writer<W>, MutableList<W>, R, A, Pair<List<W>, A>>() {
        override fun init(): MutableList<W> = ArrayList()
        override fun <X> answer(op: Op<Writer<W>, X>, state: Cell<MutableList<W>>): X = when (op) {
            is Writer.Tell -> {
                state.value.add(op.w)
                op.answer(Unit)
            }
            else -> foreign(op)
        }
        override fun value(a: A, state: MutableList<W>): Prog<F, R, Pair<List<W>, A>> {
            val ws = state.toList()
            return f(ws).fold(fx.pure(Unit)) { p: Prog<F, R, Unit>, w -> fx.bind(p) { tell(w) } }.let { told ->
                fx.bind(told) { fx.pure(ws to a) }
            }
        }
    })
