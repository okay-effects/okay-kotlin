package okay.kotlin

import kotlin.coroutines.Continuation

internal actual fun abandoned(): Abandoned = Abandoned()

/** native code has no way to copy a frame: its fields are not reachable */
internal actual fun <X> copyFrames(k: Continuation<X>, root: Continuation<*>): Continuation<X> =
    throw UnsupportedOperationException("a continuation is copied on the JVM and JavaScript, not native")
