package io.github.cdsap.selectivecache.fixtures

import org.gradle.api.Action

/**
 * Shaped like the Develocity extension the plugin reaches reflectively: an extension registered on
 * Settings exposing getBuildScan(), whose object has tag/value/buildFinished. Everything it
 * receives is printed so functional tests can assert on it.
 */
open class FakeDevelocityExtension {
    val buildScan: FakeBuildScan = FakeBuildScan()

    /**
     * Mirrors DevelocityConfiguration.getBuildCache(), which returns the cache *type*, not an
     * instance. This is what the settings plugin reads when no delegateTo was written.
     */
    open fun getBuildCache(): Class<*> =
        com.gradle.develocity.agent.gradle.buildcache.DevelocityBuildCache::class.java
}

open class FakeBuildScan {
    fun tag(name: String) = println("SCAN-TAG: $name")
    fun value(name: String, value: String) = println("SCAN-VALUE: $name = $value")

    fun buildFinished(action: Action<Any>) = println("SCAN-BUILD-FINISHED-REGISTERED")

    /**
     * Gradle decorates extension objects with Groovy `Closure` overloads alongside the `Action`
     * ones. Reflection that matches only on arity picks whichever `java.lang.Class#getMethods`
     * happens to return first and then fails with "argument type mismatch". This overload exists
     * so that mistake cannot come back unnoticed.
     */
    @Suppress("unused")
    fun buildFinished(closure: groovy.lang.Closure<*>) = println("SCAN-BUILD-FINISHED-WRONG-OVERLOAD")
}

/** An extension that looks right but blows up — the annotator must swallow this. */
open class HostileDevelocityExtension {
    fun getBuildScan(): Any = throw UnsupportedOperationException("nope")
}
