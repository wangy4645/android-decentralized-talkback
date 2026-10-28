# Replacement Cutover RC1 — Field Closure

**Status:** **CLOSED / FIELD-PROVEN** (2026-09-10)  
**Authoritative evidence:** `talkback/logs/replacement-cutover-rc1-field-20260910-140843/`  
**Session:** `c22cbcac-749a-4e61-9a49-bf7a7d562a9a`  
**Adjudication:** `VERDICT=PASS_RC1` · `Next=READY_FOR_CONTROLLED_PILOT`

```text
RC1 Audible Ownership     CLOSED / FIELD-PROVEN
Rollback blocker          CLOSED / FIELD-PROVEN
Cutover policy blocker    CLOSED / FIELD-PROVEN
Replacement readiness     READY_FOR_CONTROLLED_PILOT

ADR-0056                  KEEP / rollback path
Full production cutover   NOT AUTHORIZED
ADR-0056 deletion         NOT AUTHORIZED
NO RC2                    cutover flip gate CLOSED
```

---

## What RC1 proved (beyond Phase A SR1–SR6)

Phase A proved the replacement media path runs correctly inside the product Meeting lifecycle (shadow / metrics). RC1 proves **user-hearable ownership transfer** with resource-level mutual exclusion:

```text
ADR-0056 audible (ANCHOR_ACTIVE)
        ↓ explicit arm → execute
Multicast production AudioTrack (MULTICAST_ACTIVE + production writes > 0)
        ↓ manual rollback
ADR-0056 audible restored (ANCHOR_ACTIVE)
```

Invariant held throughout: `ProductionAudibleOwnerCount ≤ 1` (no dual-owner at any sampled point).

| Window | Result | Evidence |
|--------|--------|----------|
| A | PASS | Pilot enabled · anchor owner · shadow media operational (TX/RX/ingress/playout) |
| B | PASS | `CUTOVER_SUCCEEDED` · `MULTICAST_ACTIVE` · production writes > 0 · `OWNER_VERIFY` pass |
| C | PASS | `ROLLBACK_SUCCEEDED` · anchor restored · multicast released · no post-rollback zombie |

**Adjudicator hard conditions (all satisfied):**

```text
dual-owner at any time           → immediate FAIL (none observed)
MULTICAST_ACTIVE alone           → insufficient (production writes required)
CUTOVER_FAILED + auto-rollback   → RC1 FAIL (true cutover succeeded)
```

---

## What RC1 does NOT prove

```text
G-RES-3 N=9 envelope
3-node Real Mesh product pilot
Long-duration stability soak
ADR-0056 deletion
Full GA / unflagged production cutover
Audio quality / jitter / PLC as architecture gates
```

---

## Next authorized work

```text
Controlled Pilot             CLOSED / 3× FIELD-PROVEN (see controlled-pilot-evidence-set.md)
GA / ADR-0056 Retirement     G1 Scale · G2 Real Mesh · G3 Stability (parallel)
NO RC2 · NO SR7            integration / cutover flip track CLOSED
NO Run-4+ pilot            identical scope — marginal evidence
```

See: `meeting-product-ga-adr0056-retirement-gates.md`

---

## References

- ADR: `docs/adr/0062-replacement-cutover-rc1-audible-ownership-transition.md`
- Run card: `replacement-cutover-rc1-field-run-card.md`
- Phase A closure: `meeting-product-integration-phase-a-sr6-field-closure.md`
- Readiness review: `meeting-product-integration-replacement-readiness-review.md`
- Integration gate: `meeting-product-integration-gate.md`
