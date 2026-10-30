# PA-SR6 — Field Closure (Phase A Terminal Gate)

**Status:** **CLOSED / FIELD-PROVEN** (2026-09-10)  
**Authoritative evidence:** `talkback/logs/phase-a-sr6-field-20260910-130854/`  
**Session:** `0ed077b2-af44-49e0-90bb-6b5bbf31e9d4`  
**Adjudication:** `VERDICT=PASS_PA_SR6` · `Next=PHASE_A_CLOSED_REPLACEMENT_READINESS_REVIEW`

```text
PA-SR1–SR6     CLOSED / FIELD-PROVEN
Phase A        CLOSED / FIELD-PROVEN
ADR-0056       KEEP
Meeting cutover NOT AUTHORIZED

NEXT           Replacement Readiness Review
NO SR7         Phase A integration track CLOSED
```

---

## What SR6 proved

After a **fully operational** shadow Meeting (TX / RX / ingress / playout proven in SR4; leave/rejoin in SR5), **product Meeting end** tears down the new media runtime **completely** and keeps it **quiescent** — no resurrection.

```text
Window A  Operational baseline (TX/RX/ingress/playout)
Window B  M01 host Meeting end → bilateral SESSION_STOPPED APPLIED
          → TEARDOWN_EMPTY (M01 + M02 peer corroboration)
          → no SHADOW_FAILED hook=SESSION_STOPPED
Window C  30s quiescence: ingressDelta=0 · playoutDelta=0
```

**Integration significance:** Phase A is not only "can start / run / replace" — it is **deterministic zero after end** with no peer decoder or ingress revival.

---

## Authoritative PASS chain (`20260910-130854`)

| Window | Result | Evidence |
|--------|--------|----------|
| A | PASS | Operational shadow session baseline |
| B | PASS | M01+M02 `SESSION_STOPPED` APPLIED · bilateral `TEARDOWN_EMPTY` |
| C | PASS | Bounded quiescence · no admit / playout / scope revival |

**Prior FAIL (RCA closed):** `phase-a-sr6-field-20260910-115124/` — M02 peer `SHADOW_FAILED hook=SESSION_STOPPED` (live decoders after stop). Narrow Kotlin fix: early ingress fence + `decodeMix` hardFence/drain on teardown. Unit: `Profile01ShadowSr6TeardownTest` (6 cases PASS).

---

## What SR6 / Phase A does NOT prove

```text
ADR-0056 replacement or deletion
User-hearable cutover to new media path
G-RES-3 capacity envelope (N_max=9 evidence)
Real Mesh 3-device field proof
Capacity / stability soak at product scale
Rollback / cutover policy adjudication
```

These are **Replacement Readiness Review** scope — not Phase A integration.

---

## Phase A rollup (frozen)

| Gate | Status |
|------|--------|
| Origin / Shadow Runtime (SR1–SR2) | CLOSED / FIELD-PROVEN |
| Source chain (SR3) | CLOSED / FIELD-PROVEN |
| TX / RX / ingress / playout (SR4) | CLOSED / FIELD-PROVEN |
| Leave/rejoin (SR5) | CLOSED / UNIT+FIELD-PROVEN |
| Meeting end teardown (SR6) | CLOSED / FIELD-PROVEN |

**No re-run** unless new ADR on a protected boundary. **No SR7.**

---

## References

- Run card: `meeting-product-integration-phase-a-sr6-field-run-card.md`
- SR5 closure prerequisite: `meeting-product-integration-phase-a-sr5-field-run-card.md`
- SR4 closure: `meeting-product-integration-phase-a-sr4-field-closure.md`
- Phase A gate: `meeting-product-integration-gate.md`
- Next: `meeting-product-integration-replacement-readiness-review.md`
