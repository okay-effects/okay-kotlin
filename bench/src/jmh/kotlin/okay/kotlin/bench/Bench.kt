package okay.kotlin.bench

import okay.kotlin.Either
import okay.kotlin.Reader
import okay.kotlin.State
import okay.kotlin.ask
import okay.kotlin.either
import okay.kotlin.get
import okay.kotlin.handle
import okay.kotlin.Choose
import okay.kotlin.all
import okay.kotlin.among
import okay.kotlin.none
import okay.kotlin.Interceptor
import okay.kotlin.Operation
import okay.kotlin.intercepted
import okay.kotlin.observer
import okay.kotlin.optional
import okay.kotlin.put
import okay.kotlin.raise
import okay.kotlin.reader
import okay.kotlin.runEffects
import okay.kotlin.shift
import okay.kotlin.state
import okay.kotlin.writer
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State as JmhState
import java.util.concurrent.TimeUnit

/** an effect answered by a general handler: the rest taken and resumed, each time */
fun interface Ask {
    suspend fun ask(): Int
}

suspend fun <A> answering(n: Int, body: suspend context(Ask) () -> A): A = handle { prompt ->
    context(Ask { prompt.shift { k -> k(n) } }) { body() }
}

context(r: Reader<Int>)
suspend fun asks(n: Int): Int {
    var s = 0
    repeat(n) { s += r.ask() }
    return s
}

context(a: Ask)
suspend fun shifts(n: Int): Int {
    var s = 0
    repeat(n) { s += a.ask() }
    return s
}

context(s: State<Int>)
suspend fun counting(n: Int) = repeat(n) { s.put(s.get() + 1) }

val passing = object : Interceptor {
    override suspend fun <X> around(op: Operation, proceed: suspend () -> X): X = proceed()
}

context(_: Choose)
suspend fun triple(n: Int): Int {
    val a = among((1..n).toList())
    val b = among((a..n).toList())
    val c = among((b..n).toList())
    if (a * a + b * b != c * c) none()
    return c
}

/** n = 1000 operations a run, as okay-scala's and okay-simple's benchmarks */
@JmhState(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class Bench {
    val n = 1000

    /** a state's get and put, its handler a plain implementation */
    @Benchmark
    fun stateLoop(): Int = runEffects { state(0) { counting(n) }.first }

    /** asks of a reader under four handlers more: answered in place, nothing crossed */
    @Benchmark
    fun askAcross4(): Int = runEffects {
        reader(1) {
            state(0) {
                writer<String, _> { either<String, _> { optional { asks(n) } } }
            }
        }.second.second.let { (it as Either.Right).value!! }
    }

    /** the rest taken and resumed by a general handler, right outside */
    @Benchmark
    fun shiftNear(): Int = runEffects { answering(1) { shifts(n) } }

    /** the same across three handlers that each delimit their body: relayed by each */
    @Benchmark
    fun shiftAcross3(): Int = runEffects {
        answering(1) { (either<String, _> { optional { either<Int, _> { shifts(n) } } } as Either.Right).value!! }
            .let { (it as Either.Right).value }
    }

    /** a failure: the rest dropped, unwound, n times */
    @Benchmark
    fun failures(): Int = runEffects {
        var s = 0
        repeat(n) { s += (either<String, Int> { raise("no") } as Either.Left).error.length }
        s
    }

    /** the state's get and put, each through an interceptor that passes it on */
    @Benchmark
    fun stateIntercepted(): Int = runEffects {
        state(0) { context(contextOf<State<Int>>().intercepted(passing)) { counting(n) } }.first
    }

    /** the same, each seen by an observer with its answer */
    @Benchmark
    fun stateObserved(): Int = runEffects {
        var seen = 0
        val seeing = observer { _, _ -> seen++ }
        state(0) { context(contextOf<State<Int>>().intercepted(seeing)) { counting(n) } }.first + seen
    }

    /** multi-shot: every Pythagorean triple to 30, each choice's rest copied for every value but the last */
    @Benchmark
    fun triples(): Int = runEffects { all { triple(30) } }.size

    /** a handler that delimits its body, entered and left with no operation, n times */
    @Benchmark
    fun handles(): Int = runEffects {
        var s = 0
        repeat(n) { s += (either<String, Int> { 1 } as Either.Right).value }
        s
    }

    /** a state through get and put, from a plain loop, no effect: the floor */
    @Benchmark
    fun plainLoop(): Int {
        var s = 0
        repeat(n) { s += 1 }
        return s
    }

}
