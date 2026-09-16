import java.util.Properties

pluginManagement {
    includeBuild("../plugin")
}

plugins {
    id("com.gradle.develocity") version "4.5.1"
    id("io.github.cdsap.selective-remote-cache")
}

rootProject.name = "sample-develocity"
include("a", "b", "c")

// Point this sample at your own Develocity instance:
//
//   ../gradlew -Pdevelocity.server=https://develocity.example.com runAll --build-cache
//   DEVELOCITY_SERVER=https://develocity.example.com ../gradlew runAll --build-cache
//
// Deliberately has no default. A hardcoded host would make every clone of this repo publish
// Build Scans to someone else's server. If you only want the behavioural proof, `sample/` runs
// fully offline against a directory-backed remote cache and needs no Develocity at all.
val develocityServer: String =
    (providers.gradleProperty("develocity.server")
        .orElse(providers.environmentVariable("DEVELOCITY_SERVER"))
        .orNull
        ?: error(
            """
            sample-develocity needs a Develocity instance to talk to.

              Pass -Pdevelocity.server=https://develocity.example.com, or set DEVELOCITY_SERVER.
              Authenticate first with: ../gradlew provisionDevelocityAccessKey

            For the offline behavioural proof, use ./verify.sh (which drives sample/) instead.
            """.trimIndent()
        )).removeSuffix("/")

develocity {
    server = develocityServer
    buildScan {
        uploadInBackground = false
        tag("selective-remote-cache-test")
    }
}

val filters = Properties().apply {
    val f = File(rootDir, "filter.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

buildCache {
    local {
        directory = File(rootDir, ".caches/local")
        isEnabled = filters.getProperty("localEnabled", "true").toBoolean()
        isPush = true
    }
    remote(io.github.cdsap.selectivecache.SelectiveRemoteBuildCache::class.java) {
        isPush = true
        // The Develocity cache, configured in place. Its connector does all the network work.
        delegateTo(develocity.buildCache)
        excludedTypes = filters.getProperty("excludedTypes", "")
            .split(",").filter { it.isNotBlank() }.toSet()
        maxStoreSizeBytes = filters.getProperty("maxStoreSizeBytes", "0").toLong()
        debug = true
    }
}
