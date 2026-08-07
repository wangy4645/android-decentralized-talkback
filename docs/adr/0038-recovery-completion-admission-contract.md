# ADR-0038: Recovery Completion Admission Contract

## Status

**Status:** ACCEPTED (2026-08-05) — DESIGN FREEZE + [Clarification Amendment](./0038-recovery-completion-admission-clarification-amendment.md) (PR-RCA38-A, docs only)

Admission contract only. **No predicate implementation** in this ADR.

**Prerequisites (closed):**

| Layer | Status |
|-------|--------|
| ADR-0036 Membership / RCA-0036 | VERIFIED / RE-SIGNED |
| ADR-0037 RNA-5 Intent Terminal | FROZEN + FIELD VERIFIED |
| Gate 3C | CLOSED |
| RNA-6 Fact Producer (PR-RNA6-A/C) | IMPLEMENTED + Directed #4 PASS |

**Parent / complements:**

- [ADR-0036](./0036-recovery-completion-authority-v1.md) — membership convergence authority
- [ADR-0022](./0022-recovery-completion-ownership.md) — completion ownership, R28-L convergence
- [ADR-0021](./0021-conference-edge-recovery-lifecycle.md) — edge recovery lifecycle
- [RNA-6 fact producer contract](../analysis/rna-6-fact-producer-contract.md) — terminal → fact (closed)
- [RNA-0037 evidence completion audit](../analysis/rna-0037-evidence-completion-audit.md) — signed off

## Summary

Freeze **when a recovery episode MAY enter COMPLETED** — the admission inputs, authority boundary, and invariants — without changing the existing completion predicate implementation.

Prior work established independent clocks:

```text
Intent lifecycle     (RNA-5)     CLOSED
Fact propagation     (RNA-6)     CLOSED
Media edge recovery              independent
Membership convergence           RCA-0036 verified
```

Directed #3 and #4 proved shortcuts MUST NOT be reintroduced:

```text
INTENT_TERMINAL(EXPIRED)  !=  recovery failed
NEGOTIATION_RECOVERY_FACT !=  EDGE_RECOVERED
FACT                      !=  RECOVERY_COMPLETION
```

This ADR defines the **Completion Admission Contract V1** — what inputs the completion authority MAY consider before emitting a completion decision.

## Non-goals

- No owner resolver change
- No RNA-5 / RNA-6 lifecycle change
- No membership protocol change (RCA-0036)
- No UI / presence projection change
- No new directed field run (Directed #5 deferred until after this freeze)
- No modification of `RecoveryCompletionPolicy.evaluate()` in this phase

---

## 1. Completion Authority

### RCA-38-1 — Single completion writer

**RecoveryCompletionAuthority** (implemented today primarily via `ConferenceEdgeRecoveryController` + `RecoveryCompletionPolicy` on the per-edge controller) is the **only** component that MAY:

```text
evaluate admission inputs
emit completion decision
close recovery episode obligation
```

### Forbidden direct closers

These domains MUST NOT directly close recovery completion:

```text
NegotiationAuthority / RecoveryNegotiationAuthority
DeferredIntentAuthority
MediaRecoveryController (ICE/media path alone)
MembershipAuthority (epoch apply alone)
UI / presence projection
```

**Rationale:** Intent, media, and membership are three independent clocks (Directed #3/#4 field evidence). Collapsing them recreates the pre-RNA-5 deadlock class.

### Relationship to ADR-0022 R28-B

Per-edge **Conference Edge Recovery Controller** remains completion owner on this device for `(sessionId, remoteModuleId)`. ADR-0038 does not change ownership — it freezes **admission inputs** to that owner.

---

## 2. Admission Inputs — Classification

Admission inputs fall into three classes: **Required**, **Supporting (observed only)**, and **Forbidden as gates**.

### 2.1 Required inputs (candidate hard gates)

#### Membership convergence

**Source signal:**

```text
MEMBERSHIP_EPOCH_CONVERGED
```

(or equivalent membership convergence fact per ADR-0036 RCA-1 predicate: `MembershipConverged`)

**Rationale:** RCA-0036 field evidence proved:

```text
transport ready != membership ready
```

Recovery completion MUST NOT close before membership convergence on the conference recovery path.

#### Edge recovery

**Source signal:**

```text
RECOVERY_EDGE_RECOVERED
```

**ADR decision (V1):** Require **required recovery edge(s)** recovered — NOT default `all edges in mesh`.

```text
WRONG (V1 default):
  all edges recovered before session completion

RIGHT (V1):
  critical / obligation-scoped recovery edge(s) recovered
```

**Rationale:** In a multi-edge mesh (`M01-M02`, `M01-M03`, `M02-M03`), one failed non-obligation edge MUST NOT block completion of an unrelated recovery obligation.

**Open for implementation phase (not this ADR):** exact definition of `required recovery edge` per obligation episode — likely the edge under active recovery obligation on this controller, not full mesh closure.

#### Control reconciliation (existing ADR-0036)

Per ADR-0036 RCA-1, `ControlReconciled` remains part of the existing predicate. ADR-0038 does not remove it — admission V1 inherits:

```text
TransportReady AND MediaReady AND MembershipConverged AND ControlReconciled
```

as the **current** production predicate baseline. ADR-0038 freezes **admission semantics** around that baseline; predicate code change is a separate authorized PR.

### 2.2 Supporting inputs (observed only — MUST NOT gate completion)

#### Negotiation recovery fact

**Source signal:**

```text
NEGOTIATION_RECOVERY_FACT
```

**Meaning:** negotiation obligation for `(intentId, terminalState)` has been processed and recorded.

**MUST NOT:**

```text
FACT_REQUIRED = true                    # as hard admission gate
FACT(EXPIRED) => RECOVERY_FAILED        # intent terminal != media outcome
FACT without EDGE_RECOVERED => block    # dual-clock violation
```

**Rationale:** Directed #3/#4 proved:

```text
FACT(EXPIRED) → EDGE_RECOVERED later     allowed
FACT emitted → edge never recovered      allowed (negotiation closed; media may still be recovering or failed separately)
```

Facts are **audit and reconciliation inputs**, not success predicates.

#### Owner bilateral consistency

**Current field status:** OPEN (e.g. M01 view `owner=M03`, M03 view `owner=M01`).

**V1 decision:**

```text
owner consistency = audit signal
NOT completion admission gate
```

**Rationale:** Hard-gating on owner symmetry risks:

```text
media recovered + membership recovered → completion blocked by owner view mismatch
```

→ recovery deadlock (historical failure class).

Owner bilateral convergence remains a **separate track** — not RNA-6, not admission V1.

### 2.3 Forbidden admission shortcuts

| Shortcut | Verdict |
|----------|---------|
| `RECOVERY_NEGOTIATION_INTENT_TERMINAL` → COMPLETED | FORBIDDEN |
| `NEGOTIATION_RECOVERY_FACT` alone → COMPLETED | FORBIDDEN |
| `RECOVERY_MEDIA_ACTION_DEFERRED` → completion block | FORBIDDEN (media defer ≠ negotiation close) |
| UI ONLINE / `displayState=ONLINE` as evidence | FORBIDDEN |
| `GROUP_RESYNC_REQUEST_SENT` as convergence success | FORBIDDEN (LOCAL_DISPATCH_ACCEPTED only) |

---

## 3. Three-clock model (normative)

```text
Negotiation clock                Media clock                 Completion clock
─────────────────                ───────────                 ─────────────────

Intent                           ICE disconnect              Membership
  ↓                                ↓                         +
Terminal                           ↓                         Media admission
  ↓                              EdgeRecovered                 +
Fact                                                         Policy
                                                             ↓
                                                         COMPLETED
```

**INV-RCA38-001:** No clock MAY shortcut another.

**INV-RCA38-002:** Negotiation terminal state MUST NOT imply recovery episode terminal state.

**INV-RCA38-003:** `NEGOTIATION_RECOVERY_FACT` MUST NOT call `markRecovered()` or mutate `RECOVERY_EDGE_RECOVERED` (RNA-6 INV-006/007 — inherited).

---

## 4. Completion state machine (admission-level)

Freeze episode-level outcomes. Intent state is **not** episode state.

```text
RECOVERING
   |
   | admission inputs satisfied (membership + required edge + control)
   v
COMPLETED


   |
   | RecoveryCompletionBudgetExpired (episode-level budget — distinct from negotiation budget)
   v
RECOVERY_FAILED


   |
   | superseded / session terminated / USER_LEAVE
   v
ABORTED
```

### Forbidden transition

```text
RECOVERING
   |
   intent EXPIRED / FACT(EXPIRED)
   v
RECOVERY_FAILED          ← FORBIDDEN
```

**Evidence:** Directed #3 — `INTENT EXPIRED` then `EDGE_RECOVERED` later is legal.

### Partial recovery (semantic freeze — no implementation)

```text
membership = converged
edge       = not recovered
intent     = closed (any terminal)

=> RECOVERING (not auto FAILED)
```

Episode MAY remain `RECOVERING` until `RecoveryCompletionBudgetExpired` or explicit failure policy — NOT because negotiation budget exhausted.

---

## 5. V1 admission freeze (normative conclusion)

```text
Recovery Completion V1 admission:

Required:
  - membership convergence (MEMBERSHIP_EPOCH_CONVERGED / MembershipConverged)
  - required recovery edge(s) recovered (RECOVERY_EDGE_RECOVERED on obligation scope)
  - control reconciled (ADR-0036 inherited)

Observed only:
  - NEGOTIATION_RECOVERY_FACT (all terminal states)
  - owner bilateral consistency
  - negotiation terminal audit trail

Forbidden:
  - intent terminal directly completing recovery
  - fact terminal state mapping to RECOVERY_FAILED
  - media deferred creating completion block
  - UI ONLINE as completion evidence
  - all-mesh-edges-required default
```

---

## 6. Invariants

| ID | Rule |
|----|------|
| INV-RCA38-001 | Three clocks MUST remain independent |
| INV-RCA38-002 | Intent terminal MUST NOT close recovery episode |
| INV-RCA38-003 | Fact emission MUST NOT mutate media recovery state (RNA-6) |
| INV-RCA38-004 | `FACT(EXPIRED)` MUST NOT imply `RECOVERY_FAILED` |
| INV-RCA38-005 | Membership convergence MUST precede completion close on conference path |
| INV-RCA38-006 | Owner bilateral symmetry MUST NOT be hard admission gate in V1 |
| INV-RCA38-007 | UI projection MUST NOT be completion evidence |
| INV-RCA38-008 | Every required admission input MUST have a repair path (RCA-INV-001 inherited) |

---

## 7. Terminal matrix — confidence expansion (non-blocking)

Directed #4 field coverage:

| Negotiation terminal | Field |
|---------------------|-------|
| EXPIRED | PASS (Directed #4) |
| SUPERSEDED | not field-exercised |
| BLOCKED_BY_GLARE | not field-exercised |
| duplicate close | not field-exercised |

**Decision:** Uncovered negotiation terminal cases do **not** block ADR-0038 freeze. Prefer unit/controller evidence over WiFi flap for matrix expansion.

---

## 8. Open questions (implementation phase — not blockers for freeze)

| OQ | Question |
|----|----------|
| OQ-RCA38-01 | Exact `required recovery edge` selector per obligation episode |
| OQ-RCA38-02 | Whether `MediaReady` subsumes edge recovered or remains separate gate |
| OQ-RCA38-03 | Episode-level `RecoveryCompletionBudgetExpired` vs existing attempt timeout interaction |
| OQ-RCA38-04 | When owner bilateral becomes soft vs hard signal in V2 |
| OQ-RCA38-05 | Whether completion predicate code change is needed vs admission-only documentation | **CLOSED: NO CODE CHANGE** (PR-RCA38-A) |

---

## 9. What happens after freeze

Authorized next steps (each requires separate sign-off):

1. **PR-RCA38-A** — **COMPLETE / ACCEPTED** (read-only audit; no code change)
2. **Predicate delta PR** — only if OQ-RCA38-05 concludes code change required
3. **Directed #5** — only if admission contract changes or new hard gate added
4. **Owner bilateral track** — independent of admission V1

**Not authorized now:**

- Modify RNA-5 / RNA-6
- Fix owner symmetry under admission pressure
- UI projection changes
- WiFi flap for completion proof

---

## 10. Status board

```text
ADR-0036 Membership              VERIFIED / RE-SIGNED
ADR-0037 RNA-5                   FROZEN + FIELD VERIFIED
Gate 3C                          CLOSED
RNA-6 Fact Producer              FROZEN + FIELD VERIFIED
ADR-0038 Admission Contract      ACCEPTED (amendment clarifies I/O roles)
Recovery Completion admission    VERIFIED BY AUDIT (predicate unchanged)
Owner bilateral                  OPEN
```

---

## References

- Field evidence RNA Directed #3: `talkback/logs/wifi-recovery-m03-rna0037-directed3-20260805-193602`
- Field evidence RNA Directed #4: `talkback/logs/wifi-recovery-m03-rna0037-directed4-20260805-201458`
- Run card: [rna-directed-4-run-card.md](../analysis/rna-directed-4-run-card.md)
- Verdict: [rna-directed-4-field-verdict.md](../analysis/rna-directed-4-field-verdict.md)
- PR-RCA38-A audit: [rca38-completion-policy-admission-audit.md](../analysis/rca38-completion-policy-admission-audit.md)
- Clarification amendment: [0038-recovery-completion-admission-clarification-amendment.md](./0038-recovery-completion-admission-clarification-amendment.md)