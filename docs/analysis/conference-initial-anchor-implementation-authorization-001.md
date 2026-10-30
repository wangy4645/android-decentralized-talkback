# Implementation Authorization: Conference Initial Anchor (Option B)

**ID:** conference-initial-anchor-implementation-authorization-001  
**Date:** 2026-08-21  
**Type:** Implementation Authorization · Bounded  
**Prerequisite:** [conference-initial-anchor-semantics-decision-note.md](./conference-initial-anchor-semantics-decision-note.md) — **ACCEPTED Option B**

---

## Verdict

```text
Decision Note Option B          ACCEPTED
Implementation (this IA)        PASS — IA-1 / IA-2 / IA-3
Desk 4p baseline                PASS (20260821-102308)
P0.1g field SRD isolation       AUTHORIZED — see p01g-field-authorization-001
AnchorRanking changes           NOT AUTHORIZED
Failover policy changes         NOT AUTHORIZED
Ready / Recovery / UI           NOT AUTHORIZED
```

---

## Authorized Scope (Exactly Three Actions)

### IA-1 — `ConferenceInitialAnchorPolicy`

**Goal:** CREATE-phase initial anchor selection only.

**Rule:**

```text
On first Conference MESH → ANCHOR admission:
  candidateAnchorId = initiatorModuleId
```

**Insert seam:**

```text
anchorAdmissionDecisionInputFromSession()
  replace: electAnchorRoles()?.primary
  with:    ConferenceInitialAnchorPolicy.initialAnchorId(initiator, members, phase=CREATE)
```

**Must not:**

- Call `AnchorRanking.elect()` for initial Conference anchor selection
- Apply to GROUP / PTT sessions
- Change `ConferenceAnchorAdmissionPolicy.decide()` ranking/preserve logic
- Change failover path (`publishAnchorFailover`, RUN-phase election)

**Allowed file touch (expected):**

- New: `ConferenceInitialAnchorPolicy.kt` (+ unit tests)
- Minimal: `TalkbackCoordinator.anchorAdmissionDecisionInputFromSession()` wiring

---

### IA-2 — `ANCHOR_DECISION` observability

**Goal:** Auditable CREATE vs RUN anchor decisions.

**Required log line:**

```text
ANCHOR_DECISION
  phase=CREATE|RUN
  initiator=<moduleId>
  candidate=<moduleId>
  currentAnchor=<moduleId>
  reason=INITIAL_ANCHOR_POLICY|FAILOVER_RANKING
  scores=<optional; RUN failover only>
```

**Must not:**

- Change behavior (observation only on authorized paths)
- Replace existing topology publish logs

---

### IA-3 — P0.1g baseline gate (test card update)

**Goal:** Restore P0.1g only after deterministic CREATE topology.

**New gate (replaces `anchor=M01` luck):**

```text
initiator=M01
initialAnchor=M01    (ANCHOR_DECISION phase=CREATE)
currentAnchor=M01    (topology snapshot at test start)
edges=M01→M02,M01→M03,M01→M04
```

**Fail test as ENV_INVALID if any mismatch** — not SRD FAIL.

**P0.1g SRD isolation:** HOLD until one 4p field run passes IA-3 gate with IA-1+IA-2 merged build.

---

## Explicitly Forbidden (Even Under Option B)

```text
- AnchorRanking score / weight changes
- Host override patch: if (host) anchor = host everywhere
- Ban M02/M04 from ever being anchor (failover must remain ranking-driven)
- Merge initiatorModuleId and anchorModuleId fields
- Ready / Health / Recovery / UI workaround for Host≠Anchor
- GROUP election changes
- ADR-0056 topology contract changes
- Timeout / retry / ICE restart enlargement
```

---

## Verification (Before P0.1g Resume)

| Step | Requirement |
|------|-------------|
| Unit | `ConferenceInitialAnchorPolicyTest` — CREATE returns initiator; GROUP path untouched |
| Unit | Existing `ConferenceAnchorAdmissionPolicy` tests still pass |
| Desk | 4p meeting: `ANCHOR_DECISION phase=CREATE candidate=initiator` on M01 host create |
| Desk | Snapshot: `anchorId=M01`, edges star from M01 |
| Gate | IA-3 baseline satisfied on one capture |

---

## Implementation Sequence

```text
1. ConferenceInitialAnchorPolicy + unit tests
2. Coordinator wiring (CREATE candidate only)
3. ANCHOR_DECISION logs (CREATE + existing failover emit RUN)
4. Desk 4p baseline gate
5. Separate authorization: resume P0.1g SRD mutex field
```

---

## Posture

```text
P0.1g:              HOLD → resume only after step 4 PASS
WiFi recovery chain: CLOSED — do not attach
Frozen domains:      Ready, Recovery admission, completion predicate, RNA, UI banner
```
