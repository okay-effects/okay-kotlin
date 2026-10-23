// the benchmark, JMH on the JVM: `./gradlew :bench:jmh`
plugins {
    kotlin("jvm")
    id("me.champeau.jmh") version "0.7.3"
}

kotlin { jvmToolchain(21) }

dependencies { jmhImplementation(project(":core")) }

jmh {
    fork.set(2)
    warmupIterations.set(5)
    iterations.set(5)
    warmup.set("1s")
    timeOnIteration.set("1s")
    profilers.add("gc")
    resultFormat.set("TEXT")
}
