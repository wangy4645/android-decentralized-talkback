# ADR-0055: Conference Recovery Outcome Convergence

## Status

**ACCEPTED** (2026-08-13) · **Issue grill G1–G4 CLOSED** · **Acceptance grill Track P + Track O CLOSED** · **Implementation NOT AUTHORIZED**

**Issue:** [#188](https://github.com/wangy4645/android-decentralized-talkback/issues/188)

**Relation:**

- [#175](https://github.com/wangy4645/android-decentralized-talkback/issues/175) / [ADR-0054](./0054-conference-edge-recovery-liveness-ownership.md) — **CLOSED** · liveness reclaim · **orthogonal; do not reopen**
- [#187](https://github.com/wangy4645/android-decentralized-talkback/issues/187) — **CLOSED** · peer coordination wait · **orthogonal; do not reopen**
- [ADR-0046](./0046-successor-admission-terminal-convergence-contract.md) — successor admission vocabulary · **related; does not subsume pre-close outcome**
- [ADR-0045](./0045-post-obligation-failed-media-residency-clear-admission.md) — residency clear · **orthogonal**
- WiFi recovery main chain — **CLOSED**

```text
ADR-0055
Decision:              ACCEPTED (Track P A/A/D · Track O A/A/D)
Model:                 Two-track outcome contract under one umbrella (G1=C)
Primary evidence:      talkback/logs/187-coordination-field-20260813-211638
G3 directed evidence:  talkback/logs/188-g3-directed-20260813-215937 (G3=B)
Acceptance grill:      #188 CLOSED
Implementation:        NOT AUTHORIZED (field run cards required per track)
Does NOT reopen:       ADR-0054 · #187 · UVCP · completion predicate
Does NOT authorize:    UI-as-PASS · retry count tuning · always SUPERSEDE · cross-edge DEGRADED clear
```

---

## Context

After #175 (liveness reclaim) and #187 (coordination wait), recovery **starts correctly**, but **outcome** often fails to converge on participant and observer edges.

Symptoms (`SYNCING`, `DEGRADED`) are **downstream projections**. This ADR owns **recovery outcome** only.

**Core requirement (both tracks):** an active edge recovery attempt MUST NOT silently disappear, and MUST NOT churn indefinitely without terminal disposition. Recovery success is **not** guaranteed.

---

## Frozen issue grill (#188 G1–G4)

| # | Lock | Meaning |
|---|------|---------|
| **G1** | **C** | Single outcome-convergence contract (participant + observer) |
| **G2** | **D** | Primary **B** (bounded attempts); evidence **A**; symptoms **C** excluded |
| **G3** | **B** | Observer edge-local; not peer-`EDGE_RECOVERED`-dependent |
| **G4** | **D** | Field PASS inherits G2 layering |

---

## Acceptance grill — **CLOSED**

### Track P — Participant (post-#187 wait expiry)

| Q | Lock | Decision |
|---|------|----------|
| **P-Q1** | **A** | Owner: **`ConferenceEdgeRecoveryController`** on local participant edge |
| **P-Q2** | **A** | Each post-expiry episode MUST reach **terminal disposition** (success or explicit failure); unbounded `SUPERSEDE` without disposition is a violation |
| **P-Q3** | **D** | Minimum audit: named **`RECOVERY_DECISION`** + **`attemptTerminal=true`** + **`ATTEMPT_SUCCEEDED` \| `ATTEMPT_FAILED`** before successor `SUPERSEDE` |

**Required audit chain:**

```text
attempt N
  → terminal RECOVERY_DECISION
  → attemptTerminal=true
  → ATTEMPT_SUCCEEDED | ATTEMPT_FAILED
  → (only then) SUPERSEDE / attempt N+1
```

### Track O — Observer (local terminal timeout)

| Q | Lock | Decision |
|---|------|----------|
| **O-Q1** | **A** | Owner: **`ConferenceEdgeRecoveryController`** on observer edge `(observer → peer)` (G3=B) |
| **O-Q2** | **A** | **Timeout cannot be the last event** before obligation close; named outcome path or explicit terminal failure required |
| **O-Q3** | **D** | Minimum audit: post-timeout **`RECOVERY_DECISION`** (successor armed or explicit failure) **before** `OBLIGATION_CLOSED`, plus contiguous **`POST_OBLIGATION_CLOSE_EVAL` → `POST_CLOSE_ADMISSION_DECISION`** |

**Required chain (no silent drift):**

```text
ATTEMPT_TIMEOUT
  → named RECOVERY_DECISION
  → successor recovery armed OR explicit terminal failure
  → POST_OBLIGATION_CLOSE_EVAL
  → POST_CLOSE_ADMISSION_DECISION
```

**Forbidden:**

```text
ATTEMPT_TIMEOUT → WAIT → OBLIGATION_DEADLINE → NO_ADMISSION → DEGRADED
(with no auditable outcome owner after timeout)
```

---

## Decision 1 — Shared owner (both tracks)

Normative owner for outcome convergence on conference edge `(localModule → remoteModule)`:

**`ConferenceEdgeRecoveryController`** on the local module.

- **`TalkbackCoordinator`** MAY emit reachability / coordination facts (#187); it does **not** own post-expiry or post-timeout outcome enforcement.
- Post-close admission policy ([ADR-0046](./0046-successor-admission-terminal-convergence-contract.md)) participates in **Track O audit chain**; it does **not** replace pre-close outcome ownership.

---

## Decision 2 — Track P contract (participant)

After #187 coordination wait expires, on the local participant edge while obligation remains open:

1. Each post-expiry recovery episode MUST reach a **terminal disposition** — `EDGE_RECOVERED` / explicit terminal success **or** explicit terminal failure (e.g. `ATTEMPT_TIMEOUT`).
2. **Unbounded** `SUPERSEDE` / new attempts **without** terminal disposition of the prior attempt is a **contract violation**.
3. Before successor `SUPERSEDE`, the Controller MUST emit auditable evidence per **P-Q3=D** (decision + attemptTerminal + attempt state).

This ADR does **not** define retry counts, supersede caps, or ICE algorithms.

---

## Decision 3 — Track O contract (observer)

After local terminal attempt failure on an active observer edge (G3=B — edge-local; no peer-`EDGE_RECOVERED` gate):

1. **`ATTEMPT_TIMEOUT` MUST NOT be the last meaningful event** before obligation close.
2. Before `OBLIGATION_CLOSED`, the Controller MUST produce a named **`RECOVERY_DECISION`** documenting **either** a successor recovery path armed (edge-local material evidence) **or** explicit terminal failure.
3. The post-obligation chain **`POST_OBLIGATION_CLOSE_EVAL` → `POST_CLOSE_ADMISSION_DECISION`** MUST remain **causally contiguous** with no silent gap (O-Q3=D).

G3=B locks out cross-edge terminal-success dependency; it does **not** identify local RCA (retry, admission, inbound, ICE, obligation policy).

---

## Outcome observability (G2 / G4 — field gates)

| Layer | Signal | Role |
|-------|--------|------|
| **Primary (B)** | Obligation-bounded lifecycle; no unbounded churn without terminal disposition | PASS/FAIL authority |
| **Evidence (A)** | `EDGE_RECOVERED` or explicit terminal failure on affected edge | Strong evidence; not sole gate |
| **Symptoms (C)** | `SYNCING` / `DEGRADED` | **Excluded** from primary gate |

---

## Invariants

```text
INV-188-1  #187 coordination wait frozen; outcome owner does not redefine forbidden SUPERSEDE
INV-188-2  ADR-0054 liveness reclaim frozen; outcome owner does not replace POST_TERMINAL facts
INV-188-3  G3=B: observer outcome MUST NOT require peer EDGE_RECOVERED
INV-188-4  UI projection MUST NOT be primary contract authority
INV-188-5  Track P and Track O MAY be implemented in separate phases
INV-188-6  Active recovery attempts MUST NOT silently disappear (Track O)
INV-188-7  Active recovery attempts MUST NOT infinite-churn without terminal disposition (Track P)
```

---

## Implementation authorization

**NOT AUTHORIZED** until:

1. Per-track field run cards with G4 gates exist.
2. Implementation stays within Track P / Track O contracts above.
3. No merge with #187 / ADR-0054 protected domains without ADR amendment.

---

## References

- Issue: [#188](https://github.com/wangy4645/android-decentralized-talkback/issues/188)
- Acceptance grill close: [#188#issuecomment-5281491324](https://github.com/wangy4645/android-decentralized-talkback/issues/188#issuecomment-5281491324) onward
- G3=B: `talkback/logs/188-g3-directed-20260813-215937`
- #187 field: `talkback/logs/187-coordination-field-20260813-211638`
- [ADR-0054](./0054-conference-edge-recovery-liveness-ownership.md)
