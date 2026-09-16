package io.github.cdsap.selectivecache

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Locks down decorating the Develocity connector — the reason this plugin can be used by a
 * Develocity customer without giving up the Develocity connector.
 */
class DevelocityDelegationFunctionalTest : AbstractFunctionalTest() {

    private val develocityCache: File get() = File(projectDir, ".caches/develocity")

    private fun develocityEntries(): Set<String> =
        develocityCache.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()

    /** Mirrors the real Develocity shape: capture the connector's config object, then wrap it. */
    private fun settingsDelegatingToDevelocity(excludedTypes: List<String>, maxStoreSizeBytes: Long = 0) {
        val excludes = excludedTypes.joinToString(", ") { "'$it'" }
        settings(
            excludedTypes = excludedTypes,
            remoteBody = """
                registerBuildCacheService(
                    com.gradle.develocity.agent.gradle.buildcache.DevelocityBuildCache,
                    io.github.cdsap.selectivecache.fixtures.FakeDevelocityBuildCacheServiceFactory)

                remote(io.github.cdsap.selectivecache.SelectiveRemoteBuildCache) {
                    push = true
                    delegateTo(com.gradle.develocity.agent.gradle.buildcache.DevelocityBuildCache) {
                        directory = new File(rootDir, '.caches/develocity')
                        server = 'https://develocity.example.com'
                    }
                    excludedTypes = [$excludes] as Set
                    maxStoreSizeBytes = $maxStoreSizeBytes
                    debug = true
                }
            """.trimIndent(),
        )
    }

    @Test
    fun `the Develocity factory is instantiated with its services injected`() {
        settingsDelegatingToDevelocity(excludedTypes = emptyList())
        buildScriptWithTasks()

        val result = run("runAll")

        assertTrue(result.output.contains("FAKE-DEVELOCITY: factory ran with"),
            "Develocity's own factory must be used:\n${result.output}")
        assertTrue(result.output.contains("DefaultBuildOperationRunner"),
            "the Develocity factory must receive its injected services")
    }

    @Test
    fun `non-excluded work is stored through the Develocity service`() {
        settingsDelegatingToDevelocity(excludedTypes = emptyList())
        buildScriptWithTasks()

        run("runAll")

        assertEquals(localEntries(), develocityEntries(),
            "with no deny-list Develocity must receive everything the local cache holds")
    }

    @Test
    fun `an excluded task type never reaches the Develocity service`() {
        settingsDelegatingToDevelocity(excludedTypes = listOf("BigOutputTask"))
        buildScriptWithTasks()

        val result = run("runAll")

        assertTrue(result.output.contains("declined remote store for BigOutputTask"))
        val onlyLocal = localEntries() - develocityEntries()
        assertEquals(1, onlyLocal.size, "exactly the excluded entry must be missing from Develocity")
        assertTrue(develocityEntries().isNotEmpty(), "non-excluded work must still reach Develocity")
    }

    @Test
    fun `Develocity still serves restores for non-excluded work`() {
        settingsDelegatingToDevelocity(excludedTypes = listOf("BigOutputTask"))
        buildScriptWithTasks()
        run("runAll")

        emptyLocalCache()
        deleteOutputs()
        val result = run("runAll")

        assertEquals(TaskOutcome.FROM_CACHE, result.task(":a:small")!!.outcome)
        assertTrue(result.output.contains("FAKE-DEVELOCITY: served"),
            "the restore must come through the Develocity service")
        assertEquals(TaskOutcome.SUCCESS, result.task(":a:big")!!.outcome,
            "the excluded type must not be restored from Develocity")
    }

    @Test
    fun `Develocity keeps ownership of the build cache description`() {
        // The Build Scan must still identify the cache as Develocity's, not ours.
        settingsDelegatingToDevelocity(excludedTypes = listOf("BigOutputTask"))
        buildScriptWithTasks()

        val result = run("runAll", "--info")

        assertTrue(result.output.contains("Using remote Develocity build cache"),
            "Develocity's describer type must win:\n" +
                result.output.lines().filter { it.contains("build cache") }.joinToString("\n"))
        assertTrue(result.output.contains("filtered by = selective-remote-build-cache"),
            "our filter should still be visible in the description")
    }

    @Test
    fun `the size filter applies to the delegated Develocity cache`() {
        settingsDelegatingToDevelocity(excludedTypes = emptyList(), maxStoreSizeBytes = 500)
        buildScriptWithTasks()

        val result = run("runAll")

        assertTrue(result.output.contains("limit is 500 bytes"))
        assertTrue(develocityEntries().size < localEntries().size)
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

    @Test
    fun `delegating to a cache that is not Develocity is rejected`() {
        settings(remoteBody = """
            registerBuildCacheService(
                io.github.cdsap.selectivecache.fixtures.NotDevelocityBuildCache,
                io.github.cdsap.selectivecache.fixtures.FakeDevelocityBuildCacheServiceFactory)

            remote(io.github.cdsap.selectivecache.SelectiveRemoteBuildCache) {
                push = true
                delegateTo(io.github.cdsap.selectivecache.fixtures.NotDevelocityBuildCache)
            }
        """.trimIndent())
        buildScriptWithTasks()

        val result = runAndFail("runAll")
        assertTrue(
            result.output.contains("filters the Develocity build cache and nothing else"),
            "expected the Develocity-only rejection but got:\n" + result.output,
        )
        assertTrue(
            result.output.contains("NotDevelocityBuildCache"),
            "the message should name the offending type",
        )
    }
}
