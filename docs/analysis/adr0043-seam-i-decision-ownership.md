# ADR-0043 Seam I — Decision Ownership Design

**Status:** **APPROVED FOR NEXT DESIGN LAYER** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**ADR:** [0043-conference-recovery-membership-context-boundary.md](../adr/0043-conference-recovery-membership-context-boundary.md) (**ACCEPTED · Option A**)  
**Evidence:** [adr0043-seam-i-authority-context-evidence.md](./adr0043-seam-i-authority-context-evidence.md) (**APPROVED**)  
**Decision boundary:** [adr0043-seam-i-decision-boundary.md](./adr0043-seam-i-decision-boundary.md) (**APPROVED** · A/B/C **OPEN**)  
**Next layer:** [adr0043-seam-i-context-truth-authority.md](./adr0043-seam-i-context-truth-authority.md) (**DRAFT FOR REVIEW**)  
**Plan:** [adr0043-implementation-plan.md](./adr0043-implementation-plan.md)

---

## Status board

```text
ADR-0043                     ACCEPTED · OPTION_A
Seam I evidence              APPROVED
Seam I decision boundary     APPROVED · A/B/C OPEN
Seam I decision ownership    APPROVED FOR NEXT DESIGN LAYER
O1 / O2 / O3                 OPEN (not chosen)
Current focus                Context truth authority
Implementation               NOT AUTHORIZED
Runtime                      FROZEN
Field / branch               NOT AUTHORIZED
```

---

## Layering (frozen)

```text
Authority context evidence
        ↓
Can we resync?          (decision boundary · INV-0043-DB-001)
        ↓
Who decides?            (this document · O1/O2/O3 OPEN)
        ↓
Who owns context truth? (next · T1/T2/T3)
        ↓
How to implement?       NOT AUTHORIZED
```

Do not reverse this order. M0 was not “handler handling”; it was:

```text
issuer dispatched a resync it was not entitled to dispatch
```

---

## Single question (this document)

> Between production of PRESENT evidence and consumption (dispatch of `GROUP_RESYNC`), who holds final decision authority to allow resync?

Inherited: **INV-0043-DB-001** — no `GROUP_RESYNC` without PRESENT for target conference scope.

---

## Ownership invariant

### INV-0043-OWN-001

> The component authorizing `GROUP_RESYNC` MUST be the component that can **prove current accepted membership context** for the target conference scope.

Evaluation (not selection):

| Model | Feasible if… |
|-------|----------------|
| **O1** | Issuer can **prove** current accepted context (not merely hold a stale evidence snapshot) |
| **O2** | Authority admission proves current accepted context for this operation |
| **O3** | Dedicated membership authority can prove current accepted context |

Does not lock implementation. Rejects any owner that authorizes without proof capability.

---

## Candidates O1 / O2 / O3 (OPEN)

| ID | Owner model | M0 coverage (desk) |
|----|-------------|---------------------|
| **O1** | Issuer self-adjudicates on PRESENT evidence | Partial |
| **O2** | Authority admission token / ack | Direct |
| **O3** | Membership authority component arbitrates | Over-scoped today |

```text
O1 / O2 / O3 = OPEN
No ACCEPT of O2/O3 (or O1) in this layer.
```

### O1 — Issuer adjudicates

```text
issuer sees PRESENT evidence → GROUP_RESYNC
```

Pros: simple · low latency · no extra round trip.

Risk: issuer holds an **evidence snapshot**, not an **authority decision**. Window:

```text
t0  issuer sees PRESENT
t1  authority context disappears
t2  issuer sends RESYNC
```

```text
PRESENT evidence ≠ authority accepted this operation
```

O1 requires a **very strong freshness contract** to satisfy INV-0043-OWN-001. O1 ≠ issuer guesses from topology.

### O2 — Authority admission

```text
issuer → request permission → authority → admission → issuer → GROUP_RESYNC
```

Pros: clearest semantics — who owns context decides whether it may be used. Maps M0:

```text
no context → no admission → no resync
```

Risk: new protocol boundary (token lifetime · admission freshness · duplicate request · authority unavailable). Those belong **outside** Seam I detail and are not designed here.

```text
admission ≠ membership invent
admission ≠ handler softening
```

### O3 — Membership authority component

```text
issuer → membership authority → decision
```

Pros: long-term clean separation of membership truth vs conference session.

Risk: largest scope — new abstraction · ownership migration · new component duties. **No field evidence yet that this scale is required.**

---

## M0 contrast

```text
M01 context destroyed
M03 thinks recovery / resync possible
M01 rejects NO_MEMBERSHIP_CONTEXT
```

Gap is not “who stores membership” but:

```text
who authorizes use of membership context for GROUP_RESYNC
```

---

## Explicit non-goals

```text
NO:
- ACCEPT / select O1, O2, or O3
- select A / B / C
- design admission token protocol
- Seam II establishment
- retry / timeout / FSM
- runtime / field / branch
- handler change / Option C
```

---

## Next layer pointer

Do not ask “which of O1/O2/O3 is best” next. Ask the deeper question:

> Who holds **final authority** over accepted membership **context truth**?

→ [adr0043-seam-i-context-truth-authority.md](./adr0043-seam-i-context-truth-authority.md)

---

## One-line statement

> Ownership keeps O1/O2/O3 OPEN under INV-0043-OWN-001 (authorizer must prove current accepted context); O2 covers M0 most directly, O1 needs strong freshness, O3 is over-design today — next resolves context truth authority (T1/T2/T3), not implementation.
