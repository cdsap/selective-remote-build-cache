# selective-remote-build-cache

A Gradle settings plugin that keeps chosen task types out of the **remote** build cache, while
leaving the **local** cache untouched.

Use it when fetching a task's output from the remote cache costs more than just running the task.
Android dex merging is the usual example: a ~700 KiB entry that is often cheaper to recompute than
to pull over a WAN.

It wraps the Develocity build cache rather than replacing it, so Develocity still does the auth,
the protocol, the retries and the Build Scan reporting.

## Requirements

- Gradle 9.7+ (tested on 9.7.1)
- The `com.gradle.develocity` plugin. This plugin filters the Develocity cache and nothing else;
  it defaults to `develocity.buildCache`, and passing any other cache type to `delegateTo` fails
  during settings evaluation.

## Usage

Not published to the Gradle Plugin Portal yet, so include the build:

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../selective-remote-build-cache/plugin")
}

plugins {
    id("com.gradle.develocity") version "4.5.1"
    id("io.github.cdsap.selective-remote-cache")
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

## Sample

`sample/` is an Android build (AGP 9.4.0, three modules) with dex merging excluded. It needs an
Android SDK and a Develocity instance — set the server in `sample/settings.gradle.kts` first.

```
cd sample
../gradlew provisionDevelocityAccessKey    # once, to authenticate
../gradlew runAll --build-cache
```

Knobs are Gradle properties, defaults in `sample/gradle.properties`:

```
../gradlew runAll --build-cache -PselectiveCache.maxStoreSizeBytes=100000
```

## Tests

```
./gradlew -p plugin test
```

85 tests, ~25s, no Develocity server needed.

## More

[DESIGN.md](DESIGN.md) — how the delegation works, how the task type is recovered from build
operations, what the tests pin down, measured results, and the known limitations.

This is the capability asked for in
[gradle/gradle#27710](https://github.com/gradle/gradle/issues/27710), which Gradle closed as
`not planned` in April 2024.
