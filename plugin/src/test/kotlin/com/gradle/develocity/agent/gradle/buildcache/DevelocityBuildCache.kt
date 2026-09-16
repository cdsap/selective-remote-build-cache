package com.gradle.develocity.agent.gradle.buildcache

import org.gradle.caching.configuration.AbstractBuildCache
import java.io.File

/**
 * Test double for Develocity's build cache type.
 *
 * It carries Develocity's real fully-qualified name because that is how the plugin recognises the
 * Develocity cache. Same name, same validation path as production, and no server needed.
 */
open class DevelocityBuildCache : AbstractBuildCache() {
    var server: String? = null
    var directory: File? = null
}
