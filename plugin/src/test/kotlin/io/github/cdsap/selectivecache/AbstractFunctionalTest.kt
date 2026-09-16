package io.github.cdsap.selectivecache

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties

/**
 * Spawns real Gradle builds. These are what actually prove the wiring the unit tests cannot:
 * factory instantiation, build-operation correlation, and configuration-cache survival.
 */
abstract class AbstractFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    /** Shared across tests so the TestKit daemon is reused instead of respawned per test. */
    private val testKitDir: File = File(System.getProperty("java.io.tmpdir"), "selective-cache-testkit")

    protected val localCache: File get() = File(projectDir, ".caches/local")
    protected val remoteCache: File get() = File(projectDir, ".caches/remote")

    /** Entries actually present in the stand-in remote, excluding the cache's own bookkeeping. */
    protected fun remoteEntries(): Set<String> = entriesIn(remoteCache)

    protected fun localEntries(): Set<String> = entriesIn(localCache)

    private fun entriesIn(dir: File): Set<String> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name != "gc.properties" && !it.name.endsWith(".lock") }
            .map { it.name }.toSet()

    /**
     * Classpath of the plugin under test, plus this test source set so functional tests can put
     * their own fixtures (e.g. the fake Develocity connector) on the settings script classpath.
     */
    private fun pluginClasspath(): List<File> {
        val metadata = javaClass.classLoader.getResource("plugin-under-test-metadata.properties")
            ?: error("plugin-under-test-metadata.properties missing; is java-gradle-plugin applied?")
        val implementation = metadata.openStream().use { Properties().apply { load(it) } }
            .getProperty("implementation-classpath")
            .split(File.pathSeparator).map(::File)
        val testClasses = File(javaClass.protectionDomain.codeSource.location.toURI())
        return implementation + testClasses
    }

    private fun classpathLiteral(): String =
        pluginClasspath().joinToString(", ") { "'${it.absolutePath.replace("\\", "\\\\")}'" }

    protected fun write(path: String, content: String) {
        val text = content.trimIndent()
        File(projectDir, path).apply { parentFile.mkdirs() }.writeText(text)
        System.getProperty("selectiveCache.dumpScripts")?.let { dir ->
            File(dir).mkdirs()
            File(dir, path.replace('/', '_')).writeText(text)
        }
    }

    /**
     * Writes settings.gradle with the plugin classpath injected into the settings buildscript,
     * which is what makes the cache types referenceable from the settings script itself.
     */
    protected fun settings(
        projects: List<String> = listOf("a"),
        excludedTypes: List<String> = emptyList(),
        maxStoreSizeBytes: Long = 0,
        localEnabled: Boolean = true,
        preamble: String = "",
        remoteBody: String = defaultRemoteBody(excludedTypes, maxStoreSizeBytes),
    ) {
        val excludes = excludedTypes.joinToString(", ") { "'$it'" }
        write("settings.gradle", """
            buildscript { dependencies { classpath files(${classpathLiteral()}) } }

            rootProject.name = 'functional'
            ${projects.joinToString("\n") { "include '$it'" }}

            $preamble

            // Applied the way a real build applies it, so the tests exercise the plugin's own
            // wiring: it registers the cache type and defaults the delegate.
            apply plugin: io.github.cdsap.selectivecache.SelectiveRemoteCacheSettingsPlugin

            buildCache {
                local {
                    directory = new File(rootDir, '.caches/local')
                    enabled = $localEnabled
                    push = true
                }
                $remoteBody
            }
        """.trimIndent().replace("__EXCLUDES__", excludes))
    }

    /** Decorates the fake Develocity cache, so the tests need no Develocity server of their own. */
    private fun defaultRemoteBody(excludedTypes: List<String>, maxStoreSizeBytes: Long) = """
        registerBuildCacheService(
            com.gradle.develocity.agent.gradle.buildcache.DevelocityBuildCache,
            io.github.cdsap.selectivecache.fixtures.FakeDevelocityBuildCacheServiceFactory)

        remote(io.github.cdsap.selectivecache.SelectiveRemoteBuildCache) {
            push = true
            delegateTo(com.gradle.develocity.agent.gradle.buildcache.DevelocityBuildCache) {
                directory = new File(rootDir, '.caches/remote')
                server = 'https://develocity.example.com'
            }
            excludedTypes = [__EXCLUDES__] as Set
            maxStoreSizeBytes = $maxStoreSizeBytes
            debug = true
        }
    """.trimIndent()

    /** Two cacheable task types, declared in the build script so they land in the default package. */
    protected fun buildScriptWithTasks(projects: List<String> = listOf("a")) {
        write("build.gradle", """
            abstract class ProducerTask extends DefaultTask {
                @Input abstract Property<String> getContent()
                @OutputFile abstract RegularFileProperty getOutputFile()
                @TaskAction void produce() { outputFile.get().asFile.text = content.get() }
            }
            @org.gradle.api.tasks.CacheableTask abstract class SmallOutputTask extends ProducerTask {}
            @org.gradle.api.tasks.CacheableTask abstract class BigOutputTask extends ProducerTask {}

            subprojects { proj ->
                proj.tasks.register('small', SmallOutputTask) {
                    content.set('small ' + proj.name)
                    outputFile.set(proj.layout.buildDirectory.file('small.txt'))
                }
                proj.tasks.register('big', BigOutputTask) {
                    // High entropy and project-specific: a repeated character would gzip down to
                    // almost nothing, so the packed entry would never exceed a size threshold.
                    def rnd = new Random(proj.name.hashCode())
                    content.set((1..30000).collect { (char)(32 + rnd.nextInt(95)) }.join())
                    outputFile.set(proj.layout.buildDirectory.file('big.txt'))
                }
            }

            tasks.register('runAll') {
                dependsOn subprojects.collect { it.path + ':small' }
                dependsOn subprojects.collect { it.path + ':big' }
            }
        """)
        projects.forEach { write("$it/build.gradle", "") }
    }

    protected fun run(vararg args: String): BuildResult =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withArguments(*args, "--build-cache", "--stacktrace")
            .forwardOutput()
            .build()

    protected fun runAndFail(vararg args: String): BuildResult =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withArguments(*args, "--build-cache", "--stacktrace")
            .buildAndFail()

    protected fun deleteOutputs() {
        projectDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != ".caches" && it.name != ".gradle" }
            .forEach { File(it, "build").deleteRecursively() }
        File(projectDir, "build").deleteRecursively()
    }

    protected fun emptyLocalCache() {
        localCache.listFiles().orEmpty()
            .filter { it.isFile && it.name != "gc.properties" && !it.name.endsWith(".lock") }
            .forEach { it.delete() }
    }

    protected fun emptyRemoteCache() {
        remoteCache.listFiles().orEmpty()
            .filter { it.isFile && it.name != "gc.properties" && !it.name.endsWith(".lock") }
            .forEach { it.delete() }
    }
}
