# Phase A Shadow Runtime — RCA Handoff (Read-Only)

**Field evidence:** `talkback/logs/phase-a-shadow-runtime-20260909-105513/`  
**Verdict:** `FAIL_SHADOW_RUNTIME` (valid product fail · not INVALID)  
**Status:** **RCA-A CLOSED** · **RCA-B CLOSED (read-only)** — see `meeting-product-integration-phase-a-shadow-runtime-rca-b-handoff.md`

```text
Meeting J0               CLOSED / FIELD-PROVEN
Profile01 Meeting chain  CLOSED / FIELD-PROVEN

Phase A Shadow Runtime   RCA-A CLOSED · RCA-B CLOSED (read-only)
PA-SR1/SR2               FIELD VERIFIED (peer)
PA-SR3                   BLOCKED (TYPE A: binding not established)
PA-SR4…SR6               DOWNSTREAM / NOT CLOSED

Failure locus            shadow fact/binding handoff
J0 / P1-C / P1-E         UNCHANGED_CLOSED
ADR-0056                 KEEP
Meeting media cutover    NOT AUTHORIZED
```

---

## Executive summary

Shadow runtime fails **after** Profile01 `WIRE_INGRESS factType=1 outcome=APPLIED`. This is **not** missing wire apply and **not** a separate registry store. The dominant failure is **RCA-A**: `factPort.sessionFact()` rejects the published session control fact because **Meeting session index `rosterEpoch` ≠ registry `membershipVersion`** at synchronous post-apply replay. **RCA-B** (`DEFERRED_NO_BINDING`) is largely **downstream/cascade** of session wiring never becoming live, plus wire replay gating on `mediaConnectedModules`.

This is a **narrow shadow handoff contract gap**, not Profile01 correctness regression and not J0 continuation regression.

---

## RCA-A — `CREATION APPLIED → factPort.sessionFact` (PRIMARY)

### Question chain

```text
CREATION APPLIED
→ registry / authoritative fact state
→ MeetingProfile01AwareConferenceSessionMediaFactPort
→ sessionFact(sessionId, channelId, rosterEpoch)
→ why DEFERRED_NO_FACT
```

### 1) Where does APPLIED write?

| Step | Component | Store |
|------|-----------|-------|
| Wire ingress | `MeetingProfile01FactWireIngress.ingestCreation` | — |
| Validate + map | `Profile01ConferenceMediaFactIngress.ingestSession` | — |
| Publish | `ConferenceSessionMediaGbcPublisherBridge.publishSessionDeclaration` | **`ConferenceSessionMediaControlFactRegistry`** (`sessions[conferenceId].current`) |
| OBS | `WIRE_INGRESS … outcome=APPLIED` | `MeetingProfile01FactWireIngress.recordPublishAndReplay` when `publishOutcome ∈ {ACCEPTED, IDEMPOTENT}` |

**Conclusion:** APPLIED means registry publish succeeded (or idempotent hit). Not a shadow-only cache.

### 2) Does factPort read the same store?

`TalkbackRuntimeFactory` wires **one** registry instance:

```text
conferenceSessionMediaControlRegistry
  → ConferenceSessionMediaGbcPublisherBridge(registry)
  → Profile01ConferenceMediaFactIngress(publisherBridge)
  → MeetingProfile01FactWireIngress(ingress)

ConferenceSessionMediaCoordinatorDelegate.factPort =
  MeetingProfile01AwareConferenceSessionMediaFactPort(registry, sessionIndex)
```

**Conclusion:** Ingress publish and shadow `factPort` read are **same authority-backed registry**. Failure is **read projection / keying**, not split stores.

### 3) `sessionId ↔ conferenceId128` mapping

- Registry keys: **wire `conferenceId` hex** (`9c69f86c…` in field run).
- Coordinator hooks: **Meeting `sessionId` UUID** (`8c0307dc-…`).
- Bridge: `MeetingProfile01ConferenceSessionIndex.bindConferenceId(sessionId, conferenceId)` in `ingestCreation` **before** replay.

Field manifest confirms mapping: `hostSessionId=8c0307dc-…` · `conferenceId=9c69f86c…`.

**Conclusion:** Identity mapping present at CREATION apply. Not a missing `conferenceId` mapping at apply time.

### 4) Ordering: projection lag vs synchronous replay mismatch

**Field timing (M02, authoritative):**

```text
10:59:04.900  WIRE_INGRESS factType=1 outcome=APPLIED
10:59:04.902  SHADOW hook=SESSION_STARTED outcome=DEFERRED_NO_FACT   (+2ms)
```

Replay is **synchronous** in `recordPublishAndReplay` → `replayAfterPublish(CREATION)` → `replaySessionStarted` → `factPort.sessionFact`.

**Conclusion:** This is **not** “projection not yet visible.” Registry write completes before replay in the same call stack. Failure is **read predicate**, not missing second-chance replay.

### 5) Root read predicate (code)

`MeetingProfile01AwareConferenceSessionMediaFactPort.sessionFact`:

```text
resolveConferenceId(sessionId)
registry.session(conferenceId)
channelId match
adapter.rosterEpochMatches(control, rosterEpoch)
  ↔ control.membershipVersion == rosterEpoch
```

`replaySessionStarted` passes **`sessionBinding.rosterEpoch`** from `MeetingProfile01ConferenceSessionIndex` (set at `registerMeetingProfile01Session` from `TalkbackSession.rosterEpoch`).

**CREATION wire membershipVersion (origin):**

```kotlin
// MeetingProfile01CreationOriginBridge
val membershipVersion = 0L
```

**Meeting session index rosterEpoch at register:**

```kotlin
// TalkbackSession
var rosterEpoch = GroupMembershipSupport.INITIAL_ROSTER_EPOCH  // = 1L
```

**Therefore at CREATION apply replay:**

```text
registry.membershipVersion = 0
replay rosterEpoch           = 1
rosterEpochMatches           = false
sessionFact                  = null
→ DEFERRED_NO_FACT
```

### 6) Contrast: membership path updates epoch (but CREATION path does not)

`ingestMembership` on converge:

```text
sessionIndex.updateRosterEpoch(sessionId, generation.membershipVersion)
replayAfterMembership(..., sessionBinding.copy(rosterEpoch = generation.membershipVersion))
```

`ingestCreation` / `replayAfterPublish(CREATION)`:

```text
replaySessionStarted(sessionId, sessionBinding)   // stale rosterEpoch unchanged
```

Unit test `MeetingProfile01FactWireIngressTest.creationThenMembership_replaysDeferredSessionStarted` documents **DEFERRED_NO_FACT until membership replay** when index `rosterEpoch=0` and fixtures align — same structural pattern.

### 7) J0 latent signal (not regression)

J0 PASS log (`20260909-103707`) shows the **same** pattern on CREATION apply:

```text
10:40:10.761  WIRE_INGRESS factType=1 outcome=APPLIED
10:40:10.763  SHADOW hook=SESSION_STARTED outcome=DEFERRED_NO_FACT
```

J0 gate did not adjudicate shadow `SESSION_STARTED` APPLIED. Phase A Shadow Runtime **surfaces a pre-existing shadow handoff gap**.

### RCA-A verdict

| Hypothesis | Result |
|------------|--------|
| APPLIED not in registry | **Rejected** — publish gate for APPLIED |
| Different store / cache | **Rejected** — single registry in factory |
| Missing conferenceId mapping | **Rejected** — bound before replay |
| Async projection lag | **Rejected** — +2ms synchronous replay |
| **rosterEpoch vs membershipVersion mismatch at replay** | **Confirmed (primary)** |

**Narrow fix class (future, not authorized here):** post-CREATION replay must use **published control fact membershipVersion** (or update session index epoch on CREATION apply), mirroring membership replay — **not** retry/sleep/generic fallback.

---

## RCA-B — `MEMBER_MEDIA_READY → member binding` (SECONDARY / CASCADE)

### Question chain

```text
member authoritative/admission fact
→ member binding publication/store
→ factPort member lookup
→ MEMBER_MEDIA_READY
→ why DEFERRED_NO_BINDING
```

### Field timing (M02)

```text
10:59:04.900  CREATION APPLIED
10:59:04.902  SESSION_STARTED DEFERRED_NO_FACT
10:59:04.903  factType=11 (SOURCE_DECLARATION) APPLIED
10:59:05.112  MEMBER_MEDIA_READY module=M01 → DEFERRED_NO_BINDING
```

### Observations

1. **SESSION wiring never live** (RCA-A) — jitter/ingress/playout path cannot start; SR4/SR5/SR6 cascade.
2. **Wire replay for SOURCE_DECLARATION** only calls `onConferenceMemberMediaReady` when `moduleId ∈ sessionBinding.mediaConnectedModules`. M02 marks M01 media-connected **after** ICE connected (~`10:59:05.115`), i.e. **after** the `05.112` hook.
3. Product hook at `05.112` (`TalkbackCoordinator` ICE/media path) calls `onConferenceMemberMediaReady` regardless — still `DEFERRED_NO_BINDING` → `factPort.memberBinding` returned null at that instant.
4. `memberBinding` does **not** use `rosterEpoch`; it requires `registry.session` + `registry.member` + `memberMatchesSession`. SOURCE APPLIED at `04.903` implies member publish likely succeeded, but **no field OBS** exposes registry member slot at hook time — RCA-B cannot be closed to a single sub-cause beyond **session handoff failure + hook/replay ordering** without additional read-only telemetry.

### RCA-B verdict

**Treat as cascade of RCA-A for gate purposes.** Do not open six independent SR bugs. If RCA-A narrow fix restores `SESSION_STARTED` APPLIED, re-run field to see if `MEMBER_MEDIA_READY` + binding clears without separate surgery.

---

## Frozen routing

```text
Next = PR-PA-SR-A1 implementation (authorize separately)
Design = meeting-product-integration-phase-a-shadow-runtime-rca-a-fix-design.md
RCA-B = HOLD until A1 field re-run
NOT = J0 / P1-C / P1-E reopen
NOT = retry / sleep / generic fallback without ADR
NOT = cutover / ADR-0056 change
```

---

## References

- Field result: `meeting-product-integration-phase-a-shadow-runtime-field-result-20260909-105513.md`
- Code: `MeetingProfile01FactWireIngress.kt` · `MeetingProfile01AwareConferenceSessionMediaFactPort.kt` · `MeetingProfile01CreationOriginBridge.kt` · `TalkbackRuntimeFactory.kt` · `TalkbackSession.kt`
