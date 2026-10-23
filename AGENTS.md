# AGENTS.md — okay-kotlin

Read before anything else, then `docs/design.md`.

- Kotlin 2.4 (context parameters, stable), multiplatform: JVM always, JS and native when asked. Package `okay.kotlin`,
  artifact `okay-kotlin`. The core has NO dependency: the continuations are the standard library's
  (`kotlin.coroutines.intrinsics`); kotlinx.coroutines is a test dependency of the JVM, for the interop tests only.
- IDIOMATIC KOTLIN FIRST: an effect is an interface of suspend functions; a program's effects are its context
  parameters; a handler is a function that implements the interface and runs the body with it (`context(impl) {
  body() }`); wrapping a handler is delegation (`object : Store<K, V> by store`). No effect is data, no operation a
  class, unless an interface cannot say it.
- An effect answered in place is a plain implementation — a virtual call. Only control takes the rest of the body:
  `handle` and `Prompt.shift` / `abort`. Do not make an answered-in-place effect shift: it is ~20× slower.
- One-shot continuations, by the platform and by the user's rule: NO REPLAY to fake multi-shot. So no `Choose.all`.
- No casts, no `!!` in the library. Warnings are errors.
- A handler's type parameters cannot be inferred from what its body asks of the context: tests and docs write
  `either<String, _> { ... }`. Do not "fix" it with overloads that guess.
- Lines at most 120 wide. Commits: plain messages, no attribution trailers.
- `./gradlew check` (JVM); `./gradlew -Pplatforms=jvm,js,native :core:allTests`; `./gradlew :bench:jmh`. The README's
  example is `ReadmeTest`: change both together.
