package io.github.cdsap.selectivecache

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

/**
 * Convenience: registers the cache type so build authors only write
 *
 *     buildCache { remote(SelectiveRemoteBuildCache::class) { ... } }
 *
 * instead of also calling registerBuildCacheService themselves.
 */
class SelectiveRemoteCacheSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.buildCache.registerBuildCacheService(
            SelectiveRemoteBuildCache::class.java,
            SelectiveRemoteBuildCacheServiceFactory::class.java,
        )
    }
}
