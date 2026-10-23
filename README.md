# okay-kotlin

Algebraic effects for Kotlin, in Kotlin's own terms: an effect is an interface of suspend functions, the effects a
program needs are its context parameters, a handler is a function that implements the interface and runs the body
with it. Handlers that take the rest of the body — failure, early ends, generators, your own — are delimited
continuations on Kotlin's coroutines: deep, in constant stack, one-shot. Kotlin 2.4, JVM, JS and native; no
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
| `prompt.abort(value)` | the body ends here, the handler gives `value`; the rest unwinds with `Abandoned` |
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
| `Fresh` | `fresh` | `numbered(start)` |
| `Randomness` | `nextLong`, `nextInt`, `nextDouble` | `seeded(seed)`, `randomly(random)` |
| `Store<K, V>` | `load`, `save`, `remove` | `inMemory(initial)` |
| `Clock` | `now`, `sleep` | `virtualTime(start)` |
| `Log` | `debug`, `info`, `warn`, `error` | `logged(least)`, `logTo(sink, least)` |

Generic handlers are given their effect's type: `either<String, _> { ... }`, `collect<Int, _> { ... }` — Kotlin infers
type parameters from arguments, not from what a lambda asks of its context.

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

## Limits

- One-shot: a Kotlin continuation is resumed once, and the library does not run a body again to fake more. Every way
  of a choice (`Choose.all`) is not here.
- A general handler takes one operation at a time: children of a body that shift to the same prompt at once are not
  supported. Handlers in place have no such limit.

## Measured

JMH on the JVM, `./gradlew :bench:jmh`, 1000 operations a run, µs per run, lower is better; one run on a laptop:

| | µs | bytes |
|---|---|---|
| a state's get and put | 1.6 | 16 216 (boxed Ints) |
| ask, under four other handlers | 0.6 | 1 176 |
| a handler entered and left, no operation | 28 | 312 152 |
| shift: the rest taken and resumed by the handler right outside | 32 | 279 552 |
| shift across three delimiting handlers | 109 | 785 034 |
| a failure, the rest dropped and unwound | 751 | 936 152 |

A handler in place costs what an interface call costs — the same programs take 16–36 µs on okay-scala's machine. A
`shift` costs about 280 bytes; a failure, its exception thrown through the body.

## Building

`./gradlew check` on the JVM; `./gradlew -Pplatforms=jvm,js,native :core:allTests` on Node.js and native too.
Apache-2.0.
