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

// Defaults live in gradle.properties; override with -P. Gradle properties are
// configuration-cache inputs, so changing one invalidates the entry.
fun knob(name: String, default: String): String =
    providers.gradleProperty(name).getOrElse(default)

buildCache {
    remote(io.github.cdsap.selectivecache.SelectiveRemoteBuildCache::class.java) {
        isPush = true
        excludedTypes = knob("selectiveCache.excludedTypes", "")
            .split(",").filter { it.isNotBlank() }.toSet()
        maxStoreSizeBytes = knob("selectiveCache.maxStoreSizeBytes", "0").toLong()
        debug = true
    }
}
