package io.github.cdsap.selectivecache.scan

/**
 * Port: annotates a Build Scan.
 *
 * Exists so the reporting logic does not depend on Develocity being present, or on the reflection
 * used to reach it.
 */
interface ScanReporter {

    fun tag(name: String)

    fun value(name: String, value: String)

    /** Runs [block] once the build has finished, before the scan is published. */
    fun onBuildFinished(block: () -> Unit)
}
