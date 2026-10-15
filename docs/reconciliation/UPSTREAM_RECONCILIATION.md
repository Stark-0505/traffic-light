# Upstream reconciliation report

Selective backport of `leekleak/traffic-light` improvements into the `Stark-0505/traffic-light` rewrite.
No merge, rebase, reset or cherry-pick was used; upstream commits were read as evidence and the useful
behaviour was re-implemented against the fork's current architecture.

> **Read this first: nothing in this report was compiled or run under Gradle.**
> The authoring environment could not resolve `services.gradle.org`, `dl.google.com` or Maven Central and had no
> Android SDK. All new code and tests are statically reviewed, syntax-checked with a standalone `kotlinc`, and the
> Android-free logic (53 checks) was executed in an ad-hoc harness. The Android/Robolectric tests were **written but
> not executed**. Run `./gradlew testFullDebugUnitTest testPlayDebugUnitTest` before merging.
> Details and exact commands: [`BUILD_TEST_VERIFICATION.md`](BUILD_TEST_VERIFICATION.md).

## 1. Executive summary

* 17 new commits (15 code/test commits plus two documentation commits) on branch `reconcile/upstream-selective-backport`, on top of
  the untouched fork HEAD `a8beb2e` (backup tag `backup/fork-head-a8beb2e`).
* All 91 upstream-only commits were read and classified ([ledger](upstream-commit-decision-ledger.csv)):
  **11 adapted** (10 fully, 1 partially), **15 deferred**, **18 irrelevant**, **45 excluded by product scope**
  (30 of them the iPerf3 series), **2 judged unsafe to port as written** (`351dd77` and `6d69b14`; one idea from the first
  was adapted). Separately, the behaviour of 2 commits was already present in the fork (`0abee5c`, `a86ad79`; counted under
  "irrelevant"); the ledger's `already_present_in_fork` and `behavior_adopted` columns carry this.
* **iPerf3 is excluded entirely** (owner decision, 2026-10-14): none of its native/NDK code, module, permissions,
  UI, logging, database or dependency changes were imported. This replaces the original "defer" default.
* Every shortlist item the audit raised was checked against the real files and, where confirmed, implemented (sysfs reads,
  preview registration, widget refresh, 12/24 h formatting, callback fallback, discovery cost, alarm lifecycle, StrictMode,
  channel cleanup, residual locale removal). The full review found two further problems that were fixed (the notification
  `when` overflow and unsupported per-interface counters). One conditional item (GrapheneOS) was deliberately **not**
  ported, with the reasoning pinned by tests.
* No plan, translation, UI redesign, database, dependency or Gradle-catalog change was introduced. The only
  manifest change is the `MY_PACKAGE_REPLACED` action; the only `build.gradle.kts` change removes
  `generateLocaleConfig`.

## 2. Verified repository state

| Item | Value |
|---|---|
| Common base (merge-base) | `c1c42fa827545d221a34e48163fabe230e71ee8b` |
| Fork HEAD (unchanged, tagged `backup/fork-head-a8beb2e`) | `a8beb2ec453aa22a2414d93ef6db429e7d6f46b7` "rewrite of traffic light", 2026-09-01 |
| Upstream HEAD | `e40dd7a77e24290345b0f99f4990467758d9acbd` `v3.4`, 2026-10-11 |
| Upstream-only commits | 91 (all non-merge) |
| Fork-only commits before this work | 1 |
| Work branch | `reconcile/upstream-selective-backport` |
| Authorship of new commits | `Claude (upstream reconciliation) <noreply@anthropic.com>` (no git identity was configured; none was impersonated) |

All of this was re-derived from the supplied archives, not taken from the brief. The supplied ZIPs were not modified;
work was done in extracted copies. The packaged repo keeps `origin` (Stark-0505), and adds a **read-only** `upstream`
remote pointing at `https://github.com/leekleak/traffic-light.git` with `refs/remotes/upstream/main` = `e40dd7a`, so
every hash in the ledger resolves locally. Upstream tags that the fetch had created were removed (the fork archive had
no tags) and `remote.upstream.tagOpt = --no-tags` is set.

## 3. Constraints preserved (with evidence)

| Constraint | Status |
|---|---|
| No data-plan feature | No plan models, tables, screens, channels or navigation added. The only "plan" strings added are the two obsolete channel IDs that are *deleted* (`NotificationChannelCleanup.kt`). |
| Custom UI / navigation kept | No screen, chart, dialog or navigation file replaced. UI files touched: `History.kt` (label formatting inputs only) and `NotificationSettingsScreen.kt` (probe moved off composition). `NavDisplay` untouched. |
| English-only | 24 residual localized `values-*` directories and `androidResources.generateLocaleConfig = true` **were present** in the archive and are removed (§4, commit 9). `values-v31` is an API qualifier and kept. |
| Preference-backed data, no Room | No database code, migration or dependency added. |
| `full`/`play` flavors, DI, WorkManager, notification config | Unchanged. One Koin `single` added (`TimeFormatSource`). |
| App-usage screens / `NetworkStatsManager` / `NetworkUsageManager` | Not touched. |
| VPN-excluding accounting | `bytesPerSecond`, source-key rebaselining, physical-interface filtering and the totals fallback are unchanged in behaviour; `BytesPerSecondTest` (pre-existing, 9 tests) passes in the harness against the current source. Upstream's rewrite `351dd77` was **not** ported. |
| No new dependencies | None. Tests deliberately use Robolectric's `RuntimeEnvironment` rather than adding `androidx.test:core`. |

## 4. Changes implemented

Commit hashes are final; see [`IMPLEMENTATION_CHANGELOG.md`](IMPLEMENTATION_CHANGELOG.md) for files and tests per commit.

1. **`40f9bc5` fix: move fallback traffic reads off main thread** (upstream `b16f916`, P0).
   Audit confirmed: the sysfs fallback ran on the caller's scope, and in `SpeedNotification` that scope is the service
   `lifecycleScope` (Main). `updateSnapshot()` now suspends once on an injectable IO dispatcher; reads go through an
   injectable `SysfsReader`; the capability probe is `suspend` and `NotificationSettingsScreen` collects it with
   `produceState` instead of calling it inside `remember {}` during composition. The sample is read completely before
   it is published, so a failure cannot leave a half-updated sample. Failure semantics (IO/format/SELinux → disable
   fallback) and cancellation propagation are preserved.
2. **`a40fbbd` perf: register Glance widget previews on IO** (upstream `d319506`, P0).
   Registration runs on IO after the existing 1 s delay, only on a fresh launch (not on every rotation), and failures are
   logged instead of crashing `lifecycleScope`. `MainActivity` and its navigation startup are otherwise unchanged.
3. **`f8bdf96` fix: refresh widgets after restart and package replacement** (upstream `ea18bb4`, part of `0313dac`, P1).
   Upstream added a persisted `FORCE_REFRESH` flag. Here the *rendered-state cache is the only "refresh needed" state*;
   forcing a refresh forgets it. Boot and `MY_PACKAGE_REPLACED` run the same independent best-effort restore steps
   (permissions, speed-notification service if enabled, alarm + forced re-render only if widgets are placed).
   `PendingResult.finish()` is now guaranteed in `finally` with an 8 s budget for all three goAsync paths.
4. **`d119d5b` perf: cache system time format for history labels** (upstream `7ac70ea`, P1).
   Pure formatter taking a Boolean; one shared `StateFlow` resolves the setting off-main, observes
   `Settings.System.TIME_12_24` with a `ContentObserver`, resolves changes strictly sequentially, and holds the observer
   only while collected (+5 s). Differences from upstream: one shared observer instead of one per composable, and no
   out-of-order publication.
5. **`37a1335` fix: make network callback fallback reliable and avoid redundant interface discovery** (audit, P2 ×2, one commit
   because they share state). Confirmed: when `registerNetworkCallback` threw, the log promised per-tick discovery but the
   cache stayed frozen forever. Callbacks now only mark the cache dirty; discovery happens at most once per tick, on IO, only
   when dirty (also removes the constructor-time Binder calls). Without a callback it polls every 2 s.
   Justification is a control-flow argument, not a measurement: discovery cost is now ≤ 1/tick and zero in steady state,
   versus ≥ 1 per callback event before.
6. **`c408a54` fix: align widget alarm lifecycle with active widgets** (audit, P2).
   Confirmed: the 1-minute alarm was armed on every process start with or without widgets. It is now armed only while a
   widget exists (`syncWithWidgets`), started in `onEnabled`, and the alarm tick can cancel a stale alarm but never
   re-registers itself (re-registering with start time "now" would fire immediately in a loop).
7. **`c240cf2` chore: enable debug-only StrictMode diagnostics** (upstream `daca619`, `839d456`, `03f1feb`, P2).
   Installed first in `Application.onCreate`, gated on `BuildConfig.DEBUG`, `penaltyLog` only, with a curated detector set
   rather than `detectAll()`.
8. **`49a9a94` chore: delete notification channels left behind by older builds** (upstream `f618bbd`, conditional).
   Proven-obsolete IDs from full history: `PersistentNotification`, `PersistentNotification (Silent)`,
   `PlanNotification`, `PlanWarningNotification`. Explicit list (upstream deleted "everything not current"), never an
   active ID, off main.
9. **`7bf3907` chore: remove residual locale support** (audit). Removes the 24 localized `strings.xml` directories and
   `generateLocaleConfig`; verified first that every referenced resource exists in the default `values/strings.xml`.
   The README Translations section no longer sends contributors to Weblate.
10. **`09e415b` test: pin device-totals fallback when the platform hides networks** (upstream `6d69b14`, conditional — **not ported**, §5).
11. **`145a709` test: use RuntimeEnvironment instead of androidx.test ApplicationProvider** (keeps the dependency set unchanged).
12. **`3e97199` fix: avoid Long.MAX_VALUE as the speed notification's when timestamp** (upstream `1fddad6`/`0da3814`, found in review).
    Upstream reported an overflow on Honor skins and switched to `now + DECADE`, but `DECADE = 1000*60*60*24*365*10`
    is `Int` arithmetic and wraps to 1,827,387,392 ms (~21 days). The fork uses a correct `Long` ten-year offset.
13. **`b50c9c7` fix: use device totals when the platform serves no per-interface counters** (upstream `351dd77`, one idea, found in review).
    The fork summed per-interface counters with no guard, mapping `UNSUPPORTED` to 0; upstream (before and after its
    rework) only used them on API 31+. Rather than hard-coding an SDK cut-off, the platform's answer decides: if every
    physical interface is `UNSUPPORTED` in both directions, device totals are used. Behaviour is unchanged wherever
    per-interface counters work.
14. **`1852f3b` chore: remove unused imports from new code**, and a final **docs** commit adding this report, the ledger,
    the changelog and the verification report.

## 5. Decisions worth knowing about

**GrapheneOS (`6d69b14`) — investigated, not ported.** Upstream treats `checkSelfPermission(INTERNET) != GRANTED` as "the
platform is spoofing a network-less environment". This fork's manifests never declare `INTERNET` (upstream added it at HEAD,
for iPerf), so that check would *always* read denied and permanently route accounting to device totals — silently bringing
back VPN double counting in the `full` flavor. Without the declaration, GrapheneOS's Network toggle also has nothing to
revoke, so `full` cannot be affected. The accounting half of the upstream fix already exists (an empty/unusable interface
set selects device totals; two tests pin it). The residual effect is the "silent when disconnected" mode on the `play`
flavor, whose merged manifest I could not inspect offline. No permission was added and no filtering weakened.

**`351dd77` (traffic-counting rework) — not ported wholesale.** Reviewed for isolated ideas: callback-tracked interfaces,
negative-counter clamping and `SecurityException` handling already exist; the per-interface support gate became commit 13.

**`a86ad79` (VPN-only notification) — already satisfied.** The fork's `isNetworkAvailable()` already excludes `TRANSPORT_VPN`.

**Behaviour expansion to be aware of (commit 3).** `MY_PACKAGE_REPLACED` now also restarts the speed-notification service
(if the user has it enabled), because the update kills it, exactly as boot already did. This goes slightly beyond "refresh
widgets"; revert `AutoStarter`'s action filter to `BOOT_COMPLETED` plus keep the widget path if you prefer the narrower scope.

## 6. Not changed, and why

* **Dependencies / Gradle / catalog:** 20 version keys differ from upstream HEAD (e.g. AGP 9.3.1 vs 9.4.1, Kotlin 2.4.10 vs
  2.4.21, Glance 1.2.0-rc01 vs 1.3.0-alpha02, Gradle 9.6.1 vs 9.8.0). None is required by a selected change; the new
  code only uses APIs already present. Upgrades are deferred and should be a separate, validated change.
* **CI:** the fork workflow already runs `assembleDebug` and `testDebugUnitTest` (both flavors' debug variants), does not
  pin an SDK, and so has no API 35 vs `compileSdk 37` mismatch. Pinning a build-tools version could not be validated
  offline and risks breaking CI, so it was left alone. CodeQL (`25`–`30`, `33`) is deferred; upstream's iterations were
  shaped by the iPerf native build and could not be tested here.
* **`fastlane/metadata` translated store listings:** store text, not app resources; left in place.
* **`localeFilters`:** not applied; it would change TalkBack/content-description language on non-English devices and is a
  separate decision.
* **`TrafficStats.getTotalTxBytes()` capability probe in `TrafficSnapshot`'s constructor/flow** still runs on the caller's
  thread. It is a native counter read, not sysfs/Java I/O, and moving it changes initial-state timing; noted, not changed.
* **Settings shortcut to the silent channel (`68d7551`)** is a useful new UI feature but not a bug fix; deferred.

## 7. Known risks and unverified assumptions

1. **Nothing in this report was verified by a Gradle build.** The owner's first build found one compile error (a function accidentally deleted in `b50c9c7`, restored in `6e3c97c`); others are possible. The build has not been re-run since that fix. Imports and symbols in every changed
   file were audited with a script, and every file was syntax-checked, but type checking was not done.
2. **57 of the 101 new tests need Robolectric/mockk and have never run.** Specific assumptions: `ShadowContentResolver.getContentObservers(uri)`
   exists as a static in Robolectric 4.16.1; `StrictMode.*Policy.toString()` reflects the mask; mockk overload resolution for
   `registerNetworkCallback(any(), capture(slot))`. Any of these may need a trivial adjustment.
3. **Glance threading contract** for `setWidgetPreviews` off the main thread could not be checked against the 1.2.0-rc01
   binary. Evidence used: the fork already composes/updates widgets on `Dispatchers.Default`, and upstream does the same call on IO.
4. **Honor `when` overflow** and **per-interface counter availability** (Android 10–11) are taken from upstream's report and
   Android's documented `/proc/net` restrictions; neither was reproduced on a device.
5. Performance claims (commits 4 and 5) rest on control-flow arguments, not measurements.
6. The existing handover test in `TrafficSnapshotTest` never fires a network callback, so it passes vacuously; only its comment
   was corrected, and the real handover coverage lives in `TrafficSnapshotInterfaceDiscoveryTest`.

## 8. Reproducing the reconciliation

```
git log --oneline backup/fork-head-a8beb2e..HEAD         # the new work
git diff backup/fork-head-a8beb2e..HEAD --stat           # scope
git show <hash-from-ledger>                              # any upstream commit (upstream/main is fetched)
./gradlew testFullDebugUnitTest testPlayDebugUnitTest    # what still has to be run
```
