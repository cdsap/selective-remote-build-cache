import java.util.Properties

pluginManagement {
    includeBuild("../plugin")
}

plugins {
    id("io.github.cdsap.selective-remote-cache")
}

rootProject.name = "sample"
include("a", "b", "c")

// Scenario knobs live in a file rather than -D system properties: values passed with -D stick to
// the daemon JVM for its whole lifetime, which makes configuration-cache inputs flap between runs.
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
        // Stand-in for a real remote so the demo runs offline. Swapping this line for
        // `delegateTo(develocity.buildCache)` is the only change needed for the real thing.
        delegateTo(org.gradle.caching.local.DirectoryBuildCache::class.java) {
            directory = File(rootDir, ".caches/remote")
        }
        excludedTypes = filters.getProperty("excludedTypes", "")
            .split(",").filter { it.isNotBlank() }.toSet()
        maxStoreSizeBytes = filters.getProperty("maxStoreSizeBytes", "0").toLong()
        debug = true
    }
}
