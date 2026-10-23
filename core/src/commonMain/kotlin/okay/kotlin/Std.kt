package okay.kotlin

// THE STANDARD EFFECTS. Each is an interface; a program asks for it as a context parameter and calls it; its handler
// is a function that implements it and runs the body with it. An effect answered in place (State, Reader, Writer) is
// a plain implementation — a virtual call, no continuation; one that takes the rest of the body (Fail, Abort, Emit's
// `take`) shifts to its prompt.

/** a state of type `S`, read and written */
interface State<S> {
    suspend fun get(): S
    suspend fun put(s: S)
}

context(state: State<S>) suspend fun <S> get(): S = state.get()
context(state: State<S>) suspend fun <S> put(s: S) = state.put(s)
context(state: State<S>) suspend fun <S> modify(f: (S) -> S) = state.put(f(state.get()))
/** the state through `f` */
context(state: State<S>) suspend fun <S, A> gets(f: (S) -> A): A = f(state.get())

/** the body from `init`: the state it ends with, and its value */
suspend fun <S, A> state(init: S, body: suspend context(State<S>) () -> A): Pair<S, A> {
    val cell = object : State<S> {
        var s = init
        override suspend fun get() = s
        override suspend fun put(s: S) {
            this.s = s
        }
    }
    val a = context(cell) { body() }
    return cell.s to a
}

/** an environment of type `E`, asked for */
fun interface Reader<E> {
    suspend fun ask(): E
}

context(reader: Reader<E>) suspend fun <E> ask(): E = reader.ask()

/** the body with `env` as its environment */
suspend fun <E, A> reader(env: E, body: suspend context(Reader<E>) () -> A): A = context(Reader { env }) { body() }

/** the body with the environment through `f`; the rest sees it as it was */
context(reader: Reader<E>) suspend fun <E, A> local(f: (E) -> E, body: suspend context(Reader<E>) () -> A): A =
    reader(f(reader.ask()), body)

/** outputs of type `W`, written */
fun interface Writer<W> {
    suspend fun tell(w: W)
}

context(writer: Writer<W>) suspend fun <W> tell(w: W) = writer.tell(w)

/** what the body wrote, in order, and its value */
suspend fun <W, A> writer(body: suspend context(Writer<W>) () -> A): Pair<List<W>, A> {
    val written = ArrayList<W>()
    val a = context(Writer<W> { written.add(it) }) { body() }
    return written to a
}

/** what the body wrote, seen here, and written outside too */
context(writer: Writer<W>) suspend fun <W, A> listen(body: suspend context(Writer<W>) () -> A): Pair<List<W>, A> =
    writer(body).also { (ws, _) -> ws.forEach { writer.tell(it) } }

/** what the body wrote, through `f`, written outside when it ends */
context(writer: Writer<W>)
suspend fun <W, A> censor(f: (List<W>) -> List<W>, body: suspend context(Writer<W>) () -> A): A {
    val (ws, a) = writer(body)
    f(ws).forEach { writer.tell(it) }
    return a
}

// ---- FAILURE AND EARLY ENDS: the rest of the body dropped, its `finally` blocks run

/** a value, or the error that stopped it */
sealed interface Either<out E, out A> {
    data class Left<out E>(val error: E) : Either<E, Nothing>
    data class Right<out A>(val value: A) : Either<Nothing, A>
}

/** failure with an error of type `E` */
fun interface Fail<in E> {
    suspend fun raise(e: E): Nothing
}

context(fail: Fail<E>) suspend fun <E> raise(e: E): Nothing = fail.raise(e)

/** `cond`, or the failure `e()` */
context(fail: Fail<E>) suspend fun <E> ensure(cond: Boolean, e: () -> E) {
    if (!cond) fail.raise(e())
}

/** the value of an `Either`, or its error raised */
context(fail: Fail<E>) suspend fun <E, A> Either<E, A>.bind(): A = when (this) {
    is Either.Left -> fail.raise(error)
    is Either.Right -> value
}

/** the body's value, or the error it raised */
suspend fun <E, A> either(body: suspend context(Fail<E>) () -> A): Either<E, A> = handle { prompt ->
    Either.Right(context(Fail<E> { prompt.abort(Either.Left(it)) }) { body() })
}

/** the body's value, or `recover` of the error it raised — run outside the body, so it may raise to the next `Fail` */
suspend fun <E, A> recover(body: suspend context(Fail<E>) () -> A, recover: suspend (E) -> A): A =
    when (val r = either(body)) {
        is Either.Left -> recover(r.error)
        is Either.Right -> r.value
    }

/** an early end with no value */
fun interface Abort {
    suspend fun abort(): Nothing
}

/** the value of a nullable, or the body ended here */
context(abort: Abort) suspend fun <T : Any> T?.bind(): T = this ?: abort.abort()

/** the body's value, or `null` if it ended early */
suspend fun <A : Any> optional(body: suspend context(Abort) () -> A): A? = handle { prompt ->
    context(Abort { prompt.abort(null) }) { body() }
}

// ---- GENERATORS

/** values of type `O`, given out one by one */
fun interface Emit<in O> {
    suspend fun emit(o: O)
}

context(out: Emit<O>) suspend fun <O> emit(o: O) = out.emit(o)

/** every value the body gave out, and its value */
suspend fun <O, A> collect(body: suspend context(Emit<O>) () -> A): Pair<List<O>, A> {
    val given = ArrayList<O>()
    val a = context(Emit<O> { given.add(it) }) { body() }
    return given to a
}

/** each value given out, to `f`, as it comes */
suspend fun <O, A> forEach(body: suspend context(Emit<O>) () -> A, f: suspend (O) -> Unit): A =
    context(Emit<O> { f(it) }) { body() }

/** the first `n` values, and the body's value if it ended by then: the rest dropped at the value after them */
suspend fun <O, A> take(n: Int, body: suspend context(Emit<O>) () -> A): Pair<List<O>, A?> = handle { prompt ->
    val taken = ArrayList<O>()
    val a = context(Emit<O> { if (taken.size < n) taken.add(it) else prompt.abort(taken to null) }) { body() }
    taken to a
}
