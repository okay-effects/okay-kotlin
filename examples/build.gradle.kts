// effects of an application, their interception generated: the generator's proof
plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

kotlin {
    jvmToolchain(21)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    implementation(project(":core"))
    ksp(project(":ksp"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
