package io.github.cdsap.selectivecache

import io.github.cdsap.selectivecache.policy.DeclineTally
import io.github.cdsap.selectivecache.policy.FilterDecision
import io.github.cdsap.selectivecache.policy.RemoteCacheFilter
import io.github.cdsap.selectivecache.scan.DeclineScanReport
import io.github.cdsap.selectivecache.work.WorkOwnerSource
import org.gradle.api.logging.Logging
import org.gradle.caching.BuildCacheEntryReader
import org.gradle.caching.BuildCacheEntryWriter
import org.gradle.caching.BuildCacheKey
import org.gradle.caching.BuildCacheService

/**
 * Adapter: turns Gradle's BuildCacheService SPI into a [RemoteCacheFilter] question, and either
 * forwards to the real cache or declines.
 *
 * It holds no rules of its own — it resolves the work owner, asks the filter, records the answer
 * and delegates. Declining can never affect the local cache: Gradle holds that as a separate
 * handle in DefaultBuildCacheController and consults it before ever reaching us.
 */
internal class FilteringBuildCacheService(
    private val delegate: BuildCacheService,
    private val filter: RemoteCacheFilter,
    private val workOwner: WorkOwnerSource,
    private val tally: DeclineTally,
    private val scanReport: DeclineScanReport,
    private val debug: Boolean,
    private val onClose: () -> Unit,
) : BuildCacheService {

    private val logger = Logging.getLogger(FilteringBuildCacheService::class.java)

    override fun load(key: BuildCacheKey, reader: BuildCacheEntryReader): Boolean {
        scanReport.registerOnce()
        val decision = filter.forLoad(currentWorkOwner())
        if (decision is FilterDecision.Decline) {
            tally.recordLoad(decision.attributedTo)
            log("declined remote load for ${decision.attributedTo} — ${decision.explanation} (key ${key.hashCode})")
            return false
        }
        return delegate.load(key, reader)
    }

    override fun store(key: BuildCacheKey, writer: BuildCacheEntryWriter) {
        scanReport.registerOnce()
        val decision = filter.forStore(currentWorkOwner(), writer.size)
        if (decision is FilterDecision.Decline) {
            tally.recordStore(decision.attributedTo, writer.size)
            log("declined remote store for ${decision.attributedTo} — ${decision.explanation} (key ${key.hashCode}, ${writer.size} bytes)")
            return
        }
        delegate.store(key, writer)
    }

    override fun close() {
        try {
            logSummary()
        } finally {
            // A leaked listener is bad; a leaked cache connection is worse.
            try {
                onClose()
            } finally {
                delegate.close()
            }
        }
    }

    /** Only pay for the build-operation lookup when a rule actually depends on it. */
    private fun currentWorkOwner(): String? =
        if (filter.needsWorkOwner) workOwner.currentOwner() else null

    private fun logSummary() {
        if (tally.isEmpty) return
        logger.lifecycle(
            "Selective remote cache: declined {} remote loads and {} remote stores ({} MB not uploaded). " +
                "Local cache was unaffected. Note: Gradle still records these as Miss/Store in a Build Scan.",
            tally.declinedLoads,
            tally.declinedStores,
            tally.declinedStoreBytes / (1024 * 1024),
        )
    }

    private fun log(message: String) {
        if (debug) logger.lifecycle("Selective remote cache: $message") else logger.info("Selective remote cache: $message")
    }
}
