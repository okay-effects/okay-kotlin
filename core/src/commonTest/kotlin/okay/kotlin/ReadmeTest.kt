package okay.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals

// THE README'S EXAMPLE, compiled and run: keep the two the same

/** the domain: what the business needs */
interface Accounts {
    suspend fun balance(account: String): Int
    suspend fun deposit(account: String, amount: Int)
    suspend fun withdraw(account: String, amount: Int)
}

/** the program: the domain and a failure, nothing else */
context(accounts: Accounts, fail: Fail<String>)
suspend fun transfer(from: String, to: String, amount: Int) {
    ensure(accounts.balance(from) >= amount) { "$from has less than $amount" }
    accounts.withdraw(from, amount)
    accounts.deposit(to, amount)
}

/** the domain on a store: a handler, a program of Accounts run as a program of Store */
context(store: Store<String, Int>)
suspend fun <A> onStore(body: suspend context(Accounts) () -> A): A = context(object : Accounts {
    override suspend fun balance(account: String) = store.load(account) ?: 0
    override suspend fun deposit(account: String, amount: Int) = store.save(account, balance(account) + amount)
    override suspend fun withdraw(account: String, amount: Int) = store.save(account, balance(account) - amount)
}) { body() }

/** each operation journaled first, then passed on to the handler further out: by delegation */
context(accounts: Accounts)
suspend fun <A> journaled(journal: MutableList<String>, body: suspend context(Accounts) () -> A): A =
    context(object : Accounts by accounts {
        override suspend fun deposit(account: String, amount: Int) {
            journal += "deposit $account $amount"
            accounts.deposit(account, amount)
        }

        override suspend fun withdraw(account: String, amount: Int) {
            journal += "withdraw $account $amount"
            accounts.withdraw(account, amount)
        }
    }) { body() }

class ReadmeTest {
    @Test
    fun theStack() {
        val journal = mutableListOf<String>()
        val (db, results) = runEffects {
            inMemory(mapOf("ann" to 100, "bob" to 0)) {
                onStore {
                    journaled(journal) {
                        either<String, _> { transfer("ann", "bob", 70) } to
                            either<String, _> { transfer("ann", "bob", 70) }
                    }
                }
            }
        }
        assertEquals(Either.Right(Unit) to Either.Left("ann has less than 70"), results)
        assertEquals(mapOf("ann" to 30, "bob" to 70), db)
        assertEquals(listOf("withdraw ann 70", "deposit bob 70"), journal)
    }
}
