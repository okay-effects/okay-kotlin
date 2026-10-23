package okay.kotlin.bench

import okay.kotlin.effects.Choose
import okay.kotlin.effects.Direct
import okay.kotlin.effects.Effects
import okay.kotlin.effects.In
import okay.kotlin.effects.Machine
import okay.kotlin.effects.Next
import okay.kotlin.effects.Prog
import okay.kotlin.effects.State
import okay.kotlin.effects.all
import okay.kotlin.effects.among
import okay.kotlin.effects.flatMap
import okay.kotlin.effects.get
import okay.kotlin.effects.loop
import okay.kotlin.effects.map
import okay.kotlin.effects.none
import okay.kotlin.effects.pure
import okay.kotlin.effects.put
import okay.kotlin.effects.run
import okay.kotlin.effects.state
import okay.kotlin.effects.with
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.State as JmhState

context(fx: Effects<F>, st: In<State<Int>, R>)
fun <F, R> countData(n: Int): Prog<F, R, Int> = loop(0) { i ->
    if (i == n) get<F, Int, R>().map { Next.Done(it) }
    else get<F, Int, R>().flatMap { s -> put<F, Int, R>(s + 1).with(Next.Again(i + 1)) }
}

context(fx: Effects<F>, c: In<Choose, R>)
fun <F, R> tripleData(n: Int): Prog<F, R, Int> =
    among<F, Int, R>((1..n).toList()).flatMap { a ->
        among<F, Int, R>((a..n).toList()).flatMap { b ->
            among<F, Int, R>((b..n).toList()).flatMap { c -> if (a * a + b * b == c * c) pure(c) else none() }
        }
    }

fun <F> stateData(fx: Effects<F>, n: Int): Int = with(fx) { run(state(0) { countData(n) }).first }
fun <F> triplesData(fx: Effects<F>, n: Int): Int = with(fx) { run(all { tripleData(n) }).size }

/** the same programs as data, on each engine: 1000 gets and puts; every Pythagorean triple to 30 */
@JmhState(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class DataBench {
    @Benchmark fun stateMachine(): Int = stateData(Machine, 1000)
    @Benchmark fun stateDirect(): Int = stateData(Direct, 1000)
    @Benchmark fun triplesMachine(): Int = triplesData(Machine, 30)
    @Benchmark fun triplesDirect(): Int = triplesData(Direct, 30)
}
