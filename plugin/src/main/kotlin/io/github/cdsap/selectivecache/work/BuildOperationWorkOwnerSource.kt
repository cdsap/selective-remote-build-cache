package io.github.cdsap.selectivecache.work

import org.gradle.api.internal.tasks.execution.ExecuteTaskBuildOperationType
import org.gradle.internal.operations.BuildOperationDescriptor
import org.gradle.internal.operations.BuildOperationListener
import org.gradle.internal.operations.CurrentBuildOperationRef
import org.gradle.internal.operations.OperationFinishEvent
import org.gradle.internal.operations.OperationIdentifier
import org.gradle.internal.operations.OperationProgressEvent
import org.gradle.internal.operations.OperationStartEvent
import org.gradle.operations.dependencies.transforms.ExecutePlannedTransformStepBuildOperationType
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers "which task / transform is the current thread working for?".
 *
 * Gradle's BuildCacheService SPI only ever sees a BuildCacheKey, so the owning work unit
 * has to be recovered some other way. Gradle wraps every remote cache load/store in a build
 * operation (OpFiringRemoteBuildCacheServiceHandle), so CurrentBuildOperationRef is populated
 * on our thread — we just need opId -> owner.
 *
 * Rather than record every operation and walk the parent chain, ownership is propagated down
 * the subtree at start time: an operation inherits its parent's owner. That keeps the map to
 * operations that actually sit underneath a task or transform, and makes the lookup O(1).
 */
class BuildOperationWorkOwnerSource : BuildOperationListener, WorkOwnerSource {

    private val owners = ConcurrentHashMap<Long, String>()

    override fun started(descriptor: BuildOperationDescriptor, startEvent: OperationStartEvent) {
        val id = descriptor.id?.id ?: return
        when (val details = descriptor.details) {
            is ExecuteTaskBuildOperationType.Details ->
                owners[id] = details.taskClass.name
            is ExecutePlannedTransformStepBuildOperationType.Details ->
                owners[id] = details.transformActionClass.name
            else -> {
                // An artifact transform runs inside whatever work needed the artifact. For AGP's
                // dexing transforms that is the *input snapshotting* of the task consuming them,
                // so the transform's own cache entries sit under a task operation. Inheriting
                // there would label every dependency's dex with DexMergingTask: excluding that
                // one task type declined ~600 unrelated entries on Now in Android. Stop at the
                // boundary instead — an entry nobody can identify is left alone, not declined.
                if (isTransformWork(descriptor)) return
                val parent = descriptor.parentId?.id ?: return
                owners[parent]?.let { owners[id] = it }
            }
        }
    }

    /**
     * Neither spelling is public API — Gradle emits the chain wrapper around a whole transform
     * chain and the unit-of-work operation around each step — so both are matched, and the
     * functional tests run a real build to catch it if the wording ever moves.
     */
    private fun isTransformWork(descriptor: BuildOperationDescriptor): Boolean {
        val displayName = descriptor.displayName ?: return false
        return displayName.startsWith(TRANSFORM_CHAIN) || displayName.startsWith(TRANSFORM_STEP)
    }

    override fun progress(operationIdentifier: OperationIdentifier, progressEvent: OperationProgressEvent) = Unit

    override fun finished(descriptor: BuildOperationDescriptor, finishEvent: OperationFinishEvent) {
        descriptor.id?.id?.let(owners::remove)
    }

    /** Fully-qualified name of the task class / TransformAction the current thread serves. */
    override fun currentOwner(): String? =
        CurrentBuildOperationRef.instance().get()?.id?.id?.let(owners::get)

    private companion object {
        const val TRANSFORM_CHAIN = "Execute transform chain:"
        const val TRANSFORM_STEP = "Execute unit of work: TRANSFORM"
    }
}
