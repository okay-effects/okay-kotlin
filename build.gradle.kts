plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

allprojects {
    group = "io.github.okay-effects"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}
