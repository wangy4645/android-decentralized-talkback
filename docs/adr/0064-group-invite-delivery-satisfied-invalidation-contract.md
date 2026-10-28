# ADR-0064: Group Invite Delivery Satisfied Invalidation Contract

## Status

**PATCH A IMPLEMENTED — PATCH B/C NOT AUTHORIZED** (2026-09-18)

```text
Grill SI-Q1..Q6:                   PASS (2026-09-18)
GIDR SATISFIED audit (M01↔M02)     CLOSED
M01↔M02 Layer-2 RCA                 CLOSED

Patch A (Overlay + SI-2 gate):   IMPLEMENTED
Patch B (Invalidation emitters):   NOT AUTHORIZED
Patch C (Field / SI-EG replay):    NOT AUTHORIZED

Production Meeting policy:         UNCHANGED
Field validation:                  NOT AUTHORIZED
```

**Parents / boundaries:**

- [ADR-0057 group bootstrap convergence](./0057-group-bootstrap-convergence-contract.md) — bootstrap *generation* vocabulary; orthogonal admission path
- [ADR-0057 Track C GIDR](../analysis/adr0057-group-invite-delivery-recovery-narrow-authorization.md) — delivery obligation vs bounded retry; **amendment candidate**
- [ADR-0057 Track C GPLB-LC](../analysis/adr0057-gplb-binding-lifecycle-correction-narrow-authorization.md) — edge incarnation / binding lifecycle; **invalidation source owner**
- [ADR-0063 NTI cross-topology](./0063-negotiation-transaction-integrity-cross-topology-contract.md) — fact vs truth owner; RecoveryController out of scope
- [ADR-0040 obligation convergence](./0040-obligation-convergence.md) — L2/L3 recovery execution; **MUST NOT absorb invalidation decision**
- [ADR-0053 group bootstrap authority](./0053-group-bootstrap-authority-reconciliation.md) — origin-side authority; **membership rollback scope input only**
- Layer 1 membership authority continuity (4-phone field, 2026-09-18) — **separate track; supplies affected-edge set, not satisfied semantics**

**Field evidence (Layer 2):**

- Log bundle: `talkback/logs/membership-obs-4phone-20260918-1500/`
- Symptom: M02 permanent Syncing; M01 `GIDR M02 phase=SATISFIED SuppressSatisfied` after M02 ICE `CONNECTED→DISCONNECTED` (13ms)
- Mechanism: `GROUP_SYNC_SDP` wrote terminal satisfied on signaling completion; GPLB concurrently reported `STALE` on new pcGeneration

---

## Summary

GIDR and GPLB currently publish **compatible but unlinked truths**:

| Subsystem | Question answered | Scope |
|-----------|-------------------|-------|
| **GIDR** | Did group invite *delivery* succeed for this peer? | `remoteModuleId` (peer-level, session-scoped) |
| **GPLB / ICE** | Is this *edge incarnation* still valid? | `offerLineageId` + `pcGeneration` |

When churn replaces an edge incarnation, GIDR may remain `SATISFIED` while GPLB marks the binding `STALE`. Reconciliation then **suppresses new wire** for a **new incarnation recovery** need.

This is not “retry is too weak.” It is **terminal success fact published without validity scope** — the same class as historical false-ACCEPT / NTI lifecycle gaps:

```text
fact published
    ↓
lifecycle not bound to incarnation
    ↓
downstream consumer treats fact as still authoritative
```

For 8–10 participants (~45 pairwise edges), any membership or negotiation churn can produce **local permanent Sync** on affected edges without session restart.

---

## Problem statement

**In scope:**

```text
Define when a GIDR delivery-satisfied fact remains authoritative,
when it MUST be invalidated, who emits invalidation,
and how GIDR consumes it — without merging decision and recovery execution.
```

**Out of scope (this ADR):**

- UDP reliability, ICE algorithm, timeout budget tuning
- Membership authority election / cold-join policy (Layer 1)
- Offer glare / NTI polite rules (tertiary once invalidation is correct)
- ADR-0040 convergence predicate or completion admission (ADR-0038)
- Automatic recovery dispatch on invalidation

---

## Q1 — Redefine `SATISFIED` semantics

### Current (observed implementation)

```text
SATISFIED(remoteModuleId)
  := outbound attempt exists
  AND handoffSucceeded
  AND terminalReason ∈ { MESH_LINK_COMPLETED, GROUP_ACCEPT_RECEIVED }
```

Answers: *“This peer once completed a group delivery handoff path.”*

Does **not** answer: *“Delivery satisfaction still authorizes suppressing wire for the **current** edge incarnation.”*

### Proposed contract term

```text
DeliverySatisfiedFact
  := positive completion of invite delivery obligation
     FOR a specific SatisfiedBinding identity
     WHILE that binding remains the authoritative delivery scope
```

**SatisfiedBinding** (minimum identity — semantic; encoding deferred):

| Field | Owner | Meaning |
|-------|-------|---------|
| `sessionId` | session | Local session instance |
| `remoteModuleId` | peer | Remote module |
| `offerLineageId` | GPLB wire | Offer lineage that completed delivery |
| `pcGeneration` | media registry | PC incarnation at completion time |
| `deliveryAttemptId` | GIDR attempt | Attempt ref within lineage; **audit only — not binding identity** |
| `completedAtMs` | clock | Audit only |

Optional cross-domain correlation (deferred encoding):

| Field | When required |
|-------|----------------|
| `bootstrapGeneration` | When membership rollback or ADR-0057 generation fact affects admission binding |
| `rosterEpoch` | When invalidation scope is membership-derived (Q5) |

### Frozen invariant (SI-1)

```text
SATISFIED(binding) MUST NOT imply SATISFIED(peer) for all future incarnations.

SATISFIED ≠ CONNECTED
SATISFIED ≠ LIVE binding
SATISFIED ≠ recovery completed

Binding identity for comparison:
  (offerLineageId, pcGeneration) only
deliveryAttemptId: provenance / audit — MUST NOT participate in binding identity
```

### `currentAuthoritativeBinding` (SI-Q1 — GRILL PASS)

```text
currentAuthoritativeBinding
  := LIVE GroupPcLineageBinding on current pcGeneration
     for (sessionId, remoteModuleId)

binding identity (equality):
  (offerLineageId, pcGeneration)

deliveryAttemptId:
  audit / provenance on SatisfiedFact only
  MUST NOT participate in binding identity or suppress comparison
```

A missing LIVE binding on current pcGeneration means **no authoritative satisfied binding** exists for suppress purposes.

### Consumer rule (SI-2)

GIDR reconciliation **MUST** evaluate suppress/permit against **current** edge binding identity, not peer id alone:

```text
suppress retry  IFF  satisfiedStatus == ACTIVE
               AND  currentAuthoritativeBinding != null
               AND  completedBinding.offerLineageId
                       == currentAuthoritativeBinding.offerLineageId
               AND  completedBinding.pcGeneration
                       == currentAuthoritativeBinding.pcGeneration

permit retry    OTHERWISE (subject to existing ACTIVE / stale attempt rules)
```

M01↔M02 replay: satisfied `(L1, gen=3)` vs authoritative `(null LIVE, gen=4)` → **PermitRetry**.

---

## Q2 — Events that MUST invalidate

### Distinction (frozen)

| Class | Example | Invalidate? |
|-------|---------|-------------|
| **Incarnation change** | `pcGeneration` bump; GPLB binding `FENCED` / `RELEASED`; superseding offer lineage | **YES — mandatory** |
| **Membership scope change** | Roster rollback removes/re-admits peer; peer leave | **YES — for affected edges only** |
| **ICE transient** | `CONNECTED → DISCONNECTED → CONNECTED` same pcGeneration + same LIVE binding | **NO — direct invalidate forbidden** |
| **ICE loss on stale incarnation** | DISCONNECTED after binding already STALE | **N/A — satisfied should already be invalidated by binding change** |

Direct ICE-disconnect invalidation is **forbidden** without incarnation change, to avoid recovery storms on flaky paths.

### Event matrix (contract)

| Event | Invalidate satisfied? | Scope | Notes |
|-------|-------------------------|-------|-------|
| `pcGeneration` bump | **MUST** | per `(session, remoteModuleId)` edge | Definitive new incarnation |
| GPLB binding → `FENCED` | **MUST** | per `(session, remoteModuleId, offerLineageId)` | Includes `SUPERSEDED_BY_OFFER`, `SUPERSEDED_BY_ANSWERER_APPLY` |
| GPLB binding → `RELEASED` | **MUST** | per binding | Media-lineage release (GPLB-L4) |
| ICE `DISCONNECTED` / `FAILED` (same incarnation) | **MUST NOT** (alone) | — | Media state may change; delivery fact scope unchanged |
| ICE loss **after** incarnation already invalid | N/A | — | Ordering: binding invalidation first |
| Membership snapshot rollback | **MUST** | **affected edge set only** (Q5) | Not all peers |
| Peer leave / roster removal | **MUST** | departed peer | |
| Authority transition (Layer 1) | **CONDITIONAL** | edges whose admission binding epoch is superseded | Does not global-reset channel |
| `ATTEMPT_TIMEOUT` on active attempt | existing GIDR | per attempt | Already `STALE_RETRY_ELIGIBLE`; not satisfied |
| `EDGE_LOST` on active in-flight attempt | existing GIDR | per attempt | Terminates active only today |

### Invalidation reasons (audit enum — semantic)

```text
INVALIDATED_BY_PC_GENERATION_CHANGE
INVALIDATED_BY_BINDING_FENCED
INVALIDATED_BY_BINDING_RELEASED
INVALIDATED_BY_MEMBERSHIP_ROLLBACK
INVALIDATED_BY_PEER_REMOVED
INVALIDATED_BY_ADMISSION_BINDING_SUPERSEDED
```

### Frozen dual mechanism (SI-Q2 — GRILL PASS)

Incarnation invalidation uses **two planes** that MUST NOT be conflated:

**Correctness plane (gate authority):**

```text
GIDR reconcile
    → SI-2 pull comparison

satisfiedFact.binding != currentAuthoritativeBinding
    → PermitRetry (subject to existing ACTIVE / stale rules)
```

**Evidence plane (audit authority):**

```text
Edge lifecycle (GPLB fence/release, pcGeneration bump, membership rollback, …)
    → MUST emit SatisfiedInvalidationFact
    → marks DeliverySatisfiedFact.status = INVALIDATED (audit)
```

**Frozen boundaries:**

```text
Suppress / Permit authority:     SI-2 pull comparison
Invalidation event:              mandatory audit fact
Push fact:                         evidence + observability — NOT source of correctness

Missing push fact:               reduces observability; MUST NOT prevent PermitRetry
Persistent push vs pull mismatch:  correctness defect
```

Parallel (ADR-0063 NTI): **InvalidationFact tells what happened; currentAuthoritativeBinding determines current truth.**

---

## Q3 — Invalidation ownership

GIDR **MUST NOT** infer incarnation invalidation from peer id or terminal reason alone.

### Proposed flow

```text
GPLB / Edge lifecycle / Membership scope observer
        |
        | emit SatisfiedInvalidationFact(bindingRef, reason, observedAtMs)
        v
GIDR consumer (audit overlay only)
        |
        | mark satisfiedStatus = INVALIDATED (terminalReason preserved)
        v
(next reconcile: SI-2 pull → PermitRetry if obligation open)
        v
(existing GIE / mesh reconcile — execution unchanged by this ADR)
```

**Owners:**

| Concern | Owner |
|---------|-------|
| Binding identity truth | GPLB + media registry (`pcGeneration`) |
| Binding terminal transitions | GPLB (`FENCED`, `RELEASED`) |
| Affected edge set on roster rollback | Membership snapshot applier (Layer 1 interface) |
| Delivery suppress / permit | GIDR (consumer only) |
| Recovery execution | Existing recovery / mesh reconcile (ADR-0040 boundary) |

GIDR remains responsible for **attempt lifecycle** (ACTIVE, timeout, handoff). It does **not** own **when** a satisfied fact stops being authoritative.

### Frozen emit ownership (SI-Q3 — GRILL PASS)

```text
SatisfiedInvalidationFact emit ownership:

GPLB:
  binding lifecycle changes
  → INVALIDATED_BY_BINDING_FENCED
  → INVALIDATED_BY_BINDING_RELEASED

Media registry / engine provision:
  pcGeneration changes
  → INVALIDATED_BY_PC_GENERATION_CHANGE

Membership applier:
  roster membership invalidation
  → INVALIDATED_BY_MEMBERSHIP_ROLLBACK
  → INVALIDATED_BY_PEER_REMOVED

Consumers (audit only):
  GIDR

Forbidden emitters / consumers for this fact:
  Recovery controller
  Mesh reconcile executor
```

**Fact identity (idempotent dedupe key):**

```text
(sessionId, remoteModuleId, oldBindingIdentity, invalidationReason)
```

Multiple emitters MAY observe the same churn (pcGeneration bump + GPLB fence + membership rollback). Multiple invalidation facts are allowed; duplicate facts MUST NOT corrupt truth. Gate correctness remains SI-2 pull only.

**M01↔M02 mapping (target behavior):**

```text
t0  Media registry: INVALIDATED_BY_PC_GENERATION_CHANGE
t1  GPLB:           INVALIDATED_BY_BINDING_FENCED
    GIDR:            records audit (push)
    reconcile:       SI-2 → no LIVE binding → PermitRetry
```

If the first push fact is lost, SI-2 still prevents deadlock.

**Anti-pattern (forbidden):** central InvalidationBus as truth owner — domain owners produce facts; GIDR consumes for audit only.

---

## Q4 — Post-invalidation state

### Forbidden

```text
SATISFIED = false   // erases audit trail
clear attempt map   // loses delivery history
```

### Required model

```text
OutboundGroupInviteAttempt (overlay — SI-Q4 GRILL PASS):

Historical plane (immutable once written):
  terminalReason / lastPositiveReason
  deliveryAttemptId
  offerLineageId (at completion)
  completedBinding: (offerLineageId, pcGeneration)

Operational plane:
  satisfiedStatus: ACTIVE | INVALIDATED
  invalidation: { reason, atMs, source }?

Gate (SI-2 + overlay):
  SATISFIED phase iff:
    satisfiedStatus == ACTIVE
    AND SI-2 authoritative binding match

  obligation open otherwise:
    → INVALIDATED_RETRY_ELIGIBLE (churn / invalidation path)
    → STALE_RETRY_ELIGIBLE (timeout path — distinct for observability)
```

**Reconciliation phases (GIDR consumer view):**

```text
NONE
ACTIVE                    // in-flight handoff
STALE_RETRY_ELIGIBLE      // ATTEMPT_TIMEOUT / non-satisfied terminal
SATISFIED                 // satisfiedStatus ACTIVE + SI-2 match
INVALIDATED_RETRY_ELIGIBLE // satisfiedStatus INVALIDATED or SI-2 mismatch + obligation open
```

### Frozen invariants (SI-Q4)

**SI-Q4-1:** Invalidation never deletes delivery history.

```text
FORBIDDEN: terminalReason = null, remove attempt map entry
```

**SI-Q4-2:** Invalidated satisfied is not delivery failure.

| State | Meaning |
|-------|---------|
| ACTIVE | Still authoritative for current incarnation |
| INVALIDATED | Historically succeeded; current edge scope expired |
| INVALIDATED_RETRY_ELIGIBLE | Obligation reopened after invalidation |
| STALE_RETRY_ELIGIBLE | Obligation reopened after attempt timeout |
| FAILED | New attempt execution failed (separate domain) |

Edge churn MUST NOT be recorded as delivery failure.

**State transition (summary):**

```text
handoff → ACTIVE
GROUP_ACCEPT / MESH_COMPLETE → satisfiedStatus=ACTIVE (terminalReason preserved)
invalidation push → satisfiedStatus=INVALIDATED (terminalReason unchanged)
SI-2 mismatch + obligation open → INVALIDATED_RETRY_ELIGIBLE → PermitRetry
new handoff → ACTIVE (new attempt / binding)
```

Mapping:

```text
INVALIDATED_RETRY_ELIGIBLE + peerAdmissionIncomplete  →  PermitRetry (subject to existing gates)
INVALIDATED + obligation closed                        →  no wire (unchanged)
```

Audit retention (observation-only at implementation time):

```text
lastSatisfiedBinding
invalidationReason
invalidatedAtMs
invalidationSource
priorTerminalReason
```

---

## Q5 — Membership rollback interaction

Layer 1 (authority continuity) and Layer 2 (edge correctness) **interface at affected-edge set**, not global reset.

### Frozen affected-edge set (SI-Q5 — GRILL PASS)

On `MembershipSnapshotApplyResult.APPLIED`:

```text
removed = localCanonical \ incomingCanonical
added   = incomingCanonical \ localCanonical
affected = removed ∪ added

if incomingEpoch < localEpoch before apply:    // rollback class
  affected ∪= { peer ∈ (localCanonical ∩ incomingCanonical)
                  | outbound satisfiedStatus == ACTIVE }

if incomingEpoch >= localEpoch:               // forward / normal reconcile
  affected = removed ∪ added only
```

**Semantics:** rollback accepts an **older membership truth**; delivery obligations that depended on the superseded truth MUST be revalidated — not only roster diff edges.

**Blast-radius limit (SI-Q5-1):**

```text
Only LOCAL outbound satisfied obligations are invalidated.
MUST NOT invalidate remote-only facts, unrelated peers,
or peers without ACTIVE satisfied obligation.
```

Optional optimization (audit only; gate unchanged):

```text
If peer ∈ stable intersection AND SI-2 still matches AND satisfiedStatus=ACTIVE
→ membership invalidation emit MAY skip
```

Gate correctness remains SI-2 pull regardless.

**M01↔M02 replay:** removed `{M02,M03}`; intersection active satisfied `{M01}` → affected `{M02,M03,M01}`.

10-node: rollback invalidation on applying node is **O(|diff| + |intersection with active satisfied|)** — not channel-wide reset.

---

## Q6 — ADR-0040 / recovery boundary

### Frozen three-layer isolation (SI-Q6 — GRILL PASS)

```text
Layer 1 — Invalidation / Audit
    GPLB | Media registry | Membership applier
        → SatisfiedInvalidationFact

Layer 2 — Eligibility
    GIDR: SI-2 authoritative binding comparison + overlay satisfiedStatus
        → PermitRetry | SuppressSatisfied

Layer 3 — Execution (existing paths only)
    GROUP: mesh reconcile | GIE | late-peer dispatch
    CONFERENCE RecoveryController: independent domain;
        MUST NOT consume SatisfiedInvalidationFact
```

**Responsibility separation (frozen):**

| Layer | Answers |
|-------|---------|
| Invalidation | Past satisfied scope expired (audit) |
| GIDR | Whether retry is currently allowed |
| Reconcile / GIE | When wire execution happens |

**SatisfiedInvalidationFact is NOT a recovery trigger.**

It provides:

- audit evidence
- satisfied overlay invalidation (`satisfiedStatus → INVALIDATED`)

It does **not**:

- invoke recovery
- restart ICE
- declare edge failure
- mutate topology
- complete recovery
- schedule reconcile (implementation optimization only — **not contract**)

**Retry execution:**

```text
GROUP:
  GIDR eligibility (SI-2 + overlay) → existing mesh reconcile / GIE

CONFERENCE:
  RecoveryController remains isolated from this fact
```

### Frozen boundaries (SI-6)

```text
SatisfiedInvalidationFact  →  audit + overlay invalidation
                           →  SI-2 pull reopens PermitRetry on reconcile
                           ≠  dispatch recovery
                           ≠  ICE restart
                           ≠  EDGE_RECOVERY_CONVERGED
                           ≠  ADR-0038 completion admission input

GPLB STALE               →  fence wire ICE / ACCEPT correlation
                           ≠  by itself prove delivery obligation closed
```

Invalidation **clears a false suppress**. It does **not** schedule ICE restart, enlarge budget, or merge WiFi recovery domains.

ADR-0040 remains **execution and convergence** after L2 evidence exists. This ADR fixes **false terminal suppress** before execution gets a chance.

**M01↔M02 target chain:**

```text
GROUP_SYNC_SDP → satisfiedStatus=ACTIVE
pcGeneration bump / GPLB fence → SatisfiedInvalidationFact (audit)
reconcile: SI-2 mismatch → PermitRetry
existing GROUP mesh reconcile → new offer / binding

(no RecoveryController | no ICE restart controller | no topology mutation)
```

---

## Grill adjudication (SI-Q1..Q6)

| Gate | Result | Frozen decision |
|------|--------|-----------------|
| **SI-Q1** | PASS | `currentAuthoritativeBinding` = LIVE GPLB on current `pcGeneration`; identity `(offerLineageId, pcGeneration)` |
| **SI-Q2** | PASS | Gate = SI-2 pull; invalidation push = mandatory audit only |
| **SI-Q3** | PASS | Emit: GPLB / Media registry / Membership; consume: GIDR audit only |
| **SI-Q4** | PASS | Overlay model; history preserved; `INVALIDATED_RETRY_ELIGIBLE` |
| **SI-Q5** | PASS | Rollback: diff + active satisfied intersection; local-only blast radius |
| **SI-Q6** | PASS | Three-layer isolation; invalidation ≠ recovery trigger |

---
## Architecture conclusion (M01↔M02)

Observed failure chain:

```text
membership churn
    → edge incarnation transient invalidation (GPLB STALE / pcGeneration mismatch)
    → GIDR peer-level SATISFIED retained
    → SuppressSatisfied blocks new invite wire
    → remote waits → local permanent missing / Syncing
```

Offer collision was a **secondary amplifier**, not the terminal lock.

Root class:

```text
terminal success fact missing validity scope
```

---

## Implementation disposition

**Contract:** READY FOR IMPLEMENTATION · **Cut:** NOT AUTHORIZED

Recommended vehicle: **ADR-0057 GIDR amendment** carrying explicit SI-Q1..Q6 (GPLB/membership remain invalidation sources).

**Authorized implementation order (when cut is granted — do not merge domains):**

| Patch | Scope | Proves |
|-------|-------|--------|
| **A** | Overlay state + SI-2 gate (GIDR correctness) | false suppress removed |
| **B** | Invalidation emitters (GPLB / Media / Membership audit) | observable invalidation chain |
| **C** | GROUP reconcile validation fixtures (SI-EG*) | M01↔M02 replay class |

Forbidden single PR: membership + GIDR + recovery + ICE ownership.

Minimum implementation fixtures:

```text
SI-EG1  satisfied for binding B1; pcGeneration bump → PermitRetry; audit INVALIDATED
SI-EG2  satisfied for B1; ICE flap same generation → still SuppressSatisfied
SI-EG3  satisfied for B1; GPLB FENCED → PermitRetry
SI-EG4  rollback affects peer P only → P invalidated; stable peer Q unaffected
SI-EG5  invalidation → PermitRetry but recovery NOT auto-dispatched
SI-EG6  M01↔M02 replay class → no SuppressSatisfied after churn + stale binding
```

---

## Forbidden shortcuts

```text
SATISFIED(peer) suppresss all future incarnations
ICE DISCONNECTED → clear satisfied (without incarnation change)
membership rollback → invalidate all peers
GPLB STALE → auto recovery dispatch
invalidation → schedule reconcile (contract requirement)
invalidation → ADR-0038 completion bypass
invalidation → WiFi recovery / RNA reopen
```

---

## References

- GIDR SATISFIED audit (conversation closure, 2026-09-18)
- Field: `talkback/logs/membership-obs-4phone-20260918-1500/`
- Code seams: `OutboundGroupInviteAttemptSupport`, `GroupInviteDeliveryRecoverySupport`, `GroupPcLineageBindingSupport`, `TalkbackCoordinator.handleGroupAccept` (`GROUP_SYNC_SDP`)
- [ADR-0057 GIDR authorization](../analysis/adr0057-group-invite-delivery-recovery-narrow-authorization.md)
- [ADR-0057 GPLB-LC](../analysis/adr0057-gplb-binding-lifecycle-correction-narrow-authorization.md)
- [ADR-0040 obligation convergence](./0040-obligation-convergence.md)
