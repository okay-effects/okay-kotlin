package okay.kotlin

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted

// MULTI-SHOT ON JAVASCRIPT: a suspend function's frame is an object, its locals and its step properties of it, the
// frame it returns to one of them. Which one is found once, by a frame made to return to a known continuation — the
// frame's own property, not a variable it captured (those are named `$...`).

private val keys: (Any) -> Array<String> = js("Object.keys")

private val completionKey: String by lazy {
    val known = Continuation<Unit>(EmptyCoroutineContext) {}
    val probe = suspend {}.createCoroutineUnintercepted(known)
    keys(probe).single { !it.startsWith("$") && probe.asDynamic()[it] === known }
}

/** the frame's cached intercepted continuation: a copy makes its own */
private val interceptedKeys: List<String> by lazy {
    val probe = suspend {}.createCoroutineUnintercepted(Continuation<Unit>(EmptyCoroutineContext) {})
    keys(probe).filter { it.startsWith("_intercepted") }
}

internal actual fun <X> copyFrames(k: Continuation<X>, root: Continuation<*>): Continuation<X> = copy(k, root)

private fun <C : Continuation<*>> copy(frame: C, root: Continuation<*>): C {
    val next: dynamic = frame.asDynamic()[completionKey]
    check(next != null && next != undefined) { "a continuation cannot be copied: not a suspend function's frame" }
    val copy: C = shallow(frame)
    val nextFrame: Continuation<*> = next
    copy.asDynamic()[completionKey] = if (nextFrame === root) nextFrame else copy(nextFrame, root)
    for (key in interceptedKeys) copy.asDynamic()[key] = null
    // a loop's iterator is the frame's own — a range's too, here: each copy goes on from where the loop was
    for (key in keys(copy)) {
        val value: Any? = copy.asDynamic()[key]
        if (value is Iterator<*>) copy.asDynamic()[key] = shallow(value)
    }
    return copy
}

private fun <T> shallow(o: T): T = js("Object.assign(Object.create(Object.getPrototypeOf(o)), o)")
