package okay.kotlin

// THE SERVICES a program asks for and a test answers: numbers, randomness, storage, time, a log. Each a plain
// interface, so production and test handlers are two implementations, and a third wraps either by delegation.

/** numbers never given before */
fun interface Fresh {
    suspend fun next(): Long
}

context(fresh: Fresh) suspend fun fresh(): Long = fresh.next()

/** the body, its numbers from `start` on */
suspend fun <A> numbered(start: Long = 0, body: suspend context(Fresh) () -> A): A {
    var n = start
    return context(Fresh { n++ }) { body() }
}

/** random numbers */
interface Randomness {
    suspend fun nextLong(): Long
    /** in `[0, bound)`, `bound` positive */
    suspend fun nextInt(bound: Int): Int = nextLong().mod(bound.toLong()).toInt()
    /** in `[0, 1)` */
    suspend fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()
}

/** the body with numbers from `seed`: the same each run (splitmix64) */
suspend fun <A> seeded(seed: Long, body: suspend context(Randomness) () -> A): A {
    var s = seed
    val numbers = object : Randomness {
        override suspend fun nextLong(): Long {
            s += -7046029254386353131L
            var z = s
            z = (z xor (z ushr 30)) * -4658895280553007687L
            z = (z xor (z ushr 27)) * -7723592293110705685L
            return z xor (z ushr 31)
        }
    }
    return context(numbers) { body() }
}

/** the body with the numbers of `random` */
suspend fun <A> randomly(
    random: kotlin.random.Random = kotlin.random.Random,
    body: suspend context(Randomness) () -> A,
): A =
    context(object : Randomness {
        override suspend fun nextLong() = random.nextLong()
        override suspend fun nextInt(bound: Int) = random.nextInt(bound)
        override suspend fun nextDouble() = random.nextDouble()
    }) { body() }

/** values of type `V` kept by keys of type `K`: a database, a cache, a map */
interface Store<K, V> {
    suspend fun load(key: K): V?
    suspend fun save(key: K, value: V)
    suspend fun remove(key: K)
}

/** the body on a map from `initial`: what it holds at the end, and the body's value */
suspend fun <K, V, A> inMemory(initial: Map<K, V> = emptyMap(), body: suspend context(Store<K, V>) () -> A):
    Pair<Map<K, V>, A> {
    val map = LinkedHashMap(initial)
    val a = context(object : Store<K, V> {
        override suspend fun load(key: K) = map[key]
        override suspend fun save(key: K, value: V) {
            map[key] = value
        }
        override suspend fun remove(key: K) {
            map.remove(key)
        }
    }) { body() }
    return map to a
}

/** time, in milliseconds */
interface Clock {
    suspend fun now(): Long
    suspend fun sleep(millis: Long)
}

/** the body on a clock of its own from `start`, a sleep moving it on at once: the time it ends at, and its value */
suspend fun <A> virtualTime(start: Long = 0, body: suspend context(Clock) () -> A): Pair<Long, A> {
    var t = start
    val a = context(object : Clock {
        override suspend fun now() = t
        override suspend fun sleep(millis: Long) {
            t += millis
        }
    }) { body() }
    return t to a
}

enum class Level { Debug, Info, Warn, Error }

/** messages of a level */
fun interface Log {
    suspend fun log(level: Level, message: String)
}

context(log: Log) suspend fun debug(message: String) = log.log(Level.Debug, message)
context(log: Log) suspend fun info(message: String) = log.log(Level.Info, message)
context(log: Log) suspend fun warn(message: String) = log.log(Level.Warn, message)
context(log: Log) suspend fun error(message: String) = log.log(Level.Error, message)

/** the messages from `least` up, kept, and the body's value */
suspend fun <A> logged(
    least: Level = Level.Debug,
    body: suspend context(Log) () -> A,
): Pair<List<Pair<Level, String>>, A> {
    val kept = ArrayList<Pair<Level, String>>()
    val a = context(Log { level, m -> if (level >= least) kept.add(level to m) }) { body() }
    return kept to a
}

/** each message from `least` up, to `sink` */
suspend fun <A> logTo(sink: (Level, String) -> Unit, least: Level = Level.Info, body: suspend context(Log) () -> A): A =
    context(Log { level, m -> if (level >= least) sink(level, m) }) { body() }
