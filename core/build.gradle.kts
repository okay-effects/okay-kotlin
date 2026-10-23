// the effects: common code alone, no dependency — the continuations are the standard library's own
plugins {
    kotlin("multiplatform")
    `maven-publish`
}

/** the JVM always; JavaScript and native when asked: `./gradlew -Pplatforms=jvm,js,native check` */
val platforms = (findProperty("platforms") as String? ?: "jvm").split(',').map { it.trim() }.toSet() + "jvm"

kotlin {
    jvmToolchain(21)
    // the default hierarchy (nativeMain for every native target), kept with a source set of our own besides
    applyDefaultHierarchyTemplate()
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
        // multi-shot: a continuation copied, on the JVM and JavaScript (native has no way to copy a frame)
        val jvmAndJsTest by creating { dependsOn(commonTest.get()) }
        jvmTest.get().dependsOn(jvmAndJsTest)
        findByName("jsTest")?.dependsOn(jvmAndJsTest)
    }
}

tasks.withType<Test> { jvmArgs("-Xss512k", "--add-opens", "java.base/java.util=ALL-UNNAMED") }

// the artifacts are okay-kotlin, okay-kotlin-jvm, okay-kotlin-js, ...: the module's name is core — and so are their
// files, whose names a build that uses them keeps
publishing {
    publications.withType<MavenPublication>().configureEach { artifactId = artifactId.replace("core", "okay-kotlin") }
}
tasks.withType<AbstractArchiveTask>().configureEach {
    archiveBaseName.set(archiveBaseName.get().replace("core", "okay-kotlin"))
}

