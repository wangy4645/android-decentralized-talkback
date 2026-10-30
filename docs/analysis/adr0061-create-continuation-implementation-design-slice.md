# ADR-0061 Implementation Design Slice

**Parent:** [ADR-0061](../adr/0061-conference-create-continuation-across-media-release.md)  
**Status:** DESIGN **SIGNED OFF** — **v1 IMPLEMENTATION AUTHORIZED**  
**Scope:** Conference CREATE continuation across `mediaSessionClose` only

```text
ADR-0061                     DECISION ACCEPTED — OPTION C
This slice                   API + drain + fence + observability + fixtures
Code                         v1 IMPLEMENTED — unit T1–T5 PASS
```

---

## 1. API shape (v1)

### 1.1 New obligation intent enum

Add to `MediaSessionManager` (or adjacent media package):

```kotlin
enum class EngineRequestIntent {
    /** Default / hangup stacking — ADR-0057 + m03 semantics unchanged. */
    DEFAULT,

    /** Conference CREATE invite engine provision — eligible for post-close continuation drain. */
    CREATE_CONTINUATION,
}
```

### 1.2 `requestEngine` signature extension

```kotlin
fun requestEngine(
    moduleId: String,
    scope: MediaBearerScope,
    sessionId: String?,
    onReady: (WebRtcAudioEngine) -> Unit,
    onFailed: (() -> Unit)? = null,
    intent: EngineRequestIntent = EngineRequestIntent.DEFAULT,  // new; default preserves all non-CREATE callers
)
```

Mirror through `SessionMediaRegistry.requestConferenceEngine`:

```kotlin
fun requestConferenceEngine(
    remoteModuleId: String,
    sessionId: String?,
    onReady: (WebRtcAudioEngine) -> Unit,
    onFailed: (() -> Unit)? = null,
    intent: EngineRequestIntent = EngineRequestIntent.DEFAULT,
)
```

**v1 explicitly does not** introduce obligation tokens. Fence uses `sessionId + moduleId + intent`.

### 1.3 Caller obligation (Coordinator only for v1)

Single v1 enqueue site marks `CREATE_CONTINUATION`:

| Call site | Scope | Intent |
|-----------|-------|--------|
| `beginConferenceInviteWhenEngineReady` → `requestConferenceEngine` (meeting-start invite path) | CONFERENCE | `CREATE_CONTINUATION` when `!signalingRetry` and session is host CREATE / meeting-start dispatch |
| All other `requestConferenceEngine` / `requestGroupEngine` | * | `DEFAULT` |

Coordinator MUST NOT infer intent inside MSM. Coordinator supplies intent at enqueue based on admission context (host CREATE invite dispatch).

**Out of v1 scope:** `requestGroupEngine` (GIE/IGIE), rejoin, signaling retry, recovery re-offer paths — all remain `DEFAULT`.

---

## 2. Pending entry structure

Extend `PendingEngineRequest`:

```kotlin
private data class PendingEngineRequest(
    val scope: MediaBearerScope,
    val origin: String,           // deferral cause: releaseInFlight | requestEngine | ...
    val sessionId: String?,         // fence key (new explicit field if not already carried)
    val intent: EngineRequestIntent,
    val onReady: (WebRtcAudioEngine) -> Unit,
    val onFailed: (() -> Unit)?,
)
```

`queuePendingEngineRequest` records `sessionId` and `intent` at enqueue time. Existing log `MEDIA_RELEASE_DEFERRED_CREATE` remains; add intent to observability (see §5).

---

## 3. Drain conditions

### 3.1 Unchanged paths

| Trigger | Function | Drains |
|---------|----------|--------|
| Non-`mediaSessionClose` release complete | `scheduleDeferredProvision` | **All** pending for module |
| `mediaSessionClose` complete | `scheduleGroupReleaseInFlightPendingDrainAfterClose` | `GROUP + releaseInFlight + DEFAULT` only (**unchanged**) |

### 3.2 New path (v1)

After `mediaSessionClose` complete, **additionally** invoke `scheduleCreateContinuationDrainAfterClose(moduleId)`:

```kotlin
private fun scheduleCreateContinuationDrainAfterClose(moduleId: String) {
    if (!engineOwnershipGate.mayProvisionAfterRelease(moduleId)) return
    val pending = pendingEngineRequests[moduleId] ?: return
    val drainable = pending.filter {
        it.scope == MediaBearerScope.CONFERENCE &&
            it.intent == EngineRequestIntent.CREATE_CONTINUATION &&
            it.origin == "releaseInFlight"
    }
    if (drainable.isEmpty()) return
    // fence (§4) runs per entry before removal
    ...
    runDeferredProvision(moduleId, fenced)
}
```

**Not drained on close complete:**

- `CONFERENCE + DEFAULT` (hangup stacking)
- `CONFERENCE + CREATE_CONTINUATION` where `origin != releaseInFlight` (handled by `scheduleDeferredProvision` on provision-path release)
- All `GROUP` pending (existing ADR-0057 path)

Call order in `completeAsyncFactoryRelease` when `origin == mediaSessionClose`:

```text
1. scheduleGroupReleaseInFlightPendingDrainAfterClose(moduleId)   // unchanged
2. scheduleCreateContinuationDrainAfterClose(moduleId)            // new
3. failOrRetainRemainingWithNoDrainSource(moduleId)             // I5 — see §4.3
```

---

## 4. Fence points

### 4.1 At drain time (`scheduleCreateContinuationDrainAfterClose`)

For each candidate `CREATE_CONTINUATION` entry, invoke coordinator-supplied fence **or** inline checks:

```text
DISCARD (onFailed) if any:
  sessionId null or session no longer in sessions map
  session.type != CONFERENCE
  session terminated / not accepted for host CREATE path
  moduleId no longer in invite target set for that session/channel
  conference generation / session id mismatch vs enqueue snapshot (if available)
```

v1 minimum fence: **`sessions[sessionId]` exists, type CONFERENCE, session still host-accepted, moduleId still in roster invite set.**

### 4.2 At provision callback (Coordinator `onReady`)

Existing guards in `beginConferenceInviteWhenEngineReady` remain (session live, peer resolved). No second invite dispatch if admission handoff already active.

### 4.3 I5 — No silent abandonment (`failOrRetainRemainingWithNoDrainSource`)

After both close-complete drains, any remaining pending for module where:

```text
no live drain source exists for this (scope, intent, origin) tuple
```

MUST invoke `onFailed` and emit I6 **discarded** fact. v1 targets orphaned `CONFERENCE + DEFAULT + releaseInFlight` after close (documented gap today) — **optional narrow fail-only** if audit confirms no legitimate waiter; otherwise retain stacking semantics and only fail entries explicitly marked with no future drain contract.

**Conservative v1:** only add explicit fail for `CREATE_CONTINUATION` that failed fence at drain time. Do not change DEFAULT stacking failure mode in v1.

---

## 5. I6 observability

### 5.1 New log lines (behavior-neutral taxonomy)

| Event | When |
|-------|------|
| `MEDIA_DEFERRED_CREATE intent=CREATE_CONTINUATION session=… module=… origin=releaseInFlight` | enqueue (extend existing deferred line) |
| `MEDIA_CREATE_CONTINUATION_DRAIN module=… count=N items=…` | post-close drain selected entries |
| `MEDIA_CREATE_CONTINUATION_RESUMED module=… session=…` | `onReady` invoked from continuation drain |
| `MEDIA_CREATE_CONTINUATION_DISCARDED module=… session=… reason=…` | fenced out at drain; `onFailed` invoked |

### 5.2 Field gate revision (ops, not code blocker)

O1 adjudication MUST use **terminal** facts:

```text
PASS signal:  MEDIA_CREATE_CONTINUATION_RESUMED + GROUP_INVITE + pending=1
FAIL signal:  MEDIA_CREATE_CONTINUATION_DISCARDED
              OR deferred with no RESUMED/DISCARDED within budget
```

`Conference invites sent=0` alone → **intermediate only** (documented in ADR-0061 I6).

---

## 6. IGIE static audit (v1 scope boundary)

| Path | Engine request | Scope | Close-complete drain | Silent abandonment risk |
|------|----------------|-------|----------------------|-------------------------|
| GIE `dispatchGroupInviteAfterEngineProvision` | `requestGroupEngine` | GROUP | `scheduleGroupReleaseInFlightPendingDrainAfterClose` | **No** — ADR-0057 EG3 covers |
| IGIE `beginGroupAcceptWhenEngineReady` | `requestGroupEngine` | GROUP | same | **No** |
| Conference CREATE `beginConferenceInviteWhenEngineReady` | `requestConferenceEngine` | CONFERENCE | **Missing today** | **Yes** — ADR-0061 target |
| Rejoin / recovery `requestConferenceEngine` | CONFERENCE | DEFAULT / provision drain | Not in v1 |

**Conclusion:** v1 implementation scope stays on conference CREATE invite path only. No IGIE code changes required for v1. If future CONFERENCE inbound paths enqueue without live drain source, open separate adjudication.

---

## 7. Fixture mapping (T1–T5)

| Test ID | Fixture / file | Asserts |
|---------|----------------|---------|
| **T1** | New: `MediaSessionManagerCreateContinuationTest.closeInFlight_drainsCreateContinuation_invokesOnReady` | `mediaSessionClose` in flight → `requestEngine(CONFERENCE, CREATE_CONTINUATION)` deferred → complete → `onReady` once → simulates invite continuation |
| **T1 field** | Replay `…-101856` recipe after impl | `MEDIA_CREATE_CONTINUATION_RESUMED` → `GROUP_INVITE` → `pending=1` |
| **T2** | Existing: `Adr0057GroupInviteExecutionFixturesTest.gieEg3_releaseComplete_grantsExecutionOpportunityExactlyOnce` | GROUP drain unchanged |
| **T2** | New: `MediaSessionManagerCreateContinuationTest.provisionPath_singleDrain_noDuplicate` | `mediaSessionProvision` path still single drain (175549 fingerprint) |
| **T3** | New: `MediaSessionManagerCreateContinuationTest.closeComplete_doesNotDrainHangupStackingConference` | `CONFERENCE + DEFAULT` pending after close → not drained; generation count stable |
| **T4** | New: `MediaSessionManagerCreateContinuationTest.createContinuation_discardedWhenSessionGone` | remove session before close complete → `onFailed`, `MEDIA_CREATE_CONTINUATION_DISCARDED` |
| **T5** | New: `MediaSessionManagerCreateContinuationTest.createContinuation_exactlyOnce` | double close-complete / duplicate drain → single `onReady` |
| **Regression** | `gieEg1`–`gieEg6`, `Adr0057GroupInviteInboundExecutionFixturesTest`, `MediaSessionManagerAsyncReleaseBoundaryTest`, `ConferenceRealizationLineageTest` | all pass unchanged |

---

## 8. Implementation file touch list (preview)

| File | Change |
|------|--------|
| `MediaSessionManager.kt` | intent enum, pending shape, `scheduleCreateContinuationDrainAfterClose`, observability |
| `SessionMediaRegistry.kt` | pass-through `intent` on `requestConferenceEngine` |
| `TalkbackCoordinator.kt` | `beginConferenceInviteWhenEngineReady` passes `CREATE_CONTINUATION` for meeting-start CREATE |
| `MediaObservabilityLog.kt` | I6 log helpers |
| Tests | T1–T5 fixtures above |

**Not touched in v1:** CREATION replay bridge, MEMBERSHIP, Phase A, IGIE/GIE coordinator logic, `scheduleGroupReleaseInFlightPendingDrainAfterClose` filter.

---

## 9. Sign-off checklist (before code authorization)

- [x] API shape reviewed — enum only, no token
- [x] Drain matrix signed — CREATE_CONTINUATION vs DEFAULT vs GROUP
- [x] Fence rules signed — minimum `sessionId + moduleId + roster`
- [x] I6 log names frozen
- [x] T1–T5 mapped to concrete test classes
- [x] IGIE audit accepted — out of v1 scope
- [x] Field O1 gate doc updated to I6 terminal facts (see ADR-0061 I6)
