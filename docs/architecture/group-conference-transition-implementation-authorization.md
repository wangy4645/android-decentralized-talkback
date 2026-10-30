# GROUP → CONFERENCE Transition — Implementation Authorization

**ID:** group-conference-transition-implementation-authorization  
**Date:** 2026-08-24  
**Version:** v0.1  
**Type:** Implementation Boundary / Authorization Record  
**Status:** **AUTHORIZED** (v0.1)  
**Implementation authorization:** **START-path engine-ready continuation only**

**Does not amend ADR-0056, Failure Domain Contract, or Factory partition.** Separate gate from [Transition Contract Review](./group-conference-transition-contract-review.md) and [Implementation Mapping](./group-conference-transition-implementation-mapping.md).

**Prerequisite (all frozen / accepted):**

| Gate | Status | Artifact |
|------|--------|----------|
| Transition contract | **ACCEPTED v0.1** | [group-conference-transition-contract-review.md](./group-conference-transition-contract-review.md) |
| Implementation mapping | **FROZEN v0.1** | [group-conference-transition-implementation-mapping.md](./group-conference-transition-implementation-mapping.md) |
| Execution boundary | **FROZEN** | [coordinator-media-execution-boundary.md](./coordinator-media-execution-boundary.md) A0.1–A0.3 |
| Engine ownership gate | **FROZEN** | [media-engine-ownership-hangup-gate.md](./media-engine-ownership-hangup-gate.md) |
| v6.1 field evidence | **on record** | `talkback/logs/meeting-end-liveness-v6.1-20260824-155623/` |

```text
Transition Contract Review (ACCEPTED)
    ↓
Implementation Mapping (FROZEN)
    ↓
Implementation Authorization (this document)   ← gate
    ↓
Minimal PR + field loop
```

---

## Authorization statement

> **Authorize one minimal PR** implementing **INV-T2 · INV-T3 · INV-T5** on the **GROUP → CONFERENCE START** conference-invite path via **engine-ready continuation** (`PENDING → READY | FAILED`), without changing Factory ownership, MEETING_END hangup ordering, or Conference topology.

**No authorization for:** native isolation (P0.1g) · Factory redesign · START `HangupMediaBarrier` expansion · A0.7 watchdog strengthening · topology / membership / Failure Domain amendments.

---

## Goal (narrow)

Remove **sync engine acquisition + InviteDispatcher retry** as the substitute for **`PENDING`** on START-path conference media realization.

**Target behavior:**

```text
SWITCH_TO_CONFERENCE
    ↓
SESSION_CREATED / MEETING_START     (unchanged — already decoupled)
    ↓
per-module: requestEngine → PENDING | READY | FAILED
    ↓
on READY: createOffer → invite      (per module, no await-all barrier)
```

**Not the goal:**

```text
Prove per-module native isolation
Eliminate all cross-module runtime impact
Replace EngineOwnershipGate
Fix MEETING_END / hangup-gate §5.1 ordering
Remove leaveChannelSession GROUP hangup from product path
Guarantee meeting media-usable at MEETING_START completion
Close all TRANSPORT_NOT_READY cases
```

---

## Scope IN

### AUTH-GCT-1 — Engine acquisition continuation (T2 · T5)

**May implement or complete:**

- `MediaSessionManager.requestEngine(..., onReady, onFailed?)` pending queue for `releaseInFlight`, `mediaSessionReuse`, `ownershipBarrier`.
- Fact emission: `MEDIA_PROVISION_REQUESTED`, `MEDIA_PROVISIONED` (or existing lifecycle equivalent), `mediaProvisionFailed` → `onFailed`.
- **`MediaSessionManager` remains sole lifecycle state authority.** `SessionMediaRegistry` forwards only; **no** parallel ownership state machine.

**Forbidden:**

- Sync throw / sync `create()` on coordinator as the only not-ready exit.
- New adjudication layer between `MediaSessionManager` and `EngineOwnershipGate`.

### AUTH-GCT-2 — START conference invite engine-ready path (T3 · T5)

**May wire or complete:**

```text
dispatchConferenceInvitesWhenEngineReady
    ↓
beginConferenceInviteWhenEngineReady          (per remote module)
    ↓
requestConferenceEngine(onReady, onFailed?)
    ↓
sendConferenceInviteWithEngine
```

**Required properties:**

- Each remote module: **independent** continuation.
- Conference batch = **projection** (sent / pending / failed counts); **not** `await all peers → invite`.
- `onReady` runs on a **later coordinator turn**; no sync wait (INV-T5 / R5 guard).

**May touch:** `TalkbackCoordinator` START invite dispatch (`dispatchMeetingStartInvitesWithPolicy`, `meshCallInternal` post-session invite batch, late-spoke realization if same sync-failure class).

### AUTH-GCT-3 — InviteDispatcher demotion (not deletion)

**May:**

- Treat engine-not-ready as **`ProvisionPending` / normal PENDING** — no `InviteDispatcher` retry loop for provisioning.
- Stop using **`TRANSPORT_NOT_READY` + 3s retry** to compensate for pending engine acquisition on START path.

**May retain:**

- `InviteDispatcher` for ** genuine transport** retry where engine is already ready.

**Forbidden:**

- Deleting `InviteDispatcher` in this PR.
- Using retry loop as global serialization for all peers.

### AUTH-GCT-4 — Control admission unchanged (T1 · T4)

**Must preserve:**

- `SESSION_CREATED` + `MEETING_START` without waiting for GROUP `MEDIA_RELEASED`.
- Coordinator / governance liveness under pending realization.

**Must not add:**

- START-path `HangupMediaBarrier` or new teardown gates before admission.

### AUTH-GCT-5 — Tests (minimal)

**May add:**

- Unit tests for `MediaSessionManager` pending → provisioned / failed semantics (existing test package patterns).
- Tests proving sync path does not throw when `releaseInFlight` (continuation queued).

**Not required in this PR:**

- Full field harness · multi-device soak · topology fixtures.

---

## Scope OUT (explicit block)

```text
Factory / D-β / D-γ / dual engine per moduleId
EngineOwnershipGate semantic changes
HangupMediaBarrier expansion to START
A0.7 watchdog / release-barrier strengthening
MEETING_END hangup reordering (hangup-gate §5.1)
ADR-0056 · Failure Domain · CFC-1 contract changes
Negotiation / ICE / SRD / rollback redesign
Removing leaveChannelSession(SWITCH_TO_CONFERENCE) GROUP hangup
WiFi recovery / RNA / completion predicate
UI / banner / timeout budget changes
```

**Optional same PR only if zero new semantics:** `offerGroupMeshJoin` async `requestEngine` if it reuses AUTH-GCT-1 surface with no new gate — otherwise **defer**.

---

## Allowed file touchpoints (inventory)

| Area | Files (representative) |
|------|-------------------------|
| Media continuation | `MediaSessionManager.kt`, `MediaObservabilityLog.kt`, `SessionMediaRegistry.kt` |
| START invite | `TalkbackCoordinator.kt` (engine-ready invite chain only) |
| Invite policy | `InviteDispatcher.kt`, `InviteDispatchPolicy.kt` / send result types |
| Tests | `MediaSessionManager*Test.kt`, `MediaSessionManagerProvisionContinuationTest.kt` |

Any file outside this list requires mapping amendment — not drive-by refactors.

---

## Non-implications (MUST NOT claim in PR or field sign-off)

```text
PENDING  →  independent native isolation (P0.1g)              ✗
PENDING  →  concurrent mutation of same factory entry         ✗
PENDING  →  Conference-wide availability failure              ✗
Engine-ready PR  →  Case C = dual runtime owner             ✗
```

Shared-domain cross-module impact **may** still occur; must be **attributed**, not hidden by sync global retry.

---

## Field acceptance — GROUP → CONFERENCE loop

**Setup:** M01 host · M02/M03 peers · CH-01 · GROUP mesh `transmitReady=true` · APK = authorized PR.

**Scenario:** GROUP active → user START meeting (host initiates).

### PASS (all required)

| # | Check |
|---|--------|
| F1 | `JOIN_MEETING_TRACE` → `SESSION_CREATED` / `MEETING_START` without blocking on GROUP `MEDIA_RELEASED` |
| F2 | No log line `Conference invite SDP failed: Synchronous mesh engine create pending async release` on START path |
| F3 | No START-path `INVITE_DISPATCH_COMPLETED … FAILED_RETRY_EXHAUSTED … TRANSPORT_NOT_READY` **before** engine provision facts when failure class is pending acquisition |
| F4 | First `Conference invite sent` or `GROUP_INVITE` datagram within **≤ 2 s** of intent on happy path (D1+D2 ~200 ms; not 5 s retry class) |
| F5 | Coordinator process continues logging through invite batch (no silent coordinator death on START) |
| F6 | `MEDIA_PROVISION_REQUESTED` → lifecycle BOOTSTRAPPING / invite for at least one peer without global await-all gap |

### FAIL (any)

```text
Sync throw pending async release on invite path
InviteDispatcher 3s retry compensating engine pending (F3)
MEETING_START blocked on GROUP MEDIA_RELEASED (regression to Case A control gate)
New START HangupMediaBarrier / watchdog behavior
Coordinator stall / no logs after START click
Claimed P0.1g isolation as pass criterion
```

### OUT OF SCOPE (record only)

```text
SRD / ICE / DOMAIN_BLOCKED / MEETING_END liveness
Second JOIN R5 hangup loop (separate card unless same PR touch)
Accept-side ring timeout (downstream of invite delivery)
```

Log directory naming: `talkback/logs/group-conference-transition-field-YYYYMMDD-HHMMSS/`

---

## PR constraints

- **One minimal PR** — engine-ready START invite + AUTH-GCT-1..3 only.
- No dependency on A0.8 / A0.9.
- Commit message / PR title must reference **GCT-IA v0.1** or **Transition Implementation Authorization**.
- Post-merge: run field loop above before any further lifecycle expansion.

---

## Revision history

| Version | Date | Change |
|---------|------|--------|
| v0.1 AUTHORIZED | 2026-08-24 | START-path engine-ready continuation authorized per mapping FROZEN v0.1 |
