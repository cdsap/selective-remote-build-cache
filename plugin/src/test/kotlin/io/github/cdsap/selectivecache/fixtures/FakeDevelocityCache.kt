package io.github.cdsap.selectivecache.fixtures

import com.gradle.develocity.agent.gradle.buildcache.DevelocityBuildCache
import org.gradle.caching.BuildCacheEntryReader
import org.gradle.caching.BuildCacheEntryWriter
import org.gradle.caching.BuildCacheKey
import org.gradle.caching.BuildCacheService
import org.gradle.caching.BuildCacheServiceFactory
import org.gradle.internal.operations.BuildOperationRunner
import java.io.File
import javax.inject.Inject

/**
 * Stands in for Develocity's connector: its own factory, with a build-scoped service injected, and
 * no knowledge of the selective cache plugin. The real connector cannot be modified either.
 */
class FakeDevelocityBuildCacheServiceFactory @Inject constructor(
    // If the decorating factory instantiates this one incorrectly, construction fails here.
    private val buildOperationRunner: BuildOperationRunner,
    private val gradle: org.gradle.api.invocation.Gradle,
) : BuildCacheServiceFactory<DevelocityBuildCache> {

    override fun createBuildCacheService(
        configuration: DevelocityBuildCache,
        describer: BuildCacheServiceFactory.Describer,
    ): BuildCacheService {
        // The real connector reads server and credentials from the develocity extension rather
        // than from the cache object, which is why an unconfigured instance still works. The
        // fake mirrors that by falling back to a location under the build directory.
        val dir = configuration.directory
            ?: File(gradle.startParameter.currentDir, ".caches/develocity")
        describer.type("Develocity").config("location", dir.absolutePath)
        println("FAKE-DEVELOCITY: factory ran with ${buildOperationRunner.javaClass.simpleName}")
        return FakeDevelocityBuildCacheService(dir)
    }
}

class FakeDevelocityBuildCacheService(private val dir: File) : BuildCacheService {
    init { dir.mkdirs() }

    override fun load(key: BuildCacheKey, reader: BuildCacheEntryReader): Boolean {
        val entry = File(dir, key.hashCode)
        if (!entry.isFile) return false
        entry.inputStream().use { reader.readFrom(it) }
        println("FAKE-DEVELOCITY: served ${key.hashCode}")
        return true
    }

    override fun store(key: BuildCacheKey, writer: BuildCacheEntryWriter) {
        File(dir, key.hashCode).outputStream().use { writer.writeTo(it) }
        println("FAKE-DEVELOCITY: stored ${key.hashCode}")
    }

    override fun close() = Unit
}

/** A cache type that is NOT Develocity's, used to prove the plugin refuses to filter it. */
open class NotDevelocityBuildCache : org.gradle.caching.configuration.AbstractBuildCache()
