package okay.kotlin.effects

import okay.kotlin.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** the paper's EFF state, Fig. 10, as data: H_ST = {return x ↦ λs.return x} ⊎ {get _ k ↦ λs.k! s s} ⊎ {put s' k ↦
 * λ_.k! () s'} — the handler's value a function of the state, each clause one that resumes k when applied */
context(fx: Effects<F>)
fun <F, A> runStateEff(body: Body<F, State<Boolean>, Nil, A>): Prog<F, Nil, (Boolean) -> Prog<F, Nil, A>> =
    handled(object : General<F, State<Boolean>, Nil, A, (Boolean) -> Prog<F, Nil, A>>() {
        override fun <X> op(op: Op<State<Boolean>, X>, k: (X) -> Prog<F, Nil, (Boolean) -> Prog<F, Nil, A>>):
            Prog<F, Nil, (Boolean) -> Prog<F, Nil, A>> = when (op) {
            is State.Get -> fx.pure { s -> k(op.answer(s)).flatMap { it(s) } }
            is State.Put -> fx.pure { _ -> k(op.answer(Unit)).flatMap { it(op.value) } }
            else -> foreign(op)
        }
        override fun value(a: A): Prog<F, Nil, (Boolean) -> Prog<F, Nil, A>> = fx.pure { _ -> fx.pure(a) }
    }, body)

/** toggle = {x ← get(); y ← not! x; put y; x} */
context(fx: Effects<F>, st: In<State<Boolean>, R>)
fun <F, R> toggle(): Prog<F, R, Boolean> = get<F, Boolean, R>().flatMap { x -> put<F, Boolean, R>(!x).with(x) }

abstract class SpecTck<F>(private val fx: Effects<F>) {
    @Test
    fun effExample32() = with(fx) {
        // runState! toggle True ⇝* True
        assertEquals(true, run(runStateEff { toggle<F, With<State<Boolean>, Nil>>() }.flatMap { it(true) }))
        val three = runStateEff { toggle<F, With<State<Boolean>, Nil>>().then(toggle()).then(toggle()) }
        // true → false → true → false: the third reads true
        assertEquals(true, run(three.flatMap { it(true) }))
    }
}

class MachineSpecTck : SpecTck<Machine>(Machine)
class DirectSpecTck : SpecTck<Direct>(Direct)

/** the machine's continuations are data: `k` copied across any handler — a choice inside a failure's body */
class MachineOnlyTest {
    @Test
    fun choicesAcrossAFailure() = with(Machine) {
        val r: List<Either<String, Boolean>> =
            run(all { c -> either { context(lift(c)) { flip<Machine, With<Fail<String>, With<Choose, Nil>>>() } } })
        assertEquals(listOf(Either.Right(true), Either.Right(false)), r)
    }

    @Test
    fun directResumesOnceAcrossAPromptOfItsOwn() = with(Direct) {
        // Direct copies a continuation only where no handler that takes the rest is between: there, one-shot
        assertFailsWith<IllegalStateException> {
            run(all { c -> either<Direct, String, With<Choose, Nil>, Boolean> {
                context(lift(c)) { flip<Direct, With<Fail<String>, With<Choose, Nil>>>() } } })
        }
        Unit
    }
}
