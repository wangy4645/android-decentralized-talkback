# ADR-0054: Conference Edge Recovery Liveness Ownership

## Status

**PROPOSED** (2026-08-13) · **Grill Q1–Q9 CLOSED** · **Implementation NOT AUTHORIZED**

**Issue:** [#175](https://github.com/wangy4645/android-decentralized-talkback/issues/175) — conference cross-edge recovery / observer-edge failure (remaining after #186)

**Relation:**

- [#186](https://github.com/wangy4645/android-decentralized-talkback/pull/186) / `67408bd` **MERGED** — ADR-0052 offerer admission READY regression **closed**
- [ADR-0052](./0052-conference-transport-scope-admission-recovery-gate.md) — admission / scope gate (**orthogonal** to this ADR)
- [ADR-0045](./0045-post-obligation-failed-media-residency-clear-admission.md) — post-obligation residency clear (**orthogonal**; do not reopen)
- [ADR-0046](./0046-successor-admission-terminal-convergence-contract.md) — successor admission after `ADMIT_SUCCESSOR` (**related**; does not answer pre-close liveness gap)
- [ADR-0022](./0022-recovery-completion-ownership.md) — obligation episode / R28-I WAITING ownership (**parent vocabulary**)
- WiFi recovery main chain — **CLOSED**; do not merge membership / RNA / completion predicate changes here

```text
ADR-0054
Decision:              PROPOSED (ownership grill Q1–Q9 CLOSED)
Model:                 Conference edge recovery liveness ownership
Primary evidence:      talkback/logs/175-offerer-admission-stepb-20260813-202014
                       session 532709ad-6123-4f95-88c6-e1b7f077aec8
                       build main @ #186 merge (67408bd)
Implementation:        NOT AUTHORIZED (no impl plan in this ADR)
Does NOT reopen:       ADR-0052 admission regression · UVCP · completion predicate · RNA · membership
Does NOT authorize:    HELLO stale tuning · signaling-must-drop · observer-only patch · always SUPERSEDE
```

---

## Context

After #186, host admission regression is closed on field Step B:

```text
M01 CONFERENCE_ADMISSION_PHASE peer=M02 phase=READY
M01 RECOVERY_DECISION approved=true
zero lifecycle_not_established
M01/M02 participant UI/media recovered after M02 WiFi flap
```

**#175 remaining** is isolated to the **M03 observer edge** `(M03 → M02)` on the same session.

### Primary evidence timeline (M03 log, strict)

Log: `talkback/logs/175-offerer-admission-stepb-20260813-202014`

```text
20:21:53 RECOVERY_EDGE_STARTED attempt=1
20:22:09 RECOVERY_ATTEMPT_TIMEOUT / ATTEMPT_TIMEOUT
20:22:09 FAILED_MEDIA_RECOVERY (attempt_timeout)
20:22:10 finalPresence=DEGRADED (reachability probe)
20:22:39 RECOVERY_OBLIGATION_CLOSED reason=OBLIGATION_DEADLINE
20:22:39 RECOVERY_POST_CLOSE_ADMISSION_DECISION decision=NO_ADMISSION reason=edge_unsatisfied
no attempt=2 on M03→M02
no SUPERSEDE on M03→M02
```

Failure surface under observation:

```text
attempt=1
  → ATTEMPT_TIMEOUT
  → FAILED_MEDIA_RECOVERY
  → (peer M02 participant-side recovery progresses on other edges)
  → no attempt=2 / SUPERSEDE on M03→M02
  → OBLIGATION_CLOSED
  → POST_CLOSE_ADMISSION NO_ADMISSION
  → DEGRADED
```

### Cross-edge time-bounded fact (not causal)

At `20:22:12`, **M01→M02** logged `REATTACH_ACCEPTED` and `attempt=2` (`RECOVERY_DECISION … SUPERSEDED`).

On the same session, **M03→M02** had **no** observable successor recovery before obligation close at `20:22:39`.

This establishes **divergent recovery progression across edges**, not that peer restoration must trigger observer-edge retry.

### Proven vs not proven

| Proven | Not proven / not authorized |
|--------|------------------------------|
| Post-close produced `NO_ADMISSION` with no successor on M03→M02 | That peer media restore **must** be the successor trigger |
| M03 attempt=1 `ATTEMPT_TIMEOUT` while obligation stayed open until deadline | Implementation of the new trigger |
| Host admission regression closed (#186); M03 DEGRADED is remaining #175 | Always SUPERSEDE / retry on this fact |

---

## Grill question (single ownership axis)

> **After a participant-side recovery attempt fails because its peer is temporarily unavailable, who owns reclaiming recovery liveness when the peer becomes reachable again?**

Concrete instance (#175 Step B):

> **M03's recovery attempt on edge M03→M02 failed; M02 later recovered on other edges. Who is responsible for giving M03→M02 a new recovery opportunity?**

---

## Decision 1 — Active edge MUST have reclaim liveness (Q1)

An observer/participant conference edge that has entered recovery (`RECOVERY_EDGE_STARTED`, admission approved) **MUST** have a mechanism to reclaim recovery liveness after an attempt fails while the peer is temporarily unavailable.

M03→M02 Step B satisfies the premise; terminal DEGRADED without reclaim is a contract gap, not expected observe-only behavior.

---

## Decision 2 — Liveness owner is local edge controller (Q2)

Normative owner: **`ConferenceEdgeRecoveryController`** on the local module for edge `(localModule → remoteModule)`.

Coordinator **MAY** feed reachability facts; topology/mesh **MAY** produce triggers. Neither owns admission/recovery lifecycle. Reattach events are outcomes under this owner, not a separate owner class.

---

## Decision 3 — Primary gap is reachability fact delivery (Q3)

Step B proves M03→M02 received **no** `REMOTE_MODULE_RECOVERED` / `WAKEUP_FIRED` / open-obligation `RECOVERY_REEVALUATE` during `20:22:09–20:22:39`.

**Proven:** reachability fact did not reach the edge controller re-evaluate surface.

**Not proven:** controller would fail to re-evaluate if the fact arrived (M01→M02 same session shows the open-obligation path works).

```text
peer becomes reachable
        ↓
??? missing (Q4)
        ↓
REMOTE_MODULE_RECOVERED
        ↓
RECOVERY_REEVALUATE
        ↓
attempt=2
```

---

## Decision 4 — Dual trigger-path gap; HELLO stale primary (Q4)

M03 lacks **both** recovery-reachability trigger paths during the open obligation:

1. **HELLO stale→recovered (primary asymmetry):** M01 had `peer_edge_stale:M02` → `Remote module recovered: M02` → `REMOTE_MODULE_RECOVERED`. M03 received continuous M02 HELLO (`20:22:01–20:22:13`); signaling never became stale → `onRemoteModuleRecovered` never fired.
2. **Conference ICE / ROUTE_CONVERGED (secondary):** M03→M02 conference ICE did not re-CONNECT before obligation close → no `ROUTE_CONVERGED` backup trigger.

**Excluded:** Coordinator delivery filter (D) — no fact-production logs on M03 after timeout; gap is at production/binding, not post-delivery suppression.

**Cross-plane gap (field observation, not implementation prescription):**

```text
Media edge DOWN
      ↓
HELLO signaling STILL UP
      ↓
stale/recovered does not fire
      +
conference ICE does not reconnect
      ↓
no REMOTE_MODULE_RECOVERED
      ↓
no RECOVERY_REEVALUATE
      ↓
active edge — no reclaim
```

**Forbidden premature conclusions (Q4):**

```text
HELLO must disconnect to force recovery          ← NOT authorized
Tune moduleStaleMs to force stale transition     ← NOT authorized
```

---

## Decision 5 — Edge-scoped recovery reachability trigger required (Q5)

When **all** of the following hold on a conference edge:

```text
active recovery edge (entered lifecycle)
obligation open
media edge FAILED (e.g. FAILED_MEDIA_RECOVERY / attempt terminal)
```

…the architecture **MUST** provide an **edge-scoped recovery reachability trigger** that can re-open recovery evaluation **without** requiring:

- HELLO stale→recovered transition, or
- conference ICE CONNECTED / ROUTE_CONVERGED.

This is a **cross-plane reachability/liveness contract** for all active conference edges — not an observer-only patch and not a HELLO stale threshold change.

**State space (repeatable):** signaling alive + media dead + open obligation → existing dual triggers silent → no reclaim.

---

## Decision 6 — Coordinator produces the fact; Controller does not invent it (Q6)

```text
Coordinator
  │  edge-scoped recovery-reachability fact
  ▼
onRecoveryReachabilityChanged  (existing R28-G seam)
  ▼
ConferenceEdgeRecoveryController
  │  re-evaluate / supersede
  ▼
recovery attempt
```

- **Fact producer:** Coordinator
- **Liveness owner:** `ConferenceEdgeRecoveryController`
- Controller **MUST NOT** scan/invent the fact
- HELLO semantics **MUST NOT** be extended
- Conference ICE CONNECTED **MUST NOT** be the only trigger

---

## Decision 7 — Materiality is first post-terminal dispatch-capable observation (Q7)

After the current attempt is **terminal**, the **first** Coordinator observation that the same edge still has `canDispatchRecoverySignal=true` **is** a material recovery-reachability fact.

Latch (anti busy-loop):

```text
(obligationGen, attemptId)
        │
        ├─ first eligible observation → emit fact
        │
        ├─ subsequent HELLO / ICE / mesh events → suppress
        │
        └─ new attempt OR obligation close → reset
```

This covers signaling-always-healthy (no `false→true` required). It does **not** require every HELLO to start recovery.

**Q7 does not decide** the Controller outcome after the fact arrives (see Decision 8).

```text
terminal attempt
    ↓
first material reachability observation
    ↓
RECOVERY_REACHABILITY_CHANGED  (R28-G seam)
    ↓
Controller re-evaluate
    ↓
named SUPERSEDE | WAIT_FOR_INBOUND
```

---

## Decision 8 — Named decision required; silent FAILED_MEDIA stall forbidden (Q8)

On a material recovery-reachability fact, the Controller **MUST** produce a **named** recovery decision:

```text
material reachability fact
        ↓
Controller re-evaluate
        ↓
┌──────────────────────────────┐
│ SUPERSEDE                    │ → attempt N+1
│            OR                │
│ WAIT_FOR_INBOUND             │ → next-action owner + wakeup binding required
└──────────────────────────────┘
```

**Forbidden:**

```text
material fact → FAILED_MEDIA → (nothing) → OBLIGATION_DEADLINE
```

`WAIT_FOR_INBOUND` is legal (M01 inbound/reattach path). It **MUST** name who will wake the edge. `lastWakeup=NONE` after this fact is a contract violation.

This is a **liveness contract**, not “always retry”.

---

## Decision 9 — WAIT_FOR_INBOUND owner / wakeup binding is edge-local and verifiable (Q9)

`WAIT_FOR_INBOUND` next-action owner and wakeup binding **MUST** bind to the same `(sessionId, remoteModuleId)` edge. A later trigger **MUST** match that binding (`hasDeferredWakeupForTrigger(edge, trigger) == true`).

**Allowed:** M03→M02 waits for a named M02 inbound / reachability trigger **on that edge**.

**Forbidden:**

- Treating M01→M02 recovery as wakeup of M03→M02
- Owner log string without a matchable trigger binding
- Module-global / cross-edge wakeup as this edge’s liveness

Acceptance shape (contract, not implementation):

```text
WAIT_FOR_INBOUND
    ↓
owner = X
wakeupBinding = edge(sessionId, remoteModuleId, trigger)
    ↓
trigger occurs
    ↓
hasDeferredWakeupForTrigger(edge, trigger) == true
    ↓
RECOVERY_REEVALUATE
```

---

## Converged contract (Q1–Q9)

> **An active conference edge MUST have a verifiable liveness owner for the entire recovery obligation.**

```text
active edge
  → ConferenceEdgeRecoveryController (owner)
  → Coordinator edge-scoped reachability fact
  → first post-terminal canDispatch observation (latched)
  → named SUPERSEDE | WAIT_FOR_INBOUND
  → WAIT_FOR_INBOUND binding is edge-local and verifiable
```

**Next:** a separate implementation plan **after** this ADR is accepted. This document does **not** authorize code.

---

## Candidate owners (resolved by Q2)

**Selected:** 1. Local `ConferenceEdgeRecoveryController`.

Rejected: 2 topology (facts only) · 3 post-close successor (too late for open obligation) · 4 reattach (mechanism, not owner) · 5 out of scope (Q1 rejected).

---

## Non-goals (frozen for this ADR)

- UVCP / presentation projection changes
- ADR-0052 admission gate rework
- Completion predicate / `markRecovered()` / ADR-0038 admission changes
- RNA-5/6 / membership / owner-conflict (ADR-0039)
- WiFi recovery directed runs / timeout budget tuning
- Implementation design / code / retry policy (separate plan after acceptance)

**Forbidden shortcuts:**

```text
INTENT_TERMINAL != RECOVERY_COMPLETED
peer UI ONLINE != observer-edge recovery evidence
host admission READY != observer-edge liveness
M01 attempt=2 != proof M03 must retry
```

---

## Grill decisions (closed)

| Q | Question | Decision | Date |
|---|----------|----------|------|
| Q1 | Is M03→M02 an active edge that must have reclaim liveness? | **A — yes** (entered recovery lifecycle; not observe-only) | 2026-08-13 |
| Q2 | Normative liveness owner among candidates 1–4? | **1 — local `ConferenceEdgeRecoveryController`** | 2026-08-13 |
| Q3 | Within owner=1, primary gap? | **1 — reachability fact not delivered** | 2026-08-13 |
| Q4 | Why no reachability fact during open obligation? | **C — A+B both missing; A (HELLO stale) primary** | 2026-08-13 |
| Q5 | Independent trigger when signaling healthy + media dead? | **A — yes, edge-scoped; all active conference edges** | 2026-08-13 |
| Q6 | Who produces the edge-scoped reachability fact? | **A — Coordinator via existing `onRecoveryReachabilityChanged`** | 2026-08-13 |
| Q7 | Materiality of the new fact | **C — first post-terminal `canDispatchRecoverySignal=true`; latch per (obligationGen, attemptId)** | 2026-08-13 |
| Q8 | Controller outcome after the fact | **D — named decision required: SUPERSEDE or WAIT_FOR_INBOUND (owner + wakeup binding); silent FAILED_MEDIA stall forbidden** | 2026-08-13 |
| Q9 | WAIT_FOR_INBOUND owner / wakeup binding | **A — must be edge-local and verifiable** | 2026-08-13 |

**Q1 rationale (frozen):** `approved=true → RECOVERY_EDGE_STARTED → RECOVERY_REATTACH → ACK → ATTEMPT_TIMEOUT` proves active edge, not observe-only.

**Q2 rationale (frozen):** Topology (2) supplies facts only; post-obligation successor (3) is too late for open-obligation window; reattach (4) is mechanism not owner.

**Q3 rationale (frozen):** M03 had no `REMOTE_MODULE_RECOVERED` / `REEVALUATE` in the open window; cannot prove controller would fail if fact arrived.

**Q4 rationale (frozen):** Dual path silent; HELLO never stale on M03 is the primary asymmetry vs M01. Do not retune stale / require HELLO drop.

**Q5 rationale (frozen):** Repeatable state space `signaling alive + media dead + open obligation`. Applies to all active conference edges.

**Q6 rationale (frozen):** R28-G split — Coordinator writes facts; Controller consumes.

**Q7 rationale (frozen):** Signature change / dispatch rising-edge miss Step B. Per-HELLO emission would busy-loop. Latch = one fact per terminal attempt.

**Q8 rationale (frozen):** Must not force SUPERSEDE on every edge. Must not allow unnamed stall until deadline.

**Q9 rationale (frozen):** Cross-edge / string-only owner cannot be audited. Binding must match later trigger on the same edge.

## Grill log

**CLOSED** (Q1–Q9). No further ownership questions in this ADR.

---

## References

- Field log: `talkback/logs/175-offerer-admission-stepb-20260813-202014`
- Issue comment (evidence split): [#175#issuecomment-5280376608](https://github.com/wangy4645/android-decentralized-talkback/issues/175#issuecomment-5280376608)
- PR #186 merge: `9a93338`
- `ConferenceEdgeRecoveryController.kt`, `PostObligationCloseConvergence.kt`
- Issue #175 tracker comment (2026-08-13)
