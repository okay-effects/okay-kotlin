package okay.kotlin

/**
 * A CHOICE AMONG VALUES: its handlers resume the rest once for each value — MULTI-SHOT, the rest copied
 * ([Resume.copy]): on the JVM and JavaScript. A handler that answers in place inside the choice — `state`, `writer` —
 * is one for every way, its state shared; a state of each way is a handler that takes the rest, or a local of the
 * body, a value: a mutable object on the heap is one for every way.
 */
interface Choose {
    suspend fun <X> among(xs: List<X>): X
}

context(choose: Choose) suspend fun <X> among(xs: List<X>): X = choose.among(xs)
context(choose: Choose) suspend fun flip(): Boolean = choose.among(listOf(true, false))
/** no value: this way ends here */
context(choose: Choose) suspend fun none(): Nothing = choose.among(emptyList())

/** every value of every way the choices can go, in order */
suspend fun <A> all(body: suspend context(Choose) () -> A): List<A> = handle { prompt ->
    val choose = object : Choose {
        override suspend fun <X> among(xs: List<X>): X = prompt.shift { k ->
            if (xs.isEmpty()) k.drop()
            val found = ArrayList<A>()
            xs.forEachIndexed { i, x -> found += (if (i == xs.lastIndex) k else k.copy())(x) }
            found
        }
    }
    listOf(context(choose) { body() })
}

/** the first way the choices go to a value — backtracking, the ways after it not tried; none, `null` */
suspend fun <A : Any> first(body: suspend context(Choose) () -> A): A? = handle { prompt ->
    val choose = object : Choose {
        override suspend fun <X> among(xs: List<X>): X = prompt.shift { k ->
            var found: A? = null
            for ((i, x) in xs.withIndex()) {
                val last = i == xs.lastIndex
                found = (if (last) k else k.copy())(x)
                if (found != null) {
                    if (!last) k.drop()
                    break
                }
            }
            if (xs.isEmpty()) k.drop()
            found
        }
    }
    context(choose) { body() }
}
