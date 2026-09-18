package io.github.cdsap.selectivecache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Excluding a task must not take the task's dependencies down with it.
 *
 * Gradle runs an artifact transform inside whatever work needed the artifact, so a transform's
 * cache entries can sit underneath the operation of the task that consumes them. On Now in
 * Android that is exactly what happens: AGP dexes every dependency in a transform while the
 * dex-merging task snapshots its inputs, and attributing those entries to the task meant
 * excluding `DexMergingTask` declined ~600 dependency entries as well as the one intended.
 *
 * This runs the shape for real — a transform feeding a cacheable task — and pins the boundary:
 * the task's own entry is declined, the transform's is not.
 *
 * Note what it does not do. Gradle schedules this transform as its own node, so it carries
 * ExecutePlannedTransformStepBuildOperationType.Details and was attributed correctly even before
 * the fix. AGP's dexing transforms are unplanned, and reproducing that synthetically did not
 * work; the guard for that path is BuildOperationWorkOwnerSourceTest, whose operation tree was
 * captured from a real Now in Android build.
 */
class TransformAttributionFunctionalTest : AbstractFunctionalTest() {

    @Test
    fun `excluding a task does not decline the transforms feeding it`() {
        settings(projects = listOf("producer"), excludedTypes = listOf("ConsumeTask"))
        buildScriptWithTransform()

        val result = run("consume")

        val declinedConsume = Regex("declined remote \\w+ for ConsumeTask").findAll(result.output).count()
        val declinedOther = Regex("declined remote \\w+ for (?!ConsumeTask)").findAll(result.output).count()

        assertTrue(declinedConsume > 0, "the excluded task itself must be declined:\n${result.output}")
        assertEquals(0, declinedOther, "nothing but the excluded task may be declined:\n${result.output}")

        // The transform output reached the remote; the task's did not. Both are in the local
        // cache, because the local tier is never filtered.
        assertEquals(1, remoteEntries().size, "only the transform's entry belongs in the remote")
        assertEquals(2, localEntries().size, "the local tier keeps both")
    }

    /**
     * A transform that produces a cacheable output, and a cacheable task whose inputs are the
     * transformed artifacts — the consumer shape AGP uses for dexing.
     */
    private fun buildScriptWithTransform() {
        write("producer/build.gradle", """
            def artifactType = Attribute.of('artifactType', String)

            configurations {
                consumable('raw') {
                    attributes { attribute(artifactType, 'raw') }
                }
            }

            def makeRaw = tasks.register('makeRaw') {
                def out = layout.buildDirectory.file('raw.txt')
                outputs.file(out)
                doLast {
                    // High entropy so the packed entry is not trivially small.
                    def rnd = new Random(42)
                    out.get().asFile.text = (1..20000).collect { (char)(32 + rnd.nextInt(95)) }.join()
                }
            }

            artifacts {
                raw(layout.buildDirectory.file('raw.txt')) { builtBy(makeRaw) }
            }
        """)

        write("build.gradle", """
            import org.gradle.api.artifacts.transform.CacheableTransform
            import org.gradle.api.artifacts.transform.InputArtifact
            import org.gradle.api.artifacts.transform.TransformAction
            import org.gradle.api.artifacts.transform.TransformOutputs
            import org.gradle.api.artifacts.transform.TransformParameters

            def artifactType = Attribute.of('artifactType', String)

            @CacheableTransform
            abstract class MarkFile implements TransformAction<TransformParameters.None> {
                @InputArtifact
                @PathSensitive(PathSensitivity.RELATIVE)
                abstract Provider<FileSystemLocation> getInputArtifact()

                @Override
                void transform(TransformOutputs outputs) {
                    def input = inputArtifact.get().asFile
                    def out = outputs.file(input.name + '.marked')
                    out.text = 'marked:' + input.text
                }
            }

            @org.gradle.api.tasks.CacheableTask
            abstract class ConsumeTask extends DefaultTask {
                @InputFiles
                @PathSensitive(PathSensitivity.RELATIVE)
                abstract ConfigurableFileCollection getMarked()

                @OutputFile
                abstract RegularFileProperty getOutputFile()

                @TaskAction
                void go() {
                    outputFile.get().asFile.text = marked.files.collect { it.text }.join('\\n')
                }
            }

            configurations {
                // Gradle 9 keeps the roles apart: dependencies are declared on one configuration
                // and resolved through another.
                dependencyScope('rawDeps')
                resolvable('deps') {
                    extendsFrom rawDeps
                    attributes { attribute(artifactType, 'raw') }
                }
            }

            dependencies {
                rawDeps project(':producer')
                registerTransform(MarkFile) {
                    from.attribute(artifactType, 'raw')
                    to.attribute(artifactType, 'marked')
                }
            }

            tasks.register('consume', ConsumeTask) {
                marked.from(
                    configurations.deps.incoming.artifactView {
                        attributes { attribute(artifactType, 'marked') }
                    }.files
                )
                outputFile.set(layout.buildDirectory.file('consumed.txt'))
            }
        """)
    }
}
