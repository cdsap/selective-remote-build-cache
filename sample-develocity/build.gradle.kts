import com.example.BigOutputTask
import com.example.SmallOutputTask

// A nonce file lets an experiment force brand-new cache keys, so results cannot be confounded
// by entries an earlier run already pushed to the shared remote cache.
val nonce = File(rootDir, "nonce.txt").takeIf { it.isFile }?.readText()?.trim() ?: "0"

subprojects {
    tasks.register<SmallOutputTask>("small") {
        content.set("small output $nonce ${project.name}")
        outputFile.set(layout.buildDirectory.file("small.txt"))
    }
    tasks.register<BigOutputTask>("big") {
        content.set("big output $nonce ${project.name} " + "y".repeat(2000))
        outputFile.set(layout.buildDirectory.file("big.txt"))
    }
}

tasks.register("runAll") {
    dependsOn(subprojects.map { "${it.path}:small" }, subprojects.map { "${it.path}:big" })
}
