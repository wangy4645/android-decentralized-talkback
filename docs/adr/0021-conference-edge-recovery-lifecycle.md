# ADR-0021: Conference Edge Recovery Lifecycle (ADR-CONF-003)

## Status

**Draft** (2026-07-09) — depends on #72 Recovery Admission Foundation; implemented by #73 Edge Recovery Lifecycle.

Supersedes implicit “ICE event → action” recovery behavior. Complements ADR-0018 (media ownership), ADR-0019 (signaling/media separation), ADR-0020 (runtime projection).

## Summary

P1 soak (#72) proved **Recovery Admission** works (`RECOVERY_REATTACH` request/accept/reject with lineage), but **Recovery Lifecycle** does not: after ICE loss, recovery only starts when the user reopens the meeting screen. This ADR freezes edge-level recovery ownership, trigger predicates, ordering, termination dominance, projection rules, and soak gates for #73 v1.

**Core distinction:**

```text
RecoveryReattach Protocol   ✅  (#72 — admission / contract)
Recovery Lifecycle          ❌  (#73 — trigger / FSM / cancellation / facts)
```

## Context

RO-M3 P1Full soak (pr72, 2026-07-09) exposed:

| Symptom | Root cause |
|---------|------------|
| M02 WiFi off ~90s, no recovery until user re-enters | No automatic recovery trigger |
| Host/participant UI shows Connecting while audio works | Projection infers state from ICE/count, not recovery facts |
| S11 zombie rejoin after termination | Late recovery callbacks resurrect state |
| S14 RECOVERY_REATTACH after termination | Recovery not cancelled on `CONFERENCE_TERMINATED` |
| S13 no RECOVERING→ACTIVE pairs | No real edge recovery lifecycle state |

Prior ADRs separated Lifecycle, Membership, Media, and Projection — but recovery execution remained fragmented across coordinator, invite handlers, and ICE callbacks.

### Architectural layers (frozen)

```text
Conference Lifecycle        — ESTABLISHED / TERMINATED (Host authority)
Membership Authority        — JOINED / LEFT (committed facts)
ConferenceEdgeRecoveryController — per-edge recovery policy + state
MediaSessionManager         — ICE/PC execution only
ConferenceRuntimeProjector  — UI-visible projection from facts
```

## Decision

### R4 — Recovery Trigger Authority

ICE state alone **MUST NOT** trigger recovery.

`ConferenceEdgeRecoveryController` **MUST** verify all of:

```text
Connectivity:  ICE edge disconnected > debounce threshold (v1: 3s)
Lifecycle:     ConferenceSession == ESTABLISHED
Membership:    local endpoint == JOINED AND remote endpoint == JOINED
```

Membership **MUST NOT** be inferred from ICE or presence timeout during recovery eligibility.

### R5 — Recovery Ownership

`ConferenceEdgeRecoveryController` owns recovery **decision**, **state**, **retry budget**, and **escalation policy**.

`MediaSessionManager` owns recovery **execution** (ICE restart, renegotiate, future PC recreate).

Invite / GroupAccept handlers **MUST** dispatch recovery signals only; they **MUST NOT** own recovery state or call `create()`/`close()` for recovery.

### R6 — Recovery Ordering

Recovery **MUST** restore conference control-plane binding before media-plane repair:

```text
DISCONNECTED (debounced)
  → RECOVERY_REATTACH (lineage validation)
  → ACCEPTED
  → ICE restart (bounded)
  → RECOVERED / FAILED
```

**MUST NOT** skip reattach and jump directly to ICE restart / PC recreate.

### R7 — Reattach Semantics

`RECOVERY_REATTACH` **MUST NOT** create membership. It preserves `JOINED → JOINED` for an existing member.

It is **not** `NORMAL_JOIN`. It revalidates control context for an existing edge.

### R8 — Recovery Rejection Semantics

`RECOVERY_REATTACH` rejection **MUST NOT** implicitly transition to `NORMAL_JOIN`.

| Reject reason | Type | Outcome |
|---------------|------|---------|
| `TERMINATED_CONFERENCE`, `MEMBER_LEFT`, `STALE_SESSION`, `ENDPOINT_DRIFT` | Permanent | `FAILED_REQUIRES_USER_ACTION` or `FAILED_IDENTITY_MISMATCH` |
| `EPOCH_ADVANCED` | Recoverable (once) | `LINEAGE_REFRESHING` → one retry → else `FAILED_STALE_LINEAGE` |
| `AUTHORITY_CHANGED` | Recoverable (once) | refresh lineage → one retry |

New join **requires explicit user intent**.

### R9 — Epoch Refresh Retry Bound

On `EPOCH_ADVANCED`, controller **MAY** refresh authority snapshot and retry `RECOVERY_REATTACH` **once**.

A second lineage mismatch **MUST** terminate automatic recovery (`FAILED_STALE_LINEAGE`).

### R10 — Recovery MUST NOT Mutate Membership

Successful `RECOVERY_REATTACH` **MUST NOT** increment `membershipEpoch`.

Recovery updates connectivity / media generation only:

| Version | Owner | Recovery may change? |
|---------|-------|----------------------|
| Authority epoch | Authority | No (unless explicit failover) |
| Membership epoch | Membership Authority | **No** |
| Media / edge generation | EdgeRecoveryController | **Yes** |
| Recovery attempt id | EdgeRecoveryController | **Yes** |

### R11 — Recovery State Ownership

`ConferenceEdgeRecoveryController` is the **single owner** of recovery state per conference connectivity edge.

On Host `RECOVERY_REATTACH` accepted, controller creates/updates edge state. Membership is **read-only** for verification.

### R12 — Edge Identity Model

Edge recovery state key:

```text
(conferenceSessionId, remoteModuleId)
```

Lineage identity constraint (validated, not key):

```text
remoteEndpointKey
membershipEpoch
authorityEpoch
mediaGeneration
```

Reject `ENDPOINT_DRIFT` when module matches but endpoint identity does not.

### R13 — Recovery Must Not Change Endpoint Identity

Recovery repairs connectivity for a **known** endpoint. It **MUST NOT** perform endpoint handover.

Endpoint migration requires explicit Membership transition (future `ENDPOINT_HANDOVER_REQUEST`), not recovery.

### R14 — Termination Dominates Recovery

On `CONFERENCE_TERMINATED`:

1. Transition all active edge recovery states to `CANCELLED(reason=CONFERENCE_TERMINATED)`
2. Invalidate generation / token for pending async work
3. Drop late callbacks (`DROP_STALE_RECOVERY_EVENT`)
4. Maintain session tombstone (TTL) to prevent resurrection
5. Physical cleanup **after** logical cancellation

**MUST NOT** immediately delete recovery state (late callbacks may recreate zombie state).

### R15 — Recovery Scope Isolation

Edge-level recovery **MUST NOT** change `ConferenceRuntimePhase`.

While Lifecycle is `ESTABLISHED` and only individual edges recover:

```text
ConferenceRuntimeProjection.phase = ACTIVE
participant(Mx).connectivity = RECOVERING
degraded = true
recoveringEdges[] populated
```

Conference-level `RECOVERING` is reserved for **authority/session** recovery (future), not single-edge ICE loss.

### R16 — Recovery Facts Ownership

`ConferenceEdgeRecoveryController` is the **sole producer** of edge recovery facts.

`ConferenceRuntimeProjector` **MUST NOT** infer recovery from ICE, `connectedRemoteMediaCount`, `awaiting`, or `authority=false`.

### R17 — Bounded ICE Restart (#73 v1)

Per edge recovery attempt:

```text
maxIceRestart = 1
```

Failure → `FAILED_MEDIA_RECOVERY`. **MUST NOT** auto-retry, fallback to `NORMAL_JOIN`, or PC recreate in v1.

### R18 — Recovery Attempt Re-entry

`FAILED_MEDIA_RECOVERY` is terminal for the current attempt.

**MUST NOT** retry solely because edge remains disconnected.

New automatic attempt requires:

```text
FAILED → HEALTHY (v1: ICE CONNECTED, current generation) → DISCONNECTED (debounced)
```

Or explicit user action (new `recoveryAttemptId`).

### R19 — Terminal Edge State Retention

Terminal states owned by `ConferenceEdgeRecoveryController`. Cleared by:

1. Conference termination cancellation
2. Membership removal
3. Valid recovery to current generation + valid membership
4. User-initiated new attempt (supersede)
5. Diagnostic TTL (~5 min)

`FAILED_IDENTITY_MISMATCH` → `BLOCK_UNTIL_NEW_MEMBERSHIP` (stronger than media failure TTL).

### R20 — RecoveryDecision Inputs (frozen 2026-07-09)

Automatic recovery **MUST** be approved only via an explicit `RECOVERY_DECISION` that records:

```text
trigger            — ICE_DISCONNECTED | ICE_FAILED | REATTACH_ACCEPTED | ICE_RESTART | SESSION_CANCELLED
terminationReason  — NETWORK_LOSS | USER_LEAVE | CONFERENCE_TERMINATED | NOT_ESTABLISHED | UNKNOWN
policy             — NO_RECOVERY | REATTACH_THEN_ICE_RESTART | ICE_RESTART_ONLY
approved           — true | false
attempt            — recoveryAttemptId (budget owner scope; see R22)
```

Decision inputs **MUST** combine:

```text
Connectivity Fact   (ICE/edge state — input only, not sole trigger)
Membership Fact     (JOINED / LEFT — committed)
Termination Reason  (why the edge ended — USER_LEAVE vs NETWORK_LOSS vs HOST_HANGUP)
```

`ConferenceEdgeRecoveryController` **MUST NOT** treat raw ICE `CLOSED`/`DISCONNECTED` as sufficient approval. R4 eligibility is necessary but not sufficient once R20 is enforced in code (phase 2).

### R21 — Membership Plane vs Connectivity Plane (frozen 2026-07-09)

Two orthogonal planes:

```text
Membership Plane          Connectivity Plane
----------------          ------------------
JOIN                      ICE / DTLS / PC
LEAVE                     RECOVERY
REJOIN                    REATTACH
MEMBERSHIP                ICE_RESTART
```

Rules:

1. Planes **MAY read each other's facts**; they **MUST NOT** issue each other's commands.
2. **Membership Plane** **MUST NOT** directly start Recovery. It **MAY** emit `RejoinIntent` only.
3. **Connectivity Plane** **MUST NOT** mutate Membership (R10). `RECOVERY_REATTACH` **MUST NOT** substitute for `RejoinIntent` after `USER_LEAVE`.

Anti-pattern (B2 root cause):

```text
Rejoin (Membership) → RECOVERY_REATTACH (Connectivity)   ❌
```

### R22 — RecoveryAttempt Owns Restart Budget (frozen 2026-07-09)

Restart budget (`maxIceRestart = 1` per R17) **MUST** bind to **RecoveryAttempt**, not Edge lifetime.

```text
Edge
 ├── Attempt #1  → restart budget
 ├── Attempt #2  → restart budget (fresh)
 └── Attempt #3  → restart budget (fresh)
```

A new Join / Rejoin **MUST** allocate a new Attempt (and reset budget). `USER_LEAVE` **MUST NOT** require budget carry-over logic — Attempt supersession handles it.

Refines R17/R18: Edge is identity; Attempt is policy scope.

## #73 v1 Edge Recovery FSM

```text
CONNECTED
  | ICE disconnect > debounce
  v
DISCONNECTED_DEBOUNCING
  | eligibility predicate (R4)
  v
RECOVERY_PENDING
  v
REATTACH_REQUESTED
  |                    |
  accepted            rejected (R8)
  v                    v
ICE_RESTARTING      FAILED_* / CANCELLED
  |
  +-- connected --> RECOVERED
  +-- fail --------> FAILED_MEDIA_RECOVERY
```

**Out of scope for #73 v1:** `PC_RECREATE`, multi ICE restart, endpoint handover.

Reserve `RecoveryAction.PC_RECREATE` enum value for Phase 2 (ADR-0022 candidate).

## PR relationship

| PR | Role | Merge |
|----|------|-------|
| **#72** | Recovery Admission Protocol Foundation — payload, lineage validation, admission tests | Not standalone; keep as foundation |
| **#73** | Conference Edge Recovery Lifecycle — trigger, FSM, cancellation, facts, projection, soak | Merge candidate with #72 as RO-M3 milestone |

## Soak gates (#73 v1)

### S13 — Edge Recovery Auto-Reattach (hard)

**Given:** three-party conference, `ESTABLISHED`, all `JOINED`.

**When:** M02 WiFi off 20–30s, then on.

**Then (within 60s, no user action):**

```text
RECOVERY_EDGE_STARTED(M02)
RECOVERY_REATTACH_REQUESTED
RECOVERY_REATTACH_ACCEPTED
ICE_RESTARTING
RECOVERY_EDGE_RECOVERED(M02)
conference.phase remains ACTIVE
membershipEpoch unchanged
```

**Fail if** `joinMeeting()` / `openMeetingScreen()` precedes recovery request.

Each `RECOVERY_EDGE_STARTED` **MUST** reach exactly one terminal: `RECOVERED`, `FAILED_*`, or `CANCELLED`.

### S14 — Stale recovery after termination (hard)

Zero `RECOVERY_REATTACH` and zero `rejoin memory saved` after `CONFERENCE_TERMINATED clearRejoinState=true`.

### S16 — No Recovery after USER_LEAVE (hard, frozen 2026-07-09)

Within **120s** after explicit leave markers:

```text
Conference peer left: <M>
RECOVERY_EDGE_CANCELLED … reason=member_left
Left conference locally
```

**MUST NOT** see:

```text
RECOVERY_REATTACH_ACCEPTED … remote=<M>   (same member re-entering via Recovery plane)
RECOVERY_DECISION … approved=true terminationReason=USER_LEAVE
```

User re-enter **MUST** use Membership `RejoinIntent` / silent rejoin (R21), not Connectivity `RECOVERY_REATTACH`.

### S11 — Zombie rejoin (hard, unchanged intent)

## Considered Options

| Option | Rejected because |
|--------|------------------|
| Conference-level recovery phase | One peer failure poisons whole meeting UI |
| ICE-only trigger | Jitter + termination race + leave semantics ignored |
| Invite handler owns recovery | S8/S11/S14 fragmentation |
| Auto fallback to NORMAL_JOIN on reject | Zombie rejoin; bypasses membership authority |
| PC recreate in #73 v1 | Conflates protocol correctness with media escalation |

## Consequences

- Rename/clarify `ConferenceRecoveryController` → `ConferenceEdgeRecoveryController` (edge scope).
- New log markers: `RECOVERY_EDGE_STARTED`, `RECOVERY_EDGE_RECOVERED`, `RECOVERY_EVENT_DROPPED`, `RECOVERY_DECISION`.
- S13 migrates from conference `RECOVERING→ACTIVE` to edge lifecycle pairs.
- #73 v1 completion definition: **JOINED participant, brief connectivity loss, auto recovery via REATTACH + 1 ICE restart, no user re-enter.**

## References

- [ADR-0010](./0010-conference-membership-vs-media-projection.md) — UI projection vs facts
- [ADR-0014](./0014-conference-host-owned-lifetime.md) — Host-owned lifetime
- [ADR-0018](./0018-conference-media-lifecycle-ownership.md) — Media session ownership
- [ADR-0019](./0019-conference-signaling-media-separation.md) — Signaling/media separation
- [ADR-0020](./0020-conference-runtime-projection-contract.md) — Runtime projection contract
- PR #72 Recovery Admission Foundation
- Issue/PR #73 checklist: `docs/issue-73-edge-recovery-lifecycle-checklist.md`
- Soak: `scripts/soak-tcc-hard-gates.ps1`, `scripts/soak-conference-p0-p1.ps1`
