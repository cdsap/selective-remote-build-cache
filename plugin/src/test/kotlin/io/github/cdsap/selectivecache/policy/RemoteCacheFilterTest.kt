package io.github.cdsap.selectivecache.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The filtering rules, tested with no Gradle types at all: the reason for keeping them pure.
 * Everything the plugin decides is decided here.
 */
class RemoteCacheFilterTest {

    private val excluded = "com.example.BigTask"
    private val allowed = "com.example.SmallTask"

    private fun filter(
        excludedTypes: Set<String> = setOf(excluded),
        maxStoreSizeBytes: Long = 0,
        excludeLoads: Boolean = true,
        excludeStores: Boolean = true,
    ) = RemoteCacheFilter(TypePatterns(excludedTypes), maxStoreSizeBytes, excludeLoads, excludeStores)

    private fun assertDeclined(decision: FilterDecision, attributedTo: String) {
        assertTrue(decision is FilterDecision.Decline, "expected a decline but got $decision")
        assertEquals(attributedTo, (decision as FilterDecision.Decline).attributedTo)
    }

    // ---------- which work the owner lookup is needed for ----------

    @Test
    fun `the work owner is only needed when there is a type deny-list`() {
        assertTrue(filter().needsWorkOwner)
        assertFalse(filter(excludedTypes = emptySet()).needsWorkOwner)
        assertFalse(filter(excludedTypes = emptySet(), maxStoreSizeBytes = 100).needsWorkOwner)
    }

    // ---------- loads ----------

    @Test
    fun `an excluded type is declined on load`() {
        assertDeclined(filter().forLoad(excluded), excluded)
    }

    @Test
    fun `a non-excluded type is allowed on load`() {
        assertEquals(FilterDecision.Allow, filter().forLoad(allowed))
    }

    @Test
    fun `an unknown owner fails open`() {
        // If build-operation correlation ever fails we must degrade to normal caching,
        // never to silently dropping traffic.
        assertEquals(FilterDecision.Allow, filter().forLoad(null))
        assertEquals(FilterDecision.Allow, filter(excludedTypes = setOf("*")).forLoad(null))
    }

    @Test
    fun `excludeLoads false keeps reads for an excluded type`() {
        assertEquals(FilterDecision.Allow, filter(excludeLoads = false).forLoad(excluded))
    }

    @Test
    fun `wildcards select types on load`() {
        assertDeclined(filter(excludedTypes = setOf("com.example.*")).forLoad(excluded), excluded)
    }

    // ---------- stores ----------

    @Test
    fun `an excluded type is declined on store`() {
        assertDeclined(filter().forStore(excluded, entrySizeBytes = 10), excluded)
    }

    @Test
    fun `a non-excluded type is allowed on store`() {
        assertEquals(FilterDecision.Allow, filter().forStore(allowed, entrySizeBytes = 10))
    }

    @Test
    fun `excludeStores false keeps writes for an excluded type`() {
        assertEquals(FilterDecision.Allow, filter(excludeStores = false).forStore(excluded, 10))
    }

    @Test
    fun `a store over the size limit is declined and attributed to the size bucket`() {
        assertDeclined(
            filter(excludedTypes = emptySet(), maxStoreSizeBytes = 500).forStore(allowed, 501),
            RemoteCacheFilter.OVER_SIZE_LIMIT,
        )
    }

    @Test
    fun `a store exactly at the size limit is allowed`() {
        assertEquals(
            FilterDecision.Allow,
            filter(excludedTypes = emptySet(), maxStoreSizeBytes = 500).forStore(allowed, 500),
        )
    }

    @Test
    fun `a size limit of zero means unlimited`() {
        assertEquals(
            FilterDecision.Allow,
            filter(excludedTypes = emptySet(), maxStoreSizeBytes = 0).forStore(allowed, 10_000_000),
        )
    }

    @Test
    fun `the type rule is applied before the size rule`() {
        // An excluded type must be attributed to the type, not to the size bucket, even when it
        // also happens to be oversized.
        assertDeclined(filter(maxStoreSizeBytes = 1).forStore(excluded, 500), excluded)
    }

    @Test
    fun `the size rule still applies with no deny-list configured`() {
        assertDeclined(
            filter(excludedTypes = emptySet(), maxStoreSizeBytes = 10).forStore(null, 11),
            RemoteCacheFilter.OVER_SIZE_LIMIT,
        )
    }

    @Test
    fun `a decline carries a human-readable explanation`() {
        val typeDecline = filter().forLoad(excluded) as FilterDecision.Decline
        assertEquals("excluded type", typeDecline.explanation)

        val sizeDecline = filter(excludedTypes = emptySet(), maxStoreSizeBytes = 500)
            .forStore(allowed, 704) as FilterDecision.Decline
        assertEquals("limit is 500 bytes", sizeDecline.explanation)
    }
}
