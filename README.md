# selective-remote-build-cache

A Gradle settings plugin that keeps chosen task types out of the **remote** build cache, while
leaving the **local** cache untouched.

```
buildCache {
    remote(io.github.cdsap.selectivecache.SelectiveRemoteBuildCache::class.java) {
        isPush = isCi
        excludedTypes = setOf("com.android.build.gradle.internal.tasks.DexMergingTask")
    }
}

```

Use it when fetching a task's output from the remote cache costs more than just running the task.
It wraps the Develocity build cache rather than replacing it, so Develocity still does the auth,
the protocol, the retries and the Build Scan reporting.

> [!WARNING]
> **Experimental.** This plugin is not supported by Gradle or by Develocity, and it is not
> covered by any Develocity support agreement. It reaches into Gradle internals — the build
> cache service factory, the instantiator, and build operations — to recover which task owns a
> cache entry, so a Gradle upgrade can break it without warning. The configuration surface may
> change between releases. Measure the effect on your own build before relying on it, and treat
> a broken build after an upgrade as the expected failure mode.

## Requirements

- Gradle 9 or newer, on any JDK Gradle 9 itself runs on — **Java 17 and up**. The published
  artifact targets Java 17 bytecode regardless of the JDK that builds it, and CI builds and tests
  the plugin on 17, 21 and 25.
- The `com.gradle.develocity` plugin, **3.17 or newer** — the release that introduced the
  `com.gradle.develocity` id and `develocity.buildCache`. This plugin filters the Develocity cache
  and nothing else; it defaults to `develocity.buildCache`, and passing any other cache type to
  `delegateTo` fails during settings evaluation.

## Usage

```kotlin
// settings.gradle.kts

plugins {
    id("com.gradle.develocity") version "4.5.1"
    id("io.github.cdsap.selective-remote-cache") version "0.0.1"
}

develocity {
    server = "https://develocity.example.com"
}

buildCache {
    local { isEnabled = true; isPush = true }

    remote(io.github.cdsap.selectivecache.SelectiveRemoteBuildCache::class.java) {
        isPush = true
        excludedTypes = setOf("com.android.build.gradle.internal.tasks.DexMergingTask")
    }
}
```

There is no `delegateTo` line: the cache being filtered defaults to `develocity.buildCache`. Write
one only to configure the Develocity cache at the same time:

```kotlin
delegateTo(develocity.buildCache) {
    useExpectContinue = false
}
```

Set `isPush` and `isEnabled` on the outer block, not inside `delegateTo`. Gradle reads them from
the cache registered as the remote, which is this one; the delegate's own copies are ignored.

Excluded types still use the local cache as normal. Only the remote tier is skipped, so a second
build on the same machine still gets them `FROM-CACHE`.

## Examples

**Keep Android dex merging off the remote.** The external-dependency merge is the expensive one.

```kotlin
excludedTypes = setOf("com.android.build.gradle.internal.tasks.DexMergingTask")
```

**Never upload anything large,** with no deny-list at all. Loads are unaffected: you still download
whatever is already there.

```kotlin
maxStoreSizeBytes = 50L * 1024 * 1024
```

**Match several types at once,** including artifact transforms. `*` is the only wildcard.

```kotlin
excludedTypes = setOf(
    "com.android.build.gradle.internal.tasks.Dex*",
    "com.example.transforms.*",
)
```

**Stop uploading a type, but still download it.** Useful while a type is being evaluated: the
entries already in the cache stay usable.

```kotlin
excludedTypes = setOf("com.example.HugeReportTask")
excludeLoads = false
```

## Options

| Option | Default | Effect |
|---|---|---|
| `delegateTo(develocity.buildCache)` | `develocity.buildCache` | The Develocity cache to filter in front of. Only needed to configure it |
| `excludedTypes` | empty | Task and `TransformAction` class names that skip the remote cache. `*` wildcards allowed |
| `maxStoreSizeBytes` | `0` | Skip remote *stores* above this size. `0` means no limit |
| `excludeLoads` | `true` | Whether excluded types also skip remote downloads |
| `excludeStores` | `true` | Whether excluded types skip remote uploads |
| `debug` | `false` | Log every decision at lifecycle level |

## What you see

With `debug = true`, each decision is logged, plus a summary at the end of the build:

```
Selective remote cache: declined remote store for com.android.build.gradle.internal.tasks.DexMergingTask — excluded type (key c5a2808…, 732436 bytes)
Selective remote cache: declined 9 remote loads and 9 remote stores (2 MB not uploaded). Local cache was unaffected.
```

On a Build Scan, the same counts appear as custom values under `selective-remote-cache.*`
(`declined-loads`, `declined-stores`, `declined-store-bytes`, and a `declined` line per type).

Gradle still records these as cache misses in its own reporting: the plugin declines the call, and
Gradle has no concept of "deliberately skipped for the remote tier only".

## More

[DESIGN.md](DESIGN.md) — how the delegation works, how the task type is recovered from build
operations, what the tests pin down, measured results, and the known limitations.

This is the capability asked for in
[gradle/gradle#27710](https://github.com/gradle/gradle/issues/27710), which Gradle closed as
`not planned` in April 2024.

## License

[Apache License 2.0](LICENSE).
