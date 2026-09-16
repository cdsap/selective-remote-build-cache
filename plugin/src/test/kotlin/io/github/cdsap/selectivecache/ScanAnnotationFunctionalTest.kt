package io.github.cdsap.selectivecache

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Covers the reflective hop from the cache factory to a Develocity-shaped extension. */
class ScanAnnotationFunctionalTest : AbstractFunctionalTest() {

    @Test
    fun `a develocity extension is annotated with the filter configuration`() {
        settings(
            excludedTypes = listOf("BigOutputTask"),
            preamble = "extensions.create('develocity', io.github.cdsap.selectivecache.fixtures.FakeDevelocityExtension)",
        )
        buildScriptWithTasks()

        val output = run("runAll").output

        assertTrue(output.contains("SCAN-TAG: selective-remote-cache"),
            "the build should be tagged so these scans are findable:\n$output")
        assertTrue(output.contains("SCAN-VALUE: selective-remote-cache.excluded-types = BigOutputTask"),
            "the scan must record what was excluded:\n$output")
        assertTrue(output.contains("SCAN-VALUE: selective-remote-cache.max-store-size-bytes = unlimited"))
        assertTrue(output.contains("SCAN-BUILD-FINISHED-REGISTERED"),
            "counters must be published at build end, not at cache-service close")
    }

    @Test
    fun `the Action overload of buildFinished is selected, not the Groovy Closure one`() {
        settings(
            excludedTypes = listOf("BigOutputTask"),
            preamble = "extensions.create('develocity', io.github.cdsap.selectivecache.fixtures.FakeDevelocityExtension)",
        )
        buildScriptWithTasks()

        val output = run("runAll").output

        assertTrue(output.contains("SCAN-BUILD-FINISHED-REGISTERED"))
        assertFalse(output.contains("SCAN-BUILD-FINISHED-WRONG-OVERLOAD"),
            "matching reflective methods by arity alone picks the Closure overload and blows up")
    }

    @Test
    fun `the legacy gradleEnterprise extension name is also recognised`() {
        settings(
            excludedTypes = listOf("BigOutputTask"),
            preamble = "extensions.create('gradleEnterprise', io.github.cdsap.selectivecache.fixtures.FakeDevelocityExtension)",
        )
        buildScriptWithTasks()

        assertTrue(run("runAll").output.contains("SCAN-TAG: selective-remote-cache"))
    }

    @Test
    fun `the size limit is reported when one is configured`() {
        settings(
            excludedTypes = emptyList(),
            maxStoreSizeBytes = 500,
            preamble = "extensions.create('develocity', io.github.cdsap.selectivecache.fixtures.FakeDevelocityExtension)",
        )
        buildScriptWithTasks()

        val output = run("runAll").output
        assertTrue(output.contains("SCAN-VALUE: selective-remote-cache.max-store-size-bytes = 500"))
        assertTrue(output.contains("SCAN-VALUE: selective-remote-cache.excluded-types = (none)"))
    }

    @Test
    fun `a build with no develocity extension is unaffected`() {
        settings(excludedTypes = listOf("BigOutputTask"))
        buildScriptWithTasks()

        val output = run("runAll").output
        assertFalse(output.contains("SCAN-"), "nothing should be published without Develocity")
        assertTrue(output.contains("declined remote store for BigOutputTask"), "filtering must still work")
    }

    @Test
    fun `an extension that throws cannot break the build`() {
        // Scan annotation is a diagnostic nicety; a Develocity API change must never fail a build.
        settings(
            excludedTypes = listOf("BigOutputTask"),
            preamble = "extensions.create('develocity', io.github.cdsap.selectivecache.fixtures.HostileDevelocityExtension)",
        )
        buildScriptWithTasks()

        val output = run("runAll").output
        assertTrue(output.contains("declined remote store for BigOutputTask"),
            "filtering must survive a hostile extension:\n$output")
    }
}
