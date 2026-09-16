#!/usr/bin/env bash
# Behavioural proof for selective-remote-build-cache. Uses the repo wrapper (Gradle 9.7.1).
# Override with GRADLE=/path/to/gradle to run against a different distribution.
set -euo pipefail
cd "$(dirname "$0")/sample"
G="${GRADLE:-../gradlew}"
FLAGS=(runAll --build-cache --console=plain)

reset()   { rm -rf .caches build a/build b/build c/build; }
outputs() { rm -rf build a/build b/build c/build; }
filters() { printf '%s\n' "$@" > filter.properties; }

echo "===== 1. cold build: BigOutputTask skips remote load AND store ====="
filters "excludedTypes=com.example.BigOutputTask"
reset
$G "${FLAGS[@]}" --parallel 2>&1 | grep -E "Selective|actionable"

echo; echo "===== 2. local cache emptied, remote kept: small FROM-CACHE, big re-executes ====="
outputs; find .caches/local -type f ! -name 'gc.properties' ! -name '*.lock' -delete
$G "${FLAGS[@]}" 2>&1 | grep -E "^> Task .*(small|big)"

echo; echo "===== 3. remote emptied, local kept: big still FROM-CACHE (local tier unaffected) ====="
outputs; find .caches/remote -type f ! -name "gc.properties" ! -name "*.lock" -delete
$G "${FLAGS[@]}" 2>&1 | grep -E "^> Task .*(small|big)"

echo; echo "===== 4. configuration-cache HIT still filters (local cache off, so remote is consulted) ====="
filters "excludedTypes=com.example.BigOutputTask" "localEnabled=false"
reset
$G "${FLAGS[@]}" --configuration-cache 2>&1 | grep -E "Configuration cache entry"
outputs
$G "${FLAGS[@]}" --configuration-cache 2>&1 | grep -E "Configuration cache entry|Selective|^> Task .*(small|big)"

echo; echo "===== 5. size filter alone, no type deny-list, zero internal APIs ====="
# 500 bytes sits between the small and big task entries, so only the big ones are held back.
filters "maxStoreSizeBytes=500"
reset
$G "${FLAGS[@]}" 2>&1 | grep -E "Selective" || echo "(no size-filtered entries)"

filters "excludedTypes=com.example.BigOutputTask"
