package okay.kotlin

// THE STANDARD EFFECTS, INTERCEPTED: each a wrapper whose every operation goes to the interceptor first, then on to
// the capability it wraps. What `@Effect` generates for an interface of yours looks the same.

fun <S> State<S>.intercepted(interceptor: Interceptor): State<S> = object : State<S> {
    val inner = this@intercepted
    override suspend fun get(): S = interceptor.around(Operation("State", "get", emptyList())) { inner.get() }
    override suspend fun put(s: S) = interceptor.around(Operation("State", "put", listOf(s))) { inner.put(s) }
}

fun <E> Reader<E>.intercepted(interceptor: Interceptor): Reader<E> =
    Reader { interceptor.around(Operation("Reader", "ask", emptyList())) { ask() } }

fun <W> Writer<W>.intercepted(interceptor: Interceptor): Writer<W> =
    Writer { w -> interceptor.around(Operation("Writer", "tell", listOf(w))) { tell(w) } }

fun <E> Fail<E>.intercepted(interceptor: Interceptor): Fail<E> =
    Fail { e -> interceptor.around(Operation("Fail", "raise", listOf(e))) { raise(e) } }

fun Abort.intercepted(interceptor: Interceptor): Abort =
    Abort { interceptor.around(Operation("Abort", "abort", emptyList())) { abort() } }

fun <O> Emit<O>.intercepted(interceptor: Interceptor): Emit<O> =
    Emit { o -> interceptor.around(Operation("Emit", "emit", listOf(o))) { emit(o) } }

fun Fresh.intercepted(interceptor: Interceptor): Fresh =
    Fresh { interceptor.around(Operation("Fresh", "next", emptyList())) { next() } }

fun Randomness.intercepted(interceptor: Interceptor): Randomness = object : Randomness {
    val inner = this@intercepted
    override suspend fun nextLong() = interceptor.around(Operation("Randomness", "nextLong", emptyList())) {
        inner.nextLong()
    }
    override suspend fun nextInt(bound: Int) = interceptor.around(Operation("Randomness", "nextInt", listOf(bound))) {
        inner.nextInt(bound)
    }
    override suspend fun nextDouble() = interceptor.around(Operation("Randomness", "nextDouble", emptyList())) {
        inner.nextDouble()
    }
}

fun <K, V> Store<K, V>.intercepted(interceptor: Interceptor): Store<K, V> = object : Store<K, V> {
    val inner = this@intercepted
    override suspend fun load(key: K) = interceptor.around(Operation("Store", "load", listOf(key))) { inner.load(key) }
    override suspend fun save(key: K, value: V) =
        interceptor.around(Operation("Store", "save", listOf(key, value))) { inner.save(key, value) }
    override suspend fun remove(key: K) =
        interceptor.around(Operation("Store", "remove", listOf(key))) { inner.remove(key) }
}

fun Clock.intercepted(interceptor: Interceptor): Clock = object : Clock {
    val inner = this@intercepted
    override suspend fun now() = interceptor.around(Operation("Clock", "now", emptyList())) { inner.now() }
    override suspend fun sleep(millis: Long) =
        interceptor.around(Operation("Clock", "sleep", listOf(millis))) { inner.sleep(millis) }
}

fun Log.intercepted(interceptor: Interceptor): Log =
    Log { level, m -> interceptor.around(Operation("Log", "log", listOf(level, m))) { log(level, m) } }
