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


develocity {
    server = "https://ge.solutions-team.gradle.com/"
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
        isEnabled = false
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
