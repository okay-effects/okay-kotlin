package okay.kotlin.examples

import okay.kotlin.Effect

/** the effects of a shop: marked, so their interception is generated */
@Effect
interface Catalog {
    suspend fun price(item: String): Int?
    suspend fun search(vararg words: String): List<String>
    /** not suspend: passed on as it is */
    fun currency(): String
    val name: String
}

@Effect
interface Orders<Id : Comparable<Id>, in Line> {
    suspend fun place(lines: List<Line>): Id
    suspend fun cancel(id: Id, reason: String? = null): Boolean
    suspend fun <R> query(id: Id, view: suspend (Id) -> R): R
    suspend fun reject(why: String): Nothing
    /** two bounds: a `where` clause */
    suspend fun <T> label(t: T): String where T : CharSequence, T : Comparable<T>
    /** a default: intercepted too */
    suspend fun placeOne(line: Line): Id = place(listOf(line))
}

/** inherits all of `Catalog`'s, adds its own */
@Effect
interface Counter : Catalog {
    suspend fun count(): Int
    var limit: Int
}
