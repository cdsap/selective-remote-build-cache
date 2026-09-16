#!/usr/bin/env bash
# Behavioural proof for selective-remote-build-cache. Uses the repo wrapper (Gradle 9.7.1).
# Override with GRADLE=/path/to/gradle to run against a different distribution.
#
# Drives sample/, an Android build (AGP 9.4.0) whose dex merging is held back from the Develocity
# remote cache. Requires:
#   * an Android SDK (ANDROID_HOME, or the platform default location)
#   * a Develocity instance, via -Pdevelocity.server / DEVELOCITY_SERVER
#   * credentials for it: `./gradlew provisionDevelocityAccessKey`, or DEVELOCITY_ACCESS_KEY
#
# This plugin filters the Develocity build cache and nothing else, so there is no offline mode
# here. The plugin's own test suite (./gradlew -p plugin test) runs fully offline.
set -euo pipefail
cd "$(dirname "$0")/sample"
G="${GRADLE:-../gradlew}"

if [ -z "${DEVELOCITY_SERVER:-}" ]; then
  echo "DEVELOCITY_SERVER is not set. Export it, e.g.:" >&2
  echo "  DEVELOCITY_SERVER=https://develocity.example.com ./verify.sh" >&2
  exit 2
fi

FLAGS=(runAll --build-cache --console=plain)
DEX_TYPE=com.android.build.gradle.internal.tasks.DexMergingTask

outputs() { rm -rf build app/build core/build domain/build; }
reset()   { rm -rf .caches; outputs; }
filters() { printf '%s\n' "$@" > filter.properties; }

echo "===== 1. cold build: dex merging skips remote load AND store ====="
filters "excludedTypes=$DEX_TYPE"
reset
$G "${FLAGS[@]}" --parallel 2>&1 | grep -E "Selective|actionable"

echo; echo "===== 2. local cache emptied, Develocity kept: javac FROM-CACHE, dex merging re-executes ====="
# Everything non-excluded has to come back over the wire from Develocity; dex merging cannot,
# because its remote load is refused, so it re-executes.
outputs; find .caches/local -type f ! -name 'gc.properties' ! -name '*.lock' -delete
$G "${FLAGS[@]}" 2>&1 | grep -E "^> Task .*(DexDebug|JavaWithJavac)"

echo; echo "===== 3. outputs deleted, local cache kept: dex merging still FROM-CACHE ====="
# The point of the plugin: excluding a type from the REMOTE tier leaves the LOCAL tier intact,
# which is exactly what doNotCacheIf cannot express.
outputs
$G "${FLAGS[@]}" 2>&1 | grep -E "^> Task .*(DexDebug|JavaWithJavac)"

echo; echo "===== 4. configuration-cache HIT still filters (local cache off, so remote is consulted) ====="
filters "excludedTypes=$DEX_TYPE" "localEnabled=false"
reset
$G "${FLAGS[@]}" --configuration-cache 2>&1 | grep -E "Configuration cache entry"
outputs
$G "${FLAGS[@]}" --configuration-cache 2>&1 | grep -E "Configuration cache entry|Selective remote cache: declined [0-9]"

echo; echo "===== 5. size filter alone, no type deny-list, zero internal APIs ====="
# 100 kB sits between the small per-module dex entries and the ~700 kB external-dependency
# dex merge, so only the genuinely large entries are held back.
filters "maxStoreSizeBytes=100000"
reset
$G "${FLAGS[@]}" 2>&1 | grep -E "Selective" || echo "(no size-filtered entries)"

filters "excludedTypes=$DEX_TYPE"
