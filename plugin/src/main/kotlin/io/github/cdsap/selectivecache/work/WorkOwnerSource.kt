package io.github.cdsap.selectivecache.work

/**
 * Port: resolves the task / transform type the current thread is doing work for.
 *
 * Gradle's BuildCacheService SPI is handed nothing but a cache key, so the owning work unit has to
 * be recovered some other way. This interface is that seam — it keeps the recovery mechanism out
 * of the filtering code, and lets the rules be tested without a running Gradle build.
 */
fun interface WorkOwnerSource {

    /** Fully-qualified class name of the owning task / TransformAction, or null if unknown. */
    fun currentOwner(): String?

    companion object {
        /** Used when no rule needs the owner, so nothing is tracked and nothing is looked up. */
        val None = WorkOwnerSource { null }
    }
}
