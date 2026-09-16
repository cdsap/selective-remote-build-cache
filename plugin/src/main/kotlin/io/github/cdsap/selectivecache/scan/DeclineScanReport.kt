package io.github.cdsap.selectivecache.scan

import io.github.cdsap.selectivecache.policy.DeclineTally
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publishes what the filter declined to the Build Scan.
 *
 * This matters more than it looks. Gradle opens the remote load/store build operation *around* the
 * cache service call, so declining inside it cannot stop the operation being recorded: a declined
 * store still appears under "Store" with the entry's archive size, and a declined load still
 * appears as "Miss". These custom values are the only thing in a scan that tells the two apart.
 *
 * @param findReporter resolved lazily, on the first cache operation. When the cache service is
 *   built, the settings object is not yet attached to Gradle and the Develocity extension cannot
 *   be found; by the first load/store we are inside task execution and it can.
 */
class DeclineScanReport(
    private val findReporter: () -> ScanReporter?,
    private val configuration: List<Pair<String, String>>,
    private val tally: DeclineTally,
) {
    private val registered = AtomicBoolean()

    /** Idempotent; safe to call on every cache operation. */
    fun registerOnce() {
        if (!registered.compareAndSet(false, true)) return
        val reporter = findReporter() ?: return
        reporter.tag(TAG)
        configuration.forEach { (name, value) -> reporter.value(name, value) }
        reporter.onBuildFinished { publishTo(reporter) }
    }

    private fun publishTo(reporter: ScanReporter) {
        reporter.value("$PREFIX.declined-loads", tally.declinedLoads.toString())
        reporter.value("$PREFIX.declined-stores", tally.declinedStores.toString())
        reporter.value("$PREFIX.declined-store-bytes", tally.declinedStoreBytes.toString())
        tally.byLabel().forEach { (label, totals) ->
            reporter.value("$PREFIX.declined", "$label: loads=${totals.loads} stores=${totals.stores} bytes=${totals.bytes}")
        }
    }

    companion object {
        const val TAG = "selective-remote-cache"
        const val PREFIX = "selective-remote-cache"
    }
}
