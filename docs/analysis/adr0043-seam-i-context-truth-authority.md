# ADR-0043 Seam I — Context Truth Authority

**Status:** **APPROVED FOR NEXT DESIGN LAYER** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**ADR:** [0043-conference-recovery-membership-context-boundary.md](../adr/0043-conference-recovery-membership-context-boundary.md) (**ACCEPTED · Option A**)  
**Upstream:** Evidence · Decision boundary · [Decision ownership](./adr0043-seam-i-decision-ownership.md) (**APPROVED** · O1/O2/O3 **OPEN**)  
**Next layer:** [adr0043-projection-evidence-freshness-boundary.md](./adr0043-projection-evidence-freshness-boundary.md) (**DRAFT**)
**Plan:** [adr0043-implementation-plan.md](./adr0043-implementation-plan.md)

---

## Status board

```text
ADR-0043                     ACCEPTED · OPTION_A
Seam I evidence              APPROVED
Seam I decision boundary     APPROVED · A/B/C OPEN
Seam I decision ownership    APPROVED · O1/O2/O3 OPEN
Seam I context truth         APPROVED FOR NEXT DESIGN LAYER
T1 / T2 / T3                 OPEN (not chosen)
Current focus                Context truth mapping (desk)
Implementation               NOT AUTHORIZED
Runtime                      FROZEN
Field / branch               NOT AUTHORIZED
```

---

## Single question (this document)

> Who holds **final authority** over **accepted membership context truth** for a conference / channel scope?

If this is not frozen, O1/O2/O3 drift. This layer is design candidates only; **observed locus** is in the mapping doc.

---

## Core invariant

### INV-0043-TRUTH-001

> Membership context MUST have **exactly one** authoritative truth locus.

**Status:** **TARGET** invariant. Desk mapping reports practice as **MIXED** → classified **ARCHITECTURE GAP OBSERVED** (not a runtime bug verdict). See [mapping](./adr0043-seam-i-context-truth-mapping.md).

### T1 refinement (still OPEN)

If T1 is ever selected, scope must be:

```text
authority-owned TalkbackSession
```

not any local `TalkbackSession`. Unscoped T1 recreates dual truth.

---

## Candidates T1 / T2 / T3 (OPEN)

| ID | Truth locus | M0 fit (desk) |
|----|-------------|----------------|
| **T1** | `TalkbackSession` owns accepted membership context | Possible only if session is **authority-grade**, not issuer-local cache |
| **T2** | Authority **node** owns accepted membership context | Most direct vs M0 |
| **T3** | Dedicated membership authority | Possible but beyond evidence |

```text
T1 / T2 / T3 = OPEN
No selection in this layer.
```

### T1 — TalkbackSession

Natural if conference session is the membership lifecycle carrier.

**Must distinguish:**

| Sense | Meaning |
|-------|---------|
| A | Local session object on this process |
| B | Distributed accepted session authority |

If only A: M03 `TalkbackSession` cannot prove M01 context → degenerates to `local cache = truth` → false PRESENT.

Frozen caveat:

> TalkbackSession may own context only if the accepted context is **locally authoritative** for the role that answers RESYNC and **freshness is guaranteed**.

### T2 — Authority node

Most consistent with M0 (M01 destroyed context; M03 unaware). Natural ask/answer: “Can I resync?” → yes/no on the node that holds context.

Authority lifecycle (session gone vs node alive; authority change) is later boundary — does not block truth-ownership discussion.

```text
authority node reachable ≠ context truth PRESENT
```

### T3 — Dedicated membership authority

Long-term clean; **insufficient evidence** today. Introducing T3 expands component / protocol / failure ownership beyond RCA discipline.

---

## Inherited invariants

```text
INV-0043-DB-001   no GROUP_RESYNC without PRESENT evidence
INV-0043-OWN-001  authorizer must prove current accepted context
INV-0043-TRUTH-001 exactly one authoritative truth locus
```

---

## Next layer (not design elegance)

Do not pick T1/T2/T3 by architecture preference next.

Audit **where truth actually lives in code today**:

→ [adr0043-seam-i-context-truth-mapping.md](./adr0043-seam-i-context-truth-mapping.md)

---

## One-line statement

> Context truth asks where accepted membership context finally lives under a single-locus invariant (INV-0043-TRUTH-001); T1/T2/T3 stay OPEN until desk mapping reports the observed locus.
