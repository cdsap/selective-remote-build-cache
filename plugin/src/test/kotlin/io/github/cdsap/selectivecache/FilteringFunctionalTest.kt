package io.github.cdsap.selectivecache

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilteringFunctionalTest : AbstractFunctionalTest() {

    @Test
    fun `an excluded task type is held back from the remote cache but still stored locally`() {
        settings(excludedTypes = listOf("BigOutputTask"))
        buildScriptWithTasks()

        val result = run("runAll")

        assertTrue(result.output.contains("declined remote store for BigOutputTask"),
            "expected a skip for the excluded type:\n${result.output}")
        // The excluded entry is in the local cache and absent from the remote one.
        val onlyLocal = localEntries() - remoteEntries()
        assertEquals(1, onlyLocal.size, "exactly the excluded task's entry should be local-only")
        assertTrue(remoteEntries().isNotEmpty(), "the non-excluded task must still reach the remote")
    }

    @Test
    fun `with no exclusions every entry reaches the remote cache`() {
        settings(excludedTypes = emptyList())
        buildScriptWithTasks()

        run("runAll")

        assertEquals(localEntries(), remoteEntries(),
            "without a deny-list the two tiers must hold the same entries")
    }

    @Test
    fun `the local cache still serves an excluded task type`() {
        // This is the whole point of the plugin: doNotCacheIf would lose this.
        settings(excludedTypes = listOf("BigOutputTask"))
        buildScriptWithTasks()
        run("runAll")

        emptyRemoteCache()
        deleteOutputs()
        val result = run("runAll")

        assertEquals(TaskOutcome.FROM_CACHE, result.task(":a:big")!!.outcome,
            "the excluded type must still be restorable from the local cache")
    }

    @Test
    fun `an excluded task type is not restored from the remote cache`() {
        settings(excludedTypes = listOf("BigOutputTask"))
        buildScriptWithTasks()
        run("runAll")

        emptyLocalCache()
        deleteOutputs()
        val result = run("runAll")

        assertEquals(TaskOutcome.SUCCESS, result.task(":a:big")!!.outcome,
            "with the local cache empty the excluded type must re-execute")
        assertEquals(TaskOutcome.FROM_CACHE, result.task(":a:small")!!.outcome,
            "a non-excluded type must still be served by the remote")
        assertTrue(result.output.contains("declined remote load for BigOutputTask"))
    }

    @Test
    fun `wildcard patterns select task types`() {
        settings(excludedTypes = listOf("Big*"))
        buildScriptWithTasks()

        val result = run("runAll")

        assertTrue(result.output.contains("declined remote store for BigOutputTask"))
        assertEquals(1, (localEntries() - remoteEntries()).size)
    }

    @Test
    fun `the size filter holds back large entries without any deny-list`() {
        settings(excludedTypes = emptyList(), maxStoreSizeBytes = 500)
        buildScriptWithTasks()

        val result = run("runAll")

        assertTrue(result.output.contains("limit is 500 bytes"), "expected a size-based skip:\n${result.output}")
        assertTrue(remoteEntries().size < localEntries().size, "large entries must not reach the remote")
    }

    @Test
    fun `attribution is correct when tasks run in parallel across projects`() {
        val projects = listOf("a", "b", "c", "d")
        settings(projects = projects, excludedTypes = listOf("BigOutputTask"))
        buildScriptWithTasks(projects)

        val result = run("runAll", "--parallel")

        val skips = Regex("declined remote store for BigOutputTask").findAll(result.output).count()
        assertEquals(projects.size, skips, "every excluded task, and only those, must be skipped")
        assertEquals(projects.size, (localEntries() - remoteEntries()).size)
    }

    @Test
    fun `filtering still applies on a configuration cache hit`() {
        settings(excludedTypes = listOf("BigOutputTask"), localEnabled = false)
        buildScriptWithTasks()

        run("runAll", "--configuration-cache")
        deleteOutputs()
        val result = run("runAll", "--configuration-cache")

        assertTrue(result.output.contains("Configuration cache entry reused"),
            "expected a configuration cache hit:\n${result.output}")
        assertTrue(result.output.contains("declined remote load for BigOutputTask"),
            "filtering must survive a configuration cache hit")
    }
}
