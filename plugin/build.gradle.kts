plugins {
    // Uses Gradle's embedded Kotlin, so the compiler always matches the kotlin-stdlib that
    // gradleApi() drags in. Also applies java-gradle-plugin and adds gradleApi() implicitly.
    `kotlin-dsl`
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
    plugins {
        create("selectiveRemoteCache") {
            id = "io.github.cdsap.selective-remote-cache"
            implementationClass = "io.github.cdsap.selectivecache.SelectiveRemoteCacheSettingsPlugin"
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
