package io.github.cdsap.selectivecache.policy

/** The outcome of applying [RemoteCacheFilter] to one remote cache operation. */
sealed interface FilterDecision {

    /** Let the operation through to the real cache. */
    data object Allow : FilterDecision

    /**
     * Do not contact the real cache.
     *
     * @param attributedTo the label this decline is counted under — a work type, or a bucket
     *   such as "(over size limit)". Reported on the Build Scan.
     * @param explanation short human-readable reason, used in logs.
     */
    data class Decline(val attributedTo: String, val explanation: String) : FilterDecision
}

/**
 * Decides whether a remote cache operation should happen. Pure: no Gradle, no I/O, no state.
 *
 * The rules, in order:
 *  1. the work unit's type is on the deny-list      -> decline (loads and/or stores)
 *  2. a store's entry is larger than the size limit -> decline
 *  3. otherwise                                     -> allow
 */
class RemoteCacheFilter(
    private val excludedTypes: TypePatterns,
    private val maxStoreSizeBytes: Long,
    private val excludeLoads: Boolean,
    private val excludeStores: Boolean,
) {
    /**
     * Whether any rule depends on knowing which work unit is running. False means the caller can
     * skip the build-operation lookup entirely, which is the whole cost of this plugin.
     */
    val needsWorkOwner: Boolean get() = !excludedTypes.isEmpty

    fun forLoad(workOwner: String?): FilterDecision =
        if (excludeLoads && excludedTypes.matches(workOwner)) {
            FilterDecision.Decline(workOwner ?: UNKNOWN_OWNER, EXCLUDED_TYPE)
        } else {
            FilterDecision.Allow
        }

    fun forStore(workOwner: String?, entrySizeBytes: Long): FilterDecision = when {
        excludeStores && excludedTypes.matches(workOwner) ->
            FilterDecision.Decline(workOwner ?: UNKNOWN_OWNER, EXCLUDED_TYPE)

        maxStoreSizeBytes > 0 && entrySizeBytes > maxStoreSizeBytes ->
            FilterDecision.Decline(OVER_SIZE_LIMIT, "limit is $maxStoreSizeBytes bytes")

        else -> FilterDecision.Allow
    }

    companion object {
        const val UNKNOWN_OWNER = "(unknown)"
        const val OVER_SIZE_LIMIT = "(over size limit)"
        private const val EXCLUDED_TYPE = "excluded type"
    }
}
