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

## Interception

okay-direct shows every operation to an interceptor because its operations are data; okay-kotlin's are calls of an
interface, and a handler answering in place is no more than the call — nothing in between sees it. So interception
is a wrapper of the capability: `intercepted(interceptor)` implements the interface again, each suspend function
making an `Operation` — the effect's name, the function's, the arguments — and calling `interceptor.around(op) {
inner.f(args) }`. `around` is generic in the answer's type `X`, inferred at each call: the interceptor may pass the
operation on, see its answer as a value, or refuse it, with no cast — but it cannot make an `X` of its own. Changing
an answer is typed work: delegation, which the compiler checks.

The wrappers are code, the same for every interface: the standard effects' are written in `Intercepted.kt`, and
`okay-kotlin-ksp` writes them for any interface marked `@Effect` — its type parameters and their bounds, `vararg`,
function types, `Nothing`, defaults, inherited functions. A function that is not suspend, and a property, is passed
on as it is: `around` is a suspend function. A default function is intercepted as one operation; the calls it makes
of the wrapped capability's own functions are not.

When nothing keeps the `Operation`, the JIT does not make it: a wrapper that passes everything on costs ~0.5 ns an
operation. An observer that keeps them costs what it keeps.

A sensitive effect's handler gives a `Vault` instead of the capability, and `audit(vault, interceptor)` opens it,
wrapped: the program cannot have the capability but through an interceptor. This is okay-direct's `Audit`; okay-direct's
`Observer` — every operation of every effect, with no wrapper chosen — has no counterpart: it would tax every call
of every handler answering in place, which is what makes them free.

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
