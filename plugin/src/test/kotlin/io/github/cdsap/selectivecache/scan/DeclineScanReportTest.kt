package io.github.cdsap.selectivecache.scan

import io.github.cdsap.selectivecache.policy.DeclineTally
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * These values are the only thing in a Build Scan that tells a declined operation apart from a
 * real one, so their content is load-bearing.
 */
class DeclineScanReportTest {

    private class FakeScanReporter : ScanReporter {
        val tags = mutableListOf<String>()
        val values = mutableListOf<Pair<String, String>>()
        private var onFinish: (() -> Unit)? = null

        override fun tag(name: String) { tags += name }
        override fun value(name: String, value: String) { values += name to value }
        override fun onBuildFinished(block: () -> Unit) { onFinish = block }

        fun finishBuild() = onFinish?.invoke()
        fun valuesFor(name: String) = values.filter { it.first == name }.map { it.second }
    }

    private fun report(
        reporter: ScanReporter?,
        tally: DeclineTally = DeclineTally(),
        configuration: List<Pair<String, String>> = listOf("selective-remote-cache.excluded-types" to "A, B"),
        resolutions: () -> Unit = {},
    ) = DeclineScanReport(
        findReporter = { resolutions(); reporter },
        configuration = configuration,
        tally = tally,
    )

    @Test
    fun `registration tags the build and publishes the configuration`() {
        val reporter = FakeScanReporter()
        report(reporter).registerOnce()

        assertEquals(listOf(DeclineScanReport.TAG), reporter.tags)
        assertEquals(listOf("A, B"), reporter.valuesFor("selective-remote-cache.excluded-types"))
    }

    @Test
    fun `registration happens once however often it is called`() {
        val reporter = FakeScanReporter()
        var resolutions = 0
        val report = report(reporter, resolutions = { resolutions++ })
        repeat(5) { report.registerOnce() }

        assertEquals(1, resolutions)
        assertEquals(1, reporter.tags.size)
    }

    @Test
    fun `counters are published when the build finishes, not at registration`() {
        val reporter = FakeScanReporter()
        val tally = DeclineTally()
        report(reporter, tally).registerOnce()

        // Declines happen after registration; the numbers must reflect the end state.
        tally.recordLoad("com.example.BigTask")
        tally.recordStore("com.example.BigTask", 1380)
        assertTrue(reporter.valuesFor("selective-remote-cache.declined-loads").isEmpty())

        reporter.finishBuild()
        assertEquals(listOf("1"), reporter.valuesFor("selective-remote-cache.declined-loads"))
        assertEquals(listOf("1"), reporter.valuesFor("selective-remote-cache.declined-stores"))
        assertEquals(listOf("1380"), reporter.valuesFor("selective-remote-cache.declined-store-bytes"))
    }

    @Test
    fun `explicit zeros are published so absence is never ambiguous`() {
        val reporter = FakeScanReporter()
        report(reporter).registerOnce()
        reporter.finishBuild()

        assertEquals(listOf("0"), reporter.valuesFor("selective-remote-cache.declined-loads"))
        assertEquals(listOf("0"), reporter.valuesFor("selective-remote-cache.declined-stores"))
    }

    @Test
    fun `a per-label breakdown is published, sorted`() {
        val reporter = FakeScanReporter()
        val tally = DeclineTally()
        tally.recordStore("com.example.Zed", 10)
        tally.recordLoad("com.example.Alpha")
        report(reporter, tally).registerOnce()
        reporter.finishBuild()

        assertEquals(
            listOf(
                "com.example.Alpha: loads=1 stores=0 bytes=0",
                "com.example.Zed: loads=0 stores=1 bytes=10",
            ),
            reporter.valuesFor("selective-remote-cache.declined"),
        )
    }

    @Test
    fun `a build without Develocity is a no-op`() {
        // Must not throw, and must not keep retrying the lookup on every cache operation.
        var resolutions = 0
        val report = report(reporter = null, resolutions = { resolutions++ })
        repeat(3) { report.registerOnce() }
        assertEquals(1, resolutions)
    }
}
