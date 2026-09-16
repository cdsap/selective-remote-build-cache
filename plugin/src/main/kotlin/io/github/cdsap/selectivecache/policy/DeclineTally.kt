package io.github.cdsap.selectivecache.policy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts what the filter declined, overall and per label.
 *
 * Thread-safe because Gradle runs work units in parallel. Kept separate from the filter so the
 * rules stay pure, and separate from reporting so the numbers have one owner.
 */
class DeclineTally {

    data class Totals(val loads: Int, val stores: Int, val bytes: Long)

    private val loads = AtomicInteger()
    private val stores = AtomicInteger()
    private val storeBytes = AtomicLong()
    private val perLabel = ConcurrentHashMap<String, MutableTotals>()

    val declinedLoads: Int get() = loads.get()
    val declinedStores: Int get() = stores.get()
    val declinedStoreBytes: Long get() = storeBytes.get()
    val isEmpty: Boolean get() = declinedLoads == 0 && declinedStores == 0

    fun recordLoad(label: String) {
        loads.incrementAndGet()
        totalsFor(label).loads.incrementAndGet()
    }

    fun recordStore(label: String, entrySizeBytes: Long) {
        stores.incrementAndGet()
        storeBytes.addAndGet(entrySizeBytes)
        totalsFor(label).apply {
            this.stores.incrementAndGet()
            bytes.addAndGet(entrySizeBytes)
        }
    }

    /** Sorted so reports are stable across builds. */
    fun byLabel(): Map<String, Totals> =
        perLabel.toSortedMap().mapValues { (_, t) -> Totals(t.loads.get(), t.stores.get(), t.bytes.get()) }

    private fun totalsFor(label: String) = perLabel.computeIfAbsent(label) { MutableTotals() }

    private class MutableTotals {
        val loads = AtomicInteger()
        val stores = AtomicInteger()
        val bytes = AtomicLong()
    }
}
