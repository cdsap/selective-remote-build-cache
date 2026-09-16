import com.example.BigOutputTask
import com.example.SmallOutputTask

tasks.register<SmallOutputTask>("small") {
    content.set("small output for a")
    outputFile.set(layout.buildDirectory.file("small.txt"))
}

tasks.register<BigOutputTask>("big") {
    content.set("big output for a ".repeat(5000))
    outputFile.set(layout.buildDirectory.file("big.txt"))
}
