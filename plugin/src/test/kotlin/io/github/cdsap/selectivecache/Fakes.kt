package io.github.cdsap.selectivecache

import io.github.cdsap.selectivecache.policy.DeclineTally
import io.github.cdsap.selectivecache.policy.RemoteCacheFilter
import io.github.cdsap.selectivecache.policy.TypePatterns
import io.github.cdsap.selectivecache.scan.DeclineScanReport
import io.github.cdsap.selectivecache.work.WorkOwnerSource
import org.gradle.caching.BuildCacheEntryReader
import org.gradle.caching.BuildCacheEntryWriter
import org.gradle.caching.BuildCacheKey
import org.gradle.caching.BuildCacheService
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

internal class FakeKey(private val hash: String) : BuildCacheKey {
    override fun getHashCode(): String = hash
    override fun toByteArray(): ByteArray = hash.toByteArray()
}

internal class FakeWriter(private val bytes: ByteArray) : BuildCacheEntryWriter {
    constructor(size: Int) : this(ByteArray(size))
    override fun writeTo(output: OutputStream) = output.write(bytes)
    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    override fun getSize(): Long = bytes.size.toLong()
}

internal class CapturingReader : BuildCacheEntryReader {
    var read: ByteArray? = null
    override fun readFrom(input: InputStream) { read = input.readBytes() }
}

/** Records every call so tests can assert the delegate was — or was not — reached. */
internal class RecordingCacheService(
    private val loadResult: Boolean = true,
    private val payload: ByteArray = ByteArray(0),
    private val onLoad: (() -> Unit)? = null,
    private val onStore: (() -> Unit)? = null,
) : BuildCacheService {

    val loadedKeys = mutableListOf<String>()
    val storedKeys = mutableListOf<String>()
    val storedSizes = mutableListOf<Long>()
    var closed = false

    override fun load(key: BuildCacheKey, reader: BuildCacheEntryReader): Boolean {
        loadedKeys += key.hashCode
        onLoad?.invoke()
        if (loadResult) reader.readFrom(ByteArrayInputStream(payload))
        return loadResult
    }

    override fun store(key: BuildCacheKey, writer: BuildCacheEntryWriter) {
        storedKeys += key.hashCode
        storedSizes += writer.size
        onStore?.invoke()
    }

    override fun close() { closed = true }
}

/** Builds the adapter under test with a fake delegate and a fixed work owner. */
internal fun filteringService(
    delegate: BuildCacheService,
    owner: String? = null,
    excluded: Set<String> = emptySet(),
    maxStoreSizeBytes: Long = 0,
    excludeLoads: Boolean = true,
    excludeStores: Boolean = true,
    tally: DeclineTally = DeclineTally(),
    onOwnerLookup: () -> Unit = {},
    onClose: () -> Unit = {},
) = FilteringBuildCacheService(
    delegate = delegate,
    filter = RemoteCacheFilter(
        excludedTypes = TypePatterns(excluded),
        maxStoreSizeBytes = maxStoreSizeBytes,
        excludeLoads = excludeLoads,
        excludeStores = excludeStores,
    ),
    workOwner = WorkOwnerSource { onOwnerLookup(); owner },
    tally = tally,
    scanReport = DeclineScanReport(findReporter = { null }, configuration = emptyList(), tally = tally),
    debug = false,
    onClose = onClose,
)
