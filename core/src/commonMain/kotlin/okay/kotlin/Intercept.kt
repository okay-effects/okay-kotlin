package okay.kotlin

// INTERCEPTION: control and audit of effects. An operation of a capability wrapped by `intercepted` is shown to an
// interceptor first, as data, with the way on to its handler: the interceptor tells, passes it on, or refuses it.
// Changing an answer needs its type: that is delegation, `object : Store<K, V> by store { ... }`, checked by the
// compiler. The wrappers of the standard effects are below (Intercepted.kt); `@Effect` generates them for yours.

/** marks an effect's interface for okay-kotlin's generator: `Name.intercepted(interceptor)` made for it */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Effect

/** an operation as data: its effect's name, its own, its arguments */
data class Operation(val effect: String, val name: String, val args: List<Any?>) {
    override fun toString() = "$effect.$name(${args.joinToString()})"
}

/** what happens around each operation of the capabilities it wraps */
interface Interceptor {
    /** `proceed` is the way on to the handler — called, the operation is performed and its answer given back; not
     * called, the operation is refused: throw, or `raise` to a `Fail` of the program's */
    suspend fun <X> around(op: Operation, proceed: suspend () -> X): X
}

/** each operation seen after it is performed, with its outcome: its answer, or what it threw — [Abandoned] if its
 * handler dropped the rest */
fun observer(see: suspend (Operation, Result<Any?>) -> Unit): Interceptor = object : Interceptor {
    override suspend fun <X> around(op: Operation, proceed: suspend () -> X): X {
        val outcome = runCatching { proceed() }
        see(op, outcome)
        return outcome.getOrThrow()
    }
}

/** each operation shown to `check` before it is performed: it passes if `check` returns, refused if it throws */
fun guard(check: suspend (Operation) -> Unit): Interceptor = object : Interceptor {
    override suspend fun <X> around(op: Operation, proceed: suspend () -> X): X {
        check(op)
        return proceed()
    }
}

/** this interceptor around `inner`: an operation goes through this one first */
operator fun Interceptor.plus(inner: Interceptor): Interceptor = object : Interceptor {
    override suspend fun <X> around(op: Operation, proceed: suspend () -> X): X =
        this@plus.around(op) { inner.around(op, proceed) }
}

/** thrown by an interceptor that refuses an operation: an error, not a cancellation — it is not swallowed */
class Refused(val operation: Operation, reason: String = "refused") : RuntimeException("$reason: $operation")

// ---- SENSITIVE EFFECTS: performed only under an audit

/**
 * A SENSITIVE EFFECT's handler, as it gives itself to its body: nothing is performed through a vault but under an
 * [audit], which gives its body the capability with every operation through an interceptor. So a program has the
 * capability only where it is audited.
 */
class Vault<E> internal constructor(private val effect: E, private val intercepted: (E, Interceptor) -> E) {
    internal fun open(interceptor: Interceptor): E = intercepted(effect, interceptor)
}

/** `effect` kept in a vault; `intercepted` its wrapper: `vault(payments, Payments::intercepted)` */
fun <E> vault(effect: E, intercepted: (E, Interceptor) -> E): Vault<E> = Vault(effect, intercepted)

/** the body with the vault's effect, every operation of it through `interceptor` */
suspend fun <E, A> audit(vault: Vault<E>, interceptor: Interceptor, body: suspend context(E) () -> A): A =
    context(vault.open(interceptor)) { body() }
