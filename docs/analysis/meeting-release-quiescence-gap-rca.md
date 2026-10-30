# Meeting Release / Quiescence Gap — RCA

**ID:** meeting-release-quiescence-gap-rca-20260908  
**Status:** INVESTIGATION / NO IMPLEMENTATION  
**Scope:** GROUP→CONFERENCE CREATE invite admission vs media release race  
**Out of scope:** CREATION replay, MEMBERSHIP, Phase A, signaling transport

## Frozen posture

```text
CREATION replay          NO CHANGE
MEMBERSHIP               HOLD
Phase A                  BLOCKED
Current blocker          Meeting release/quiescence gap
```

## Symptom

Field J0 with O0+O0b PASS still fails O1:

```text
GROUP→CONFERENCE CREATE
→ mediaSessionClose(M02) already in flight (or starts at CREATE)
→ invite admission requests CONFERENCE engine for M02
→ releaseInFlight → MEDIA_RELEASE_DEFERRED_CREATE
→ Conference invites sent=0 / pending=0
→ ~123ms later MEDIA_RELEASE_COMPLETE
→ no MEDIA_PENDING_DRAIN for CONFERENCE
→ no GROUP_INVITE / no btnJoinMeeting
```

This is **not** “environment not clean before CREATE.” O0b can pass while CREATE still manufactures a release race.

## Evidence runs

| RunId | O0/O0b | O1 | Key delta |
|-------|--------|-----|-----------|
| `meeting-product-j0-same-session-20260907-175549` | not scored (pre-O0b) | **PASS** (`pending=1` → Accept → J0_PASS) | deferred + **drained** same ms |
| `meeting-product-j0-creation-e2e-20260908-101856` | **PASS** | **FAIL** | deferred + **not drained** |

Evidence paths:

- PASS: `talkback/logs/meeting-product-j0-same-session-20260907-175549/m01-projection-live.txt`
- FAIL: `talkback/logs/meeting-product-j0-creation-e2e-20260908-101856/m01-host-live.txt`, `M01-talkback.txt`

---

## Q1 — What is the `MEDIA_RELEASE_DEFERRED_CREATE` contract?

### Observable emission

`MEDIA_RELEASE_DEFERRED_CREATE` is emitted when `MediaSessionManager.queuePendingEngineRequest` enqueues an engine provision while release/admission cannot proceed immediately:

```524:534:talkback/android-board-talkback/src/main/java/com/talkback/core/media/MediaSessionManager.kt
    private fun queuePendingEngineRequest(
        moduleId: String,
        scope: MediaBearerScope,
        onReady: (WebRtcAudioEngine) -> Unit,
        origin: String,
        onFailed: (() -> Unit)? = null
    ) {
        pendingEngineRequests.computeIfAbsent(moduleId) { mutableListOf() }
            .add(PendingEngineRequest(scope, origin, onReady, onFailed))
        MediaObservabilityLog.mediaReleaseDeferredCreate(moduleId, scope)
    }
```

Enqueue origins relevant here:

| Origin | Trigger | Expected continuation |
|--------|---------|---------------------|
| `releaseInFlight` | `requestEngine` while `releaseInFlight.contains(moduleId)` | **Scope-dependent drain** (see Q2) |
| `requestEngine` | async path needs `mediaSessionProvision` first | `scheduleDeferredProvision` on non-`mediaSessionClose` complete |
| `mediaSessionReuse` | GROUP→CONFERENCE reuse barrier | reuse release + deferred provision |

### Contract answer (as implemented today)

**Not a single uniform “always retry after release.”** Behavior depends on **release origin** and **pending scope/origin pair**:

1. **`mediaSessionProvision` / non-close release complete** → `scheduleDeferredProvision` drains **all** pending for that module (including `CONFERENCE:requestEngine` and `CONFERENCE:releaseInFlight`).

2. **`mediaSessionClose` complete** → `scheduleGroupReleaseInFlightPendingDrainAfterClose` drains **only** `GROUP + releaseInFlight` pending. **CONFERENCE pending is intentionally preserved** (hangup stacking; see m03-host-pc-generation RCA, ADR-0057 narrow auth).

```478:500:talkback/android-board-talkback/src/main/java/com/talkback/core/media/MediaSessionManager.kt
    /**
     * After [mediaSessionClose], drain only GROUP requests queued while release was in flight.
     * Preserves hangup stacking for CONFERENCE pending (see m03-host-pc-generation RCA).
     */
    private fun scheduleGroupReleaseInFlightPendingDrainAfterClose(moduleId: String) {
        ...
        val drainable =
            pending.filter {
                it.origin == "releaseInFlight" && it.scope == MediaBearerScope.GROUP
            }
        ...
    }
```

3. **Coordinator layer** explicitly does **not** retry invite sends on dispatch failure:

```19449:19452:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
    /**
     * ADR-0017: consume invite dispatch outcome only — Coordinator does not retry sends.
     * [sentCount] must reflect invites actually dispatched in this completion window.
     */
```

### RCA conclusion for Q1

For **GROUP invite execution (ADR-0057)**, deferred `releaseInFlight` **must** retry via MSM drain after `mediaSessionClose` — fixture `gieEg3_releaseComplete_grantsExecutionOpportunityExactlyOnce` encodes this.

For **CONFERENCE invite at GROUP→CONFERENCE CREATE**, the same log line (`MEDIA_RELEASE_DEFERRED_CREATE`) masks **two different contracts**:

- `CONFERENCE:requestEngine` during `mediaSessionProvision` → **will retry** (PASS run)
- `CONFERENCE:releaseInFlight` during `mediaSessionClose` → **will not retry** at MSM layer; coordinator also will not redrive

**Gap:** conference CREATE invite uses defer semantics without a matching post-`mediaSessionClose` continuation for `CONFERENCE` scope.

---

## Q2 — Does `MEDIA_RELEASE_COMPLETE` re-drive the conference invite?

### Continuation machinery (exists, but narrow)

| Release complete path | Function | Drains CONFERENCE pending? |
|----------------------|----------|----------------------------|
| `origin != mediaSessionClose` | `scheduleDeferredProvision` | **Yes** (all pending) |
| `origin == mediaSessionClose` | `scheduleGroupReleaseInFlightPendingDrainAfterClose` | **No** (GROUP+releaseInFlight only) |

`onReady` callback from `requestConferenceEngine` → `beginConferenceInviteWhenEngineReady` is stored inside the pending request. If pending is never drained, **invite continuation is lost** even though release completes successfully.

### FAIL run (`…-101856`) timeline

```text
10:20:50.966  MEDIA_RELEASE_SCHEDULED module=M02 origin=mediaSessionClose
10:20:50.971  Conference solo host / SESSION_CREATED
10:20:51.004  MEDIA_RELEASE_DEFERRED_CREATE module=M02 requestedScope=CONFERENCE
10:20:51.006  Conference invites sent=0
10:20:51.129  MEDIA_RELEASED / MEDIA_RELEASE_COMPLETE origin=mediaSessionClose
              (no MEDIA_PENDING_DRAIN)
```

Pending request shape (inferred from code + PASS contrast): `CONFERENCE:releaseInFlight` queued while `mediaSessionClose` in flight.

After complete, only `scheduleGroupReleaseInFlightPendingDrainAfterClose` runs → **no drain** → `onReady` never fires → no `GROUP_INVITE`.

### Caller redrive paths (do not cover this case)

- `onMeetingStartInviteDispatchCompleted` records failure / freezes declaration; **no resend**.
- `resendConferenceInvitesToUnconnected` exists but targets ring-timeout / stale reconnect scenarios, not “engine deferred during CREATE”.
- No hook observed from `MEDIA_RELEASE_COMPLETE` → `sendConferenceInvitesInternal` for CREATE-time deferral.

### RCA conclusion for Q2

**Continuation exists in data structures (`pendingEngineRequests` + `onReady`), but the post-`mediaSessionClose` drain path deliberately does not execute it for CONFERENCE.** No alternate coordinator redrive compensates. This is why `invites sent=0` survives past `MEDIA_RELEASE_COMPLETE`.

---

## Q3 — PASS run (`…-175549`) comparison

### PASS had the same defer log, different release branch

```text
17:56:38.788  MEDIA_RELEASE_DEFERRED_CREATE module=M02 requestedScope=CONFERENCE
17:56:38.791  Conference invites sent=0
17:56:38.791  MEDIA_PENDING_DRAIN module=M02 count=1 items=0:CONFERENCE:requestEngine
17:56:38.792  MEDIA_PROVISION_REQUESTED module=M02 scope=CONFERENCE origin=requestEngine
17:56:38.841  MEDIA_PROVISIONED ... origin=requestEngine
17:56:38.883  GROUP_INVITE → M02
17:56:38.887  pending=1
```

Critical deltas vs FAIL:

| Signal | PASS `…-175549` | FAIL `…-101856` |
|--------|-----------------|-----------------|
| Pre-CREATE `MEDIA_RELEASE_SCHEDULED … mediaSessionClose` | **Absent** | **Present** (+5ms before solo host) |
| Pending drain item | `CONFERENCE:requestEngine` | **None** |
| `MEDIA_RELEASE_COMPLETE` before invite wire | N/A (provision path) | Yes (+123ms), no follow-up |
| `pending=1` after CREATE | Yes (+96ms) | **Never** |
| `GROUP_INVITE` | Yes | **No** |

### Interpretation

PASS did **not** avoid `MEDIA_RELEASE_DEFERRED_CREATE`. It avoided the **`mediaSessionClose` + `releaseInFlight` + CONFERENCE** combination. Invite engine request went through **`mediaSessionProvision` → `scheduleDeferredProvision`**, which **does** retry.

FAIL exposed the path where GROUP mesh still holds a closable engine entry at CREATE, triggering `mediaSessionClose` before/at invite admission — the path **explicitly excluded** from post-close CONFERENCE drain.

---

## Root cause chain (confirmed)

```text
GROUP mesh engine present for M02 at CREATE
→ mediaSessionClose(M02) scheduled (origin=mediaSessionClose)
→ CREATE / apply_group_topology / sendConferenceInvites
→ requestConferenceEngine(M02, CONFERENCE)
→ releaseInFlight → queue CONFERENCE:releaseInFlight (MEDIA_RELEASE_DEFERRED_CREATE)
→ dispatch returns invites sent=0 (async engine not ready; ADR-0017 no coordinator retry)
→ mediaSessionClose completes (~123ms)
→ scheduleGroupReleaseInFlightPendingDrainAfterClose (GROUP only)
→ CONFERENCE pending orphaned
→ O1 FAIL (no pending invite, no peer Join UI)
```

Secondary observation (orthogonal to drain): FAIL run also shows sustained `GROUP_LATE_PEER_ADMISSION_SKIPPED … HELLO_CHANNEL_ABSENT` — peer channel alignment may be a separate admission issue, but **O1 fails even before peer UI** because host never arms `pending=1`.

---

## Candidate classification

### A. RETRY_CONTRACT_GAP — **PRIMARY**

Deferred `CONFERENCE:releaseInFlight` during `mediaSessionClose` has **no matching retry drain**, while the observability line suggests generic deferral. The `onReady` continuation is queued then abandoned relative to conference CREATE invite intent.

Supporting design intent (not bug in GROUP path): ADR-0057 + m03 RCA **intentionally** limit post-close drain to GROUP to preserve CONFERENCE hangup stacking — but conference CREATE invite now hits that blind spot.

### B. CALLER_REDRIVE_GAP — **SECONDARY / CONFIRMED**

`TalkbackCoordinator` documents no invite retry (ADR-0017). Meeting-start completion and resend helpers do not re-drive CREATE-time invites after media release. Even if MSM policy stays narrow, **no caller compensates**.

### C. EVENT_ORDERING_VARIANT — **EXPLAINS PASS, NOT ROOT**

`…-175549` is not “no defer.” It is “defer via `requestEngine`/`mediaSessionProvision` with immediate drain.” Same CREATE recipe can PASS or FAIL depending on whether `mediaSessionClose` precedes invite engine request — a **stable timing gap**, not ops noise.

---

## Adjudication

```text
Primary:    A. RETRY_CONTRACT_GAP
Secondary:  B. CALLER_REDRIVE_GAP
Explainer:  C. EVENT_ORDERING_VARIANT (PASS vs FAIL divergence)
```

**Not** a CREATION replay defect. **Not** fixed by stronger O0b alone (O0b PASS already demonstrated). Next work belongs on **meeting media lifecycle / conference invite admission after deferral** — contract + ADR before implementation.

---

## Open questions (for follow-up ADR, not this RCA)

1. Should `CONFERENCE:releaseInFlight` pending during **meeting-start invite** (not hangup) drain after `mediaSessionClose`, or should coordinator redrive `sendConferenceInvitesInternal` on release complete?
2. How to distinguish **hangup stacking** (preserve pending) vs **CREATE invite** (must complete) without breaking m03 GCT constraints?
3. Should O1 gate treat initial `invites sent=0` as valid when `MEDIA_PENDING_DRAIN` is imminent (PASS also logs `sent=0` before drain)?

---

## References

- `talkback/android-board-talkback/src/main/java/com/talkback/core/media/MediaSessionManager.kt`
- `talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt` (`sendConferenceInvitesInternal`, `onMeetingStartInviteDispatchCompleted`)
- `talkback/docs/analysis/m03-host-pc-generation-ownership-lineage-rca-20260824.md`
- `talkback/docs/analysis/adr0057-group-invite-execution-narrow-authorization.md`
- Field evidence: `…-175549`, `…-101856`
