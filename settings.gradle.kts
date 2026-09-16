// The two real builds live one level down: `plugin` is the plugin itself, `sample` is the
// demo that consumes it via its own `includeBuild("../plugin")`. Neither needs this file to
// build — `cd sample && ../gradlew ...` still works. It exists so that opening the repository
// root in an IDE imports both builds as one Gradle project instead of a plain folder.
rootProject.name = "selective-remote-build-cache-repo"

includeBuild("plugin")
includeBuild("sample")
