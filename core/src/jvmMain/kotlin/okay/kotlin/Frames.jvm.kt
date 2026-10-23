package okay.kotlin

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

// MULTI-SHOT ON THE JVM: a suspend function's frame is an object of the class the compiler made for it — its locals
// fields, its step a field, the frame it returns to `completion`. A rest's frames, copied field by field up to its
// handler's root, are a rest of their own.

private val frameClass = Class.forName("kotlin.coroutines.jvm.internal.BaseContinuationImpl")
private val completion: Field = frameClass.getDeclaredField("completion").apply { isAccessible = true }
private val intercepted: Field =
    Class.forName("kotlin.coroutines.jvm.internal.ContinuationImpl").getDeclaredField("intercepted")
        .apply { isAccessible = true }
private val factory = sun.reflect.ReflectionFactory.getReflectionFactory()
private val objectConstructor = Any::class.java.getDeclaredConstructor()

/** a class's fields, its superclasses' with them, each copied; `null` if the module system closes any of them */
private val fields = object : ClassValue<List<Field>?>() {
    override fun computeValue(type: Class<*>): List<Field>? =
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && it != completion && it != intercepted }.toList()
            .takeIf { all -> all.all { it.trySetAccessible() } }
}

internal actual fun <X> copyFrames(k: Continuation<X>, root: Continuation<*>): Continuation<X> = copy(k, root)

private fun <C : Continuation<*>> copy(frame: C, root: Continuation<*>): C {
    val type = frame.javaClass
    check(frameClass.isInstance(frame)) { "a continuation of ${type.name} cannot be copied: not a suspend function's" }
    val copy = shallow(frame) ?: error("a continuation of ${type.name} cannot be copied: its fields are closed")
    // a loop's iterator is the frame's own: each copy goes on from where the loop was. The JDK's collections'
    // iterators are closed by the module system unless opened (`--add-opens java.base/java.util=ALL-UNNAMED`):
    // closed, a `for` over one is shared by the copies
    for (f in fields.get(type).orEmpty()) {
        val value = f.get(frame)
        if (value is Iterator<*>) shallow(value)?.let { f.set(copy, it) }
    }
    val next = Continuation::class.java.cast(completion.get(frame))
    completion.set(copy, if (next === root) next else copy(next, root))
    return copy
}

/** a class's instances made without a constructor of theirs: made once a class */
private val blank = object : ClassValue<java.lang.reflect.Constructor<*>>() {
    override fun computeValue(type: Class<*>): java.lang.reflect.Constructor<*> =
        factory.newConstructorForSerialization(type, objectConstructor)
}

/** a copy of `o`, field by field, made without its constructor; `null` if its fields are closed */
private fun <T : Any> shallow(o: T): T? {
    val type = o.javaClass
    val all = fields.get(type) ?: return null
    val copy = type.cast(blank.get(type).newInstance())
    for (f in all) f.set(copy, f.get(o))
    return copy
}
