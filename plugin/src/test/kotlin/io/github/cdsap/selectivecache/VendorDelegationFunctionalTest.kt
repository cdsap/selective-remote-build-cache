package io.github.cdsap.selectivecache

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Locks down decorating a connector we do not control — the reason this plugin can be used by a
 * Develocity customer without giving up the Develocity connector.
 */
class VendorDelegationFunctionalTest : AbstractFunctionalTest() {

    private val vendorCache: File get() = File(projectDir, ".caches/vendor")

    private fun vendorEntries(): Set<String> =
        vendorCache.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()

    /** Mirrors the real Develocity shape: capture the vendor's config object, then wrap it. */
    private fun settingsDelegatingToVendor(excludedTypes: List<String>, maxStoreSizeBytes: Long = 0) {
        val excludes = excludedTypes.joinToString(", ") { "'$it'" }
        settings(
            excludedTypes = excludedTypes,
            remoteBody = """
                registerBuildCacheService(
                    io.github.cdsap.selectivecache.fixtures.FakeVendorBuildCache,
                    io.github.cdsap.selectivecache.fixtures.FakeVendorBuildCacheServiceFactory)

                remote(io.github.cdsap.selectivecache.SelectiveRemoteBuildCache) {
                    push = true
                    delegateTo(io.github.cdsap.selectivecache.fixtures.FakeVendorBuildCache) {
                        directory = new File(rootDir, '.caches/vendor')
                        vendorToken = 'token'
                    }
                    excludedTypes = [$excludes] as Set
                    maxStoreSizeBytes = $maxStoreSizeBytes
                    debug = true
                }
            """.trimIndent(),
        )
    }

    @Test
    fun `the vendor factory is instantiated with its services injected`() {
        settingsDelegatingToVendor(excludedTypes = emptyList())
        buildScriptWithTasks()

        val result = run("runAll")

        assertTrue(result.output.contains("FAKE-VENDOR: factory ran with"),
            "the vendor's own factory must be used:\n${result.output}")
        assertTrue(result.output.contains("DefaultBuildOperationRunner"),
            "the vendor factory must receive its injected services")
    }

    @Test
    fun `non-excluded work is stored through the vendor service`() {
        settingsDelegatingToVendor(excludedTypes = emptyList())
        buildScriptWithTasks()

        run("runAll")

        assertEquals(localEntries(), vendorEntries(),
            "with no deny-list the vendor must receive everything the local cache holds")
    }

    @Test
    fun `an excluded task type never reaches the vendor service`() {
        settingsDelegatingToVendor(excludedTypes = listOf("BigOutputTask"))
        buildScriptWithTasks()

        val result = run("runAll")

        assertTrue(result.output.contains("declined remote store for BigOutputTask"))
        val onlyLocal = localEntries() - vendorEntries()
        assertEquals(1, onlyLocal.size, "exactly the excluded entry must be missing from the vendor")
        assertTrue(vendorEntries().isNotEmpty(), "non-excluded work must still reach the vendor")
    }

    @Test
    fun `the vendor still serves restores for non-excluded work`() {
        settingsDelegatingToVendor(excludedTypes = listOf("BigOutputTask"))
        buildScriptWithTasks()
        run("runAll")

        emptyLocalCache()
        deleteOutputs()
        val result = run("runAll")

        assertEquals(TaskOutcome.FROM_CACHE, result.task(":a:small")!!.outcome)
        assertTrue(result.output.contains("FAKE-VENDOR: served"),
            "the restore must come through the vendor service")
        assertEquals(TaskOutcome.SUCCESS, result.task(":a:big")!!.outcome,
            "the excluded type must not be restored from the vendor")
    }

    @Test
    fun `the vendor keeps ownership of the build cache description`() {
        // The Build Scan must still identify the cache as the vendor's, not ours.
        settingsDelegatingToVendor(excludedTypes = listOf("BigOutputTask"))
        buildScriptWithTasks()

        val result = run("runAll", "--info")

        assertTrue(result.output.contains("Using remote fake-vendor build cache"),
            "the vendor's describer type must win:\n" +
                result.output.lines().filter { it.contains("build cache") }.joinToString("\n"))
        assertTrue(result.output.contains("filtered by = selective-remote-build-cache"),
            "our filter should still be visible in the description")
    }

    @Test
    fun `the size filter applies to a delegated vendor cache`() {
        settingsDelegatingToVendor(excludedTypes = emptyList(), maxStoreSizeBytes = 500)
        buildScriptWithTasks()

        val result = run("runAll")

        assertTrue(result.output.contains("limit is 500 bytes"))
        assertTrue(vendorEntries().size < localEntries().size)
    }

    @Test
    fun `a missing delegateTo is rejected with a clear message`() {
        settings(remoteBody = """
            remote(io.github.cdsap.selectivecache.SelectiveRemoteBuildCache) {
                push = true
            }
        """.trimIndent())
        buildScriptWithTasks()

        val result = runAndFail("runAll")
        assertTrue(result.output.contains("needs a 'delegateTo' cache to filter in front of"),
            "expected a clear configuration message but got:\n" + result.output)
    }
}
