# Controlled Pilot Run-1 — Product Operations Card

**Status:** **CLOSED / 3× FIELD-PROVEN** (2026-09-10)  
**Evidence set:** `meeting-product-controlled-pilot-evidence-set.md`  
**Prerequisite:** RC1 **CLOSED / FIELD-PROVEN** — `replacement-cutover-rc1-field-closure.md`  
**Next:** Scale/Deletion track — `meeting-product-ga-adr0056-retirement-gates.md`  
**Nature:** Product / deployment evidence — **not** a continuing gate chain. **No Run-4+ at identical scope.**

---

## Posture lock

```text
Phase A                    CLOSED / FIELD-PROVEN
Replacement Sweep-1        CLOSED / FROZEN
RC1 Audible Ownership      CLOSED / FIELD-PROVEN
Meeting integration        100%
Controlled-pilot readiness  100%
Controlled pilot evidence   PASS (3× field)

Full replacement / GA       ~96–97%  (not authorized)
ADR-0056 deletion           <100% (not authorized)
```

**Project phase:** Scale/Deletion track — not integration development · not more identical pilot runs.

---

## Parallel tracks (only two)

| Track | Goal | Blocks Controlled Pilot? |
|-------|------|--------------------------|
| **Pilot Track** | 2-device controlled pilot; real audible use under flag | **No — start now** |
| **Scale/Deletion Track** | G-RES-3 N=9 · 3-node Real Mesh · long soak · ADR-0056 deletion criteria | **No** |

Do **not** wait for G-RES-3 before Pilot Run-1. G-RES-3 blocks **scale declaration** and **ADR-0056 deletion**, not this 2-device ownership flip.

---

## Run-1 scope (frozen conservative)

```text
Devices          M01 + M02 (known-good underlay)
Topology         1 Meeting · host create · peer join
Pilot flag       explicit ON (debug / feature gate)
ADR-0056         retained · rollback enabled
Flow             Anchor → multicast production → normal meeting use → rollback if needed
```

**Not in Run-1 scope:**

```text
RC2 / SR7 / new integration slices
3-device mesh pilot
ADR-0056 deletion
Unflagged production cutover
Re-proof SR4 reachability as PASS criterion
```

---

## Evidence to collect (product operations)

Primary signals — **not** harness-style SR4 re-proof:

```text
cutover success / failure (and reason)
rollback count + trigger (manual vs auto)
unexpected auto rollback
audible ownership violation (dual owner)
replacement runtime fatal
session completion / teardown cleanliness
```

Secondary (observe only; do not auto-escalate to architecture blocker in Run-1):

```text
audio quality · short jitter · PLC behavior
```

---

## Operational boundaries

```text
Pilot flag required for multicast audible ownership
Rollback must remain available without data wipe
Any dual-owner observation → stop pilot slice · RCA · no predicate widening
AUTO_ROLLBACK without operator intent → pilot incident (rollback safety may PASS; cutover intent FAIL)
```

---

## Run-1 operational recipe (frozen)

```text
Harness reuse     p1c-package-field-first-run.ps1 -DriverMode PaPilot1
Wrapper           controlled-pilot-run1-field-first-run.ps1
Adjudicator       controlled-pilot-run1-field-adjudicate.ps1
Result            RUN_1_RESULT.txt (light product-ops summary)

Flow              Meeting start → healthy → cutover → multicast use → Meeting end → teardown
Mandatory rollback  false (only on defect; record trigger/recovery)
Code freeze         SkipBuildInstall default — no Kotlin without real pilot defect
```

Hard FAIL only:

```text
dual audible owner
production AudioTrack / runtime fatal
unexpected auto rollback / loss of multicast ownership
required rollback fails
Meeting end teardown not empty
```

Soft observe only (do not auto-escalate):

```text
PLC · jitter · isolated underrun · short packet loss · subjective audio quality
```

**CLOSED:** Run-1..3 PASS — minimum evidence set complete. Do **not** run Run-4/5/10 at identical scope.

---

## Exit (satisfied)

Minimum evidence set **CLOSED**. Does **not** authorize:

```text
ADR-0056 deletion
N=9 envelope declaration
Full production cutover
Removal of pilot flag
```

---

## References

- RC1 closure: `replacement-cutover-rc1-field-closure.md`
- ADR-0062: `docs/adr/0062-replacement-cutover-rc1-audible-ownership-transition.md`
- Readiness review: `meeting-product-integration-replacement-readiness-review.md`
- G-RES-3: `docs/adr/0060-conference-media-transport-resilience-contract.md`
