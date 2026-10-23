// the effects: common code alone, no dependency — the continuations are the standard library's own
plugins {
    kotlin("multiplatform")
    `maven-publish`
}

/** the JVM always; JavaScript and native when asked: `./gradlew -Pplatforms=jvm,js,native check` */
val platforms = (findProperty("platforms") as String? ?: "jvm").split(',').map { it.trim() }.toSet() + "jvm"

kotlin {
    jvmToolchain(21)
    jvm()
    if ("js" in platforms) js { nodejs() }
    if ("native" in platforms) { macosArm64(); linuxX64() }

    compilerOptions {
        allWarningsAsErrors.set(true)
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") }
    }
}

tasks.withType<Test> { jvmArgs("-Xss512k") }
