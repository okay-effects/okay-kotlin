package okay.kotlin.effects

import kotlin.test.Test
import kotlin.test.assertEquals

/** renamings and interception: every engine the same */
abstract class RenamingTck<F>(private val fx: Effects<F>) {
    @Test
    fun localSeesTheEnvironmentChangedTheRestAsItWas() = with(fx) {
        val r: Triple<Int, Int, Int> = run(reader(10) {
            ask<F, Int, _>().flatMap { a ->
                local({ e: Int -> e * 2 }, ask<F, Int, _>()).flatMap { b ->
                    ask<F, Int, _>().map { c -> Triple(a, b, c) }
                }
            }
        })
        assertEquals(Triple(10, 20, 10), r)
    }

    @Test
    fun listenAndCensor() = with(fx) {
        val listened: Pair<List<String>, Pair<List<String>, Int>> = run(writer {
            listen(tell<F, String, _>("a").then(tell("b")).with(7))
        })
        assertEquals(listOf("a", "b") to (listOf("a", "b") to 7), listened)
        val censored: Pair<List<String>, Int> = run(writer {
            censor({ ws: List<String> -> ws.map { it.uppercase() } }, tell<F, String, _>("a").then(tell("b")).with(7))
        })
        assertEquals(listOf("A", "B") to 7, censored)
    }

    @Test
    fun observeEachOperationIntoAWriter() = with(fx) {
        // each State operation told to the Writer outside, then performed
        val r: Pair<List<String>, Pair<Int, Int>> = run(writer { w ->
            state(5) { st ->
                context(lift(w)) {
                    observe(st, get<F, Int, _>().flatMap { n -> put<F, Int, _>(n + 1).with(n) }) { op ->
                        tell(if (op is State.Get<*, *>) "get" else "put")
                    }
                }
            }
        })
        assertEquals(listOf("get", "put") to (6 to 5), r)
    }

    @Test
    fun aroundChangesWhatAGetGives() = with(fx) {
        val doubling = object : Around<F, State<Int>, With<State<Int>, Nil>> {
            override fun <X> around(op: Op<State<Int>, X>, proceed: Prog<F, With<State<Int>, Nil>, X>):
                Prog<F, With<State<Int>, Nil>, X> = when (op) {
                // the state's own, asked of the handler outside, doubled
                is State.Get ->
                    fx.perform(Place.Last<State<Int>, Nil>(), State.Get<Int, Int> { it }).map { op.answer(it * 2) }
                else -> proceed
            }
        }
        assertEquals(5 to 10, run(state(5) { st -> around(st, get<F, Int, _>(), doubling) }))
    }

    @Test
    fun aProgramOfAnotherRowLiftedPastAHandlerItDoesNotSee() = with(fx) {
        val outer: Prog<F, With<Reader<Int>, Nil>, Int> = context(Place.Last<Reader<Int>, Nil>()) { ask() }
        // inside a reader of 2, lifted: it asks the reader of 1, past the one it does not see
        assertEquals(1, run(reader(1) { reader(2) { outer.lifted() } }))
    }
}

class MachineRenamingTck : RenamingTck<Machine>(Machine)
class DirectRenamingTck : RenamingTck<Direct>(Direct)
