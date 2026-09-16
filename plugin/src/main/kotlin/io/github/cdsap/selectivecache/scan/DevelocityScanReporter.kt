package io.github.cdsap.selectivecache.scan

import org.gradle.api.Action
import org.gradle.api.internal.GradleInternal
import org.gradle.api.invocation.Gradle

/**
 * Adapter: implements [ScanReporter] against the Develocity Gradle plugin.
 *
 * Gradle fires the remote load/store build operation *around* our service call, so declining
 * inside it cannot stop the operation being recorded: a scan shows the declined stores under
 * "Store" (sized by the entry's archive size, not by bytes transferred) and the declined loads
 * under "Miss". That is indistinguishable from a genuine miss or a real upload.
 *
 * Since the operation cannot be suppressed, the next best thing is to make the scan say so.
 *
 * Reached reflectively so the plugin keeps working with no Develocity plugin, with either the
 * `develocity` or the legacy `gradleEnterprise` extension, and across agent versions.
 */
class DevelocityScanReporter private constructor(private val buildScan: Any) : ScanReporter {

    override fun tag(name: String) = quietly {
        method("tag", String::class.java).invoke(buildScan, name)
    }

    override fun value(name: String, value: String) = quietly {
        method("value", String::class.java, String::class.java).invoke(buildScan, name, value)
    }

    /**
     * Runs [block] once the build has finished but before the scan is published, which is when
     * the skip counters are final. Registering at this point is deliberate: doing it from the
     * cache service's close() would race scan publication.
     */
    override fun onBuildFinished(block: () -> Unit) = quietly {
        val action = Action<Any> { quietly(block) }
        method("buildFinished", Action::class.java).invoke(buildScan, action)
    }

    /**
     * Matches on parameter types, not just arity. Gradle decorates extension objects with extra
     * Groovy `Closure` overloads, so `buildFinished(Closure)` can shadow `buildFinished(Action)`
     * and reflection then fails with "argument type mismatch".
     */
    private fun method(name: String, vararg parameterTypes: Class<*>) =
        buildScan.javaClass.methods.first {
            it.name == name && it.parameterTypes.contentEquals(parameterTypes)
        }

    /** Scan annotation is a diagnostic nicety; it must never be able to fail a build. */
    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        } catch (_: LinkageError) {
        }
    }

    companion object {
        private val EXTENSION_NAMES = listOf("develocity", "gradleEnterprise")

        /**
         * Must be called during task execution, not while the cache service is being created:
         * at cache-controller creation time `GradleInternal.getSettings()` still throws
         * "The settings are not yet available for build ':'".
         */
        fun findOrNull(gradle: Gradle): DevelocityScanReporter? = try {
            val settings = (gradle as GradleInternal).settings
            val extension = EXTENSION_NAMES.firstNotNullOfOrNull { settings.extensions.findByName(it) }
            extension
                ?.let { it.javaClass.methods.first { m -> m.name == "getBuildScan" && m.parameterCount == 0 } .invoke(it) }
                ?.let(::DevelocityScanReporter)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}
