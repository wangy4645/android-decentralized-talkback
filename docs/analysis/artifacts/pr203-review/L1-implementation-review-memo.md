# PR #203 — L1 implementation review memo (partial)

**Date:** 2026-10-09  
**Basis:** Offline patches under `docs/analysis/artifacts/pr203-review/` (`0001`–`0007` production)  
**Scope:** Behavioral / lifecycle / threading — **not** full 163-file line sign-off  
**Program:** Scheme B L1 prefix · `d06f17c` deferred · listening **UNKNOWN**

---

## Review depth

| Area | Depth |
|------|--------|
| Commits `0001`–`0005`, `0007` | Patch read + contract notes |
| Commit `0006` (L4 / coordinator bulk) | Patch grep + structural review; **not** every hunk in +2465 coordinator lines |
| Remaining production `.kt` in `0006` | Rely on listed desk/integration tests + post-merge optional targeted `testDebugUnitTest` |

---

## Per-commit findings

### `0001` — late-peer channel-less HELLO

- **Intent:** Resolve HELLO without channel id against **existing accepted GROUP** session; explicit **no guess** when ambiguous (`GroupLatePeerChannelResolutionSupport` / `GroupLatePeerAdmissionSupport`).
- **Coordinator:** HELLO path hooks + test harness counters (`testDeliverHello`, metrics) — stays on **`runOnCoordinatorSync`** for tests.
- **Tests:** `GroupLatePeerAdmissionSupportTest`, `GroupLatePeerChannelResolutionSupportTest`, `GroupLatePeerAdmissionIntegrationTest` (345 lines) — **primary regression anchor** for this commit.
- **Risk:** Low–medium — logic is isolated; failure mode is **reject admit** rather than wrong channel if resolution ambiguous (per commit message).

### `0002` — End Meeting off main thread

- **Intent:** UI updates on Main; `joinMeeting` / `toggleMeetingMute` / leave on **`Dispatchers.Default`**; Toast back on Main with `isAdded` guard.
- **Runtime:** `leaveChannelSession` can take explicit `sessionId` + `conference` flag to call `leaveConference` without re-querying active session.
- **Risk:** Low for ANR — ensure no new **UI mutation** on Default (patch shows `withContext(Main)` for navigation/Toast). **Not re-run** UI tests in this memo.

### `0003` — leave hangup independent of SDP wait

- **Intent:** P0 session-close (`HANGUP` / `GROUP_LEAVE`) must not block on `PEER_EDGE_READY` / SDP (`PeerControlSignalingAdmission.maySendSessionClose`).
- **WebRTC:** `abortPendingNegotiation()` + chunked `await()` loop checks abort flag — unblocks coordinator leave while SDP latch pending.
- **`ConferenceControlSnapshot`:** Immutable peer list for hangup off coordinator — **documented thread-safe copy** for close path.
- **Tests:** `PeerControlSignalingAdmissionTest` extended.
- **Risk:** Medium — abort flag must be set on **all** leave entry paths; patch shows `mediaRegistry.abortPendingNegotiation()` in leave/hangup refactor in `0006` (see below). Worth confirming **every** `leaveConference` / `hangup` path calls abort (grep in `0006` shows at least one callsite).

### `0004` — ACCEPT lineage freeze

- Small coordinator delta (2 files). **Risk:** Low; desk coverage in commit footprint — spot-check if merge blocked on missing test run.

### `0005` — CFC-1 PARTIAL desk gate

- Large **failure-domain** scaffolding (classifier, telemetry, projector) — **desk-first** per subject (“without runtime wiring” in original message; wiring expanded in `0006`).
- **Tests:** `ConferenceFailureCfc1PartialValidationTest`, classifier/pipeline tests.
- **Risk:** Medium for **false terminal attribution** if wired wrong in `0006`; this commit alone is mostly **observability / desk contract**.

### `0006` — Phase B L4 / CPP UI fuse / CFC-1 field wiring

- **Bulk:** ~137 files; **Coordinator +2465 lines** in cumulative PR diff (includes `0001`/`0007` coordinator edits — not all L4-only).
- **Themes (from patch):**
  - **L4 adjudication** (`ConferenceL4Adjudicator`, `ConferenceL4MediaUsableContract`, room state LIVE vs avatar DEGRADED).
  - **JNI / media thread:** `WebRtcJniThreadGuard`, `ConferenceMediaJniAffinity`, `PeerMediaExecutors`.
  - **Leave path:** `mediaRegistry.abortPendingNegotiation()` on leave; `hangup`/`leaveConference` still **`runOnCoordinator` / `runOnCoordinatorSync`**.
  - **Governance / SDP:** `ConferenceEdgeSdpApplyGate`, `runOnCoordinator` timeouts for SDP apply.
  - **Health projection:** `ConferenceHealth*`, CPP UI fuse in `talkback-app`.
- **Tests (non-exhaustive):** `ConferenceL4ScenarioCValidationTest`, `ConferenceHealthPhase25IntegrationTest`, `ConferenceRecoveryBindingPhase23IntegrationTest`, many `*FixturesTest` / `*ContractTest`.
- **RealWebRtc in PR:** +166/−7 vs L0 — **observation / program sender snapshot / diagnostic hooks** from L1 stack, **not** `d06f17c` SRD fence.
- **Risk:** **Highest in L1** — coordinator concurrency and L4↔UI fuse; **cannot** certify without running listed integration tests or targeted field card. **Not** Meeting GA multicast.

### `0007` — MEETING_END transition timeout

- **Intent:** `expireGovernanceTransitionTimeouts()` on **scheduler thread**, not only inside backed-up `runOnCoordinator` queue.
- **On terminal:** READY → existing readiness log; TIMED_OUT/FAILED/ABORTED → log + **`reconcileGroupMeshInternal`**.
- **Risk:** Low–medium — avoids stuck MEETING_END; reconcile on timeout may increase mesh churn (intended per message).

---

## Cross-cutting lifecycle map (L1 prefix)

```text
UI (Main) ──Default──► ViewModel join/leave/mute
                              │
Coordinator queue ◄── runOnCoordinator / Sync
                              │
         leave/hangup ──► abortPendingNegotiation() ──► WebRtc engines
                              │
Scheduler ──► expireGovernanceTransitionTimeouts() (0007)
```

---

## Deferred / out of PR

- **`d06f17c`:** SRD stale-peer fence, `SrdAdmissionDecision`, large RealWebRtc churn — **absent** from patches `0001`–`0007`.
- **L3 GA / multicast / P1–P3:** absent (prior range audit).

---

## Suggested gates before merge approval (optional)

Minimal JVM subset on `substrate/l1-ops` (may hit unrelated compile debt on full module — scope filters only):

```powershell
./gradlew :android-board-talkback:testDebugUnitTest `
  --tests "com.talkback.app.GroupLatePeerAdmissionIntegrationTest" `
  --tests "com.talkback.core.signaling.peer.PeerControlSignalingAdmissionTest" `
  --tests "com.talkback.core.session.ConferenceL4ScenarioCValidationTest" `
  --tests "com.talkback.core.session.ConferenceHealthPhase25IntegrationTest"
```

| Run | Result |
|-----|--------|
| 2026-10-09 · worktree `talkback-substrate-l1` · `origin/substrate/l1-ops` @ `413d4ff` | **PASS** (exit 0, ~33s) |

**Not** a substitute for listening-quality or Meeting GA proof.

---

## Memo verdict (for human merge decision)

| Item | Status |
|------|--------|
| Obvious threading regression in reviewed patches (`0002`/`0003`/`0007`) | **No blocker identified** in patch review |
| `0006` coordinator/L4 correctness | **Not fully reviewed** — memo JVM subset **PASS** (L4/health anchors); not full `0006` sign-off |
| Full 163 `.kt` line sign-off | **Not claimed** in this memo |
| Merge authorization | **Still human** — memo supports **conditional** approval if optional JVM subset passes |

---

## Artifacts

- Patches: `pr203-l1-production-only.patch`, `commits/0001`–`0007`
- Path list: `files-changed.txt`
- Range audit: prior evidence package (L0 on `main`, path set vs `1410578...cac628d`)
