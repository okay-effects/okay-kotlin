// the generator: for each interface marked `@Effect`, its `intercepted(interceptor)` — `ksp("...:okay-kotlin-ksp")`
plugins {
    kotlin("jvm")
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies { implementation("com.google.devtools.ksp:symbol-processing-api:2.3.12") }
