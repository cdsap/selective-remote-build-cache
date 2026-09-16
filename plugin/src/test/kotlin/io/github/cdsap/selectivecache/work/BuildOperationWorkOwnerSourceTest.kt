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

    private fun start(id: Long, parentId: Long?, details: Any? = null) {
        val descriptor = BuildOperationDescriptor.displayName("op-$id")
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
}
