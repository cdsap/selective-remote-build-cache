import com.example.BigOutputTask
import com.example.SmallOutputTask

tasks.register<SmallOutputTask>("small") {
    content.set("small output for c")
    outputFile.set(layout.buildDirectory.file("small.txt"))
}

tasks.register<BigOutputTask>("big") {
    content.set("big output for c ".repeat(5000))
    outputFile.set(layout.buildDirectory.file("big.txt"))
}
