# ADR-0043 — O1 / O2 / O3 Evaluation Criteria

**Status:** **APPROVED FOR NEXT DESIGN LAYER** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Decision:** **E1–E5 ACCEPT** · hard-gate discipline PASS · **ordering ≠ selection**  
**Wording patch:** E3 — O must not produce the proof it evaluates  
**Upstream:** [adr0043-o-selection-constraint-memo.md](./adr0043-o-selection-constraint-memo.md) (**APPROVED**)  
**Next:** [adr0043-o-decision-memo.md](./adr0043-o-decision-memo.md) (**DRAFT** · pre-selection)

---

## Status board

```text
E1–E5:                    ACCEPTED
Hard gate:                PASS
Desk ordering:            O1 ≳ O2 >> O3  (≠ selection)
O-selection:              STILL OPEN
Implementation / Field:   FROZEN
```

---

## Hard gate (pass/fail before scoring)

```text
O-INV-001…006 · P1-AUTH-001 · A1–A5 · truth ≠ evidence ≠ authorization
```

Fail → exit candidate set (e.g. admission that becomes membership truth replica).

---

## E1–E5 — ACCEPTED

| ID | Criterion | Role |
|----|-----------|------|
| **E1** | Ownership delta | Primary · inherits C-OWN-MIN · `O3 stronger locus ≠ O3 preferred` |
| **E2** | Failure-ownership clarity | ABSENT / UNKNOWN / DENY must not collapse |
| **E3** | Coupling | Consumes PRESENT; not second truth DB. **O MUST NOT become the producer of membership proof it evaluates** (`authorization consumes proof, not creates proof`) |
| **E4** | Protocol / role surface | Secondary · `small surface ≠ correct` |
| **E5** | Reopen cost | Contained auth-rule tweak vs reopen truth locus |

Desk ordering (not ACCEPT): `O1 ≳ O2 >> O3`

O2 watch: Admission artifact → cache → treated as membership state → MIXED recurrence.

---

## Next

→ [adr0043-o-decision-memo.md](./adr0043-o-decision-memo.md)

Final compare under E1–E5; list invariants each O must retain if later selected — **no ACCEPT**.

---

## One-line statement

> Evaluate O with E1–E5 under O-INV gates; authorization consumes proof and does not create it; ordering may lean O1 ≳ O2 >> O3; selection still OPEN.
