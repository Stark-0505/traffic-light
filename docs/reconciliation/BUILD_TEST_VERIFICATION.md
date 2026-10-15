# Build and test verification report

Status vocabulary: **PASSED** = executed and succeeded · **FAILED** = executed and failed · **BLOCKED** = could not be run,
reason given · **SKIPPED** = deliberately not run · **STATIC** = reviewed or parsed, not executed.
Nothing below is reported as passed unless it was executed.

## 1. Environment

| Item | Value |
|---|---|
| JDK | OpenJDK 21.0.10 (project CI uses 17) |
| Android SDK | none (`ANDROID_HOME` unset, no platform, no build-tools) |
| Gradle | wrapper `gradle-9.6.1-bin.zip`, **not obtainable** |
| Network | DNS resolution failed for `services.gradle.org`, `dl.google.com`, `repo.maven.apache.org`; only GitHub release assets, PyPI, npm and Ubuntu archives were reachable |
| Project | `compileSdk 37`, `minSdk 28`, flavors `full` + `play`, build types `debug` + `release` |

The prior audit's constraint (no Gradle distribution, no SDK) reproduced exactly. The retry was made, not assumed:

```
curl -sS -m 10 -o /dev/null -w "http=%{http_code}\n" https://services.gradle.org/distributions/gradle-9.6.1-bin.zip
  -> curl: (6) Could not resolve host: services.gradle.org   http=000
curl -sS -m 10 -o /dev/null https://dl.google.com/dl/android/maven2/index.html
  -> curl: (6) Could not resolve host: dl.google.com
curl -sS -m 10 -o /dev/null https://repo.maven.apache.org/maven2/
  -> curl: (6) Could not resolve host: repo.maven.apache.org
```

Even with a Gradle distribution (obtainable from GitHub releases) AGP, Compose, Glance, Koin and Robolectric artifacts come from
Google Maven / Maven Central, so a real build was not possible.

## 2. Gradle checks (the ones that matter) — all BLOCKED

| Command | Result |
|---|---|
| `./gradlew assembleFullDebug assemblePlayDebug` (CI uses `assembleDebug`) | **BLOCKED** – no Gradle/AGP/SDK |
| `./gradlew testFullDebugUnitTest testPlayDebugUnitTest` (CI uses `testDebugUnitTest`) | **BLOCKED** |
| `./gradlew testFullReleaseUnitTest testPlayReleaseUnitTest` | **BLOCKED** |
| `./gradlew lintFullDebug lintPlayDebug` (`abortOnError = false` in this project) | **BLOCKED** |
| Release-variant R8/shrink check of the new code | **BLOCKED** |

Task names are derived from the project's flavor/build-type configuration, not from running `./gradlew tasks`.

## 3. What was actually executed

### 3.1 Ad-hoc harness: 53 checks PASSED (not Gradle)

`kotlinc 2.2.20` (standalone, from GitHub releases) + the bundled `kotlinx-coroutines-core-jvm.jar`, a minimal stub of
`org.junit`, a Timber stub, and a *real-time* shim of `kotlinx.coroutines.test` (`runTest`/`advanceTimeBy`/`runCurrent`).
The timing-sensitive `BroadcastWorkTest` cases were run from a time-scaled copy (8 s → 800 ms etc.). The production sources are
compiled directly from the repo; Android-only declarations are excluded by extracting the Android-free parts verbatim
(`bytesPerSecond`, `forceRefresh`, `WidgetAlarmController`). Script: re-created from the commands in this report; it lives in the
authoring sandbox, not in the repo.

| Test class | Tests | Result |
|---|---|---|
| `SysfsFallbackCountersTest` (new) | 10 | PASSED |
| `BytesPerSecondTest` (**pre-existing**, unchanged) | 9 | PASSED |
| `HourFormattingTest` (new) | 3 | PASSED |
| `NotificationOrderingTest` (new) | 4 | PASSED |
| `BroadcastWorkTest` (new, scaled time) | 5 | PASSED |
| `RestartRestorerTest` (new) | 10 | PASSED |
| `ForceRefreshTest` (new) | 6 | PASSED |
| `WidgetAlarmControllerTest` (new) | 6 | PASSED |
| **Total** | **53** | **53 passed, 0 failed** |

Caveats: this is not real JUnit and not real `runTest` (virtual time); it demonstrates the logic, not the project's test setup.

The harness also caught one real defect before any Gradle run could have: a KDoc containing `statistics/*_bytes` opened a nested
block comment in Kotlin and left it unclosed. It was fixed before the first commit.

### 3.2 Static checks — STATIC

* Every changed/added `.kt` file (41) was parsed with `kotlinc` and filtered for syntax errors: **0** syntax errors.
  (Unresolved-reference errors are expected without a classpath and were ignored.)
* A script audited imports and symbol use across all changed files: no unresolved project-internal imports; two unused imports
  that I introduced were removed (`1852f3b`); remaining hits were false positives (parameter names, generated `R`, `by`-delegate
  imports).
* Scope audit of `git diff backup/fork-head-a8beb2e..HEAD`: no iPerf references added, no Gradle catalog/dependency change,
  manifest change limited to one `<action>`, `app/build.gradle.kts` change limited to removing `generateLocaleConfig`.
* Working tree clean at the end of the authoring session (verified before packaging).

## 4. Tests written but NOT executed (need Robolectric/mockk/Android) — BLOCKED

57 of the 101 new tests. They are expected to pass but **have not been run**.

| Test class | Tests | Needs | Risky assumption |
|---|---|---|---|
| `TrafficSnapshotFallbackTest` | 13 | Robolectric, mockk | real `Dispatchers.IO` thread names start with `DefaultDispatcher-worker` |
| `TrafficSnapshotInterfaceDiscoveryTest` | 11 | Robolectric, mockk | `registerNetworkCallback(any(), capture(slot))` overload resolution |
| `TrafficSnapshotPerInterfaceSupportTest` | 4 | Robolectric, mockk | – |
| `TimeFormatSourceTest` | 6 | Robolectric | `ShadowContentResolver.getContentObservers(uri)` is available as a static in 4.16.1; observer dispatch via main looper |
| `NotificationChannelCleanupTest` | 7 | Robolectric | `ShadowNotificationManager` supports create/list/delete channels |
| `DebugDiagnosticsTest` | 5 | Robolectric | `StrictMode.ThreadPolicy/VmPolicy.toString()` reflects the mask |
| `WidgetPreviewRegistrationTest` | 5 | `kotlinx-coroutines-test` | virtual-time `advanceTimeBy` with a real IO hop |
| `WidgetRenderStateTest` | 6 | Robolectric (loads `Widget`) | `mutablePreferencesOf` from datastore-preferences-core |

Pre-existing tests (30 before this work): `BytesPerSecondTest` (9) was executed in the harness and PASSED;
`TrafficSnapshotTest` (3), `NetworkUsageManagerTest` (8) and `DateRangesTest` (10) were **not run** (BLOCKED).
Only `TrafficSnapshotTest` was edited (a stale comment; no assertion changed, nothing deleted or weakened). Its stubs were
re-read to confirm the new unsupported-counter detection cannot reinterpret them (all per-interface stubs return
non-negative values).

## 5. Required verification areas from the brief

| Area | Status |
|---|---|
| Existing sampling / byte-rate tests | `BytesPerSecondTest` PASSED (harness); `TrafficSnapshotTest` BLOCKED |
| Fallback: valid, missing/unreadable, malformed, zero interfaces, regression, independent up/down | logic PASSED (harness, `SysfsFallbackCountersTest`); `TrafficSnapshot` integration BLOCKED |
| VPN and physical sampling, handover, stale cache / callback failure | tests written (BLOCKED) |
| Widget force refresh: unchanged cache, boot, package replace, 0/1/many widgets, exception cleanup | orchestration PASSED (harness); Glance/datastore parts BLOCKED |
| 12/24 h formatting and observer lifecycle | formatter PASSED (harness); observer/lifecycle tests BLOCKED |
| StrictMode debug/release | disabled-path logic by control flow (`BuildConfig.DEBUG`), test BLOCKED |
| Compile + test both flavors | BLOCKED |
| Lint / static analysis | BLOCKED (project lint not runnable) |

## 6. What to run first

```
./gradlew testFullDebugUnitTest testPlayDebugUnitTest
./gradlew assembleFullDebug assemblePlayDebug
./gradlew lintFullDebug lintPlayDebug
```
If a Robolectric test fails, check the "risky assumption" column first; those are the likeliest causes and are test-side
adjustments, not product-code defects.
