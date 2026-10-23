package okay.kotlin.effects

// PROGRAMS WRITTEN ONCE: generic in the engine `F` and in the row `R`, their effects their places

/** the state's value, then one more; past 3, a failure */
context(fx: Effects<F>, st: In<State<Int>, R>, fail: In<Fail<String>, R>)
fun <F, R> tick(): Prog<F, R, Int> = get<F, Int, R>().flatMap { n ->
    if (n >= 3) raise("big $n") else put<F, Int, R>(n + 1).with(n)
}

/** `n` ticks */
context(fx: Effects<F>, st: In<State<Int>, R>, fail: In<Fail<String>, R>)
fun <F, R> ticks(n: Int): Prog<F, R, List<Int>> =
    if (n == 0) pure(emptyList()) else tick<F, R>().flatMap { a -> ticks<F, R>(n - 1).map { listOf(a) + it } }

/** a million gets and puts, in a loop of the program */
context(fx: Effects<F>, st: In<State<Int>, R>)
fun <F, R> count(n: Int): Prog<F, R, Int> = loop(0) { i ->
    if (i == n) get<F, Int, R>().map { Next.Done(it) }
    else get<F, Int, R>().flatMap { s -> put<F, Int, R>(s + 1).with(Next.Again(i + 1)) }
}

context(fx: Effects<F>, c: In<Choose, R>)
fun <F, R> triple(n: Int): Prog<F, R, Triple<Int, Int, Int>> =
    among<F, Int, R>((1..n).toList()).flatMap { a ->
        among<F, Int, R>((a..n).toList()).flatMap { b ->
            among<F, Int, R>((b..n).toList()).flatMap { c ->
                if (a * a + b * b == c * c) pure(Triple(a, b, c)) else none()
            }
        }
    }
