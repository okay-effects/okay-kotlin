package okay.kotlin.effects

// ROWS AND PLACES: the effects a program may perform are in its type, a row; an operation of the row is placed in it,
// and a handler takes it apart. okay-scala's `R + E` and its typed places, as Kotlin writes them: no implicit search,
// so a handler gives its body the place of its effect, and the body lifts the places it has from outside.

/** an operation of the effect `E`, answered by a value of type `X`: data */
interface Op<E, X>

/** the row of no effect */
class Nil private constructor()

/** the row `R` with the effect `E` last — innermost, the first handled: okay-scala's `R + E` */
class With<E, R> private constructor()

/** an operation of the row `R`, placed: the last effect's, or one further out's */
sealed interface At<R, X> {
    class Last<E, R, X>(val op: Op<E, X>) : At<With<E, R>, X>
    class Init<E, R, X>(val op: At<R, X>) : At<With<E, R>, X>
}

/** an operation of a row met by the handler of `E`: its own, or another's, placed in the row outside it */
sealed interface Taken<E, Rest, X> {
    class Mine<E, Rest, X>(val op: Op<E, X>) : Taken<E, Rest, X>
    class Theirs<E, Rest, X>(val op: At<Rest, X>) : Taken<E, Rest, X>
}

/**
 * THE PLACE OF `E` IN `R`, and `R` without it, `Rest`: a path typed by the row. It places an operation of `E` in `R`,
 * takes an operation of `R` apart for `E`'s handler, and finds `E`'s handler in a row of them. Each place knows its own
 * shape, so the types line up with no inference and no cast: the Kotlin spelling of Scala's GADT over places.
 */
sealed interface Place<E, R, Rest> {
    fun <X> inject(op: Op<E, X>): At<R, X>
    fun <X> take(op: At<R, X>): Taken<E, Rest, X>
    /** `E`'s handler in a row of handlers */
    fun find(handlers: Handlers<R>): Handling<E>
    /** a row of handlers outside, with `E`'s put at this place */
    fun put(outside: Handlers<Rest>, handling: Handling<E>): Handlers<R>

    /** the last effect of `With<E, R>` */
    class Last<E, R> : Place<E, With<E, R>, R> {
        override fun <X> inject(op: Op<E, X>): At<With<E, R>, X> = At.Last(op)
        override fun <X> take(op: At<With<E, R>, X>): Taken<E, R, X> = when (op) {
            is At.Last -> Taken.Mine(op.op)
            is At.Init -> Taken.Theirs(op.op)
        }
        override fun find(handlers: Handlers<With<E, R>>): Handling<E> = when (handlers) {
            is Handlers.Slot -> handlers.handling
            else -> error("no handler at the last place")
        }
        override fun put(outside: Handlers<R>, handling: Handling<E>): Handlers<With<E, R>> =
            Handlers.Slot(handling, outside)
    }

    /** one further out than the last, `G` */
    class Init<E, G, R, Rest>(val inner: Place<E, R, Rest>) : Place<E, With<G, R>, With<G, Rest>> {
        override fun <X> inject(op: Op<E, X>): At<With<G, R>, X> = At.Init(inner.inject(op))
        override fun <X> take(op: At<With<G, R>, X>): Taken<E, With<G, Rest>, X> = when (op) {
            is At.Last -> Taken.Theirs(At.Last(op.op))
            is At.Init -> when (val taken = inner.take(op.op)) {
                is Taken.Mine -> Taken.Mine(taken.op)
                is Taken.Theirs -> Taken.Theirs(At.Init(taken.op))
            }
        }
        override fun find(handlers: Handlers<With<G, R>>): Handling<E> = when (handlers) {
            is Handlers.Slot -> inner.find(handlers.outside)
            else -> error("no handler further out")
        }
        override fun put(outside: Handlers<With<G, Rest>>, handling: Handling<E>): Handlers<With<G, R>> =
            when (outside) {
                is Handlers.Slot -> Handlers.Slot(outside.handling, inner.put(outside.outside, handling))
                else -> error("no handler further out")
            }
    }
}

/** `E` is in `R`, at some place: what a program needs to perform `E` */
typealias In<E, R> = Place<E, R, *>

/** inside a handler of `G` over `Rest`: the places of `Rest` lifted past `G` by [lift] */
class Under<G, Rest> internal constructor()

/** a place from outside a handler of `G`, inside it: `context(lift(fail)) { ... }` */
context(_: Under<G, Rest>)
fun <E, G, Rest, R0> lift(place: Place<E, Rest, R0>): Place<E, With<G, Rest>, With<G, R0>> = Place.Init(place)

/** a handler as an object answering its effect's operations: how an engine of continuations finds it */
interface Handling<E> {
    suspend fun <X> perform(op: Op<E, X>): X
}

/** the handlers of a row, one at each place */
sealed interface Handlers<R> {
    object None : Handlers<Nil>
    class Slot<E, R>(val handling: Handling<E>, val outside: Handlers<R>) : Handlers<With<E, R>>
}
