# okay-kotlin

Algebraic effects for Kotlin, in Kotlin's own terms: an effect is an interface of suspend functions, the effects a
program needs are its context parameters, a handler is a function that implements the interface and runs the body
with it. Handlers that take the rest of the body — failure, early ends, generators, your own — are delimited
continuations on Kotlin's coroutines: deep, in constant stack; multi-shot on the JVM and JavaScript, the rest's
frames copied. Kotlin 2.4, JVM, JS and native; no
dependency, the continuations are the standard library's. Runs inside kotlinx.coroutines as any suspend code does.

The okay family's effects — [okay-simple](https://github.com/okay-effects/okay-simple),
[okay-scala](https://github.com/okay-effects/okay-scala), [okay-direct](https://github.com/okay-effects/okay-direct) —
for Kotlin. [docs/design.md](docs/design.md) tells how it works, and why it is built so.

## A program, its handlers, and the stack

```kotlin
// the domain: what the business needs
interface Accounts {
    suspend fun balance(account: String): Int
    suspend fun deposit(account: String, amount: Int)
    suspend fun withdraw(account: String, amount: Int)
}

// the program: the domain and a failure, nothing else — its context parameters say so, the compiler checks it
context(accounts: Accounts, fail: Fail<String>)
suspend fun transfer(from: String, to: String, amount: Int) {
    ensure(accounts.balance(from) >= amount) { "$from has less than $amount" }
    accounts.withdraw(from, amount)
    accounts.deposit(to, amount)
}

// the domain on a store: a handler — a program of Accounts run as a program of Store
context(store: Store<String, Int>)
suspend fun <A> onStore(body: suspend context(Accounts) () -> A): A = context(object : Accounts {
    override suspend fun balance(account: String) = store.load(account) ?: 0
    override suspend fun deposit(account: String, amount: Int) = store.save(account, balance(account) + amount)
    override suspend fun withdraw(account: String, amount: Int) = store.save(account, balance(account) - amount)
}) { body() }

// each operation journaled first, then passed on to the handler further out: delegation
context(accounts: Accounts)
suspend fun <A> journaled(journal: MutableList<String>, body: suspend context(Accounts) () -> A): A =
    context(object : Accounts by accounts {
        override suspend fun deposit(account: String, amount: Int) {
            journal += "deposit $account $amount"
            accounts.deposit(account, amount)
        }

        override suspend fun withdraw(account: String, amount: Int) {
            journal += "withdraw $account $amount"
            accounts.withdraw(account, amount)
        }
    }) { body() }
```

```kotlin
val journal = mutableListOf<String>()
val (db, results) = runEffects {
    inMemory(mapOf("ann" to 100, "bob" to 0)) {        // a Store: a map here, a database in production
        onStore {
            journaled(journal) {
                either<String, _> { transfer("ann", "bob", 70) } to
                    either<String, _> { transfer("ann", "bob", 70) }
            }
        }
    }
}
// results: (Right(Unit), Left(ann has less than 70)); db: {ann=30, bob=70}
// journal: [withdraw ann 70, deposit bob 70] — the failed transfer stopped before it wrote anything
```

`transfer` never changes: the store becomes a database, the journal goes or stays, the failure is handled where the
caller wants it. That is `ReadmeTest`.

## Writing a handler

An effect answered in place is a plain implementation — a virtual call, nothing more:

```kotlin
suspend fun <S, A> state(init: S, body: suspend context(State<S>) () -> A): Pair<S, A>
```

One that ends the body early aborts to its prompt; `finally` blocks of the body run as it unwinds:

```kotlin
suspend fun <E, A> either(body: suspend context(Fail<E>) () -> A): Either<E, A> = handle { prompt ->
    Either.Right(context(Fail<E> { prompt.abort(Either.Left(it)) }) { body() })
}
```

One that takes the rest of the body shifts to its prompt: `k` is the rest, resumed once, and what the handler does
with its value is the handler's value:

```kotlin
suspend fun <A> answering(n: Int, body: suspend context(Ask) () -> A): A = handle { prompt ->
    context(Ask { prompt.shift { k -> k(n) } }) { body() }
}
```

| Word | What it is |
|---|---|
| `handle { prompt -> ... }` | the body delimited: `shift` and `abort` take the rest of it up to here |
| `prompt.shift { k -> ... }` | the rest of the body as `k`, a `Resume<X, R>`: resumed once, it gives the rest's value |
| `k.drop()` | the rest dropped and unwound: `Abandoned` thrown where it waits, its `finally` blocks run |
| `k.copy()` | a copy of the rest, resumed apart: multi-shot, on the JVM and JavaScript |
| `prompt.abort(value)` | the body ends here, the handler gives `value`; the rest dropped |
| `runEffects { ... }` | a program with every effect handled and nothing to wait for, run; otherwise `runBlocking` |

## The standard effects

| Effect | Words | Handlers |
|---|---|---|
| `State<S>` | `get`, `put`, `modify`, `gets` | `state(init)` |
| `Reader<E>` | `ask` | `reader(env)`, `local(f)` |
| `Writer<W>` | `tell` | `writer`, `listen`, `censor(f)` |
| `Fail<E>` | `raise`, `ensure`, `Either.bind()` | `either`, `recover` |
| `Abort` | `T?.bind()` | `optional` |
| `Emit<O>` | `emit` | `collect`, `forEach(f)`, `take(n)` |
| `Choose` (multi-shot: JVM, JS) | `among`, `flip`, `none` | `all`, `first` |
| `Fresh` | `fresh` | `numbered(start)` |
| `Randomness` | `nextLong`, `nextInt`, `nextDouble` | `seeded(seed)`, `randomly(random)` |
| `Store<K, V>` | `load`, `save`, `remove` | `inMemory(initial)` |
| `Clock` | `now`, `sleep` | `virtualTime(start)` |
| `Log` | `debug`, `info`, `warn`, `error` | `logged(least)`, `logTo(sink, least)` |

Generic handlers are given their effect's type: `either<String, _> { ... }`, `collect<Int, _> { ... }` — Kotlin infers
type parameters from arguments, not from what a lambda asks of its context.

## Effects as data: one program, two engines

`okay.kotlin.effects` is the other style of the family, okay-simple's and okay-scala's: an operation is data, a
program is data — `Prog<F, R, A>`, of an engine `F`, performing the effects of the row `R` — and handlers interpret
it. A program is written once, generic in its engine, which it takes as a context parameter (Scala's import, here a
parameter); the rows and the typed places are okay-scala's, so an operation goes to the nearest handler of its effect
at run time, as in the literature's EFF.

```kotlin
// an effect: operations as data; `answer` turns the state into the operation's own answer type, with no cast
class State<S> private constructor() {
    class Get<S, X>(val answer: (S) -> X) : Op<State<S>, X>
    class Put<S, X>(val value: S, val answer: (Unit) -> X) : Op<State<S>, X>
}

// a program: any engine F, any row R with a State<Int> and a Fail<String> — its places say where
context(fx: Effects<F>, st: In<State<Int>, R>, fail: In<Fail<String>, R>)
fun <F, R> tick(): Prog<F, R, Int> = get<F, Int, R>().flatMap { n ->
    if (n >= 3) raise("big $n") else put<F, Int, R>(n + 1).with(n)
}

// run on either engine: the handler gives its body its own place; a place from outside is lifted past it
with(Machine) { run(either<_, String, _, _> { fail -> state(0) { context(lift(fail)) { tick() } } }) }
with(Direct)  { run(either<_, String, _, _> { fail -> state(0) { context(lift(fail)) { tick() } } }) }
```

| Engine | What it is |
|---|---|
| `Machine` | okay-scala's CEK machine: `k` is data — deep, multi-shot on every platform, native too |
| `Direct` | this library's continuations: a handler in place a call; `k` copied where a continuation can be |

Handlers come in okay-scala's shapes — `General` (`k`, any number of times), `Tail` (answered in place), `Abort` (the
rest dropped), `Stateful` (in place, a state of its own) — and the standard ones are written once, for both:
`state`, `reader`, `writer`, `either`, `all`, `first`, `take`. A program moves between rows by a renaming,
`p.into(sub)` or `p.lifted()`; `intercept(at, p, h)` handles an effect's operations in the row they are in, and on it
are built `observe`, `around`, `local`, `listen`, `censor` — okay-scala's. `EffectsTck` and `RenamingTck` hold every
engine to the same answers; `SpecTck` runs the paper's EFF state (Fig. 10) on both.

## Interception: control and audit

An effect's capability wrapped by `intercepted(interceptor)` shows each operation to the interceptor first, as data —
`Operation(effect, name, args)` — with `proceed`, the way on to its handler. The interceptor tells (an observer, a
trail), passes it on, or refuses it (throws, or `raise`s to the program's own `Fail`):

```kotlin
val trail = observer { op, outcome -> log += "$op -> $outcome" }            // each operation and its answer
val noDeletes = guard { op -> if (op.name == "remove") raise("no deletes: $op") }

either<String, _> {
    context(store.intercepted(trail + noDeletes)) { move("a", "b") }       // trail first, then the guard
}
```

Changing an answer needs its type: that is delegation, `object : Store<K, V> by store { ... }`, checked by the
compiler. The standard effects have their `intercepted`; for an effect of yours, mark it and the generator writes it:

```kotlin
@Effect
interface Orders<Id : Comparable<Id>, in Line> {
    suspend fun place(lines: List<Line>): Id
    suspend fun cancel(id: Id, reason: String? = null): Boolean
}
// generated: fun <Id : Comparable<Id>, Line> Orders<Id, Line>.intercepted(interceptor: Interceptor): Orders<Id, Line>
```

```kotlin
plugins { id("com.google.devtools.ksp") version "2.3.12" }
dependencies { ksp("io.github.okay-effects:okay-kotlin-ksp:0.1.0") }
```

**Sensitive effects** are given only under an audit: their handler gives its body a `Vault`, not the capability, and
`audit(vault, interceptor) { ... }` gives the capability with every operation through the interceptor — so a program
can pay only where an auditor sees and may refuse each payment:

```kotlin
payments { vault ->                                      // the handler: vault(bank, Payments::intercepted)
    audit(vault, auditor + limit) { salaries() }          // salaries() needs a Payments: it has one only here
}
```

## With kotlinx.coroutines

A handled body may wait for anything — `delay`, `withContext(Dispatchers.IO)`, children in a `coroutineScope` — and
its handlers keep working: they are suspend functions, the machine waits for the body as any suspend caller does.

```kotlin
runBlocking {
    either<String, _> {
        delay(20)
        withContext(Dispatchers.Default) { raise("stopped on another thread") }
    }
}
```

## Semantics

The calculi of Forster, Kammar, Lindley and Pretnar's *On the Expressive Power of User-Defined Effects* (ICFP 2017),
checked by `SpecTest`: `handle`/`shift` are its DEL (shift0 and reset, the reset named); handlers are its EFF, deep,
made by its EFF→DEL translation; its examples — state in EFF, DEL and MON — run as written there. `k` is a value: a
clause may keep it and have it resumed after it returns, after `handle` returns. `k.drop()` unwinds the rest
(`finally` runs); `abort` drops it. [docs/design.md](docs/design.md#against-the-literature) has the comparison.

## Multi-shot

A Kotlin continuation is resumed once. `k.copy()`, taken before `k` is resumed, is another: the rest's frames — the
objects the compiler makes of a suspend function, its locals their fields — copied up to the handler, and the
iterators of its loops with them. Nothing runs again: the copy goes on from where the rest was. So `k` is used as
many times as the literature's:

```kotlin
context(_: Choose)
suspend fun triple(n: Int): Triple<Int, Int, Int> {
    val a = among((1..n).toList())
    val b = among((a..n).toList())
    val c = among((b..n).toList())
    if (a * a + b * b != c * c) none()
    return Triple(a, b, c)
}

runEffects { all { triple(20) } }      // [(3, 4, 5), (5, 12, 13), (6, 8, 10), (8, 15, 17), (9, 12, 15), (12, 16, 20)]
runEffects { first { queens(8) } }     // backtracking: the first way to a value, the rest not tried
```

- On the JVM and JavaScript; native code has no way to copy a frame.
- What the frames point to on the heap is shared by the copies: a mutable list built along the way, the state of a
  handler answering in place outside the choice. A way's own state is a local holding a value, or a handler that
  takes the rest.
- Not across a handler that delimits its body: `all { either { ... } }` is refused, `either { all { ... } }` works.
- On the JVM, a `for` over a JDK collection keeps its iterator in a closed module: open it to copy it,
  `--add-opens java.base/java.util=ALL-UNNAMED` (ranges and `repeat` need nothing). From JDK 26, copying writes
  final fields: `--enable-final-field-mutation=ALL-UNNAMED` keeps it quiet, and will be needed when the JDK blocks it.

## Limits
- Handlers are chosen lexically, not dynamically as in EFF: an operation goes to the handler whose capability it was
  given. A function made under one handler and called under another of the same effect asks the first — the
  semantics of lexically scoped handlers, abstraction-safe; the only one Kotlin can type. A capability kept after its
  handler returned fails, if its handler takes the rest of the body.
- A general handler takes one operation at a time: children of a body that shift to the same prompt at once are not
  supported. Handlers in place have no such limit.

## Measured

JMH on the JVM, `./gradlew :bench:jmh`, 1000 operations a run, µs per run, lower is better; one run on a laptop:

| | µs | bytes |
|---|---|---|
| a state's get and put | 1.6 | 16 216 (boxed Ints) |
| ask, under four other handlers | 0.6 | 1 176 |
| the same, each through an interceptor passing it on | 2.1 | 16 240 |
| the same, each seen by an observer with its answer | 17 | 168 392 |
| a handler entered and left, no operation | 32 | 360 152 |
| shift: the rest taken and resumed by the handler right outside | 32 | 271 600 |
| shift across three delimiting handlers | 109 | 785 034 |
| multi-shot: every Pythagorean triple to 30 — 4 959 copies, 5 456 dead ends | 4 752 | 4 449 170 |
| as data on `Machine`: 1000 gets and puts / every triple to 30 | 62 / 411 | 425 568 / 2 222 640 |
| as data on `Direct`: the same | 59 / 2 699 | 573 013 / 6 224 097 |
| a failure, the rest dropped and unwound | 779 | 1 000 152 |

Data costs what data costs: a node and a closure a step — 40 times an interface call. Multi-shot is where it pays:
the machine's `k` is data, nothing copied, ten times faster than copying frames.

A handler in place costs what an interface call costs — the same programs take 16–36 µs on okay-scala's machine. A
`shift` costs about 280 bytes; a failure, its exception thrown through the body.

## Building

Modules: `core` (`okay-kotlin`: the effects, interception, no dependency), `ksp` (`okay-kotlin-ksp`: the
generator), `examples` (effects of a shop, their interception generated and tested), `bench`.

`./gradlew check` on the JVM; `./gradlew -Pplatforms=jvm,js,native :core:allTests` on Node.js and native too.
Apache-2.0.
