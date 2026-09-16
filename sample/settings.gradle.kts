pluginManagement {
    includeBuild("../plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.gradle.develocity") version "4.5.1"
    id("io.github.cdsap.selective-remote-cache")
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sample"
include("app", "core", "domain")

// Point this sample at your own Develocity instance:
//
//   ../gradlew -Pdevelocity.server=https://develocity.example.com runAll --build-cache
//   DEVELOCITY_SERVER=https://develocity.example.com ../gradlew runAll --build-cache
//
// Deliberately has no default. A hardcoded host would make every clone of this repo publish
// Build Scans and cache entries to someone else's server. Authenticate once with
// `../gradlew provisionDevelocityAccessKey`, or set DEVELOCITY_ACCESS_KEY.
val develocityServer: String =
    (providers.gradleProperty("develocity.server")
        .orElse(providers.environmentVariable("DEVELOCITY_SERVER"))
        .orNull
        ?: error(
            """
            sample needs a Develocity instance to talk to.

              Pass -Pdevelocity.server=https://develocity.example.com, or set DEVELOCITY_SERVER.
              Authenticate first with: ../gradlew provisionDevelocityAccessKey

            This plugin filters the Develocity build cache and nothing else, so there is no
            offline mode for the sample. The plugin's own test suite runs offline.
            """.trimIndent()
        )).removeSuffix("/")

develocity {
    server = develocityServer
    buildScan {
        uploadInBackground = false
        tag("selective-remote-cache-test")
    }
}

// Demo knobs come from Gradle properties - defaults in gradle.properties, overridable with -P.
// They are real configuration-cache inputs, so changing one invalidates the entry rather than
// silently reusing a stale configuration.
fun knob(name: String, default: String): String =
    providers.gradleProperty(name).getOrElse(default)

buildCache {
    local {
        directory = File(rootDir, ".caches/local")
        isEnabled = knob("selectiveCache.localEnabled", "true").toBoolean()
        isPush = true
    }

    // The Develocity cache, configured in place. Its connector does all the network work.
    remote(io.github.cdsap.selectivecache.SelectiveRemoteBuildCache::class.java) {
        isPush = true
        delegateTo(develocity.buildCache) {
            isPush = true
        }
        excludedTypes = knob("selectiveCache.excludedTypes", "")
            .split(",").filter { it.isNotBlank() }.toSet()
        maxStoreSizeBytes = knob("selectiveCache.maxStoreSizeBytes", "0").toLong()
        debug = true
    }
}
