# L1 (`d06f17c`) Compile Dependency RCA

**Status:** ADOPTED — **Option A** (2026-10-09)  
**Context:** Scheme B · PR #203 draft · L0 merged (`main` @ `a4a7b01`)  
**Not:** merge authorization · production fix · listening-quality work

---

## Summary

| Checkpoint | `compileDebugKotlin` | Notes |
|------------|----------------------|--------|
| `main` + L0 | PASS | — |
| L1 through **`cac628d`** / cherry-pick **`12bcbec`** | **PASS** | 7 of 8 L1 commits |
| L1 audit tip **`d06f17c`** (SRD fence) | **FAIL** | Not cherry-pick-specific; same on historical tip |
| PR #203 (prefix) `substrate/l1-ops` @ **`12bcbec`** | **PASS** (post–Option A) | Excludes `d06f17c` |
| Former PR #203 @ `ef9f4b7` | **FAIL** | Included `d06f17c` — superseded |

**Conclusion:** Inventory tip `d06f17c` is **not an independently compilable L1 closure** on top of L0+`main`. The SRD fence commit assumes symbols/files that first land in **L3 `1ab10ae` (Meeting GA)** and related WebRTC observability expansions.

---

## Failure owner commit

**`d06f17c`** — `fix(conference): fence SRD from stale peer/stats native overlap`  
Large `RealWebRtcAudioEngine.kt` change (+342/−51) references:

- `ConferenceRealizationLineage` — **added only in `1ab10ae`**
- `WebRtcAudioEngine` diagnostic properties (`transportDiagnosticOfferLineageId`, etc.) — expanded in GA stack
- `MediaObservabilityLog.pcCloseEnter` / `pcCloseExit` / `pcCloseSkipped` — expanded in GA stack
- `WebRtcSharedFactory.release(...)` overload — GA stack

Prior L1 commit **`296a213`** also touches `RealWebRtcAudioEngine` but **does not** introduce `ConferenceRealizationLineage`; it still compiles after L0.

---

## Probe: minimal L3 file backport (not adopted)

On top of `12bcbec`, checked out from `1ab10ae`:

- `ConferenceRealizationLineage.kt`
- `WebRtcAudioEngine.kt`
- `MediaObservabilityLog.kt`
- `WebRtcSharedFactory.kt`

Then re-applied `d06f17c` → **still FAIL** (e.g. `FactoryHit`, `EngineRequestIntent`, `WebRtcNetworkBridgeInstall` — further L3-only types).  
Whole-file backport from L3 is **not** a minimal L1 fix; it drags Meeting GA observability surface.

---

## Adjudication — **Option A adopted**

- **L1 merge scope:** cherry-picks through **`cac628d`** only (`12bcbec` on `substrate/l1-ops` after L0 `main`).
- **`d06f17c`:** **deferred** — land with L2/L3 dependency boundary later; no L1 production patch; no smuggled L3 files.
- **PR #203:** converged to L1 prefix (7 commits + RCA doc); **draft — not merged** until human approval after compile gate.

---

## PR #203 state

- **Draft** — L1 prefix only; `d06f17c` removed from branch history on PR.
- Cherry-pick set: **7** non-merge commits (omit `ee634bb`, omit `d06f17c`).

---

## Unchanged program constraints

- Final tip **`9c88067`**
- P1/P2/P3 **CLOSED**
- Listening **UNKNOWN**
- No field / four-device campaign for L1
