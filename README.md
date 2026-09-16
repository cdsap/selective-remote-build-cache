# selective-remote-build-cache

Per-task-type (and per-transform-type) control over the **remote** Gradle build cache, leaving the
**local** cache fully intact — and **without replacing your existing cache connector**.

This is the capability asked for in
[gradle/gradle#27710](https://github.com/gradle/gradle/issues/27710), which Gradle closed as
`not planned` in April 2024 with *"Let's reopen this if we see evidence that doing this would help."*

```kotlin
// settings.gradle.kts
plugins {
    id("com.gradle.develocity") version "…"
    id("io.github.cdsap.selective-remote-cache")
}

buildCache {
    local { isEnabled = true; isPush = true }

    remote(io.github.cdsap.selectivecache.SelectiveRemoteBuildCache::class.java) {
        isPush = true

        // Filter in FRONT of the Develocity cache. Its connector still does all the real work:
        // auth, protocol, retries, telemetry. We never talk to the network ourselves.
        delegateTo(develocity.buildCache)

        // These never touch the remote cache. They still use the local cache normally.
        excludedTypes = setOf(
            "com.android.build.gradle.tasks.MergeResources",
            "com.example.transforms.*",           // '*' wildcards supported
        )

        // Independently: never upload anything above this size.
        maxStoreSizeBytes = 50L * 1024 * 1024
    }
}
```

`delegateTo` accepts only Develocity's `develocity.buildCache`; anything else is rejected during
settings evaluation, naming the type it got. The plugin never talks to a cache backend itself. It
only decides whether to call the Develocity connector you already have.

## How it works

### Decorating Develocity's cache rather than replacing it

An earlier version of this plugin registered its own remote cache type, which meant a Develocity
customer had to give up the Develocity connector to use it. That was unacceptable, and it is not
necessary.

`AbstractBuildCacheControllerFactory` builds the remote service by looking up a factory type from
`BuildCacheConfigurationInternal.getBuildCacheServiceFactoryType(configClass)` and instantiating it
with `instantiatorFactory.inject(services)`. Both of those are reachable from our own factory by
constructor injection, so we can:

1. take Develocity's configuration object (`develocity.buildCache`) straight from its extension,
2. look up **Develocity's** registered factory,
3. instantiate it with exactly the `InstanceGenerator` Gradle would have used, so every service it
   expects is injected,
4. call its `createBuildCacheService(...)` and wrap the result.

The Develocity plugin needs no cooperation and is never modified. It is handed the real
`Describer`, so the Build Scan still reports the cache as Develocity's. The description reads
`Using remote Develocity build cache … (authenticated = true,
filtered by = selective-remote-build-cache, excludedTypes = …)`: Develocity's `type` wins, and our
filter config is appended as extra parameters.

### Recovering the task identity

Gradle's `BuildCacheService` SPI sees nothing but a `BuildCacheKey` — no task, no type. Three facts
make the correlation possible:

1. `OpFiringRemoteBuildCacheServiceHandle` wraps every remote load/store in a build operation, so
   `CurrentBuildOperationRef` is populated on our thread when the SPI method runs.
2. `ExecuteTaskBuildOperationType.Details` exposes `getTaskClass()`;
   `ExecutePlannedTransformStepBuildOperationType.Details` exposes `getTransformActionClass()`.
   Both live in `platforms/enterprise/enterprise-operations` — the contract Develocity itself
   consumes, so they are as stable as internal Gradle gets.
3. Returning `false` from `load` short-circuits before the delegate is called at all; a no-op
   `store` skips the upload. Neither can reach the local cache, which Gradle holds as a separate
   handle in `DefaultBuildCacheController` and consults *first*.

`BuildOperationWorkOwnerSource` propagates ownership down the operation subtree at start time
rather than walking parent chains on lookup, so the map only holds operations beneath a task or
transform, and the lookup is O(1).

The listener is registered from the factory's `@Inject` constructor rather than the settings
plugin, because Gradle recreates the cache service on every build including configuration-cache
hits, whereas settings scripts are not re-evaluated on a hit.

## Architecture

Three layers, and dependencies point inward. `policy` knows nothing about Gradle; `work` and
`scan` each hide one awkward external system; the root package is Gradle integration.

```
io.github.cdsap.selectivecache          Gradle integration + public API
├── SelectiveRemoteCacheSettingsPlugin   registers the cache type
├── SelectiveRemoteBuildCache            the DSL the user writes
├── SelectiveRemoteBuildCacheServiceFactory   composition root — builds the graph, holds no logic
└── FilteringBuildCacheService           adapter: Gradle's SPI -> a policy question
    │
    ├── policy/                          the rules. No Gradle, no I/O, no state.
    │   ├── RemoteCacheFilter            decides Allow or Decline(attributedTo, explanation)
    │   ├── TypePatterns                 wildcard matching on class names
    │   └── DeclineTally                 thread-safe counts of what was declined
    │
    ├── work/                            "which work unit is this thread serving?"
    │   ├── WorkOwnerSource                  port
    │   └── BuildOperationWorkOwnerSource    adapter over Gradle build operations
    │
    └── scan/                            "tell the Build Scan what we declined"
        ├── ScanReporter                     port
        ├── DevelocityScanReporter           adapter over the Develocity plugin (reflective)
        └── DeclineScanReport                what to publish, and when
```

**Why it is split this way.** Before, one class decided what to filter, counted it, published it to
the Build Scan, logged it, and called the delegate. Every one of those needed a running Gradle build
to test. Now:

- `RemoteCacheFilter` is a pure function of (work owner, entry size). `RemoteCacheFilterTest` covers
  every rule with no Gradle types at all, in milliseconds.
- The two genuinely hard integrations — recovering the task type from build operations, and reaching
  Develocity reflectively — sit behind one-method ports. Neither leaks into the rules.
- `FilteringBuildCacheService` is ~30 lines: resolve owner, ask the filter, record, delegate.
- The factory is a wiring diagram. If you want to know what talks to what, read that one file.

**Reading order for a walkthrough:** `RemoteCacheFilter` (what it decides) ->
`FilteringBuildCacheService` (where the decision is applied) -> `SelectiveRemoteBuildCacheServiceFactory`
(how it is all assembled) -> `work/` and `scan/` (the two mechanisms that make it possible).

## Tests

`./gradlew -p plugin test` — 83 tests, ~25s, no Develocity server needed.

| Suite | Tests | Layer | What it pins down |
|---|---|---|---|
| `RemoteCacheFilterTest` | 15 | policy | every filtering rule, rule precedence, size boundaries, fail-open on unknown owner — no Gradle types involved |
| `TypePatternsTest` | 11 | policy | wildcards, literal dots, regex-metacharacter escaping, case sensitivity |
| `DeclineTallyTest` | 5 | policy | counts, per-label breakdown, stable ordering, 16-thread concurrency |
| `BuildOperationWorkOwnerSourceTest` | 12 | work | ownership propagation down a build-operation subtree, sibling isolation, cleanup, concurrent attribution |
| `DeclineScanReportTest` | 6 | scan | what is published and when, idempotent registration, explicit zeros, no-Develocity safety |
| `FilteringBuildCacheServiceTest` | 12 | adapter | a decline really stops short of the delegate, bookkeeping, owner-lookup avoidance, close semantics |
| `FilteringFunctionalTest` | 8 | end to end | real Gradle builds: exclusion, local tier unaffected, wildcards, size filter, `--parallel`, configuration-cache hit |
| `DevelocityDelegationFunctionalTest` | 8 | end to end | decorating the Develocity connector, and rejecting any other cache type |
| `ScanAnnotationFunctionalTest` | 6 | end to end | the reflective hop to a Develocity-shaped extension |

The functional tests spawn real Gradle builds via TestKit and need no Develocity server. They
decorate a directory-backed test double carrying Develocity's own fully-qualified type name,
`com.gradle.develocity.agent.gradle.buildcache.DevelocityBuildCache`, with its own factory and an
injected build-scoped service. Same name, so the tests take the same validation path as
production.

Debug aid: `-DselectiveCache.dumpScripts=<dir>` writes the generated functional-test scripts
somewhere inspectable, since TestKit project directories are temporary.

### A bug the suite caught

The delegate property was originally called `delegate`. In Kotlin DSL that works, which is why both
samples passed. In a Groovy `settings.gradle` it silently does not:

```
DBG closure delegate BEFORE = SelectiveRemoteBuildCache_Decorated
delegate = develocityCache
DBG closure delegate AFTER  = DevelocityBuildCache_Decorated
```

`delegate` inside a Groovy closure is the closure's own delegate. The assignment never reached a
property — and worse, every later assignment in that block would have landed on the Develocity
object instead. Hence `delegateTo`.

## Behavioural demo

The tests above run offline on every push. CI also exercises the plugin end to end against a
real Develocity instance: the `android` job in
[`.github/workflows/ci.yml`](.github/workflows/ci.yml), which needs the `DEVELOCITY_URL` and
`DEVELOCITY_ACCESS_KEY` repository secrets.

To run the same thing by hand:

```
cd sample
export DEVELOCITY_SERVER=https://develocity.example.com
../gradlew provisionDevelocityAccessKey    # once, or set DEVELOCITY_ACCESS_KEY
../gradlew runAll --build-cache
```

`sample/` is an Android build (AGP 9.4.0, three application modules) with
`com.android.build.gradle.internal.tasks.DexMergingTask` on the deny-list. Dex merging is a good
candidate for exclusion: the external-dependency merge produces a ~715 KiB entry that is usually
cheaper to recompute than to fetch over a WAN.

What it proves:

| # | Behaviour | Result |
|---|---|---|
| 0 | Develocity connector delegation | Develocity's factory runs, injected `BuildOperationRunner` resolves, Develocity's service does the caching |
| 1 | Cold parallel build, 3 Android modules, `DexMergingTask` excluded | 9 remote loads + 9 remote stores skipped, ~2 MB not uploaded; every other cacheable task unaffected |
| 2 | Local cache emptied, remote kept | all `compileDebugJavaWithJavac` -> `FROM-CACHE`; all dex merge tasks re-execute (remote load refused) |
| 3 | Outputs deleted, local cache kept | all dex merge tasks -> **`FROM-CACHE`** — the local tier is genuinely unaffected |
| 4 | `--configuration-cache` on a **reused** entry, local cache off | `Configuration cache entry reused` *and* filtering still applies |
| 5 | Size filter alone (`maxStoreSizeBytes=100000`) | only the three ~715 KiB external-dex entries held back; the small per-module dex entries still go to the remote |

Scenario 3 is the one that matters: it is the proof that disabling remote does not disable local,
which is exactly what `doNotCacheIf` cannot give you.

### Harness notes (both cost time to track down)

- Knobs are Gradle properties: defaults in `sample/gradle.properties`, overridden with `-P`, e.g.
  `-PselectiveCache.maxStoreSizeBytes=100000`. Read via `providers.gradleProperty`, so they are
  configuration-cache inputs and changing one invalidates the entry. `-D` system properties are not
  a substitute — those attach to the daemon JVM for its whole lifetime and leak between runs.
- `sample/` needs an Android SDK. `ANDROID_HOME` is used if set, otherwise AGP falls back to the
  platform default location. `local.properties` is git-ignored, so pointing at a local SDK never
  ends up committed.

## Verified against real Develocity

`sample/` applies the actual `com.gradle.develocity` 4.5.1 plugin and delegates to its cache. The
results below were measured against a real Develocity instance, not projected.

The server is set in `sample/settings.gradle.kts`. Point it at your own instance before running
this, or you will publish Build Scans and cache entries somewhere you did not intend:

```
cd sample
../gradlew provisionDevelocityAccessKey    # once, to authenticate
../gradlew runAll --build-cache
```

**API note.** `develocity.buildCache` is a `Class`, not an instance — the documented Develocity
usage is `remote(develocity.buildCache) { }`. `delegateTo` takes that class directly and builds the
configuration object itself with Gradle's `ObjectFactory`, so there is one block, not two:

```kotlin
remote(SelectiveRemoteBuildCache::class.java) {
    delegateTo(develocity.buildCache)                       // or, with configuration:
    // delegateTo(develocity.buildCache) { server = "…"; useExpectContinue = false }
    excludedTypes = setOf("com.example.BigOutputTask")
}
```

The block is typed, so delegate-specific members are available. It runs during settings
evaluation rather than being deferred to cache-service creation: deferring it ran the user's block
at execution time, and a block touching script state such as `rootDir` then failed the
configuration cache. The test suite caught that.

**Results** — measured on the earlier synthetic sample (three projects, a `small` and a `big`
task each), before `sample/` became an Android build. The mechanism is unchanged; only the tasks
being filtered differ.

| Check | Outcome |
|---|---|
| Develocity's own factory instantiated by us | works — full service injection, no changes to the Develocity plugin |
| Real remote pull through the wrapper | local cache wiped -> all `small` tasks `FROM-CACHE` from the DV cache |
| Excluded type skips remote | all `big` tasks re-execute with local wiped |
| Local tier unaffected | outputs deleted, local kept -> all `big` tasks `FROM-CACHE` |
| Configuration cache | scan reports `gradleConfigurationCache.outcome: HIT` with filtering still applied |
| Build Scan cache identity | `remote: {type: "Develocity", url: "https://<your-server>/cache", isPushEnabled: true}` — Develocity's identity is preserved; `className` also shows our wrapper, which helps when diagnosing |

**Measured effect** — same 6 tasks, entries present in the remote, local cache off:

| | no exclusions | both types excluded |
|---|---|---|
| build time | 1624 ms | **895 ms** |
| serial task execution time | 2230 ms | **50 ms** |
| remote cache avoidance savings | **-2211 ms** | 0 ms |
| cache download overhead | 2209 ms | 0 ms |
| task outcomes | 6x `avoided_from_remote_cache`, every one with negative savings (-180 to -567 ms) | 6x `executed_cacheable`, 3-8 ms each |

Read this as a demonstration that the mechanism produces the intended effect, **not** as a
projection for a real build. The sample is deliberately the extreme of the negative-savings case:
the tasks take 3-8 ms while each remote fetch over the WAN costs 180-570 ms. A real customer's
numbers have to be measured on their own workload.

It is, however, a live instance of exactly the pathology #27710 describes — small artifacts where
the round-trip dominates the work — reproduced end to end on a Develocity instance.

## Known limitations — read before deploying

- **A Build Scan still counts declined operations under Miss and Store — read the custom values,
  not the Operations table.** Gradle fires the remote load/store build operation *around* our
  service call, so declining inside it cannot stop the operation being recorded. With 6 excluded
  tasks the Operations table reads `Hit 0 / Miss 6 / Store 6 / 3.3 KiB` even though **nothing was
  uploaded** (proven by forcing fresh cache keys with a nonce, running with exclusions, then
  re-running without them and with the local cache wiped: all six tasks re-executed).

  The numbers are derived, not measured. `StoreOperationDetails` carries the entry's `archiveSize`
  regardless of whether bytes moved: 446+703+703+703+447+446 = 3448 B = 3.37 KiB, exactly the
  "3.3 KiB" shown. Throughput is that size divided by the operation duration: 446 B / 0.003 s =
  145.2 KiB/s, exactly the "145.1 KiB/s" shown. The operation result does carry `isStored: false`,
  but the Operations table counts it anyway.

  The plugin therefore annotates the scan so it is legible. Verified on a real scan:

  ```
  tags: selective-remote-cache
  selective-remote-cache.excluded-types       = com.example.BigOutputTask, com.example.SmallOutputTask
  selective-remote-cache.max-store-size-bytes = unlimited
  selective-remote-cache.declined-loads       = 6
  selective-remote-cache.declined-stores      = 6
  selective-remote-cache.declined-store-bytes = 2741
  selective-remote-cache.declined             = com.example.BigOutputTask: loads=3 stores=3 bytes=1380
  selective-remote-cache.declined             = com.example.SmallOutputTask: loads=3 stores=3 bytes=1361
  ```

  So `Miss 6` next to `declined-loads = 6` means every miss was a deliberate decline, not a cold
  cache. The operation itself still cannot be suppressed per type — only a global `push = false`
  avoids firing it.

  Two implementation notes, both of which cost a debugging round:
  the Develocity extension is resolved on the **first cache operation**, not when the cache service
  is built — at that earlier point `GradleInternal.getSettings()` still throws "The settings are not
  yet available"; and the reflective method lookup matches on **parameter types**, because Gradle's
  decorated extension objects carry Groovy `Closure` overloads that shadow the `Action` ones and
  produce "argument type mismatch".

- **Exercised on a small build only.** `sample/` is an Android build with real dex merging,
  running against Develocity with a real remote cache and real scans, but it is small. Behaviour
  under load and with many excluded types is unmeasured.
- **More internal API surface than the replacement approach.** Delegation needs
  `BuildCacheConfigurationInternal`, `InstantiatorFactory`, `ServiceRegistry` and
  `BuildOperationListenerManager`. All are long-standing, but re-verify on each Gradle major.
  Tested on Gradle 9.7 only.
- **Local hits mask the filter.** Gradle consults local before remote, so an excluded type that
  hits locally produces no skip log. Correct behaviour, but it makes debug output look sparse.
- **Ordering assumption.** The listener registers when the build cache controller is first created.
  In every run here that happened before the first task started. If it ever registered late the
  effect is fail-open — filtering does not apply, caching behaves normally. No correctness risk.

## Why this is worth building even though Gradle said no

The core feature is a 2–3 month cross-product change: `CachingState` is an `Either<Enabled,Disabled>`
consumed binary-fashion throughout the execution engine, `BuildCacheController.load/store` would
need a tier parameter, and the `CachingDisabledReasonCategory` build-operation contract that
Develocity ingests would need a new tri-state that the scan plugin, server and UI all understand.

This plugin is roughly a month to production quality, and it produces the *evidence* Gradle asked
for in #27710 — measured before/after on a real customer instance. That is the cheapest route to
reopening the core issue, not a detour around it.
