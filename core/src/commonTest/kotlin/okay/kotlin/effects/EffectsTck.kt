package okay.kotlin.effects

import okay.kotlin.Either
import kotlin.test.Test
import kotlin.test.assertEquals

/** WHAT EVERY ENGINE DOES: the same programs, the same handlers, the same answers */
abstract class EffectsTck<F>(private val fx: Effects<F>) {
    @Test
    fun stateAndFailureInBothOrders() = with(fx) {
        // the state outside the failure: kept up to it
        val outside: Pair<Int, Either<String, List<Int>>> =
            run(state(0) { st -> either { context(lift(st)) { ticks(5) } } })
        assertEquals(3 to Either.Left("big 3"), outside)
        // inside: dropped with it
        val inside: Either<String, Pair<Int, List<Int>>> =
            run(either { fail -> state(0) { context(lift(fail)) { ticks(5) } } })
        assertEquals(Either.Left("big 3"), inside)
        val fine: Either<String, Pair<Int, List<Int>>> =
            run(either { fail -> state(0) { context(lift(fail)) { ticks(2) } } })
        assertEquals(Either.Right(2 to listOf(0, 1)), fine)
    }

    @Test
    fun theReadmesExample() = with(fx) {
        // one tick from 0: the value 0, the state 1
        val r = run(either<_, String, _, _> { fail -> state(0) { context(lift(fail)) { tick() } } })
        assertEquals(Either.Right(1 to 0), r)
    }

    @Test
    fun theNearestHandlerTakesTheOperation() = with(fx) {
        // dynamic: the place says which handler of the row, at run time; the last is the nearest
        val near: Int = run(reader(1) { reader(2) { ask<F, Int, _>() } })
        assertEquals(2, near)
        val far: Int = run(reader(1) { r1 -> reader(2) { context(lift(r1)) { ask<F, Int, _>() } } })
        assertEquals(1, far)
    }

    @Test
    fun aMillionOperationsInConstantStack() = with(fx) {
        assertEquals(1_000_000 to 1_000_000, run(state(0) { count(1_000_000) }))
    }

    @Test
    fun writerAndTake() = with(fx) {
        val written: Pair<List<String>, Int> =
            run(writer { tell<F, String, _>("a").then(tell("b")).with(7) })
        assertEquals(listOf("a", "b") to 7, written)
        fun upTo(n: Int, out: In<Emit<Int>, With<Emit<Int>, Nil>>): Prog<F, With<Emit<Int>, Nil>, String> =
            context(out) {
                loop(1) { i -> if (i > n) pure(Next.Done("done")) else emit<F, Int, _>(i).with(Next.Again(i + 1)) }
            }
        val two: Pair<List<Int>, String?> = run(take(2) { upTo(5, it) })
        assertEquals(listOf(1, 2) to null, two)
        val all: Pair<List<Int>, String?> = run(take(9) { upTo(3, it) })
        assertEquals(listOf(1, 2, 3) to "done", all)
    }

    @Test
    fun aGeneralHandlerSeesWhatTheRestGives() = with(fx) {
        // the clause after the rest: k's value, doubled
        val doubled = object : General<F, Reader<Int>, Nil, Int, Int>() {
            override fun <X> op(op: Op<Reader<Int>, X>, k: (X) -> Prog<F, Nil, Int>): Prog<F, Nil, Int> = when (op) {
                is Reader.Ask -> k(op.answer(10)).map { it * 2 }
                else -> foreign(op)
            }
            override fun value(a: Int): Prog<F, Nil, Int> = pure(a + 1)
        }
        assertEquals(22, run(handled(doubled) { ask<F, Int, _>() }))
    }
}

/** the choices: multi-shot, `k` resumed for every value */
abstract class ChoiceTck<F>(private val fx: Effects<F>) {
    @Test
    fun everyTriple() = with(fx) {
        assertEquals(listOf(Triple(3, 4, 5), Triple(5, 12, 13), Triple(6, 8, 10), Triple(8, 15, 17),
            Triple(9, 12, 15), Triple(12, 16, 20)), run(all { triple(20) }))
        assertEquals(Triple(3, 4, 5), run(first { triple(20) }))
    }

    @Test
    fun choicesUnderAState() = with(fx) {
        // the state outside the choice: one for every way, counted across them
        val r: Pair<Int, List<Boolean>> = run(state(0) { st ->
            all { context(lift(st)) { flip<F, _>().flatMap { b -> modify<F, Int, _> { it + 1 }.with(b) } } }
        })
        assertEquals(2 to listOf(true, false), r)
    }
}

class MachineTck : EffectsTck<Machine>(Machine)
class MachineChoiceTck : ChoiceTck<Machine>(Machine)
class DirectTck : EffectsTck<Direct>(Direct)
