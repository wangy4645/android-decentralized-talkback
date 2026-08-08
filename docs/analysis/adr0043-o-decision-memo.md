# ADR-0043 — O1 / O2 / O3 Decision Memo (Pre-Selection)

**Status:** **APPROVED** · **pre-selection complete** · **O-selection still OPEN until ACCEPT**  
**Date:** 2026-08-08  
**Upstream:** [adr0043-o-evaluation-criteria.md](./adr0043-o-evaluation-criteria.md) (**APPROVED**)  
**Next:** [adr0043-o-selection-decision.md](./adr0043-o-selection-decision.md) (**DRAFT** · A/B/C/D grill only)

---

## Status board

```text
Pre-selection memo:       APPROVED
Desk ordering:            O1 ≳ O2 >> O3  (≠ selection)
O-selection:              OPEN → decision grill next
No further analysis layers before O-selection
Implementation / Field:   FROZEN
```

---

## Compressed chain (done)

```text
M0 → Truth → Evidence → Projection(P1) → Freshness(F1∧F4)
   → Invalid patterns → Auth(PRESENT≠GRANT) → O-INV → E1–E5
```

Architecture problem space is **decomposed**. Remaining = **closing decisions**, not new discovery.

---

## Pre-selection summary (frozen)

| | O1 | O2 | O3 |
|-|----|----|-----|
| Desk lean | Preferred | Viable | Disadvantaged |
| If later ACCEPT | Eval-time F-MIN; PRESENT≠GRANT; consume≠produce proof | Admission≠truth; no membership replica | Explicit OWN reopen |

```text
ordering ≠ selection
```

---

## Next (no new analysis layer)

→ [adr0043-o-selection-decision.md](./adr0043-o-selection-decision.md)

A/B/C/D only. Then (if O1) thin O1 boundary · F Class II deferred · Seam II deferred · architecture close.
