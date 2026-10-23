# Releasing

Releases are a Maven repository on GitHub Pages: the `gh-pages` branch, its `maven/` directory, served at
https://okay-effects.github.io/okay-kotlin/maven. No account and no token is needed to use it, only a repository.

## Using a release

```kotlin
repositories {
    mavenCentral()
    maven("https://okay-effects.github.io/okay-kotlin/maven")
}

dependencies {
    implementation("io.github.okay-effects:okay-kotlin:0.1.0")   // multiplatform: the JVM, JS or native variant
    ksp("io.github.okay-effects:okay-kotlin-ksp:0.1.0")          // optional: `@Effect`'s interception, generated
}
```

Kotlin 2.4 or later: context parameters are stable there. Native: `macosArm64` and `linuxX64`.

## Making one

1. On `main`, everything green, on every platform:

   ```sh
   ./gradlew check
   ./gradlew -Pplatforms=jvm,js,native :core:allTests
   ```

2. The version: `version` in `build.gradle.kts` without `-SNAPSHOT`, committed, tagged (`v0.1.0`).

3. The repository written into `build/pages-maven/`, for every platform:

   ```sh
   rm -rf build/pages-maven
   ./gradlew -Pplatforms=jvm,js,native publishAllPublicationsToPagesRepository
   ```

4. Onto `gh-pages`, under `maven/`, beside the releases already there:

   ```sh
   git worktree add /tmp/okay-kotlin-pages gh-pages    # the first time: git worktree add --orphan -b gh-pages ...
   mkdir -p /tmp/okay-kotlin-pages/maven
   rsync -a build/pages-maven/ /tmp/okay-kotlin-pages/maven/
   touch /tmp/okay-kotlin-pages/.nojekyll
   git -C /tmp/okay-kotlin-pages add -A
   git -C /tmp/okay-kotlin-pages commit -m "release 0.1.0"
   git -C /tmp/okay-kotlin-pages push origin gh-pages
   git push origin v0.1.0
   ```

   The first time, GitHub Pages is turned on for the branch `gh-pages`.

5. The version back to the next `-SNAPSHOT`.

A release is never rewritten: a version published is what every build that resolved it has.
