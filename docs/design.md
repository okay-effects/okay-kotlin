# Design

okay-kotlin is the okay family's effects for Kotlin: the same ideas as
[okay-scala](https://github.com/okay-effects/okay-scala) (one machine, deep handlers, no cast) and
[okay-direct](https://github.com/okay-effects/okay-direct) (direct style, capabilities), in the forms Kotlin has for
them.

## An effect is an interface, a program's effects its context parameters

```kotlin
interface State<S> {
    suspend fun get(): S
    suspend fun put(s: S)
}

context(state: State<Int>, fail: Fail<String>)
suspend fun tick(): Int { ... }
```

Kotlin has no higher-kinded types and no GADT refinement, so an effect as data — an operation a class, its answer's
type a type parameter recovered in the handler — costs a witness or a cast. An interface needs neither: each
operation is a method with its own signature, and the compiler checks every answer. What okay-simple's rows do —
say what a program needs, and refuse it where no handler gives it — context parameters do: `tick()` compiles only
where a `State<Int>` and a `Fail<String>` are in the context. Two states are told apart by their types.

A handler is a function: it implements the interface and runs the body with it, `context(impl) { body() }`. One that
wraps another handler is an implementation by delegation, `object : Store<K, V> by store { ... }` — the interceptors
of okay-direct and okay-scala's `intercept` and `around`, with nothing of the library in them.

## Three kinds of handler

- **In place** — the answer given where the operation is asked, the body going on: `State`, `Reader`, `Writer`,
  `Store`, `Log`, `Clock`. A plain implementation: a virtual call, no continuation, nothing allocated. This is
  okay-scala's `Tail` and `Stateful` shapes, and costs what any interface call costs.
- **Abort** — the rest dropped, a value of the handler's instead: `Fail`, `Abort`, `take`. `prompt.abort(value)`.
- **General** — the rest taken as `k`, resumed in the handler: `prompt.shift { k -> ... }`.

The last two need the rest of the body as a value: a delimited continuation, `handle` and `shift`.

## Delimited continuations on Kotlin's coroutines

A suspend function compiles to a state machine whose continuation is an object: the rest of the computation. The
standard library gives it out — `suspendCoroutineUninterceptedOrReturn` — and starts coroutines with no dispatcher —
`createCoroutineUnintercepted`. Nothing else is used; kotlinx.coroutines is not needed.

`handle(body)` makes a `Prompt` and a `Machine`, and starts the body as a coroutine of its own, its context marked
with the machine's `Running` element. `prompt.shift(f)` suspends the body: the continuation `k` and the clause `f` are
left with the machine as an operation, and the body's coroutine returns `COROUTINE_SUSPENDED` to whoever resumed it —
the machine's loop, `drive`.

**Deep handlers in constant stack.** The clause runs as a coroutine of the machine too. When it calls `k(x)`, it does
not call the body: it suspends, its own continuation pushed on the machine's `frames`, and the loop resumes the body
with `x`. When the body ends, its value goes to the clause on top of `frames`, which goes on with it as `k`'s answer.
So a handler that resumes in its clause a million times uses a list on the heap, not a million stack frames: this is
okay-scala's CEK machine — `frames` its continuation, the loop its step — with Kotlin's state machines for the
expressions.

**Handlers in between.** An operation for a prompt further out suspends the innermost body's coroutine — it reaches
the innermost loop, not its own. That loop does it again in its own frame (`Relay`): its own frame is in the next
body out, so the shift reaches the next loop, and so on to the prompt's. The answer comes back the same way, and the
loop resumes the body with it. Each machine in between costs one relay; a handler in place costs nothing, it has no
machine. A clause's `k` resumed from inside another handler is relayed in the same way.

**The rest dropped.** A clause that returns without calling `k` drops the rest of the body. The rest is not left
hanging: the machine resumes it with `Abandoned`, a `CancellationException`, so the body unwinds — its `finally`
blocks run, `use` closes what it opened — and then gives the clause's value. This is okay-scala's `ensuring` and
`bracket`, written as Kotlin writes them.

**One-shot.** A Kotlin continuation is resumed once. `k` used twice is an error; multi-shot handlers — every way of a
choice, `Choose.all` — need a continuation copied, which Kotlin does not give, or the body run again from the start,
which this library does not do.

## Waiting for the outside

A body may suspend for something that is not an effect — `delay`, `withContext(Dispatchers.IO)`, an `await`. Then the
loop finds no event: it parks, its own continuation kept, and the next event — the body's end, an operation, a relay —
signals it from whatever thread it happens on. So handled bodies run inside kotlinx.coroutines as any suspend code
does, and an effect's handler may be outside a `coroutineScope` whose children ask it. A general handler's operations
must come from one coroutine at a time: two children shifting to the same prompt at once is not supported.

`runEffects` runs a program with every effect handled and nothing to wait for; anything that waits runs in a
coroutine, `runBlocking { ... }`, with the handlers as the suspend functions they are.

## What it costs

See the README. In-place handlers cost an interface call. A `shift` costs a few hundred bytes and tens of
nanoseconds: the operation, the clause's coroutine, the frame. A failure costs its unwinding — an exception thrown
through the body's frames, with no stack trace on the JVM.
