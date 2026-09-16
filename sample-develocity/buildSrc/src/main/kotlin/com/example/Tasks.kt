package com.example

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class ProducerTask : DefaultTask() {
    @get:Input abstract val content: Property<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun produce() {
        outputFile.get().asFile.writeText(content.get())
    }
}

/** Ordinary cacheable task — should use both local and remote cache. */
@CacheableTask abstract class SmallOutputTask : ProducerTask()

/** Stands in for the "huge artifact, negative remote savings" case — local cache only. */
@CacheableTask abstract class BigOutputTask : ProducerTask()
