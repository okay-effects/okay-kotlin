plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

allprojects {
    group = "io.github.okay-effects"
    version = "0.1.1-SNAPSHOT"
    repositories { mavenCentral() }
}

/** RELEASES, ON GITHUB PAGES: `./gradlew -Pplatforms=jvm,js,native publishAllPublicationsToPagesRepository` writes
 * every module, for every platform, into `build/pages-maven`, which goes onto the `gh-pages` branch (RELEASING.md) */
val pagesMaven = layout.buildDirectory.dir("pages-maven")

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories { maven { name = "pages"; url = uri(pagesMaven) } }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(artifactId)
                    description.set("okay-kotlin: algebraic effects for Kotlin")
                    url.set("https://github.com/okay-effects/okay-kotlin")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    scm { url.set("https://github.com/okay-effects/okay-kotlin") }
                }
            }
        }
    }
}

