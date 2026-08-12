# ADR-0053: GROUP Bootstrap Authority Reconciliation

## Status

**ACCEPTED** (2026-08-12) · Grill complete · Clarifications A–C applied · Implementation **AUTHORIZED** (separate PR; scope locked per § Implementation guidance)

**Tracker:** [#176](https://github.com/wangy4645/android-decentralized-talkback/issues/176) — RCA COMPLETE · CONTRACT FROZEN on issue · this ADR supersedes issue comment stack as normative contract

**Parents / boundaries:**

- [ADR-0008](./0008-group-runtime-health-projection.md) — Group health **projection**; does not own bootstrap authority
- [ADR-0009](./0009-group-session-identity-consistency.md) — session identity **after** authority converges; does not substitute for stale detection
- [ADR-0027](./0027-canonical-session-lineage-and-bootstrap-convergence.md) — post-meeting lineage / canonical publish; orthogonal admission path; **does not** define asymmetric stale-ownership recovery (#176)
- [ADR-0052](./0052-conference-transport-scope-admission-recovery-gate.md) — conference transport / admission; **out of scope**

**Parallel track (do not merge):** [#175](https://github.com/wangy4645/android-decentralized-talkback/issues/175) — recovery health **projection** convergence (`DEGRADED` sticky); edge observer domain, not GROUP bootstrap authority

```text
ADR-0053
Decision:              ACCEPTED
Domain:                GROUP channel bootstrap authority
Problem class:         stale GROUP session ownership after asymmetric session loss
Implementation target: GroupChannelAuthority + 4 coordinator seams (separate PR)
Does NOT reopen:       ADR-0052 / #174 · WiFi recovery · RNA-5/6 · completion predicate
                       · Meeting-as-required-path · JOIN retry aging · QUEUED timeout
```

---

## Summary

After asymmetric session loss, a node may retain a **stale GROUP session** while still acting as nominal primary. The mesh planner then performs **JOIN-oriented maintenance** on a session that no longer holds bootstrap authority. Followers queue `GROUP_JOIN` indefinitely (`QUEUED_NO_SESSION`). The channel appears stuck on **Sync Channel** until an accidental destructive transition (e.g. Meeting) clears the stale session.

This ADR defines:

1. **When** an existing GROUP session is no longer authoritative (Detection: E1/E2/E3)
2. **Who** may bootstrap in a decentralized system (Authority selection)
3. **How** recovery proceeds without JOIN retry aging (Reconciliation: invalidation → bootstrap)

**Core upgrade:**

```text
Before:  session != null  →  maintenance (GROUP_JOIN)
After:   authority_state  →  invalidation | bootstrap | maintenance
```

`localSessionId != null` is **not** a sufficient condition for bootstrap authority.

---

## Field evidence

| Run | Path | Role |
|-----|------|------|
| Deadlock + Meeting breakout | `talkback/logs/group-bootstrap-meeting-breakout-20260812-103555/` | Positive failure; destructive repair observed |
| Cold-start negative control | `talkback/logs/group-cold-start-control-20260812-105005/` | Symmetric cold start PASS; ~463ms transient `QUEUED_NO_SESSION` is legal race |
| Stability baseline | `talkback/logs/stability-baseline-20260812-111904/` | Main path healthy |

### Negative control (must preserve)

```text
M01: mesh_create → GROUP_SESSION_CREATE → GROUP_INVITE
M02: waitingForPrimary → GROUP_JOIN → QUEUED_NO_SESSION (~463ms) → acceptGroupInvite → converge
```

### Positive failure (#176)

```text
M01: stale GROUP session retained · membershipReady=true · GROUP_JOIN (no mesh_create / no GROUP_INVITE)
M02: localSessionId="" · waitingForPrimary · GROUP_JOIN → QUEUED_NO_SESSION (permanent)
```

Meeting repair proves the system **can** recover via destructive session clearing; Meeting is **not** the correct API.

---

## Problem statement

The missing contract:

> When must a primary invalidate its current GROUP session ownership and re-bootstrap the channel?

Root cause is **bootstrap authority recovery**, not pending-queue deficiency or JOIN retry timing.

---

## Authority model

### Primary role ≠ primary authority

A node may claim bootstrap authority only when all hold:

```text
authority_valid :=
    localSessionId != null
    AND session identity matches current topology ownership claim
    AND membership view is compatible
```

**Frozen invariant:**

> A node **cannot** remain `VALID_PRIMARY` solely because its old session still exists.

### Bootstrap exclusivity

```text
BOOTSTRAP_OWNER may:     GROUP_SESSION_CREATE · GROUP_INVITE
NON_BOOTSTRAP_MEMBER may: GROUP_JOIN (after admission only)

GROUP_JOIN MUST NOT be used as bootstrap recovery.
```

### Authority snapshot (decision + audit)

`authority_state` is the **decision**. **Evidence** is the auditable basis; without it, implementations regress to `if (session != null)`.

```text
AuthoritySnapshot {
    state: AuthorityState
    candidate: ModuleId?          // resolveBootstrapPrimary(dialable ∪ {local})
    evidence: AuthorityEvidence    // why this state was entered
    sessionId: String?            // local GROUP session handle, if any
    topologyEvidence: ...         // ownership claim compatibility
    membershipEvidence: ...       // roster / peer posture observations
}
```

- **MUST** be observable in memory snapshot + trace (e.g. `GroupTransitionReadinessLog`)
- **MUST NOT** require durable persistence in v1
- Evidence **MUST** survive long enough to debug field runs and unit failures

---

## Detection (Phase 1)

Evidence families transition `VALID_PRIMARY` → `STALE_PRIMARY`. **E1, E2, and E3 are independent paths** — any one family suffices **unless** bootstrap-in-progress exemption applies. They are **not** conjunctive (e.g. E2 does **not** require E3).

> **Normative:** E3 is **sufficient** evidence, not the **exclusive** detection mechanism. E1 or E2 (or any equivalent locally observable topology/session incompatibility) may independently invalidate authority even when no peer is actively sending `GROUP_JOIN`.

### E1 — Local authority missing (authority holder only)

```text
primary_role
AND (localSessionId == null OR session terminal)
```

Strongest signal. Direct invalidation of local authority claim.

**Role distinction (MUST NOT mis-implement):**

```text
primary_role + authority missing  →  E1  →  may enter STALE_PRIMARY
follower + localSessionId == null →  NOT E1  →  waitingForPrimary (normal)
```

A follower without a local GROUP session **MUST NOT** enter `STALE_PRIMARY` solely because `localSessionId == null`.

### E2 — Session identity invalid

```text
session exists
BUT session identity cannot be reconciled with current topology ownership claim
```

Contract layer only: *the current session cannot prove it still belongs to the current topology ownership claim.*

**Deferred to implementation / later ADR:** lineage id, epoch, digest, CTA — not part of this detection contract.

### E3 — Membership / bootstrap incompatibility (primary-side)

`QUEUED_NO_SESSION` **alone** is **never** STALE evidence. It may only contribute to a **combination**.

```text
E3 :=
    localSessionId != null
    AND peer ∈ claimed roster
    AND locally observed peer bootstrap-posture evidence
        (e.g. peer JOIN ingress → QUEUED_NO_SESSION on this node)
    AND NO active bootstrap emission on this node
        (no mesh_create · no outbound GROUP_INVITE in observation window)
    AND primary continues JOIN-oriented reconciliation
```

**Decentralized rule:** E3 is defined on **locally observed** peer bootstrap-posture evidence — not on a global fact such as `peer.waitingForPrimary` unless that posture is **observable on this node** via roster/mesh/JOIN ingress/membership observation.

ADR defines **what evidence suffices**; implementation defines **which transport carries it** (directory, JOIN rejection, membership snapshot, etc.).

### Bootstrap-in-progress exemption (negative control)

```text
waitingForPrimary + QUEUED_NO_SESSION
```

is **legal** while the elected candidate is in **active bootstrap emission** (`mesh_create` / outbound `GROUP_INVITE`). Must not trigger STALE.

**Forbidden detection triggers:** fixed timeout · retry count · QUEUED queue depth aging.

---

## Authority states and transitions

```text
VALID_PRIMARY
      |  E1 / E2 / E3
      v
STALE_PRIMARY
      |  commitAuthorityInvalidation()  [synchronous logical transaction]
      v
AUTHORITY_INVALIDATED
      |  candidate evaluates BOOTSTRAP_REQUIRED
      v
BOOTSTRAP_REQUIRED
      |  candidate: mesh_create → GROUP_INVITE
      |  non-candidate: wait
      v
VALID_PRIMARY (recovered)
```

**Frozen:**

```text
STALE_PRIMARY + JOIN retry loop
```

must not become a stable system state.

During `STALE_PRIMARY`:

- outbound `GROUP_SESSION_CREATE` / `GROUP_INVITE` **forbidden**
- inbound `GROUP_JOIN`: **evidence-only** — observe, record, **reject**; **MUST NOT** enter `pendingGroupJoins`

---

## Authority selection (Phase 2)

### Bootstrap candidate

```text
BOOTSTRAP_CANDIDATE = resolveBootstrapPrimary(dialable ∪ {local})
```

- Deterministic function of **election-level dialable** membership + health directory (existing `ChannelMeshHostElection`)
- **Not** a vote; **not** a sticky lock on historical primary
- Recomputed on each authority decision and on dialable change

### Emission eligibility

A node may act as `BOOTSTRAP_OWNER` only when:

```text
local == BOOTSTRAP_CANDIDATE
AND authority_state == BOOTSTRAP_REQUIRED
AND NOT valid_authority_held_anywhere
```

Evaluated **at emission time** by the **candidate only**. Non-candidates **never** bootstrap regardless of their view of authority absence.

### `valid_authority_held_anywhere` (local evidence)

Blocks candidate emission when **any** of:

| Id | Condition |
|----|-----------|
| V-self | Local `authority_state == VALID_PRIMARY` (E1∧E2∧E3 pass) |
| V-peer-primary | Dialable peer is candidate and satisfies VALID_PRIMARY triple (locally corroborated) |
| V-peer-booting | Dialable peer is candidate in **active bootstrap emission** |

**Does not count:** follower `waitingForPrimary` alone · peer `QUEUED_NO_SESSION` alone · peer JOIN-only loop without emission · guessed peer state.

**`V-peer-booting` self vs peer:**

```text
observedCandidate != local  →  active emission BLOCKS local emission
observedCandidate == local  →  NOT a competing authority
                               (only after invalidation commit — see below)
```

**`V-peer-booting(self)`** may apply only **after** old authority invalidation commit.

### STALE primary that is also candidate (#176 path)

```text
STALE_PRIMARY → invalidate → BOOTSTRAP_REQUIRED → mesh_create → GROUP_INVITE
```

**Forbidden:**

```text
STALE_PRIMARY → in-place mesh_create / GROUP_INVITE (old session still authoritative)
```

**Frozen:**

> A stale authority **MUST NOT** emit `GROUP_SESSION_CREATE` or `GROUP_INVITE` until its previous GROUP authority has been invalidated.

### Failover (candidate ∉ dialable)

Failover trigger is **election-level dialable membership**, not media-level ICE/Wi-Fi connectivity.

| Event | Recompute candidate? |
|-------|----------------------|
| ICE DISCONNECTED alone | No |
| Wi-Fi flap (peer still dialable) | No |
| Media edge FAILED alone | No |
| Peer absent from election-level dialable set | **Yes** |
| Process offline / force-stop | **Yes** |
| Peer explicitly leaves channel / election set | **Yes** |

```text
candidate ∉ dialable
    ⇒ candidate MUST NOT retain bootstrap eligibility
    ⇒ resolveBootstrapPrimary() MUST be recomputed
```

**Semantic commitment (T6):** M01 absent from dialable ⇒ M02 **may** become candidate ⇒ authority evaluation ⇒ **may** reach `BOOTSTRAP_REQUIRED` ⇒ bootstrap. This is **not** "M01 offline ⇒ M02 immediately creates session"; existing planner scheduling still applies. **No** implicit "absent for N seconds" threshold.

**Partition:** divergent dialable views may yield divergent candidates — deferred to Phase 3 reconciliation (epoch / digest / CTA). Not solved in this ADR.

```text
Authority failure taxonomy:

        authority invalid
               |
     +---------+---------+
     |                   |
still dialable      not dialable
     |                   |
STALE_PRIMARY         FAILOVER
(self-repair)    (new candidate)
```

---

## Reconciliation (Phase 3 contract — mechanism deferred)

### Logical invalidation (synchronous commit)

```text
commitAuthorityInvalidation():
    authority_state := AUTHORITY_INVALIDATED
    remove local GROUP session from planner visibility
    clear pendingGroupJoins(oldSessionId)
    forbid old-lineage outbound emission
    reject old-session JOIN ingress (STALE_AUTHORITY_REJECTED)
    schedule reconcile
```

Physical ICE / PeerConnection / media teardown **MAY** lag. Bootstrap emission **MUST NOT** wait for physical teardown completion (no hidden timeout).

### Invariants

```text
I-AUTH-1
After AUTHORITY_INVALIDATION_COMMIT:
  old sessionId MUST NOT be observable as an authoritative local GROUP session
  by any GROUP planner.

I-AUTH-2
An invalidated session MUST NOT be the target of GROUP_JOIN maintenance.

I-AUTH-3
Physical ICE/media teardown MAY lag logical invalidation,
but MUST NOT restore planner visibility of the invalidated session.
```

### Reconcile planner contract

`reconcileGroupMeshInternal` (or successor) **MUST** branch on `authority_state`, not `session != null` alone:

```text
VALID_PRIMARY           → maintenance (GROUP_JOIN for ICE reconnect permitted)
STALE_PRIMARY           → commitAuthorityInvalidation(); return
AUTHORITY_INVALIDATED   → evaluate BOOTSTRAP_REQUIRED; return
BOOTSTRAP_REQUIRED
    candidate             → mesh_create → GROUP_INVITE
    non-candidate         → wait (waitingForPrimary)
```

**Forbidden reconcile triggers for bootstrap:** inbound `GROUP_JOIN` · `QUEUED_NO_SESSION` depth · retry aging.

`GROUP_JOIN` **MAY** strengthen E3 detection; **MUST NOT** schedule bootstrap or maintenance while authority is stale.

### JOIN ingress semantics

| Decision | Meaning |
|----------|---------|
| `QUEUED_NO_SESSION` | Legal bootstrap race / session not yet established |
| `STALE_AUTHORITY_EVIDENCE_ONLY` | JOIN observed during STALE; recorded; not queued |
| `STALE_AUTHORITY_REJECTED` | JOIN after invalidation or to dead authority; control-plane reject |

`STALE_AUTHORITY_REJECTED` is **not** ICE recovery failure, media failure, or retry escalation.

### Recovery sequence (normative)

```text
detect E1 / E2 / E3 → STALE_PRIMARY
    → commitAuthorityInvalidation()
    → BOOTSTRAP_REQUIRED (candidate, no valid authority elsewhere)
    → mesh_create → GROUP_INVITE
    → follower accept → GROUP_SESSION_CREATE
    → drain pending (new session only)
    → VALID_PRIMARY
```

---

## Meeting dependency

Meeting-triggered destructive transition is an **incidental** recovery path observed in field evidence. **MUST NOT** be a prerequisite for GROUP bootstrap authority recovery.

Normative path:

```text
GROUP STALE → invalidate → bootstrap
```

Not:

```text
GROUP stuck → Start Meeting → Hangup → End Meeting → GROUP repaired
```

---

## Non-goals

- `QUEUED_NO_SESSION` timeout / JOIN retry escalation
- Meeting as implicit fix path
- ADR-0052 / conference admission / recovery serialization changes
- #175 observer projection / `DEGRADED` sticky
- WiFi recovery / RNA-5/6 / completion predicate changes
- Epoch / digest / CTA / unified topology framework (implementation choices for later)
- UI / banner / Sync Channel presentation changes in this ADR

---

## Implementation guidance

Minimal surface agreed in design review:

```text
GroupChannelAuthority (core/session)
    ├── AuthoritySnapshot + AuthorityEvidence
    ├── evaluate() → E1/E2/E3
    ├── commitAuthorityInvalidation()
    └── emission / ingress gates

TalkbackCoordinator seams:
    1. reconcileGroupMeshInternal()
    2. handleGroupJoin()
    3. offerGroupMeshJoin() / reconnectExistingGroupMeshPeers()
    4. dialable / election change → re-evaluate
```

Reuse without change: `resolveBootstrapPrimary` · `meshCallInternal` · `GROUP_INVITE` · `waitingForPrimary` follower path.

**PR title (implementation):** `GROUP bootstrap authority reconciliation after stale session ownership`

### Election / dialable stability (informative — not part of #176 contract)

Failover contract (§ Failover) requires **no fixed N-second absence threshold** for stale detection or bootstrap. Partition / dual-lineage from brief dialable flap is deferred to Phase 3 reconciliation.

The implementation PR **MAY** reuse **existing** election-level dialable / health-directory stability mechanisms (if any) to reduce spurious candidate churn. Such mechanisms:

- **MUST NOT** use `QUEUED_NO_SESSION`, JOIN retry aging, or arbitrary stale timeouts as authority evidence
- **MUST NOT** weaken § Failover single-absence contract for **authority** decisions
- **MUST** be documented separately from #176 stale detection so "election debounce" is not confused with "timeout workaround"

---

## Acceptance / test matrix

| Id | Scenario | Type | Pass criteria |
|----|----------|------|---------------|
| T1 | Symmetric cold start | Integration | `mesh_create→INVITE`; brief QUEUED converges; no STALE |
| T2 | #176 asymmetric stale | Integration | M01 E3→invalidate→INVITE; M02 converges; pending drains on new session |
| T3 | STALE-period JOIN | Unit | No `pendingGroupJoins` growth; `STALE_AUTHORITY_*` decision |
| T4 | Invalidation atomicity | Unit | Post-commit: no old handle; same tick no maintenance JOIN; I-AUTH-1/2/3 |
| T5 | M01 dialable + stale | Integration | M02 does **not** `mesh_create` |
| T6 | M01 ∉ dialable | Integration | M02 may become candidate and bootstrap (no fixed delay) |
| T7 | VALID_PRIMARY ICE reconnect | Regression | Normal `offerGroupMeshJoin` still works |
| T8 | Field negative control | Manual | `group-cold-start-control-20260812-105005` unchanged |
| T9 | Field positive case | Manual | Asymmetric stale → no permanent Sync Channel |
| T10 | Invalidate vs inbound JOIN race | Unit/Integration | After commit: old JOIN → `STALE_AUTHORITY_REJECTED`; new path → `GROUP_INVITE`; no drain of old JOIN |

**T10 is merge-blocking.**

### Field acceptance (post-implementation)

1. Asymmetric session loss → no permanent Sync Channel; no Meeting required
2. Symmetric cold start → no false STALE; no dual bootstrap

---

## Relation to #176 issue

Issue #176 retains RCA evidence and historical discussion. **This ADR is normative** for implementation. Update issue status to reference ADR-0053 and open implementation PR.

---

## Decision log (grill freeze)

| Topic | Decision |
|-------|----------|
| E1 | Authority-holder only; follower `localSessionId == null` → `waitingForPrimary`, not STALE |
| E1/E2/E3 | Independent paths; any one suffices; E3 not exclusive |
| E3 | Combination evidence; `QUEUED_NO_SESSION` alone ≠ STALE |
| STALE invalidate | Required before re-bootstrap (not in-place) |
| `valid_authority_held_anywhere` | Local evidence; candidate-only evaluation |
| `V-peer-booting` | Blocks peers; not self post-invalidation |
| Failover | Single absence from election-level dialable; no N-second threshold |
| JOIN during STALE | Evidence-only; reject; no queue |
| Invalidation | Logical commit sync; physical teardown async |
| Planner axis | `authority_state` not `session != null` |
