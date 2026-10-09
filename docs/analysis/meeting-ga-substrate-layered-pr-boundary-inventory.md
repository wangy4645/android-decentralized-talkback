# Meeting GA Substrate → `origin/main` — Layered PR Boundary Inventory

**Status:** BOUNDARY INVENTORY (2026-10-09); PR #200 disposition **ADOPTED** — see `meeting-ga-pr200-disposition.md`  
**Decision frozen:** Scheme **B** — layered PRs; final tip fixed at **`9c88067`**.  
**Not this document:** implementation plan, merge commands, git operations, listening-quality work.

```text
Scheme                         B (layered PRs)
Final tip                      9c88067
origin/main (audit base)       8700621
History shape                  linear; main is ancestor of tip (FF-capable if done as one shot — rejected for review)
P1/P2/P3                       CLOSED via PR #201; L6 carries history only — do not re-implement
Field listening quality        UNKNOWN (out of scope)
```

---

## Global constraints

1. Advance `origin/main` **in layer order** L0 → L6. Do not open a later layer PR until the previous layer is merged (or explicitly waived in writing).
2. Each layer PR’s **head tip** is the layer’s end SHA below; **base** is the previous layer’s end tip (L0 base = `origin/main` at start of program).
3. Do **not** cherry-pick `1ab10ae` / `b04be4c` onto bare `main` — they depend on L0–L2 tree state.
4. Do **not** treat L6 as new P1/P2/P3 work. Desk/regression already signed; listening remains UNKNOWN.
5. **PR #200** ownership must be resolved at L2 (see L2 section) before or as part of landing `faf8884` on `main`.

---

## Layer index

| Layer | End tip | Commits in layer | Files (three-dot vs prev tip) | Role |
|-------|---------|------------------|-------------------------------|------|
| L0 | `1410578` | 5 | 33 | ADR / Phase 1a |
| L1 | `d06f17c` | 9 | 185 | Ops / investigate fixes |
| L2 | `faf8884` | 1 | 5 | RCA5-A pipeline lock |
| L3 | `1ab10ae` | 1 | 519 | **Meeting GA main gate** |
| L4 | `b04be4c` | 3 | 21 | Release freeze / 1.0.1 |
| L5 | `5628b71` | 2 | 3 | AudioTrack route + JNI fence |
| L6 | `9c88067` | 2 | 28 | Closed P1/P2/P3 tip only |

Cumulative at final tip: **23 commits**, **742 files** vs `origin/main` (`8700621`).

---

## L0 — ADR / Phase 1a

| Field | Boundary |
|-------|----------|
| **Base** | `origin/main` (`8700621` at inventory time) |
| **Head tip** | `1410578` |
| **Commits** | `647f280` → `bfd8ed0` → `03612a2` → `2bf0889` → `1410578` |
| **File scope** | ~33 files — mostly `android-board-talkback` conference-audio / webrtc stubs + `docs` ADR-0056 |
| **Depends on** | Current `main` only |
| **Unlocks** | L1 (ops fixes build on Phase 1a audio path concepts) |
| **Acceptance gate** | Design-record + early implementation boundary: ADR-0056 / Phase 1a docs present; AudioMixer / PcmInjection / OBS-056 path exist; no requirement for full Meeting GA multicast stack |
| **Rollback** | Revert layer merge on `main`, or reset `main` to pre-L0 tip if not yet depended on by later merges |

**Out of scope for L0 review:** Profile01 multicast conference package, cutover, RCA5 lock, P1/P2/P3.

---

## L1 — Ops / investigate fixes

| Field | Boundary |
|-------|----------|
| **Base** | L0 tip `1410578` |
| **Head tip** | `d06f17c` |
| **Commits** | `6809425`, `ee634bb` (merge), `c2f4842`, `d58e08c`, `ed636c6`, `9491fa6`, `296a213`, `cac628d`, `d06f17c` |
| **File scope** | ~185 files — session/admission/UI/`talkback-app` + conference ops; includes late-peer HELLO, End Meeting threading, ACCEPT lineage, CFC-1, MEETING_END timeout, SRD stale-peer fence |
| **Depends on** | L0 |
| **Unlocks** | L2 (RCA5-A parent is `d06f17c`) |
| **Acceptance gate** | Dependency check for listed ops themes; desk/unit evidence already carried in those commits where present; no GA multicast production stack required yet |
| **Rollback** | Revert L1 merge; do not land L2 until L1 tip restored |

**Review focus (not exhaustive file list):** late-peer channel-less HELLO; End Meeting off main thread; leave hangup vs SDP wait; ACCEPT lineage freeze; CFC-1 PARTIAL desk + Phase B wiring; MEETING_END timeout off coordinator queue; SRD stale peer/stats fence (`d06f17c`).

**Note:** `d06f17c` is also the first commit on open **PR #200** head history — see L2.

---

## L2 — RCA5-A (`faf8884`)

| Field | Boundary |
|-------|----------|
| **Base** | L1 tip `d06f17c` |
| **Head tip** | `faf8884` |
| **Commits** | `faf8884` only |
| **Exact files** | `ConferenceSessionMediaWiring.kt`; `Profile01ShadowPlayoutClockSeam.kt`; `Profile01ShadowRuntimeObservability.kt`; `Profile01ShadowRca5ConcurrencyReproTest.kt`; `docs/analysis/rca5a-playout-ingress-pipeline-lock.md` |
| **Depends on** | L1 (`d06f17c`) |
| **Unlocks** | L3 (`1ab10ae` parent is `faf8884`) |
| **Acceptance gate** | Independent review of ingress/playout serialization lock; concurrency desk `Profile01ShadowRca5ConcurrencyReproTest`; analysis note present |
| **Rollback** | Revert L2 only; L3 must not proceed without L2 tip |

### PR #200 ownership (required before/with L2 land)

| Item | Fact |
|------|------|
| PR | https://github.com/wangy4645/android-decentralized-talkback/pull/200 |
| State | OPEN |
| Base | `investigate/ui-anchor-0056-198` |
| Head | `fix/rca5a-ingress-pipeline-lock` @ `faf8884` |
| Commits on PR | `d06f17c`, `faf8884` |

**Rule:** `faf8884` is already in the Scheme B target history. **Do not** land the same change twice via PR #200 and via L2.

**Allowed dispositions (pick one in writing when executing L2):**

1. **Close PR #200 as superseded** by Scheme B L1+L2 landing on `main` (preferred if L1/L2 PRs are the chosen vehicle), or  
2. **Retarget / reuse PR #200** as the L2 vehicle onto post-L1 `main` **without** adding a second copy of `faf8884`, or  
3. **Merge PR #200 first** into a base that then becomes L1/L2 on `main` — only if that preserves a single instance of `faf8884` in history.

Parallel double-merge expressing the same patch is **forbidden**.

---

## L3 — Meeting GA main gate (`1ab10ae`)

| Field | Boundary |
|-------|----------|
| **Base** | L2 tip `faf8884` |
| **Head tip** | `1ab10ae` |
| **Commits** | `1ab10ae` only |
| **File scope** | ~519 files — primary introduction of `com.talkback.core.conference.*` production stack (~228 conference paths in this commit), build/native/libs/`talkback-app` wiring |
| **Depends on** | L0–L2 (hard) |
| **Unlocks** | L4–L6 |
| **Acceptance gate (main gate)** | Production wiring review; rollback/path narrative; build and release capability of the GA stack; treat as the Meeting capability admission review — not a drive-by ops patch |
| **Rollback** | Highest-cost layer: revert L3 only if L4+ not merged; after L4+, coordinated revert of L3–Ln |

**Out of scope for L3 review:** inventing new audio RCA; P1/P2/P3 re-implementation (those are L6 history).

---

## L4 — Release freeze / GA 1.0.1 (`c2fb0fa` … `b04be4c`)

| Field | Boundary |
|-------|----------|
| **Base** | L3 tip `1ab10ae` |
| **Head tip** | `b04be4c` |
| **Commits** | `c2fb0fa` → `0a74d87` → `b04be4c` |
| **File scope** | ~21 files — launcher resource extension; RC identity freeze; GA field fixes (accept/purge/cutover readiness tests and supports) |
| **Depends on** | L3 |
| **Unlocks** | L5 |
| **Acceptance gate** | Confirm release identity / launcher / 1.0.1 field-fix intent matches lab non-production labeling where present |
| **Rollback** | Revert L4 merge; identity pins may need explicit product acknowledgment |

---

## L5 — AudioTrack route + JNI fence

| Field | Boundary |
|-------|----------|
| **Base** | L4 tip `b04be4c` |
| **Head tip** | `5628b71` |
| **Commits** | `1b5ca8a`, `5628b71` |
| **Exact files** | `AndroidAudioTrackPlayoutSeam.kt`; `RealWebRtcAudioEngine.kt`; `talkback-app/.../MainActivity.kt` |
| **Depends on** | L4 (playout seam exists in GA tree) |
| **Unlocks** | L6 |
| **Acceptance gate** | Standalone review: VOICE_COMMUNICATION / volume key routing; no PeerConnection JNI after release |
| **Rollback** | Revert L5 (3 files) without touching L6 if L6 not yet merged |

---

## L6 — Closed P1/P2/P3 tip only

| Field | Boundary |
|-------|----------|
| **Base** | L5 tip `5628b71` |
| **Head tip** | **`9c88067`** (final Scheme B tip) |
| **Commits** | `36037c3`, `9c88067` (merge of PR #201) |
| **File scope** | 28 files — as already reviewed in PR #201 |
| **Depends on** | L5 tip (= former `baseline/meeting-ga-for-p123-desk` pre-#201 tip) |
| **Unlocks** | Scheme B complete on `main` |
| **Acceptance gate** | **Carry-only:** confirm tip equals closed work; run card `docs/analysis/meeting-audio-p1-p2-p3-desk-merge-run-card.md`; **no new implementation**; listening remains UNKNOWN |
| **Rollback** | Revert L6 only (returns `main` to `5628b71`); does not reopen P1/P2/P3 design |

**Forbidden in L6:** new audio RCA, encoder/ULE/gain/topology/ingress policy changes, re-authoring P1/P2/P3.

---

## Suggested PR title stubs (inventory only)

| Layer | Suggested title stub |
|-------|----------------------|
| L0 | `substrate(L0): ADR-0056 / Phase 1a onto main` |
| L1 | `substrate(L1): investigate ops fixes through SRD fence` |
| L2 | `substrate(L2): RCA5-A ingress/playout pipeline lock` (+ PR #200 disposition) |
| L3 | `substrate(L3): Meeting GA integration baseline (main gate)` |
| L4 | `substrate(L4): RC identity freeze and GA 1.0.1 field fixes` |
| L5 | `substrate(L5): multicast AudioTrack route and PC post-release JNI fence` |
| L6 | `substrate(L6): carry closed P1/P2/P3 tip to 9c88067` |

---

## Explicit non-goals

- No direct FF of all 23 commits in one unreviewed shot (Scheme A rejected for this program).
- No cherry-pick-only shortcut for L3/L4 onto bare `main` (Scheme C rejected as primary path).
- No listening-quality field campaign.
- No reopening P1/P2/P3 implementation.
- No git merge execution in the inventory phase.

---

## Next action after this inventory

1. **PR #200:** **CLOSED as superseded** — disposition in `meeting-ga-pr200-disposition.md`.  
2. **L0 PR:** open against `main`, head tip `1410578` (+ program docs commit if present).  
3. Keep final tip **`9c88067`** unchanged unless a new adjudication revises Scheme B.
