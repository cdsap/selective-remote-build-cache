package io.github.cdsap.selectivecache

import io.github.cdsap.selectivecache.policy.DeclineTally
import io.github.cdsap.selectivecache.policy.RemoteCacheFilter
import io.github.cdsap.selectivecache.policy.TypePatterns
import io.github.cdsap.selectivecache.work.BuildOperationWorkOwnerSource
import io.github.cdsap.selectivecache.work.WorkOwnerSource
import javax.inject.Inject
import org.gradle.api.invocation.Gradle
import org.gradle.caching.BuildCacheService
import org.gradle.caching.BuildCacheServiceFactory
import org.gradle.caching.configuration.BuildCache
import org.gradle.caching.configuration.internal.BuildCacheConfigurationInternal
import org.gradle.internal.instantiation.InstantiatorFactory
import org.gradle.internal.operations.BuildOperationListenerManager
import org.gradle.internal.service.ServiceRegistry

/**
 * Composition root: turns the user's configuration into the object graph, and nothing else.
 *
 * Gradle instantiates this with its own InstanceGenerator (AbstractBuildCacheControllerFactory),
 * so constructor injection of build-scoped services works — the same mechanism Gradle's own
 * DefaultHttpBuildCacheServiceFactory uses. Everything lives here rather than in a Settings plugin
 * because the cache service is created on every build, including configuration-cache hits,
 * whereas settings scripts are not re-evaluated on a hit.
 */
class SelectiveRemoteBuildCacheServiceFactory @Inject constructor(
    private val listenerManager: BuildOperationListenerManager,
    private val gradle: Gradle,
    private val instantiatorFactory: InstantiatorFactory,
    private val services: ServiceRegistry,
    private val buildCacheConfiguration: BuildCacheConfigurationInternal,
) : BuildCacheServiceFactory<SelectiveRemoteBuildCache> {

    override fun createBuildCacheService(
        configuration: SelectiveRemoteBuildCache,
        describer: BuildCacheServiceFactory.Describer,
    ): BuildCacheService {
        val filter = filterFrom(configuration)
        val tally = DeclineTally()
        val workOwner = workOwnerSourceFor(filter)

        describe(describer, configuration)

        return FilteringBuildCacheService(
            delegate = delegateServiceFor(configuration, describer),
            filter = filter,
            workOwner = workOwner.source,
            tally = tally,
            debug = configuration.debug,
            onClose = workOwner.detach,
        )
    }

    private fun filterFrom(configuration: SelectiveRemoteBuildCache) = RemoteCacheFilter(
        excludedTypes = TypePatterns(configuration.excludedTypes),
        maxStoreSizeBytes = configuration.maxStoreSizeBytes,
        excludeLoads = configuration.excludeLoads,
        excludeStores = configuration.excludeStores,
    )

    /** Tracking build operations costs something, so only do it when a rule needs the owner. */
    private fun workOwnerSourceFor(filter: RemoteCacheFilter): AttachedWorkOwnerSource {
        if (!filter.needsWorkOwner) return AttachedWorkOwnerSource(WorkOwnerSource.None) {}
        val tracker = BuildOperationWorkOwnerSource()
        listenerManager.addListener(tracker)
        return AttachedWorkOwnerSource(tracker) { listenerManager.removeListener(tracker) }
    }

    private class AttachedWorkOwnerSource(val source: WorkOwnerSource, val detach: () -> Unit)

    /**
     * Builds the delegate's service using the delegate's own factory.
     *
     * `instantiatorFactory.inject(services)` reproduces exactly the InstanceGenerator that
     * BuildCacheServices hands to AbstractBuildCacheControllerFactory, so the delegate's factory
     * receives every service it would normally be injected with. It also gets the real Describer,
     * so the Build Scan still reports its cache type and configuration, not ours.
     */
    private fun delegateServiceFor(
        configuration: SelectiveRemoteBuildCache,
        describer: BuildCacheServiceFactory.Describer,
    ): BuildCacheService {
        val delegateConfig = requireNotNull(configuration.delegateCache) {
            "Selective remote build cache needs a 'delegateTo' cache to filter in front of"
        }
        @Suppress("UNCHECKED_CAST")
        val factoryType = buildCacheConfiguration
            .getBuildCacheServiceFactoryType(delegateConfig.javaClass as Class<BuildCache>)
        return instantiatorFactory.inject(services)
            .newInstance(factoryType)
            .createBuildCacheService(delegateConfig, describer)
    }

    private fun describe(
        describer: BuildCacheServiceFactory.Describer,
        configuration: SelectiveRemoteBuildCache,
    ) {
        describer
            .config("filtered by", "selective-remote-build-cache")
            .config("excludedTypes", configuration.excludedTypesDisplay)
            .config("maxStoreSize", configuration.maxStoreSizeDisplay)
    }

    private val SelectiveRemoteBuildCache.excludedTypesDisplay: String
        get() = excludedTypes.sorted().joinToString(", ").ifEmpty { "(none)" }

    private val SelectiveRemoteBuildCache.maxStoreSizeDisplay: String
        get() = if (maxStoreSizeBytes > 0) "$maxStoreSizeBytes bytes" else "unlimited"
}
