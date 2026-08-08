# RNA Intent Lifecycle — Observation Hypothesis

**Status:** **DRAFT** · **observation only** · **no runtime authorization** · **no field authorization**  
**Date:** 2026-08-08  
**Parents:** [rna-intent-lifecycle-observation-analysis.md](./rna-intent-lifecycle-observation-analysis.md) · [adr0042-transport-truth-boundary-review.md](./adr0042-transport-truth-boundary-review.md) · [adr0043-checkpoint-close.md](./adr0043-checkpoint-close.md)

---

## Status board

```text
ADR-0043       CLOSED ✅
ADR-0042       REVIEWED ✅
RNA intent     HYPOTHESIS OPEN (this doc)
RNA run        NOT AUTHORIZED
Field          NOT AUTHORIZED
```

---

## Convergence (why this track)

```text
ADR-0043:  membership authorization problem  ❌ (Appendix B PASS)
ADR-0042:  transport truth problem           ❌ (boundary review PASS)
Remaining: RNA intent lifecycle semantics   ✅ (this track)
```

**Not asking:** “为什么没有恢复？” / “M03 多久 ONLINE？”  
**Asking:** “谁拥有把 recovery intent 从 NONE 推向 terminal 的架构权力？”

---

## Scope

```text
In:   observation hypothesis · authority questions · existing log fields
Out:  run card · field authorization · completion predicate change
      handler change · WiFi flap · UI / latency metrics
```

**Gate chain (not yet entered):**

```text
RNA Observation Hypothesis (rna-intent-lifecycle-hypothesis.md)
        ↓
Contract Review (rna-intent-lifecycle-contract-review.md) ← COMPLETE
        ↓
（必要时）Directed Observation Run Card — NOT AUTHORIZED
```

---

## Anchor facts (Appendix B, M02→M03)

```text
19:00:56.798  DEFERRED_INTENT_CREATED intentId=R1 gateBlock=OFFER_AWAITING_ANSWER state=CREATED
19:00:56.935  uncoveredIntent=true · intentTerminal=NONE · SYNC_PENDING
19:01:06.804  NEGOTIATION_RECOVERY_FACT terminalState=EXPIRED reason=NEGOTIATION_BUDGET_EXHAUSTED
19:01:06.805  DEFERRED_INTENT_SUPERSEDED R1 → SUPERSEDED (never EXECUTED)
```

Media / control / membership had converged before SYNC_PENDING. Intent `R1` was created but blocked; terminal arrived ~10s later via budget exhaustion, not execution.

---

## H1 — Intent owner

**Question:** Who creates this obligation, and who owns its lifecycle?

**Observed:**

```text
uncoveredIntent=true
intentTerminal=NONE
DEFERRED_INTENT_CREATED intentId=R1
```

**Candidate loci (hypothesis only — no selection):**

| Candidate | Role (from code/docs) | Observe via |
|-----------|----------------------|-------------|
| `DeferredIntentAuthority` | ADR-0022 §E.16.1 — sole owner of deferred-intent transitions | `DEFERRED_INTENT_*` logs |
| `ConferenceEdgeRecoveryController` | Creates intent, arms fence, bridges supersede | `DEFERRED_INTENT_CREATED` · `RECOVERY_*` |
| Negotiation layer | Terminal facts via `NEGOTIATION_RECOVERY_FACT` | `intentId` · `terminalState` · `closeSource` |
| `CompletionObservationProjection` | Reads `uncoveredIntent` for completion wait | `RECOVERY_EPISODE_OBSERVATION` |
| Membership convergence | GROUP_RESYNC / digest — **not** intent owner | exclude from H1 primary |

**H1 observation target:**

```text
intentId lineage:
  createdAt · createdBy (domain) · target edge · parent/supersede relation
```

Existing fields (no new instrumentation):

```text
DEFERRED_INTENT_CREATED
DEFERRED_INTENT_AUTHORITY_REGISTERED
DEFERRED_INTENT_HELD / DRAIN_RETRY / EXECUTED / SUPERSEDED
iceRestartIntentId on edge record
```

---

## H2 — Terminal transition

**Question:** What event should move intent from `NONE` toward a terminal outcome?

**Observed gap:**

```text
intentTerminal=NONE          (RECOVERY_EDGE_STATE projection)
DEFERRED_INTENT state=CREATED → SUPERSEDED   (authority record)
NEGOTIATION_RECOVERY_FACT terminalState=EXPIRED
```

**Hypothesis space (not decided):**

```text
NONE
  |
  +--> COVERED / EXECUTED     (intent obligation satisfied)
  |
  +--> SUPERSEDED / EXPIRED   (lineage replaced or budget)
  |
  +--> CANCELLED              (explicit discard)
  |
  +--> WAITING_FOREVER        (architecture defect — must rule out)
```

**H2 observation target:** state transition chain for `intentId=R1`:

```text
CREATED → ? → terminal
```

Watch for:

```text
DEFERRED_INTENT_HELD
DEFERRED_INTENT_DRAIN_RETRY / DRAIN_ATTEMPT / REPROBE_RESULT
DEFERRED_INTENT_EXECUTED
DEFERRED_INTENT_SUPERSEDED
RECOVERY_NEGOTIATION_INTENT_TERMINAL (if present)
NEGOTIATION_RECOVERY_FACT
```

**Do not** change completion predicate to force terminal. First confirm whether terminal authority exists and fires.

---

## H3 — Close authority

**Question:** Who may declare `coveredIntent=true` (obligation satisfied)?

**Observed:**

```text
media recovered      ✅
control recovered    ✅
membership recovered ✅
uncoveredIntent      ❌ (until authority acts)
```

**Must preserve separation:**

```text
Evidence:        recovery facts occurred
Intent closure:  obligation declared satisfied
```

**Forbidden collapse (again):**

```text
media recovered  ≠  intent complete
control reconciled ≠ intent covered
GROUP_RESYNC accepted ≠ intent terminal
```

**H3 candidate close authorities (hypothesis only):**

| Actor | May close? | Evidence |
|-------|------------|----------|
| `DeferredIntentAuthority.markExecuted` | EXECUTED path | `DEFERRED_INTENT_EXECUTED` |
| Negotiation terminal writer | EXPIRED / terminal fact | `NEGOTIATION_RECOVERY_FACT` |
| `CompletionPolicy` | **Reads** uncovered — does not create cover | `RECOVERY_COMPLETION_DECISION` |
| Media / transport layers | **Must not** | ADR-0042 INV-T4 |

**H3 observation target:** identify the **last component that could emit** `covered` / `EXECUTED` / non-`UNCOVERED` completion — and whether it fired for `R1`.

If no component produces cover → **ownership gap** (deeper than transport/membership).

---

## What to observe (when authorized later)

### Intent lineage

```text
intentId · attemptId · obligationGen
DEFERRED_INTENT_CREATED / REGISTERED / HELD / EXECUTED / SUPERSEDED
gateBlock · requestingDomain · terminalReason
```

### State transition

```text
authority ExecutionState: CREATED → HELD_DISPATCH → EXECUTED | SUPERSEDED
edge projection: intentTerminal field vs authority record alignment
```

### Authority evidence

```text
Who emitted terminal fact?
Who could have emitted EXECUTED but did not?
uncoveredIntent flip: true → false (if ever)
```

---

## Explicit non-observations

```text
❌ WiFi flap protocol
❌ recovery success rate
❌ latency / M03 ONLINE time
❌ UI syncing duration
❌ completion SLA
❌ handler / predicate / timeout changes
```

---

## Preliminary hypothesis (DRAFT — not adjudicated)

From Appendix B single episode:

```text
Intent R1 was created (OFFER_AWAITING_ANSWER gate block)
        ↓
Never reached EXECUTED
        ↓
Completion blocked on DEFERRED_INTENT_UNCOVERED while intentTerminal=NONE
        ↓
~10s later: NEGOTIATION_BUDGET_EXHAUSTED → SUPERSEDED
```

**Open:** Is `intentTerminal=NONE` on `RECOVERY_EDGE_STATE` a projection lag, or missing bridge from `DeferredIntentAuthority` / negotiation terminal to edge observation?

**Not concluded:** bug vs expected RNA-5 budget behavior vs missing drain path.

---

## Next step (not this doc)

```text
Resolve H1–H3 from existing logs + code contracts
        ↓
If gaps remain → Directed Observation Run Card (separate authorization)
```

**Do not** open run card until authority questions have named observables.

---

## One-line statement

> RNA hypothesis: intent `R1` was created and blocked — who owns cover/terminal, and does any authority emit `covered` before budget expiry?
