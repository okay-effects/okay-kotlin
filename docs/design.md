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

**`k` is a value.** A clause may keep `k`, return, and have it resumed later — by a function it returns, by whoever
it gives `k` to, after `handle` itself has returned. Resumed where no loop drives its machine, `k` starts one there:
the rest runs under its handler again, where it is resumed. This is what the parameterised handlers of the
literature do — state as a function of the state, `get _ k ↦ λs. k! s s`.

**The rest dropped.** `k.drop()` resumes the rest with `Abandoned`, a `CancellationException`: the body unwinds — its
`finally` blocks run, `use` closes what it opened — OCaml's `discontinue`. `abort(value)` drops the rest and gives
`value`; `Fail`, `Abort` and `take` end so. A rest neither resumed nor dropped is left as it is: nothing can know
that a kept `k` will never be resumed.

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

## Against the literature

Forster, Kammar, Lindley and Pretnar (*On the Expressive Power of User-Defined Effects*, ICFP 2017) give three calculi
on call-by-push-value: EFF (effect handlers), MON (monadic reflection), DEL (shift0 and its reset, `S0 k.M` and
`⟨M|x.N⟩`). okay-kotlin's `handle` and `shift` are DEL's, with the reset named by its prompt; its handlers are EFF's,
made of them as the paper's EFF→DEL translation makes them (§6.4). `SpecTest` runs the paper's examples and rules:

| The paper | okay-kotlin | |
|---|---|---|
| (op): `k` = `λx.handle H[return x] with H` — deep | the rest resumed runs under the handler again | same |
| (ret): `handle (return V) with H ⇝ H^ret[V/x]` | the body's value, through the code after it in `handle` | same |
| (capture): `S0 k.M` runs `M` with the reset removed | the clause runs outside the body | same |
| `k` a value, used any number of times | a value; `k.copy()` for every use but one | JVM and JS |
| EFF state (Fig. 10), DEL state (Fig. 18) | `runStateEff`, `runStateDel`: a clause returns `λs. k(s)(s)` | same |
| MON→EFF (§6.5) untypeable: operations monomorphic | `reflect`: an interface's generic function | as conjectured |
| the operation handled by the nearest handler, at run time | by the handler whose capability it was given | lexical |

**Multi-shot.** The calculi's continuations are thunks, forced any number of times; Kotlin's are resumed once. A
copy of the rest is a continuation of its own: on the JVM and JavaScript, `k.copy()` copies its frames — see below.
Native code cannot, and there okay-kotlin has the handlers that resume at most once.

**Lexical, not dynamic.** In EFF an operation is a name, and the nearest handler of that name on the stack takes it:
a function made under one handler and called under another asks the second. In okay-kotlin an operation is a call
of a capability, and the capability is the handler that made it: the function asks the first. This is the semantics
of lexically scoped handlers — Zhang and Myers' tunnelling (POPL 2019), Biernacki et al.'s lexically scoped handlers
(POPL 2020), Effekt's capabilities (Brachthäuser et al., OOPSLA 2020) — chosen there because the dynamic one breaks
abstraction: a function that handles its own `Fail` inside would catch the failures of a callback it is given, which
it knows nothing of. It is also the only one Kotlin can type: a handler found at run time by its effect's type finds
an erased type — `State<Int>` and `State<String>` are one class — and its answer would need a cast.

The two agree on every program that uses each capability under the handler that gave it, called from where it is in
scope — the first-order programs; they differ when a function that performs is passed under another handler of the
same effect. What lexical scoping costs: a capability can outlive its handler — kept in a variable, returned — and
Kotlin has no capture checking to stop it. A handler answering in place keeps answering (it is an object); one that
takes the rest fails at run time, `shift outside its handler's body`.

## Copying a continuation

A suspend function compiles to a class whose object is its frame: its locals and its step are fields, the frame it
returns to is `completion`. The rest of a body is the chain of frames from the operation up to the body's root. A copy
of each — fields copied, `completion` pointed at the next copy — is a rest of its own, at the same point; resumed, it
goes on as the original would, and the original is still there to be resumed. This is not replay: nothing runs again.

On the JVM the frames are copied by reflection: an instance made without a constructor (`ReflectionFactory`), every
field set — final fields too, which JDK 26 warns of (`--enable-final-field-mutation`). On JavaScript a frame is an
object: `Object.assign(Object.create(prototype), frame)`, and the name of the `completion` property is found once, by a
probe. Native code gives no way to read a frame's fields.

A copy is shallow, as the frames are: what the locals point to on the heap is one for every copy. A loop's iterator is
the frame's own, so it is copied with it — a range's on JavaScript, a collection's on both (the JDK's when its module
is opened). A frame of a handler that delimits its body cannot be copied — its machine would be one for both copies —
so a copy is refused when the operation came through one (`relayed`); the copying handler goes inside it.

## Effects as data, and engines

`okay.kotlin.effects` is the family's other style, whose programs okay-simple runs on its machine and its Free and
okay-scala on its CEK machine: an operation is data, a program is data, a handler interprets it.

**The interface.** okay-simple's `Effects` has an abstract type of programs, `A ! R`, each implementation its own.
Kotlin has no type of a type constructor; so an engine is named by a type `F`, its programs are `Prog<F, R, A>` — an
abstract class each engine's nodes extend — and `Effects<F>` is its words. A program generic in `F` takes its engine
as a context parameter: Scala's import, as a parameter. An engine refuses another's program with an error, never a
cast: `is Node` on a `Prog<Machine, R, A>` finds the node's type by its declaration.

**Rows and places.** okay-scala's `R + E` is `With<E, R>`; an operation of the row is `At<R, X>`, placed; a `Place<E,
R, Rest>` injects an operation of `E` into `R`, takes one of `R` apart into `E`'s own or another's in `Rest`, and finds
`E`'s handler in a row of handler objects — each place a class that knows its shape, which is what okay-scala's
matches on GADTs know. Scala finds places by implicit search; Kotlin does not, so a handler gives its body the place
of its effect (as a context parameter and as `it`), and a place from outside is lifted past it by `lift`, its types
inferred from the handler's `Under<G, Rest>`.

**Answers.** A `when` over an operation does not tell Kotlin that `X` is the case's answer type: an operation whose
class fixes its answer carries `answer`, the function from its answer to `X` — the identity, made by its word. One
whose answer is its own type parameter (`Choose.Among<X>`) needs none. An effect's operations are open, so a `when`
over them has an `else`: `foreign(op)`.

**The machine** is okay-scala's, with Kotlin's dispatch in place of matches: a node takes its step, a frame its value,
a level its operation — each knowing its own types, so the machine has no cast. An operation crosses levels into
`Crossed`; at its handler `k` is a node that puts them back on whatever is outside when it is resumed. Deep,
multi-shot, a loop — on every platform.

**Direct** runs the same programs on okay-kotlin's continuations: binds in a loop, the row's handlers objects, found
at their places ([`Handlers`]). A handler in place (`Tail`, `Stateful`) is a call; one taking the rest a prompt, `k`
the coroutine's rest, copied for each resumption where a copy can be made — not across another prompt, where it is
one-shot. A `k` resumed under other handlers than where it was taken finds, for the effects outside its own, the
handlers of where it was taken: the machine finds those of where it is resumed.

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
