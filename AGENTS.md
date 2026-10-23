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
- Interception is a wrapper of a capability: `intercepted(interceptor)`, each suspend function an `Operation` through
  `Interceptor.around`. The standard effects' wrappers are hand-written in `Intercepted.kt` and must look exactly like
  what `ksp/` generates for `@Effect`; a new standard effect gets its wrapper and a line in `InterceptTest`.
- THE SPEC is Forster, Kammar, Lindley, Pretnar, ICFP 2017 (EFF, MON, DEL); `SpecTest` its examples and rules,
  `docs/design.md#against-the-literature` the comparison. Deliberate differences: handlers chosen lexically
  (capabilities), not dynamically; multi-shot only where a frame can be copied (JVM, JS). `k` is a value: never
  assume it is resumed inside its clause.
- Multi-shot by COPYING the rest's frames (`Resume.copy`, `Frames.jvm.kt`, `Frames.js.kt`), never by replay (the
  user's rule). Native has none. A copy is taken before `k` is resumed; a delimiting handler between refuses it.
- No casts, no `!!` in the library. Warnings are errors.
- A handler's type parameters cannot be inferred from what its body asks of the context: tests and docs write
  `either<String, _> { ... }`. Do not "fix" it with overloads that guess.
- Lines at most 120 wide. Commits: plain messages, no attribution trailers.
- `./gradlew check` (JVM: core, the generator on `examples`); `./gradlew -Pplatforms=jvm,js,native :core:allTests`;
  `./gradlew :bench:jmh`. The README's example is `ReadmeTest`: change both together.
