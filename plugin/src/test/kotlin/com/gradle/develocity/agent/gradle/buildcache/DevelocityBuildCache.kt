package com.gradle.develocity.agent.gradle.buildcache

import org.gradle.caching.configuration.AbstractBuildCache
import java.io.File

/**
 * Test double for Develocity's own build cache type.
 *
 * It deliberately carries the real fully-qualified name, because the plugin identifies the
 * Develocity cache by exactly that name. Using the same name means the tests exercise the real
 * validation path with no test-only branch in the production code, and without needing a live
 * Develocity server on the test classpath.
 */
open class DevelocityBuildCache : AbstractBuildCache() {
    var server: String? = null
    var directory: File? = null
}
