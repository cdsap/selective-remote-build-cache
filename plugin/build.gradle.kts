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
            description = "Keeps chosen task types out of the remote build cache while leaving the " +
                "local cache untouched, for tasks that are cheaper to re-run than to fetch over a WAN."
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
