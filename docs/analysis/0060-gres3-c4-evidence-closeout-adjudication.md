# G-RES-3-C4 — Evidence Adjudication and Closeout (Adjudication)

## Status

```text
G-RES-3-C4: ACCEPTED — MODEL E1
Date:       2026-09-03
Parent:     0060-gres3-capacity-envelope-contract-outline.md
Prerequisites: G-RES-3-C1 ACCEPTED (RequiredNMax = 9)
        G-RES-3-C2 ACCEPTED (Model B1)
        G-RES-3-C3 ACCEPTED (Model T1)
Scope:  ProvenNMax derivation; 3-run reproducibility; evidence bundle;
        G-RES-3 closeout state machine
        C1–C3 thresholds NOT reopened
Next:       implement benchmark harness; collect 3×N=9 evidence
```

---

## Question

How is **`ProvenNMax`** derived from benchmark evidence, and when does
**G-RES-3 PASS** close?

C4 freezes **evidence adjudication and closeout only**. It does **not** modify
C1 contract values, C2 workload, or C3 PASS thresholds.

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **E1** | 3 independent valid runs @ N=9; all must PASS; monotonic ProvenNMax | **ACCEPT** |
| **E2** | 2/3 majority PASS sufficient | **REJECT** |
| **E3** | Extrapolate ProvenNMax from PASS @ N=7 or N=8 | **REJECT** |
| **E4** | Single PASS @ N=9 sufficient | **REJECT** |
| **E5** | Split one 30-minute session into three 10-minute "runs" | **REJECT** |
| **E6** | INVALID harness failure counts as capacity FAIL | **REJECT** |

---

## Core definitions (frozen)

```text
RequiredNMax = 9   (G-RES-3-C1 contract — unchanged)

ProvenNMax =
  maximum fan-out target count N for which complete C2/C3 benchmark contract
  is satisfied with qualifying evidence per E1

ProvenNMax is evidence-derived, NEVER configuration-declared.
```

```text
V1 exit requirement:

  ProvenNMax >= 9
  → G-RES-3 PASS

ProvenNMax discovery beyond 9 (e.g. N=10 stress) is optional;
NOT required for G-RES-3 closeout.
```

---

## Frozen decision — Model E1

### 3-run reproducibility @ N=9

```text
RunCountRequired = 3

For RequiredNMax = 9:
  3 independent VALID runs at N = 9
  ALL 3 MUST PASS C3 Tier-1 + Tier-2 gates
  → ProvenNMax >= 9
  → G-RES-3 PASS

Any VALID FAIL @ N=9:
  → ProvenNMax >= 9 NOT PROVEN
  → G-RES-3 NOT PASS / REMEDIATION REQUIRED
```

```text
No majority voting (E2 REJECT):
  C3 is deterministic capacity gate, not statistical network delivery test.
  One deadline miss / local send failure / thermal gate failure = not stable proof.
```

### No upward extrapolation (frozen — G3-C4-I6)

```text
PASS @ N=7  ≠ evidence for N=9
PASS @ N=8  ≠ evidence for N=9

FORBIDDEN:
  N=9 run 1 PASS + N=8 runs 2 PASS → ProvenNMax=9

REQUIRED:
  candidate N must satisfy full repeatability requirement at that N

ProvenNMax >= 9 derives ONLY from 3×VALID PASS @ N=9.
```

```text
Optional future limit discovery:
  3×VALID PASS @ N=10 → ProvenNMax >= 10
  (outside current G-RES-3 exit requirement)
```

---

## Run independence (frozen — G3-C4-I3)

Each of 3 runs MUST:

```text
execute full C2 phase structure:
  60s warmup
  600s measurement
  30s cooldown

start from clean benchmark lifecycle:
  rebuild sender benchmark runtime / sockets / counters at run start
  MUST NOT split one continuous 30-minute session into three 10-minute files
```

```text
Reboot between runs: NOT required (boot state is not a controlled variable)

MUST record per run metadata:
  device model / build
  app / benchmark harness build SHA
  network interface / IP configuration
  target endpoint set (9 distinct receiver identities)
  battery / power state
  thermal start / end / max
  run timestamps (start/end)
```

---

## VALID vs INVALID / INCOMPLETE runs (frozen — G3-C4-I5)

```text
Three adjudication outcomes per run:

  VALID PASS     — contributes to 3-run qualifying set
  VALID FAIL     — capacity failure; blocks G-RES-3 PASS
  INVALID / INCOMPLETE — neither PASS nor capacity FAIL; must rerun
```

### INVALID / INCOMPLETE examples (must rerun)

```text
instrumentation crash during measurement
receiver harness not fully started (< 9 targets)
thermal telemetry required but THERMAL_STATUS = UNKNOWN (C3)
measurement integrity incomplete
required telemetry missing for Tier-2 adjudication
configuration mismatch (target count != 9, multicast enabled, wrong artifact size)
probe/harness fault
```

```text
INVALID run:
  MUST NOT count toward 3 PASS runs
  MUST NOT count as VALID capacity FAIL
  → discard and rerun
```

### Tier-2 INCOMPLETE closeout rule (frozen)

```text
C3 Tier-1 PASS + Tier-2 INCOMPLETE
  → RUN_VERDICT = INVALID / INCOMPLETE
  → does NOT contribute to 3 PASS runs
  → does NOT count as capacity FAIL

FORBIDDEN:
  Tier-1 PASS + thermal unknown → overall PASS
```

Validity stage MUST filter before capacity PASS/FAIL adjudication.

---

## Per-run adjudicator output (frozen)

Each run produces machine-readable verdict:

```text
VALIDITY:        VALID | INVALID

C3_TIER1:        PASS | FAIL

C3_TIER2:        PASS | INCOMPLETE | FAIL

RUN_VERDICT:     PASS | FAIL | INVALID
```

```text
RUN_VERDICT mapping:
  VALID + Tier1 PASS + Tier2 PASS     → PASS
  VALID + Tier1 FAIL or Tier2 FAIL    → FAIL
  INVALID or Tier2 INCOMPLETE         → INVALID
```

Final G-RES-3 adjudication MUST NOT rely on manual log browsing.

---

## Evidence bundle (frozen — G3-C4-I7)

Each run MUST archive minimum bundle:

```text
run-manifest.json
benchmark-config.json          (C1/C2/C3 frozen parameters)

summary.json:
  logicalSlotsExpected
  logicalSlotsAttempted
  logicalSlotsEmitted
  actualDatagramsAttempted

  slotDeadlineMissCount
  catchUpEmissionCount
  totalPerLegSendFailureCount

  fanoutCompletionP50/P95/P99/Max
  schedulingLatenessP50/P95/P99/Max
  slotExecutionCompletionFromNominalMax

  per-target send attempts / failures

  memory start / end / max
  GC observations
  CPU / backlog observations
  thermal start / end / max / status

raw per-slot / per-leg timeseries (or equivalent)
logcat / runtime logs

adjudicator-output.json:
  VALIDITY, C3_TIER1, C3_TIER2, RUN_VERDICT
```

```text
Only complete evidence bundles may contribute to ProvenNMax.
Partial bundle → INVALID.
```

### Qualifying evidence set (3 runs)

```text
gres3-evidence-set/
  run-001/ ... adjudicator PASS
  run-002/ ... adjudicator PASS
  run-003/ ... adjudicator PASS
  closeout-summary.json
```

---

## Evidence lineage after remediation (frozen)

```text
If any VALID FAIL @ N=9 occurs and implementation changes in ways that
may affect capacity (sender path, pacing, socket layer, memory, threading):

  MUST collect fresh qualifying set of 3 VALID PASS runs on new build
  MUST NOT combine old PASS runs with new PASS runs to reach count of 3

Prior PASS runs from pre-remediation build are superseded for G-RES-3 closeout.
```

---

## G-RES-3 closeout state machine (frozen)

```text
RequiredNMax = 9

ValidRunsAtN9_PASS < 3
  → G-RES-3 EVIDENCE PENDING
  → ProvenNMax >= 9 NOT CLAIMED

3 VALID runs @ N=9 exist:
  ├─ all 3 RUN_VERDICT = PASS
  │     → ProvenNMax >= 9
  │     → G-RES-3 PASS
  │
  └─ any RUN_VERDICT = FAIL
        → ProvenNMax >= 9 NOT PROVEN
        → G-RES-3 FAIL / REMEDIATION REQUIRED

INVALID runs:
  → do not advance state toward PASS or FAIL
  → rerun until 3 VALID outcomes obtained for adjudication
```

```text
FORBIDDEN closeout states:
  PASS / AMBER
  conditional PASS with 2/3 runs
  ProvenNMax declared in config without evidence
```

### VALID FAIL remediation routing (pointer)

```text
ProvenNMax < 9 after qualifying attempts:

  A. optimize sender implementation → fresh 3-run set
  B. shrink V1 envelope (e.g. 8 members) → new ADR / C1 amendment
  C. relay/replicator topology → new ADR (ADR-0060 V1 rejected)

MUST NOT silently lower RequiredNMax post-failure without ADR.
```

---

## What G-RES-3 PASS proves (frozen — G3-C4-I10)

```text
G-RES-3 PASS proves:
  sender-side transport replication capacity for N=9 worst-case fallback
  under frozen C2/C3 benchmark contract

G-RES-3 PASS does NOT prove:
  full Media Engine CPU budget
  Opus encode/decode budget
  AudioRecord / AudioTrack behavior
  10-node end-to-end conference quality
  G-RES-4 publication channel capacity
  Profile 03 runtime implementation (G-RES-2 evidence)
  Profile 02 wire corpus (G-RES-1 evidence)
```

```text
After G-RES-3 PASS:
  Media Engine = NOT AUTHORIZED
  until G-RES-1, G-RES-2, G-RES-5 (and remaining evidence) close
```

---

## Amendment closeout verdict (three tiers)

### Tier 1 — Adjudication (this document)

```text
G-RES-3 C1–C4 adjudication: FROZEN / COMPLETE

MAY claim after C4 ACCEPT:
  G-RES-3 contract adjudication chain closed
  C1–C3 semantics frozen; E1 evidence rules frozen

MUST NOT claim:
  G-RES-3 PASS (without 3×VALID PASS evidence)
  ProvenNMax >= 9
  Media Engine AUTHORIZED
```

### Tier 2 — Evidence collection

```text
G-RES-3 evidence: PENDING

Implement C2 harness + C3 adjudicator → collect 3×N=9 qualifying runs
```

### Tier 3 — Gate closure

```text
3×VALID PASS @ N=9 with complete bundles
  → G-RES-3 PASS
  → ProvenNMax >= 9
  → G-RES-3 implementation/evidence CLOSED
```

---

## Invariants (frozen)

```text
G3-C4-I1   RequiredNMax remains 9.

G3-C4-I2   ProvenNMax is evidence-derived, never configuration-declared.

G3-C4-I3   N=9 requires 3 independent VALID full-duration runs.

G3-C4-I4   All three N=9 runs MUST PASS C3; no majority voting.

G3-C4-I5   INVALID/INCOMPLETE run is neither PASS nor capacity FAIL; must rerun.

G3-C4-I6   Lower-N results cannot be extrapolated upward.

G3-C4-I7   Only complete evidence bundles may contribute to ProvenNMax.

G3-C4-I8   3×PASS@N9 → ProvenNMax>=9 → G-RES-3 PASS.

G3-C4-I9   Any VALID FAIL@N9 prevents current G-RES-3 PASS until fresh
           qualifying evidence set after remediation.

G3-C4-I10  G-RES-3 PASS proves transport fan-out capacity only,
           not full Media Engine readiness.
```

---

## Normative contract insert

```text
G-RES-3-C4 — Evidence closeout (Model E1):

ProvenNMax from evidence; 3 independent VALID runs @ N=9 all PASS C3.
INVALID/INCOMPLETE → rerun, not FAIL. No extrapolation from lower N.
Complete evidence bundle + machine adjudicator per run.
Remediation → fresh 3-run set; no stitching old/new PASS.
G-RES-3 adjudication COMPLETE; evidence PENDING until 3×PASS.

E2–E6 rejected. G3-C4-I1..I10 frozen.
```

---

## Consequences

```text
Next engineering: benchmark harness + adjudicator implementing C2/C3/C4
No further G-RES-3 semantic expansion without new ADR
ADR-0060 G-RES-3 gate: contract CLOSED; evidence collection authorized
```

---

## References

- `docs/analysis/0060-gres3-capacity-envelope-contract-outline.md`
- `docs/analysis/0060-gres3-c3-pass-metrics-adjudication.md`
- `docs/analysis/0060-gres3-c2-sender-capacity-test-contract-adjudication.md`
- `docs/analysis/0060-gres3-c1-product-capacity-envelope-adjudication.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
