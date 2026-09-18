import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Uses Gradle's embedded Kotlin, so the compiler always matches the kotlin-stdlib that
    // gradleApi() drags in. Also applies java-gradle-plugin and adds gradleApi() implicitly.
    `kotlin-dsl`
    // Publishes to the Gradle Plugin Portal. Also applies maven-publish and signing, and wires
    // up the sources and javadoc jars the Portal requires, so nothing else needs to.
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "io.github.cdsap"
version = "0.1.0"

repositories { mavenCentral() }

// Without this the artifact inherits whatever JDK built it: java-gradle-plugin records
// org.gradle.jvm.version in the module metadata, so a jar compiled on JDK 21 refuses to resolve
// for anyone on Java 17 — and Java 17 is the minimum a Gradle 9 daemon runs on. Deliberately a
// target, not a toolchain: a toolchain would also move the test JVM, and CI runs the suite on
// each supported JDK to prove the plugin works there.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(gradleTestKit())
}

gradlePlugin {
    website = "https://github.com/cdsap/selective-remote-build-cache"
    vcsUrl = "https://github.com/cdsap/selective-remote-build-cache.git"

    plugins {
        create("selectiveRemoteCache") {
            id = "io.github.cdsap.selective-remote-cache"
            implementationClass = "io.github.cdsap.selectivecache.SelectiveRemoteCacheSettingsPlugin"
            displayName = "Selective Remote Build Cache"
            // The Portal listing is the first thing a stranger reads, so the warning goes here
            // too, not only in the README.
            description = "EXPERIMENTAL. Keeps chosen task types out of the remote build cache " +
                "while leaving the local cache untouched, for tasks that are cheaper to re-run " +
                "than to fetch over a WAN. Uses Gradle internals; not supported by Gradle or " +
                "Develocity, and a Gradle upgrade can break it."
            tags = listOf("build-cache", "develocity", "performance", "android")
        }
    }
}

// The Portal rejects a POM without a license, and plugin-publish does not infer one.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // Debug aid: -DselectiveCache.dumpScripts=<dir> writes the generated functional-test scripts
    // somewhere inspectable, since TestKit project dirs are temporary.
    providers.systemProperty("selectiveCache.dumpScripts").orNull
        ?.let { systemProperty("selectiveCache.dumpScripts", it) }
    testLogging { events("failed") }
    // Functional tests spawn Gradle builds; give them room but keep them honest.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}
