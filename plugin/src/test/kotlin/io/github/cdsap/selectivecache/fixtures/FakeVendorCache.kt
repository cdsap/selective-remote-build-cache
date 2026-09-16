package io.github.cdsap.selectivecache.fixtures

import org.gradle.caching.BuildCacheEntryReader
import org.gradle.caching.BuildCacheEntryWriter
import org.gradle.caching.BuildCacheKey
import org.gradle.caching.BuildCacheService
import org.gradle.caching.BuildCacheServiceFactory
import org.gradle.caching.configuration.AbstractBuildCache
import org.gradle.internal.operations.BuildOperationRunner
import java.io.File
import javax.inject.Inject

/**
 * Stand-in for a third-party connector such as Develocity's: its own BuildCache type and its own
 * factory, with a build-scoped service injected. It knows nothing about the selective cache
 * plugin — which is the point, since the real connector cannot be modified either.
 */
open class FakeVendorBuildCache : AbstractBuildCache() {
    var directory: File? = null
    var vendorToken: String? = null
}

class FakeVendorBuildCacheServiceFactory @Inject constructor(
    // If the decorating factory instantiates this one incorrectly, construction fails here.
    private val buildOperationRunner: BuildOperationRunner,
) : BuildCacheServiceFactory<FakeVendorBuildCache> {

    override fun createBuildCacheService(
        configuration: FakeVendorBuildCache,
        describer: BuildCacheServiceFactory.Describer,
    ): BuildCacheService {
        val dir = requireNotNull(configuration.directory) { "fake vendor cache needs a directory" }
        require(!configuration.vendorToken.isNullOrEmpty()) { "fake vendor cache needs a token" }
        describer.type("fake-vendor").config("location", dir.absolutePath)
        println("FAKE-VENDOR: factory ran with ${buildOperationRunner.javaClass.simpleName}")
        return FakeVendorBuildCacheService(dir)
    }
}

class FakeVendorBuildCacheService(private val dir: File) : BuildCacheService {
    init { dir.mkdirs() }

    override fun load(key: BuildCacheKey, reader: BuildCacheEntryReader): Boolean {
        val entry = File(dir, key.hashCode)
        if (!entry.isFile) return false
        entry.inputStream().use { reader.readFrom(it) }
        println("FAKE-VENDOR: served ${key.hashCode}")
        return true
    }

    override fun store(key: BuildCacheKey, writer: BuildCacheEntryWriter) {
        File(dir, key.hashCode).outputStream().use { writer.writeTo(it) }
        println("FAKE-VENDOR: stored ${key.hashCode}")
    }

    override fun close() = Unit
}
