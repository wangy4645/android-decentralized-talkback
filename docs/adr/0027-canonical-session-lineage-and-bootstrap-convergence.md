# ADR-0027: Canonical Session Lineage and Bootstrap Convergence (ADR-GROUP-001)

## Status

**Draft — Frozen boundary** (2026-07-15)

P2-0 observation complete. **P2-0.5 (terminal semantics freeze)** adds documentation + observability markers only — no gating, predicate, or bootstrap behavior changes. P2-1+ require explicit approval per phase.

Complements:

- ADR-0016 — Transition Completion Contract (`MEETING_END` predicate)
- ADR-0022 — Recovery Completion Ownership (conference edge recovery; P0-a transition observation)
- ADR-0023 — Conference Membership Mutation Authority Boundary (CTA / single writer)
- ADR-0025 — Conference Presence Plane Projection (presentation; not ground truth for canonical session)
- ADR-0026 — Conference Media Transmit Barrier Scope (edge-scoped media; orthogonal to GROUP lineage)

Cross-reference: ADR-0009 (Group endpoint identity) applies **after** canonical session membership converges; it does not substitute for lineage stability.

## Summary

P0-a soak (`20260715-111759`, WiFi flap → meeting end → M02/M03 PTT failure) proved the failure is **not** Floor arbitration or playback routing. Root cause class:

> After conference termination, **canonical GROUP session identity does not converge** across devices. Bootstrap churn produces competing or partial sessions; local `terminalReady` fires while peers are on different trace identities or ICE paths.

This ADR freezes the **contract** for post-meeting GROUP convergence:

1. **Lineage monotonicity** — one convergence window, one canonical lineage; recreate allowed only with explicit invalidation.
2. **Bootstrap admission protocol** — only resolved bootstrap primary **publishes canonical lineage**; participants join via admission; temporary recovery sessions allowed but must not publish canonical authority.
3. **Membership continuity** — baseline at `MEETING_END_BEGIN` + grace lease; no silent member drop.
4. **Separated terminal authority** — `LOCAL_TERMINAL` vs `CANONICAL_TERMINAL`; canonical-authoritative terminal requires a matching `CANONICAL_DECISION`.
5. **P2-0 observation gate** — prove whether `primaryResolve` churn is mutation source (closed: resolver is noise, not churn source).
6. **P2-0.5 semantic freeze** — name and observe terminal authority before any bootstrap admission code (P2-1).

**Non-goals:** Floor arbitration changes; playback routing; global distributed barrier; consensus protocol.

**P2-0.5 non-goal (explicit):** Canonical convergence latency is **not** a requirement for immediate local PTT availability. Canonical convergence governs membership consistency and lineage stability; local transmit capability is evaluated per peer leg (P2-2 / P2-3), not by waiting for global `CANONICAL_READY`.

## Context

### P0-a evidence chain (session stamp `20260715-111759`)

```text
MEETING_END_BEGIN
        |
        v
multiple BOOTSTRAP_ATTEMPT (M01=5, M02=12, M03=20)
        |
        v
sessionTraceId drift (not simultaneous triple-split — temporal churn within one window)
        |
        v
peer ICE CLOSED / orphanBelief (M02 max ~24s)
        |
        v
local terminalReady=true (M01 @11:22:59, peers still CLOSED)
        |
        v
GROUP PTT gate appears open
        |
        v
partial media path (M01 joinedMembers shrinks to M01,M02; M03=CLOSED)
```

| Signal | M01 | M02 | M03 |
|--------|-----|-----|-----|
| `bootstrapAttemptCount` | 5 | 12 | 20 |
| `primaryResolveCount` | 309 | 339 | 221 |
| `orphanBelief` window | — | max ~24s | — |
| Final topology symptom | `joinedMembers=M01,M02`, M03 CLOSED | same partial mesh | excluded from primary canonical view |

**Decisive observations:**

1. **Trace churn, not slow ICE alone** — devices briefly shared trace `8b5a7fc8` (all CONNECTED), then primary rebuilt to `a7cf1849` with M03 dropped. Identity drift continued across the whole post-meeting window.
2. **Bootstrap churn** — M03 `waitingForPrimary` with 20 attempts; participant-side active session creation pressure during transition.
3. **Terminal false positive** — `terminalReady` semantics today ≈ local GROUP operational; product usage ≈ GROUP available for PTT. These must not be conflated (P2-0.5 freezes vocabulary; P2-2 / P2-3 tighten predicates).

### P2-0 soak evidence (`20260715-115346`, three post-meeting attempts)

| Attempt | M01 terminal | M03 terminal | M02 terminal | Symptom |
|---------|--------------|--------------|--------------|---------|
| 1 (fail) | 870ms | 851ms | **7551ms** | M01/M03 READY while M02 still BUILDING; M02↔M03 ICE CHECKING ~7s |
| 2 | 1236ms | **no terminal** | **no terminal** | M02↔M03 ICE CHECKING >60s; transition never completed on M02/M03 |
| 3 (pass) | 704ms | 680ms | 704ms | All mesh converged ~700ms |

**P2-0 conclusions (closed):**

```text
primaryChangeCount == 0        → resolver is NOT churn source
silentRecreateCount == 0       → no silent recreate in window
sessionTraceId per-device CREATE → NOT failure by itself
```

**Failure signature (motivates P2-0.5):**

```text
same convergence window:
  M01: terminalReady=true
  M03: terminalReady=true
  M02: meshRecoveryState=BUILDING, transmitReady=false
```

> **SessionTraceId divergence is not a failure. Failure is a participant declaring a canonical-authoritative terminal without a canonical convergence decision. A lease-expired self-declared DEGRADED terminal is legal but must not publish canonical lineage.**

### Problem decomposition (frozen)

| Layer | Role |
|-------|------|
| **Deterministic root cause** | Teardown bootstrap churn + session identity split |
| **Amplifier** | `terminalReady` predicate releases transition before canonical session + required transport are stable locally |
| **Ruled out (this soak)** | Floor (`holderAudioReachable=true` observed); idle playback (`NO_FLOOR_OWNER` is normal) |

### Architectural placement

```text
ADR-0022  Recovery completion ownership     (conference edge)
ADR-0023  CTA / membership mutation authority (who may write roster)
ADR-0025  Presence projection                (UI read model)
ADR-0026  Edge-scoped media barrier           (who may transmit in conference)
        |
        +---- ADR-0027  Canonical lineage + bootstrap convergence
              (conference → GROUP transition contract)
```

## Decision

### Core model — lineage vs trace

Introduce **`sessionLineageId`**: the continuous canonical identity across a post-meeting **convergence window**.

| Concept | Meaning |
|---------|---------|
| `sessionLineageId` | Canonical lineage for one MEETING→GROUP convergence episode |
| `sessionTraceId` | Concrete GROUP session instance within a lineage |
| `parentTraceId` | Previous trace when recreate occurs within same lineage |

```text
lineage L1
 |
 +-- traceId A  (initial primary create)
 |
 +-- traceId B  (explicit recreate, parentTraceId=A, reason=ICE_FATAL)
 |
 +-- traceId C  (explicit recreate, parentTraceId=B, reason=...)
```

**Forbidden:** silent recreate; new lineage within the same convergence window without window close.

**Allowed:** recreate within lineage when explicit invalidation fires (see P2-I5).

`sessionTraceId` alone is insufficient as the convergence contract — multiple traces per lineage are permitted; **lineage split** within one window is not.

---

### P2-I1 — Canonical Session Identity Monotonicity

For one convergence window (see § Window boundaries):

```text
lineageId(t2) == lineageId(t1)
```

unless the window has closed and a new `MEETING_END_BEGIN` opens a new window.

Trace may change only via **explicit invalidation + documented recreate** (P2-I5).

**Normative:** Not "one transition → one traceId", but **one convergence window → one canonical lineage**.

---

### P2-I2 — Convergence window boundaries

#### Window open

First `MEETING_END_BEGIN` on channel creates:

```text
TransitionLineage {
  lineageId
  startedAtMs
  baselineMembershipEpoch
  baselineMembers[]   // snapshot at MEETING_END_BEGIN
}
```

#### Window close

Canonical session reaches session terminal **`READY` or `DEGRADED`** (§ Terminal semantics) and remains stable for:

```text
stabilityDurationMs >= N   // v1 proposal: N = 3000
```

#### Re-entrancy during open window

Additional events in the same window:

- `MEETING_END` (remote-triggered)
- `GROUP_RECONCILE`
- `REMOTE_LEAVE`

**MUST** append to the active lineage. **MUST NOT** start a new `lineageId` or silent new canonical session.

---

### P2-I3 — Bootstrap admission protocol (canonical publish authority)

While convergence window is open, only **`resolvedBootstrapPrimaryModuleId`** may **publish canonical lineage**:

- create canonical GROUP session (canonical trace + epoch)
- mutate canonical membership on canonical session
- emit `CANONICAL_DECISION` (see §4 Terminal Authority Semantics)

Non-primary devices **MAY:**

- `requestAdmission`
- observe bootstrap state
- bounded retry join to primary-published session
- hold a **temporary recovery session** when primary is unreachable (election / lease timeout)

Non-primary devices **MUST NOT:**

- publish canonical lineage (independent competing canonical GROUP session)
- emit `CANONICAL_DECISION` or `CANONICAL_TERMINAL` with canonical authority

**Not the same as "single writer":** participants may create local recovery artifacts; they must not **publish** canonical truth.

**Authority field separation (observability):** snapshots **MUST** retain distinct fields:

- `initiatorModuleId`
- `anchorModuleId`
- `floorAuthorityModuleId`
- `resolvedBootstrapPrimaryModuleId` (bootstrap primary)

**MUST NOT** collapse into a single `canonicalOwner` field — that hides divergence P2-0 must detect.

---

### P2-I4 — Bounded wait + escalation (non-primary)

```text
WAIT_PRIMARY
       |
       | (bounded)
       v
PRIMARY_SUSPECTED_FAILED
       |
       v
request reelection / invalidation path
```

**Forbidden path:**

```text
WAIT_PRIMARY → timeout → self mesh_create()
```

Escalation **MUST** route through primary invalidation / reelection — not participant-local canonical session creation.

---

### P2-I5 — Membership continuity (baseline + grace lease)

**Not:** `canonicalMembers ⊇ all historical members forever` (causes primary starvation when a peer is genuinely gone).

**Instead:**

#### Baseline

```text
membershipBaseline = roster snapshot @ MEETING_END_BEGIN
```

Example: `[M01, M02, M03]`.

#### Grace lease per baseline member

Each baseline member enters `PENDING_RETAIN` with `leaseExpireAt = T + TTL`.

While lease active, primary **MUST** attempt `ADMIT` — **MUST NOT** silently drop from canonical rebuild.

#### Expire

After TTL without successful admit:

```text
PRESENCE_LEASE_EXPIRED
membershipEpoch++
removedMembers(reason=PRESENCE_LEASE_EXPIRED)
```

Silent removal without reason and epoch bump is **forbidden**.

#### Primary rebuild completeness

Any canonical session recreate **MUST** carry:

```text
membershipEpoch
knownMembers (from baseline + admitted)
removedMembers(reason)
```

Primary rebuild **MUST NOT** produce `newCanonicalMembers` that omits a baseline member still within `PENDING_RETAIN` lease.

---

### P2-I6 — Explicit invalidation recreate policy

Recreate is **not** forbidden. Silent recreate **is** forbidden.

#### Allowed invalidation reasons (v1 set)

| Reason | Typical trigger |
|--------|-----------------|
| `ICE_FATAL` | All required peer ICE failed; session unusable |
| `SESSION_CORRUPTED` | Local canonical state inconsistent |
| `PRIMARY_CHANGED` | Authority / bootstrap primary reelection |
| `MEMBERSHIP_EPOCH_CONFLICT` | Roster divergence vs canonical epoch |

Each recreate **MUST** emit:

```text
SESSION_INVALIDATION { lineageId, traceId, reason }
SESSION_RECREATE     { oldTraceId, newTraceId, lineageId, parentTraceId, reason }
```

and **MUST** inherit membership baseline / epoch per P2-I5.

**Primary during transition:** at most one **active** canonical trace per lineage unless previous trace was explicitly invalidated.

---

### P2-I7 — Session terminal semantics (P2-b scope; defined here for window close)

Session-level terminal (governance / transition completion) is **three-valued**:

| Terminal | Meaning |
|----------|---------|
| `READY` | Canonical session stable; required peers available from **this device's** canonical view |
| `DEGRADED` | Deadline expired; canonical session exists; some required peers unavailable — **session label only** |
| `FAILED` | No canonical session or authority unresolved |

`DEGRADED` **MUST NOT** be interpreted as global permission to PTT.

#### Local PTT gate (orthogonal)

Local gate opens only when:

```text
localCanonicalSession == true
AND
localTransportCapability == true
```

`localTransportCapability` means: floor control reachable **and** required media transport for this device's PTT role is available.

If session is `DEGRADED` but this device has zero usable transport → local state is `LOCAL_UNAVAILABLE`, not "gate open because DEGRADED".

`missingPeers` is diagnostic for partial connectivity — it does not override local gate falsity.

**Implementation note:** Tightening `MEETING_END` completion predicate to match P2-I7 is **P2-2 / P2-3** — not P2-0.5. P2-0.5 does not change predicate behavior.

---

## Section 4 — Terminal Authority Semantics (P2-0.5 freeze)

Today `GroupTopologyReadiness.OPERATIONAL` means **"I can transmit locally"** but is consumed as **"GROUP is operational"** — semantic pollution. P2-0.5 freezes vocabulary before P2-1 changes bootstrap ownership.

### LOCAL_TERMINAL

**Source:** participant-local judgment.

**Allowed when:**

```text
local session usable (LOCAL_OPERATIONAL)
OR
lease expired degradation (PRESENCE_LEASE_EXPIRED)
```

Example:

```text
LOCAL_TERMINAL(state=DEGRADED, reason=PRESENCE_LEASE_EXPIRED)
```

**Properties:**

- May drive local UI / runtime state
- Does **not** own canonical authority
- Must **not** publish canonical lineage
- Log marker: `LOCAL_TERMINAL_SELF_LEASE` (lease path) or `TRANSITION_TERMINAL_READY terminalAuthority=LOCAL_OPERATIONAL`

### CANONICAL_TERMINAL

**Source:** primary `CANONICAL_DECISION`, applied by participants.

Example:

```text
CANONICAL_DECISION {
  decisionId
  lineageId
  sessionTraceId
  membershipEpoch
  decision = READY | DEGRADED
  members[]
  bootstrapPrimaryModuleId
  ...
}
```

**Properties:**

- Convergence fact for the lineage (analyzer anchor)
- Participants reference via `CANONICAL_DECISION_APPLIED`
- Only primary may **emit** `CANONICAL_DECISION`

### Dual-path terminal (partition-safe)

```text
participant terminal =
  (a) CANONICAL_DECISION_APPLIED after primary CANONICAL_DECISION   -- normal path
  OR
  (b) LOCAL_TERMINAL_SELF_LEASE(DEGRADED)                           -- partition fallback
```

**Not:** "only primary may terminal." **Instead:** "only canonical-authoritative terminal requires canonical decision; self-lease degrade is legal but non-authoritative."

### Readiness vocabulary (rename target for P2-2 / P2-3)

| Today | Frozen target | Meaning |
|-------|---------------|---------|
| `GroupTopologyReadiness.OPERATIONAL` (local) | `LOCAL_OPERATIONAL` | Local session + required local transmit peers ready |
| (missing) | `CANONICAL_OPERATIONAL` | Primary view: baseline + required peers transmitReady + lineage stable |
| `group_operational` predicate | split → `localGroupOperational` / `canonicalGroupOperational` | Do not rename runtime until P2-2; document intent now |

### G-P2-TERM-1 — Canonical Terminal Authority Invariant

> Every **CANONICAL-authoritative** terminal declaration MUST have exactly one matching `CANONICAL_DECISION` on the primary (or `CANONICAL_DECISION_APPLIED` on the participant referencing that decision).
>
> `LOCAL_TERMINAL(DEGRADED, self-lease)` is valid fallback and MUST NOT publish canonical lineage.

**Legal:**

```text
M01: CANONICAL_DECISION READY → TRANSITION_TERMINAL_READY terminalAuthority=CANONICAL
M03: CANONICAL_DECISION_APPLIED → TRANSITION_TERMINAL_READY terminalAuthority=CANONICAL_APPLIED
M03 (partition): LOCAL_TERMINAL_SELF_LEASE DEGRADED reason=PRESENCE_LEASE_EXPIRED
```

**Illegal (observation flags violation; P2-1+ will enforce):**

```text
M03: TRANSITION_TERMINAL_READY terminalAuthority=LOCAL_OPERATIONAL
     (treated as premature local terminal — not canonical-authoritative, but documents the bug class)
```

### P2-0.5 observability markers (no gating)

| Marker | Emitter | Purpose |
|--------|---------|---------|
| `CANONICAL_DECISION` | Primary @ terminal observation | Convergence anchor |
| `CANONICAL_DECISION_APPLIED` | Participant joined primary mesh @ terminal | Applied canonical fact |
| `LOCAL_TERMINAL_SELF_LEASE` | Participant @ lease expiry (future hook; unit-testable now) | Legal non-authoritative degrade |
| `TRANSITION_TERMINAL_READY` | All | Extended: `terminalAuthority`, `canonicalDecisionId` |

**P2-0.5 acceptance:**

- ADR Section 4 + G-P2-TERM-1 frozen
- Markers present in build
- Unit tests cover authority classification
- Soak analyzer can correlate terminal timestamps to `CANONICAL_DECISION`

**P2-0.5 explicitly does NOT change:** terminal trigger conditions; `group_operational` predicate; PTT gate; Floor; Playback; Recovery; mesh bootstrap.

---

### P2-I8 — `membershipEpoch` ownership

`membershipEpoch` is **owned by bootstrap primary**, monotonically increasing within a lineage.

Epoch **MUST** be published with canonical bootstrap state and **MUST** survive trace recreate within lineage.

Participants **MUST NOT** locally bump epoch.

---

## P2-0 — Observation gate (implementation phase 0)

**Scope:** instrumentation only. No guards, no mesh/floor/playback changes.

**Soak script:** `scripts/soak-p2-0-canonical-lineage.ps1`

### Log markers (grep)

| Marker | Purpose |
|--------|---------|
| `CONVERGENCE_WINDOW_BEGIN` | Opens observation lineage + `baselineMembers` |
| `PRIMARY_RESOLVE` | Per-resolve: `primaryChanged`, `sessionMutated`, cumulative counters |
| `GROUP_SESSION_CREATE` | First trace in window / after clear |
| `GROUP_SESSION_RECREATE` | `oldTraceId`, `newTraceId`, `parentTraceId`, `reason` |
| `GROUP_TRANSITION_READINESS_SNAPSHOT` | Extended: `sessionLineageId`, `parentTraceId`, `membershipEpoch`, P2 counters |

P0-a markers (`MEETING_END_BEGIN`, `BOOTSTRAP_ATTEMPT`, `TRANSITION_TERMINAL_READY`) retained.

P2-0.5 markers: `CANONICAL_DECISION`, `CANONICAL_DECISION_APPLIED`, `LOCAL_TERMINAL_SELF_LEASE`; `TRANSITION_TERMINAL_READY` extended with `terminalAuthority`, `canonicalDecisionId`.

Answer before P2-1:

> Is `primaryResolveCount` high-frequency idempotent observation, or a churn source that triggers session mutation?

### Required markers / fields

#### Primary resolver

| Field | Purpose |
|-------|---------|
| `primaryResolveCount` | Total resolve invocations |
| `primaryResolveNoMutationCount` | Resolve returned same primary, no session side effect |
| `primaryChangeCount` | Resolved primary identity changed |
| `sessionRecreateTriggeredByPrimaryChange` | Recreate attributed to primary change |

**Healthy noise signature:**

```text
primaryResolveCount >> 0
primaryChangeCount == 0
sessionRecreateTriggeredByPrimaryChange == 0
```

**Churn source signature:**

```text
primaryChangeCount > 0
OR sessionRecreate without SESSION_INVALIDATION
```

#### Session lineage

| Field | Purpose |
|-------|---------|
| `sessionLineageId` | Convergence window lineage |
| `sessionTraceId` | Instance trace |
| `parentTraceId` | Prior trace on recreate |
| `sessionCreateCount` | Creates in window |
| `sessionRecreateCount` | Recreates in window |
| `sessionRecreateReason` | Invalidation reason |

#### Membership

| Field | Purpose |
|-------|---------|
| `membershipEpoch` | Primary-owned epoch |
| `baselineMembers` | Snapshot @ MEETING_END_BEGIN |
| `currentMembers` | Canonical members now |
| `removedMembersReason` | Explicit removals only |

#### Bootstrap

| Field | Purpose |
|-------|---------|
| `waitingForPrimaryDurationMs` | Participant wait time |
| `bootstrapAttemptCount` | Per window |
| `bootstrapAttemptReason` | `wait_primary`, `mesh_create`, etc. |

Extends P0-a markers (`MEETING_END_BEGIN`, `GROUP_TRANSITION_READINESS_SNAPSHOT`, `BOOTSTRAP_ATTEMPT`, `TRANSITION_TERMINAL_READY`).

### Analyzer derived metrics (three-device join)

| Metric | Target (post-P2) |
|--------|------------------|
| `lineageSplitCount` | 0 per window |
| `traceChangeCount` per lineage | may be >0 if every change has `SESSION_RECREATE` + reason |
| `bootstrapAttemptCount` / window | bounded; `participantSelfCreateDuringTransition == 0` |
| `max(orphanBeliefDurationMs)` | ≈ 0 |
| `missingKnownMemberAfterRebuild` | 0 while member in `PENDING_RETAIN` lease |

### P2-0 acceptance (no behavior change)

Proceed to **P2-0.5** after P2-0 soak. Proceed to **P2-1** only after P2-0.5 acceptance.

P2-0 gate (for P2-0.5 entry):

```text
primaryChangeCount == 0
AND all sessionRecreate have explicit reason
AND lineageId stable within window
AND membershipEpoch monotonic
AND no silent member loss (without PRESENCE_LEASE_EXPIRED or authority mutation)
```

---

## Implementation phases (frozen order)

| Phase | Scope | Runtime change |
|-------|--------|----------------|
| **P2-0** | Observation fields + analyzer | None |
| **P2-0.5** | Terminal authority semantics (ADR §4) + `CANONICAL_DECISION` markers | Observability only |
| **P2-1** | Bootstrap admission protocol + membership baseline/lease on rebuild | Yes |
| **P2-2** | Explicit invalidation recreate + lineage inheritance + predicate rename | Yes |
| **P2-3** | `localGroupOperational` / `canonicalGroupOperational` predicate + local PTT gate separation | Yes |

**P2-0.5 / P2-1 first patches MUST NOT touch:** Floor arbitration, playback routing, global barrier, terminal trigger conditions, PTT gate.

### P2-1 acceptance (identity-first)

Not "hear audio on first try." Required:

```text
sessionLineageId same across M01/M02/M03 at window close attempt
participantSelfCreateDuringTransition == 0
orphanBeliefDurationMs ≈ 0
missingKnownMemberAfterRebuild == 0 (within lease)
```

User-visible PTT is secondary confirmation after identity invariants pass.

---

## Relationship to existing predicates

ADR-0016 `MEETING_END` completion today:

```text
GROUP topology OPERATIONAL + membership digest aligned + no conference
```

ADR-0027 does **not** immediately amend ADR-0016. It defines the **missing contract** that explains P0-a false positives. Predicate tightening is **P2-3** after P2-1/P2-2 stabilize lineage.

---

## Frozen one-liner

> **P2 does not forbid GROUP rebuild. It requires rebuild to be an owned, lineaged, membership-complete, explicitly-reasoned process — with terminal authority separated from local transmit capability, and canonical publish restricted to bootstrap primary admission.**

---

## References

- ADR-0016 — Transition Completion Contract
- ADR-0017 — Transition Declaration and Media Lifecycle
- ADR-0022 — Recovery Completion Ownership (§ P0-a observation)
- ADR-0023 — Conference Membership Mutation Authority Boundary
- ADR-0025 — Conference Presence Plane Projection Contract
- ADR-0026 — Conference Media Transmit Barrier Scope
- ADR-0009 — Group Session Identity Consistency
- P0-a logs: `logs/soak-p0a-*-20260715-111759.log`
- P0-a summary: `logs/soak-p0a-summary-20260715-111759.txt`
- Script: `scripts/soak-p0a-group-transition.ps1`
