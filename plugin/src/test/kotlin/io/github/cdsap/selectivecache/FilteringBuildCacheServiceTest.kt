package io.github.cdsap.selectivecache

import io.github.cdsap.selectivecache.policy.DeclineTally
import io.github.cdsap.selectivecache.policy.RemoteCacheFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private const val EXCLUDED = "com.example.BigTask"
private const val ALLOWED = "com.example.SmallTask"

/**
 * The adapter holds no rules of its own — those are covered by RemoteCacheFilterTest. What is
 * tested here is that a decline really does stop short of the delegate, and that the bookkeeping
 * and lifecycle around it are right.
 */
class FilteringBuildCacheServiceTest {

    @Test
    fun `a declined load never reaches the delegate`() {
        val delegate = RecordingCacheService()
        val hit = filteringService(delegate, owner = EXCLUDED, excluded = setOf(EXCLUDED))
            .load(FakeKey("k"), CapturingReader())

        assertFalse(hit, "a decline must be reported as a miss")
        assertTrue(delegate.loadedKeys.isEmpty(), "the delegate must not be contacted at all")
    }

    @Test
    fun `an allowed load is delegated and its result returned`() {
        val delegate = RecordingCacheService(loadResult = true, payload = "cached".toByteArray())
        val reader = CapturingReader()

        assertTrue(filteringService(delegate, owner = ALLOWED, excluded = setOf(EXCLUDED)).load(FakeKey("k"), reader))
        assertEquals(listOf("k"), delegate.loadedKeys)
        assertEquals("cached", reader.read?.decodeToString())
    }

    @Test
    fun `a delegate miss is still a miss`() {
        val delegate = RecordingCacheService(loadResult = false)
        assertFalse(filteringService(delegate, owner = ALLOWED).load(FakeKey("k"), CapturingReader()))
        assertEquals(listOf("k"), delegate.loadedKeys)
    }

    @Test
    fun `a declined store never reaches the delegate`() {
        val delegate = RecordingCacheService()
        filteringService(delegate, owner = EXCLUDED, excluded = setOf(EXCLUDED)).store(FakeKey("k"), FakeWriter(100))
        assertTrue(delegate.storedKeys.isEmpty())
    }

    @Test
    fun `an allowed store is delegated unchanged`() {
        val delegate = RecordingCacheService()
        filteringService(delegate, owner = ALLOWED, excluded = setOf(EXCLUDED)).store(FakeKey("k"), FakeWriter(100))
        assertEquals(listOf("k"), delegate.storedKeys)
        assertEquals(listOf(100L), delegate.storedSizes)
    }

    @Test
    fun `declines are recorded against the label the filter chose`() {
        val tally = DeclineTally()
        val service = filteringService(
            RecordingCacheService(), owner = EXCLUDED, excluded = setOf(EXCLUDED),
            maxStoreSizeBytes = 10, tally = tally,
        )
        service.load(FakeKey("k1"), CapturingReader())
        service.store(FakeKey("k1"), FakeWriter(250))

        assertEquals(1, tally.declinedLoads)
        assertEquals(1, tally.declinedStores)
        assertEquals(250L, tally.declinedStoreBytes)
        assertEquals(setOf(EXCLUDED), tally.byLabel().keys)
    }

    @Test
    fun `a size-limited store is recorded under the size bucket`() {
        val tally = DeclineTally()
        filteringService(RecordingCacheService(), owner = ALLOWED, maxStoreSizeBytes = 10, tally = tally)
            .store(FakeKey("k"), FakeWriter(50))

        assertEquals(setOf(RemoteCacheFilter.OVER_SIZE_LIMIT), tally.byLabel().keys)
    }

    @Test
    fun `the work owner is not looked up when no rule needs it`() {
        // The build-operation lookup is the only running cost of this plugin; skip it when idle.
        var lookups = 0
        val delegate = RecordingCacheService()
        val service = filteringService(
            delegate, owner = EXCLUDED, excluded = emptySet(),
            maxStoreSizeBytes = 10, onOwnerLookup = { lookups++ },
        )
        service.load(FakeKey("k"), CapturingReader())
        service.store(FakeKey("k"), FakeWriter(50))

        assertEquals(0, lookups, "no deny-list means no reason to ask who is running")
    }

    @Test
    fun `the work owner is looked up when a deny-list exists`() {
        var lookups = 0
        filteringService(
            RecordingCacheService(), owner = ALLOWED, excluded = setOf(EXCLUDED),
            onOwnerLookup = { lookups++ },
        ).load(FakeKey("k"), CapturingReader())

        assertEquals(1, lookups)
    }

    @Test
    fun `delegate failures propagate`() {
        val loadFailure = RecordingCacheService(onLoad = { throw IllegalStateException("boom") })
        assertThrows<IllegalStateException> {
            filteringService(loadFailure, owner = ALLOWED).load(FakeKey("k"), CapturingReader())
        }
        val storeFailure = RecordingCacheService(onStore = { throw IllegalStateException("boom") })
        assertThrows<IllegalStateException> {
            filteringService(storeFailure, owner = ALLOWED).store(FakeKey("k"), FakeWriter(1))
        }
    }

    @Test
    fun `close releases the listener and closes the delegate`() {
        val delegate = RecordingCacheService()
        var released = false
        filteringService(delegate, onClose = { released = true }).close()

        assertTrue(released)
        assertTrue(delegate.closed)
    }

    @Test
    fun `close still closes the delegate when listener removal fails`() {
        // A leaked listener is bad; a leaked cache connection is worse.
        val delegate = RecordingCacheService()
        assertThrows<IllegalStateException> {
            filteringService(delegate, onClose = { throw IllegalStateException("removal failed") }).close()
        }
        assertTrue(delegate.closed)
    }
}
