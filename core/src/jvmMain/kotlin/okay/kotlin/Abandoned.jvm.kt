package okay.kotlin

/** no stack trace: filling it would cost more than the rest of a failure together */
private class Stackless : Abandoned() {
    override fun fillInStackTrace(): Throwable = this
}

internal actual fun abandoned(): Abandoned = Stackless()
