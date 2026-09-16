// tasks live in the subprojects

tasks.register("runAll") {
    dependsOn(subprojects.map { "${it.path}:small" }, subprojects.map { "${it.path}:big" })
}
