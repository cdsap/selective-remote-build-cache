package io.github.cdsap.selectivecache

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.caching.configuration.AbstractBuildCache
import org.gradle.caching.configuration.BuildCache

/**
 * A filter placed in front of an existing remote build cache. It can exclude specific task and
 * artifact-transform types from the REMOTE cache only; the local cache is untouched, because
 * Gradle holds that as a separate handle in DefaultBuildCacheController and consults it first.
 *
 * This is the per-type local/remote split that gradle/gradle#27710 asked for and that Gradle
 * closed as not-planned.
 */
open class SelectiveRemoteBuildCache @Inject constructor(
    private val objectFactory: ObjectFactory,
) : AbstractBuildCache() {

    internal var delegateCache: BuildCache? = null

    /**
     * The Develocity build cache to filter in front of, configured in place. This plugin filters
     * the Develocity remote cache and nothing else: anything other than `develocity.buildCache`
     * is rejected here, while the settings script is still evaluating.
     *
     * ```
     * remote(SelectiveRemoteBuildCache::class.java) {
     *     delegateTo(develocity.buildCache) { isPush = true }
     *     excludedTypes = setOf("com.android.build.gradle.internal.tasks.DexMergingTask")
     * }
     * ```
     *
     * The delegate's configuration object is built with Gradle's own ObjectFactory, exactly as
     * Gradle would have built it for a `remote(...)` call.
     *
     * This plugin never talks to a cache backend itself. It looks up the delegate's own registered
     * BuildCacheServiceFactory, instantiates it with Gradle's own injecting instantiator, and
     * decorates the service it returns, so the delegate keeps doing all of its own work: auth,
     * protocol, retries, telemetry, and its own Build Scan description.
     *
     * Deliberately NOT named `delegate`: inside a Groovy closure `delegate` is the closure's own
     * delegate, so `delegate = ...` in a `settings.gradle` would silently assign to the closure.
     */
    @JvmOverloads
    fun <T : BuildCache> delegateTo(type: Class<T>, configure: Action<in T> = Action {}) {
        requireDevelocity(type)
        // Built and configured here, during settings evaluation, rather than deferred to when the
        // cache service is created. Deferring would run the user's block at execution time, and a
        // block that touches script state such as `rootDir` then fails the configuration cache.
        val config = objectFactory.newInstance(type)
        configure.execute(config)
        delegateCache = config
    }

    /**
     * Walks the supertypes rather than comparing the class directly, so a decorated or subclassed
     * Develocity cache type is still recognised.
     */
    private fun requireDevelocity(type: Class<*>) {
        val isDevelocity = generateSequence(type) { it.superclass }
            .any { it.name == DEVELOCITY_BUILD_CACHE_TYPE }
        require(isDevelocity) {
            "Selective remote build cache filters the Develocity build cache and nothing else.\n" +
                "  expected: $DEVELOCITY_BUILD_CACHE_TYPE\n" +
                "  got:      ${type.name}\n" +
                "Apply the 'com.gradle.develocity' plugin and pass its cache:\n" +
                "  remote(SelectiveRemoteBuildCache::class.java) { delegateTo(develocity.buildCache) }"
        }
    }

    /**
     * Fully-qualified task classes and/or TransformAction classes that must NOT use the remote
     * cache. Supports '*' wildcards, e.g. "com.android.build.gradle.tasks.*".
     */
    var excludedTypes: Set<String> = emptySet()

    /**
     * Skip remote *stores* for entries larger than this many bytes. 0 = no limit.
     * This needs no build-operation correlation — the size is on BuildCacheEntryWriter.
     */
    var maxStoreSizeBytes: Long = 0

    /** When true, excluded types skip remote loads as well as stores. */
    var excludeLoads: Boolean = true

    /** When true, excluded types skip remote stores. */
    var excludeStores: Boolean = true

    /** Log every filtering decision at lifecycle level. Useful while tuning the deny-list. */
    var debug: Boolean = false

    companion object {
        /** The only cache type this plugin will filter in front of. */
        const val DEVELOCITY_BUILD_CACHE_TYPE =
            "com.gradle.develocity.agent.gradle.buildcache.DevelocityBuildCache"
    }
}
