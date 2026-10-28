# G-RES-3 — Capacity Envelope and Evidence Contract (Outline)

## Status

```text
ADJUDICATION COMPLETE (C1–C4 ACCEPTED)
IMPLEMENTATION EVIDENCE PENDING (3×VALID PASS @ N=9)
G-RES-3-H1 EVIDENCE HARNESS: H1a IN PROGRESS (qualification/preflight only)
Parent:     ADR-0060 ACCEPTED — FROZEN
Prerequisites: Profile 02 A9; Profile 02 A6; G-RES-4 P1–P7 COMPLETE
Scope:      V1 product envelope + N_max + capacity evidence contract
Media Engine: NOT AUTHORIZED
```

---

## Problem statement

ADR-0060 requires sender-side unicast fan-out as the **correctness path**. Capacity
sign-off must prove worst case:

```text
all remote receivers lose multicast eligibility simultaneously
→ sender must serve (M - 1) unicast legs per active Source
```

```text
RequiredNMax  = contract (C1)
ProvenNMax    = evidence (C4 E1)
```

---

## Adjudication map (C1–C4)

```text
G-RES-3-C1  Product capacity envelope                          ACCEPTED (Model N1)
G-RES-3-C2  Sender capacity test contract                      ACCEPTED (Model B1)
G-RES-3-C3  PASS metrics                                       ACCEPTED (Model T1)
G-RES-3-C4  Evidence adjudication + closeout                   ACCEPTED (Model E1)
```

---

## G-RES-3-C1 — Product capacity envelope

**Status: ACCEPTED — MODEL N1**
(see `0060-gres3-c1-product-capacity-envelope-adjudication.md`)

```text
V1MaxConferenceMembers=10; N_max=9; MaxUnicastFanOutTargetsPerSource=9
RequiredNMax=9 FROZEN; ProvenNMax evidence-derived
```

---

## G-RES-3-C2 — Sender capacity test contract

**Status: ACCEPTED — MODEL B1**
(see `0060-gres3-c2-sender-capacity-test-contract-adjudication.md`)

```text
1×9×50×120B real UDP fan-out; multicast=0; protect-once
```

---

## G-RES-3-C3 — PASS metrics

**Status: ACCEPTED — MODEL T1**
(see `0060-gres3-c3-pass-metrics-adjudication.md`)

```text
600s measurement; hard+health gates; thresholds frozen
```

---

## G-RES-3-C4 — Evidence adjudication + closeout

**Status: ACCEPTED — MODEL E1**
(see `0060-gres3-c4-evidence-closeout-adjudication.md`)

```text
3 independent VALID runs @ N=9; all PASS C3 → ProvenNMax>=9 → G-RES-3 PASS
INVALID/INCOMPLETE → rerun; no extrapolation from lower N; no 2/3 majority
Complete evidence bundle + machine adjudicator per run
Remediation → fresh 3-run set

G3-C4-I1..I10 frozen | E2–E6 REJECTED
```

---

## Closeout state

```text
G-RES-3 contract adjudication:     COMPLETE
G-RES-3 evidence (3×PASS @ N=9):   PENDING

G-RES-3 PASS requires:
  3 VALID runs @ N=9, all RUN_VERDICT=PASS, complete bundles

G-RES-3 PASS ≠ Media Engine AUTHORIZED
```

---

## Implementation evidence track (H1 / E2)

```text
G-RES-3-H1 EVIDENCE HARNESS
  H1a QUALIFICATION (M03 loopback)          PASS
  H1b JVM adjudicator + fixtures            COMPLETE
  H1c formal topology qualification         FAIL / STOP (lightweight #1 MAX 10.795ms)
  H1d remediation series                    CLOSED AS INSUFFICIENT
  G-RES-3 E2 execution-model adjudication   AUTHORIZED (E1 per-target socket probe)
  3×QUALIFYING @ N=9                        HOLD
```

See `0060-gres3-e2-sender-execution-model-adjudication.md`.

---

## References

- `docs/adr/0060-conference-media-transport-resilience-contract.md`
- `docs/analysis/0060-gres3-c4-evidence-closeout-adjudication.md`
- `docs/analysis/0060-gres3-c3-pass-metrics-adjudication.md`
- `docs/analysis/0060-gres3-c2-sender-capacity-test-contract-adjudication.md`
- `docs/analysis/0060-gres3-c1-product-capacity-envelope-adjudication.md`
- `docs/analysis/0060-gres3-e2-sender-execution-model-adjudication.md`

## Next action

**G-RES-3-C4 ACCEPTED (E1).** G-RES-3 contract adjudication **COMPLETE**.

**Implementation evidence:** H1d closed insufficient. Proceed to **G-RES-3 E2**
per-target socket probe (E1). H1c / 3×QUALIFYING remain HOLD until E2 completes.

Code: `android-board-talkback/.../core/conference/capacity/`
Scripts: `scripts/adr0060-gres3-h1c-qualification-run.ps1`
