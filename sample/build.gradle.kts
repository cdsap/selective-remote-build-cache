plugins {
    id("com.android.application") version "9.4.0" apply false
}

tasks.register("runAll") {
    dependsOn(subprojects.map { "${it.path}:assembleDebug" })
}
