# PLAN: "Clear all" also wipes Gecko browsing data (cookies / cache / site storage)

_Planned 2026-07-18 (Fable, post-session-P discussion). Executor: Opus or Sonnet driver +
subagents (resumeproject-style). Small, single-session scope._

## Goal / user intent

The History page's **Clear all** currently wipes only OUR visit log
(`HistoryStore.clear`). The user wants "clear history" to ALSO clear Gecko's
browsing data — cookies, caches, DOM/site storage — i.e. log-me-out-everywhere.
Per-row ✕ stays log-only (unchanged).

## Grounded facts (verified in code 2026-07-18 — re-verify cheaply, don't re-explore)

- GeckoRuntime is a **process-wide singleton**: `@Volatile private var runtime` in
  `MainActivity`'s companion (`MainActivity.kt:2191`), reached via
  `internal fun sharedRuntime(activity: Activity)` (`MainActivity.kt:2194`,
  double-checked locking, creates on first use). `HistoryActivity` can call
  `MainActivity.sharedRuntime(this)` directly — no new plumbing.
- Clear-all lives in `HistoryActivity.onCreate` top row (`HistoryActivity.kt:56-66`):
  `makeButton("Clear all") { confirmImpact("Clear all history?", …) { HistoryStore.clear(prefs); page = 0; renderList() } }`.
  `confirmImpact(title, body, onAccept)` (`HistoryActivity.kt:223`) is the house
  custom scrim+pane confirm (NOT AlertDialog — e-ink rule).
- `HistoryStore.clear(prefs)` = `HistoryStore.kt:120`.
- **Zero existing** `StorageController`/`clearData`/`ClearFlags` usage.
- **No coroutines in this codebase** — async is `GeckoResult.accept { … }` only.
  Follow that idiom.

## Design decisions (settled with user — do not re-litigate)

- One button, one action: **Clear all = visit log + Gecko browsing data**. No
  separate "clear cookies" button, no checkbox matrix (e-ink minimalism).
- Confirm popup body must state the real impact: history list cleared AND
  "you'll be signed out of websites; caches and site data are wiped".
- Use `StorageController.ClearFlags.ALL`. Note: this also drops per-site
  permission grants and Gecko site settings (e.g. HTTPS-only "proceed anyway"
  exceptions) — acceptable, it's the honest meaning of "clear everything".
  Our own prefs (`rawHosts`, settings, layout) are native SharedPreferences —
  untouched by ClearFlags, correct.

## Implementation (one Sonnet subagent; ~15 lines total)

All in `HistoryActivity.kt`:

1. In the Clear-all click handler, replace the `confirmImpact` call so that:
   - Title/body updated per Design above.
   - `onAccept` does, in order:
     a. `HistoryStore.clear(prefs)`; `page = 0`; `renderList()` (existing behavior —
        instant UI feedback).
     b. `MainActivity.sharedRuntime(this).storageController
           .clearData(StorageController.ClearFlags.ALL)
           .accept({ /* optional: log "[history] gecko data cleared" */ },
                   { e -> /* log failure; do NOT crash */ })`
2. Imports: `org.mozilla.geckoview.StorageController` (and whatever the runtime
   accessor needs — likely nothing new; `MainActivity` is same package).
3. Keep the button label "Clear all" (space is tight); the confirm body carries
   the expanded meaning.

Notes for the implementer:
- `clearData` returns `GeckoResult<Void>` — fire-and-forget with `.accept` is
  fine; do not block the UI thread waiting on it.
- Calling `sharedRuntime(this)` from HistoryActivity may CREATE the runtime if
  the user somehow reached History without MainActivity ever running. That's
  harmless (runtime creation is cheap-ish and idempotent) but if you want to be
  surgical: only clear Gecko data when a runtime already exists. Simpler path:
  just call it — HistoryActivity is only reachable from the hub, which is only
  reachable from MainActivity, so the runtime always exists in practice.
- Do NOT touch per-row ✕, retention pruning, or `HistoryStore`.

## Verification (driver, on-device — Nomad preferred)

Build: `./gradlew :app:assembleDebug`; install
`~/Library/Android/sdk/platform-tools/adb -s <serial|100.67.164.61:5555> install -r app/build/outputs/apk/debug/app-debug.apk`.
Package `com.afluffywaffle.avosetta.dev`. Read
`~/Develop/supernote-dev-reference/README.md` before Supernote work.

1. Load a page that sets a cookie / has a login (e.g. log into any site, or use
   a cookie-test page driven via the nav trick:
   `am start -n com.afluffywaffle.avosetta.dev/com.afluffywaffle.avosetta.MainActivity -f 0x24000000 --es navUrl <URL>`).
2. Hub → ◷ History → Clear all → confirm. Check:
   - History list empties (existing behavior intact).
   - Logcat shows the success log (if added).
   - Revisit the site: logged out / cookie gone.
3. Confirm per-row ✕ still works and does NOT log you out of anything.
4. Regression sniff: normal browsing, autocomplete, history recording still fine
   after the wipe (HistoryStore starts fresh, records again).

Human drives page loads/logins (IME swallows synthetic text). `run-as` prefs
read-back can verify the log side; cookie side needs the human eyeball.

## Out of scope

- A broader "privacy/security" settings page (security pillar, later).
- Clearing data per-site (site-exceptions manager territory).
- Any change to 7-day retention / backstop.
