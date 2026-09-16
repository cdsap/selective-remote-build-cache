package io.github.cdsap.selectivecache

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.caching.configuration.BuildCache

/**
 * Registers the cache type, so build authors write
 *
 *     buildCache { remote(SelectiveRemoteBuildCache::class) { ... } }
 *
 * without calling registerBuildCacheService themselves, and defaults the delegate to the
 * Develocity cache so `delegateTo` is only needed when that cache has to be configured.
 */
class SelectiveRemoteCacheSettingsPlugin : Plugin<Settings> {

    override fun apply(settings: Settings) {
        settings.buildCache.registerBuildCacheService(
            SelectiveRemoteBuildCache::class.java,
            SelectiveRemoteBuildCacheServiceFactory::class.java,
        )

        // Deferred to settingsEvaluated for two reasons: the remote cache has not been declared
        // yet when this plugin is applied, and the Develocity plugin may be applied after us.
        // Resolving here rather than at cache-service creation also means the delegate is part of
        // the configuration that gets serialised, so configuration-cache hits need nothing extra.
        settings.gradle.settingsEvaluated {
            val remote = settings.buildCache.remote
            if (remote is SelectiveRemoteBuildCache && remote.delegateCache == null) {
                remote.delegateTo(develocityBuildCacheType(settings))
            }
        }
    }

    /**
     * `develocity.buildCache` is a Class, not an instance. Read reflectively so this plugin does
     * not need a compile dependency on the Develocity plugin.
     */
    private fun develocityBuildCacheType(settings: Settings): Class<out BuildCache> {
        val develocity = settings.extensions.findByName(DEVELOCITY_EXTENSION)
            ?: error(
                "Selective remote build cache filters the Develocity build cache, but no " +
                    "'$DEVELOCITY_EXTENSION' extension was found.\n" +
                    "Apply the Develocity plugin in settings.gradle.kts:\n" +
                    "  plugins { id(\"com.gradle.develocity\") version \"…\" }\n" +
                    "Or name the cache yourself with delegateTo(...) if you register it another way."
            )

        val getBuildCache = develocity.javaClass.methods
            .firstOrNull { it.name == "getBuildCache" && it.parameterCount == 0 }
            ?: error(
                "The '$DEVELOCITY_EXTENSION' extension is a ${develocity.javaClass.name}, which has " +
                    "no getBuildCache(). Pass the cache explicitly with delegateTo(...)."
            )

        val type = getBuildCache.invoke(develocity) as? Class<*>
            ?: error("$DEVELOCITY_EXTENSION.buildCache did not return a cache type.")

        @Suppress("UNCHECKED_CAST")
        return type as Class<out BuildCache>
    }

    private companion object {
        const val DEVELOCITY_EXTENSION = "develocity"
    }
}
