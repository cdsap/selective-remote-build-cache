package io.github.cdsap.selectivecache.work

import org.gradle.api.internal.tasks.execution.ExecuteTaskBuildOperationType
import org.gradle.internal.operations.BuildOperationDescriptor
import org.gradle.internal.operations.CurrentBuildOperationRef
import org.gradle.internal.operations.DefaultBuildOperationRef
import org.gradle.internal.operations.OperationFinishEvent
import org.gradle.internal.operations.OperationIdentifier
import org.gradle.internal.operations.OperationStartEvent
import org.gradle.operations.dependencies.transforms.ExecutePlannedTransformStepBuildOperationType
import org.gradle.operations.dependencies.transforms.PlannedTransformStepIdentity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the tracker with synthetic build operations. This is where the ownership-propagation
 * rule is actually pinned down — the production path only exercises it indirectly.
 */
class BuildOperationWorkOwnerSourceTest {

    private val tracker = BuildOperationWorkOwnerSource()

    @AfterEach
    fun clearOperationRef() = CurrentBuildOperationRef.instance().clear()

    // ---------- helpers ----------

    private fun taskDetails(taskClass: Class<*>) = object : ExecuteTaskBuildOperationType.Details {
        override fun getBuildPath() = ":"
        override fun getTaskPath() = ":someTask"
        override fun getTaskId() = 1L
        override fun getTaskClass(): Class<*> = taskClass
    }

    private fun transformDetails(actionClass: Class<*>) =
        object : ExecutePlannedTransformStepBuildOperationType.Details {
            override fun getPlannedTransformStepIdentity(): PlannedTransformStepIdentity =
                throw UnsupportedOperationException("not needed")
            override fun getTransformActionClass(): Class<*> = actionClass
            override fun getTransformerName() = "transformer"
            override fun getSubjectName() = "subject"
        }

    private fun start(id: Long, parentId: Long?, details: Any? = null, displayName: String = "op-$id") {
        val descriptor = BuildOperationDescriptor.displayName(displayName)
            .apply { if (details != null) details(details) }
            .build(OperationIdentifier(id), parentId?.let(::OperationIdentifier))
        tracker.started(descriptor, OperationStartEvent(0))
    }

    private fun finish(id: Long, parentId: Long?) {
        val descriptor = BuildOperationDescriptor.displayName("op-$id")
            .build(OperationIdentifier(id), parentId?.let(::OperationIdentifier))
        tracker.finished(descriptor, OperationFinishEvent(0, 1, null, null))
    }

    private fun asCurrentOperation(id: Long, parentId: Long?): String? {
        CurrentBuildOperationRef.instance()
            .set(DefaultBuildOperationRef(OperationIdentifier(id), parentId?.let(::OperationIdentifier)))
        return tracker.currentOwner()
    }

    private class TaskA
    private class TaskB
    private class TransformA

    // ---------- tests ----------

    @Test
    fun `a task operation owns itself`() {
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        assertEquals(TaskA::class.java.name, asCurrentOperation(1, null))
    }

    @Test
    fun `a transform operation reports its action class`() {
        start(id = 1, parentId = null, details = transformDetails(TransformA::class.java))
        assertEquals(TransformA::class.java.name, asCurrentOperation(1, null))
    }

    @Test
    fun `ownership propagates to a direct child`() {
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        start(id = 2, parentId = 1)
        assertEquals(TaskA::class.java.name, asCurrentOperation(2, 1))
    }

    @Test
    fun `ownership propagates down a deep subtree`() {
        // The remote cache load operation sits several levels below the task operation.
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        start(id = 2, parentId = 1)
        start(id = 3, parentId = 2)
        start(id = 4, parentId = 3)
        assertEquals(TaskA::class.java.name, asCurrentOperation(4, 3))
    }

    @Test
    fun `operations outside any task have no owner`() {
        start(id = 1, parentId = null)
        start(id = 2, parentId = 1)
        assertNull(asCurrentOperation(2, 1))
    }

    @Test
    fun `sibling tasks do not leak ownership to each other`() {
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        start(id = 2, parentId = null, details = taskDetails(TaskB::class.java))
        start(id = 11, parentId = 1)
        start(id = 22, parentId = 2)

        assertEquals(TaskA::class.java.name, asCurrentOperation(11, 1))
        assertEquals(TaskB::class.java.name, asCurrentOperation(22, 2))
    }

    @Test
    fun `finishing an operation removes it from the map`() {
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        start(id = 2, parentId = 1)
        finish(id = 2, parentId = 1)
        assertNull(asCurrentOperation(2, 1))
        // The parent task is still in flight and still resolvable.
        assertEquals(TaskA::class.java.name, asCurrentOperation(1, null))
    }

    @Test
    fun `finishing a task releases it so the map does not grow unbounded`() {
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        finish(id = 1, parentId = null)
        assertNull(asCurrentOperation(1, null))
    }

    @Test
    fun `a child started after its parent finished inherits nothing`() {
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        finish(id = 1, parentId = null)
        start(id = 2, parentId = 1)
        assertNull(asCurrentOperation(2, 1))
    }

    @Test
    fun `no current operation means no owner`() {
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        CurrentBuildOperationRef.instance().clear()
        assertNull(tracker.currentOwner())
    }

    @Test
    fun `a nested task operation overrides the inherited owner`() {
        // Defensive: if a task operation ever appears beneath another, the innermost wins.
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        start(id = 2, parentId = 1, details = taskDetails(TaskB::class.java))
        assertEquals(TaskB::class.java.name, asCurrentOperation(2, 1))
    }

    @Test
    fun `concurrent tasks on different threads are attributed independently`() {
        // Mirrors --parallel: several task subtrees alive at once, each resolved per thread.
        val taskCount = 8
        val ready = CountDownLatch(taskCount)
        val go = CountDownLatch(1)
        val results = arrayOfNulls<String>(taskCount)

        val classes = listOf(TaskA::class.java, TaskB::class.java, TransformA::class.java)
        val threads = (0 until taskCount).map { i ->
            val taskId = (i + 1) * 100L
            val childId = taskId + 1
            Thread {
                val owner = classes[i % classes.size]
                start(id = taskId, parentId = null, details = taskDetails(owner))
                start(id = childId, parentId = taskId)
                ready.countDown()
                go.await(5, TimeUnit.SECONDS)
                results[i] = asCurrentOperation(childId, taskId)
                CurrentBuildOperationRef.instance().clear()
            }
        }
        threads.forEach(Thread::start)
        ready.await(5, TimeUnit.SECONDS)
        go.countDown()
        threads.forEach { it.join(5_000) }

        (0 until taskCount).forEach { i ->
            assertEquals(classes[i % classes.size].name, results[i], "thread $i was mis-attributed")
        }
    }

    // ---------- artifact transforms running inside a task ----------
    //
    // Gradle runs an unplanned transform inside whatever work needed the artifact. The operation
    // tree below is the one AGP produces on Now in Android: the dexing transform for every
    // dependency runs while the dex-merging task snapshots its inputs, so the transform's cache
    // entries hang under that task's operation. Before this was handled, excluding
    // DexMergingTask declined ~600 dependency entries that had nothing to do with it.

    @Test
    fun `a transform running inside a task does not inherit the task type`() {
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        start(id = 2, parentId = 1, displayName = "Snapshot task inputs for :app:mergeExtDexDemoDebug")
        start(id = 3, parentId = 2, displayName = "Execute transform chain: guava-33.0.jar (com.google.guava:guava:33.0)")
        start(id = 4, parentId = 3, displayName = "Execute unit of work: TRANSFORM")
        start(id = 5, parentId = 4, displayName = "Load entry abc123 from remote build cache")

        assertNull(asCurrentOperation(5, 4), "a dependency's transform entry must not be attributed to the task")
    }

    @Test
    fun `the unit-of-work operation alone is enough to stop inheritance`() {
        // Defensive: the chain wrapper is not always present.
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        start(id = 2, parentId = 1, displayName = "Execute unit of work: TRANSFORM")
        start(id = 3, parentId = 2)

        assertNull(asCurrentOperation(3, 2))
    }

    @Test
    fun `the task's own cache entry is still attributed to the task`() {
        // The load that belongs to the task itself sits outside any transform subtree, and is
        // exactly the one an exclusion is meant to catch.
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        start(id = 2, parentId = 1, displayName = "Load entry abc123 from remote build cache")

        assertEquals(TaskA::class.java.name, asCurrentOperation(2, 1))
    }

    @Test
    fun `a planned transform step under a task still reports its own action class`() {
        // Planned steps carry their own details, so they name the transform rather than stopping
        // at unknown. Excluding a TransformAction type keeps working.
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        start(id = 2, parentId = 1, details = transformDetails(TransformA::class.java))
        start(id = 3, parentId = 2, displayName = "Load entry abc123 from remote build cache")

        assertEquals(TransformA::class.java.name, asCurrentOperation(3, 2))
    }

    @Test
    fun `work below a transform in a task is not attributed to the task either`() {
        // Whatever the transform spawns is the transform's business, not the task's.
        start(id = 1, parentId = null, details = taskDetails(TaskA::class.java))
        start(id = 2, parentId = 1, displayName = "Execute transform chain: dep.jar (g:dep:1)")
        start(id = 3, parentId = 2, displayName = "Execute unit of work: TRANSFORM")
        start(id = 4, parentId = 3)
        start(id = 5, parentId = 4)

        assertNull(asCurrentOperation(5, 4))
    }
}
