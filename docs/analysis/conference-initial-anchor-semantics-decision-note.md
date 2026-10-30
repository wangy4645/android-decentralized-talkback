# Decision Note: Conference Initial Anchor Semantics

**ID:** conference-initial-anchor-semantics-decision-note-001  
**Date:** 2026-08-21  
**Type:** Decision Note · Product semantics  
**Related:** [ADR-0056](../adr/0056-conference-topology-first.md) (topology authority; orthogonal CREATE ownership)

---

## Status

**ACCEPTED — Option B** (2026-08-21)

**Decision owner:** Product semantics  
**Architecture recommendation:** Option B (aligned with acceptance)

**Implementation:** NOT AUTHORIZED by this note — see [conference-initial-anchor-implementation-authorization-001.md](./conference-initial-anchor-implementation-authorization-001.md)

---

## Q1 — Product Decision

**Question:** 用户点击「开会」后，是否接受第一次连接媒体的人可能不是点击开会的人？

**Answer:** **否**

**Accepted semantics:**

```text
CREATE:
  Initial Anchor = Initiator (initiatorModuleId)

RUN:
  Current Anchor maintained until health/failure
       |
       v
  AnchorRanking failover → new Current Anchor
```

**Not accepted:** `Host forever Anchor` / `anchor = host` hard binding for all time.

---

## Context (Frozen Facts)

Current Conference path:

```text
First MESH → ANCHOR admission
        |
        v
candidateAnchorId = AnchorRanking.elect(members)
        |
        v
ConferenceAnchorAdmissionPolicy → Current Anchor
```

This allows `initiatorModuleId != anchorModuleId`. Observed field samples (deterministic ranking, not defect):

| Session | Host | Anchor | Log dir |
|---------|------|--------|---------|
| `30a3850e…` | M01 | M02 | `logs/p01g1-srd-mutex-20260821-092521` |
| `f33ffca4…` | M01 | M04 | `logs/p01g1-srd-mutex-20260821-093830` |

Cause: `AnchorRanking.elect()` over HELLO health snapshot (online tenure, charging, battery). **Not a ranking bug.**

---

## Problem Statement

Two lifecycle questions share one election entry today:

| Question | Appropriate mechanism |
|----------|----------------------|
| Who owns first media establishment at Conference CREATE? | **Initial Anchor policy** (was missing) |
| Who is best relay after failure? | **AnchorRanking** (existing) |

Mixing them destabilizes P0.1g / SRD isolation baselines and conflicts with user create-meeting semantics.

---

## Role Definitions

| Role | Field / concept | Responsibility |
|------|-----------------|----------------|
| **Initiator** | `initiatorModuleId` | Create, invite, UI owner |
| **Initial Anchor** | Lifecycle concept (no separate persistent field required v1) | First media owner at CREATE admission |
| **Current Anchor** | `anchorModuleId`, `snapshot.anchorId` | Runtime relay / realization owner |

**Timing (Option B):** Initial Anchor is selected at **first MESH → ANCHOR admission** (threshold met), not at `SESSION_CREATED` solo-MESH instant.

---

## Decision — Option B

### CREATE phase

At first Conference MESH → ANCHOR admission:

```text
candidateAnchorId = initiatorModuleId
```

Do **not** call `AnchorRanking.elect()` for initial selection.

### RUN phase

Unchanged:

```text
preservedAnchor() while healthy
failover → AnchorRanking.elect()
```

### GROUP / PTT

**Out of scope.** GROUP keeps dynamic election / ranking-first semantics.

---

## Out of Scope (This Decision)

- `AnchorRanking` algorithm (score formula)
- Failover thresholds and health predicates
- Recovery, Ready, Health, media realization execution
- ADR-0056 topology contract changes
- Merging `initiatorModuleId` and `anchorModuleId` fields

---

## Engineering Consequences

### If Option B (accepted)

- P0.1g baseline gate (after impl): `initiator = initialAnchor = currentAnchor = M01`
- SRD isolation tests media execution, not election variance

### Prior gate retired

```text
host=M01 && anchor!=M01 => INVALID   (P0.1g pre-decision)
```

Replaced after implementation by explicit initial-anchor gate (see IA doc).

---

## Observability Requirement (Definition Only)

Log family (name fixed in IA):

```text
ANCHOR_DECISION
  phase=CREATE|RUN
  initiator=
  candidate=
  currentAnchor=
  reason=INITIAL_ANCHOR_POLICY|FAILOVER_RANKING
  scores=          (RUN / failover only)
```

Implementation authorized separately.

---

## Code Mapping Appendix

```text
Today:
  anchorAdmissionDecisionInputFromSession()
    candidateAnchorId = electAnchorRoles()?.primary
                        = AnchorRanking.elect(members, anchorHealthMap())

After Option B (CREATE only):
  candidateAnchorId = ConferenceInitialAnchorPolicy.decide(...)
                    = initiatorModuleId   (first MESH→ANCHOR admission)

Unchanged:
  AnchorRanking, preservedAnchor(), publishAnchorFailover(),
  ConferenceTopologyAuthority, ActualMediaEdgeSet
```

---

## Current Track State

```text
Decision Note:     ACCEPTED — Option B
P0.1g:             HOLD until IA complete + baseline gate
Implementation:    See IA-001 (authorized, bounded)
```

---

## Appendix A — Field Evidence (Informative)

Observed:

```text
Session 30a3850e: host=M01, anchor=M02
Session f33ffca4: host=M01, anchor=M04
```

Cause: `AnchorRanking.elect()` with differing HELLO health inputs.

Conclusion:

```text
Current behavior is deterministic protocol output.
Not a ranking defect.
Product chose Option B to separate CREATE ownership from RUN election.
```
