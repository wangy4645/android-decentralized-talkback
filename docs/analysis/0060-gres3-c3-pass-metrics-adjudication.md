# G-RES-3-C3 — PASS Metrics and Soak Duration (Adjudication)

## Status

```text
G-RES-3-C3: ACCEPTED — MODEL T1
Date:       2026-09-03
Parent:     0060-gres3-capacity-envelope-contract-outline.md
Prerequisites: G-RES-3-C1 ACCEPTED (N_max = 9)
        G-RES-3-C2 ACCEPTED (Model B1 benchmark)
Scope:  PASS/FAIL thresholds; soak duration; hard vs health gates
        ProvenNMax derivation → C4
Next:       G-RES-3 evidence collection (3×VALID PASS @ N=9)
```

---

## Question

When does a G-RES-3-C2 benchmark run **PASS** capacity evidence for
`RequiredNMax = 9`?

C3 is the first adjudication that turns "runs" into "capacity PASS". Thresholds
are frozen here; C4 defines evidence bundle and closeout only — **no further
performance threshold changes in C4**.

---

## Gate philosophy — Model T1 (frozen)

```text
Two tiers:

  Hard correctness gates     — any violation → G-RES-3 FAIL
  Capacity health gates      — prove PASS not from sustained overload luck

T1 philosophy:
  jitter may exist
  but 9-leg correctness workload MUST NOT:
    cross 20ms slot boundary end-to-end
    accumulate backlog
    retry/catch-up historical slots
    exhibit local send failure
    pass only on pre-thermal-throttle short sprint
```

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **T1** | Hard timing/send/catch-up gates + health stability; CPU as saturation not absolute % | **ACCEPT** |
| **T2** | Fan-out MAX must be < 20ms (full slot budget) | **REJECT** (no jitter headroom) |
| **T3** | Allow non-zero catchUpEmissionCount under load | **REJECT** (P02 violation) |
| **T4** | Hard CPU < 30% absolute gate | **REJECT** (device-dependent) |
| **T5** | Hard fixed heap MB ceiling | **REJECT** (device-dependent) |
| **T6** | Measurement < 60s sufficient | **REJECT** (insufficient soak) |

---

## Phase durations (frozen)

```text
WarmupDurationSec        = 60
MeasurementDurationSec   = 600        (10 minutes scored measurement)
CooldownDurationSec      = 30
```

```text
At 50 logical slots/s during measurement:

  ExpectedLogicalSlots      = 30,000
  ExpectedLegSendAttempts   = 270,000   (30,000 × 9)
```

```text
G-RES-3 is a capacity gate, NOT 7×24 reliability proof.
Longer soak MAY be collected as supplementary evidence; not required for C3 PASS.
```

---

## Tier 1 — Hard correctness gates (frozen)

Any single violation during **measurement phase** → **G-RES-3 FAIL**.

### Slot integrity and catch-up

```text
slotDeadlineMissCount     = 0
catchUpEmissionCount      = 0
```

```text
catchUpEmissionCount non-zero = contract violation (P02 late media expendable),
not mere performance degradation.
```

### Send failure

```text
totalPerLegSendFailureCount = Σ perLegSendFailureCount = 0
```

```text
Benchmark context: sender-local, controlled network, 9 admitted endpoints.
sendto() / local socket / queue failure → FAIL.

Receiver non-delivery on UDP wire is NOT a C3 sender gate.
```

### Fan-out completion latency (isolated)

```text
perSlotFanoutCompletionUs over all emitted slots in measurement:

  fanoutCompletionP99Us   <= 5,000
  fanoutCompletionMaxUs   <= 10,000
```

```text
Rationale:
  20ms = full logical slot budget (SlotPeriodMs)
  PASS at MAX < 20ms leaves no scheduler/GC/socket headroom
  9 UDP sends should be well under 20ms; V1 requires:
    P99 <= 5ms
    MAX <= 10ms
  preserving ~half slot for runtime jitter
```

### Scheduling lateness (advisory thresholds + derived gate)

```text
slotSchedulingLatenessUs — recorded per emitted slot:

  schedulingLatenessP99Us   <= 5,000    (reporting threshold; not sole FAIL)
  schedulingLatenessMaxUs   <= 20,000   (reporting ceiling)
```

Scheduling lateness alone is **not** the primary fan-out FAIL criterion (C2 split).

### End-to-end slot execution gate (frozen — primary timing audit)

```text
Derived per emitted slot:

  slotExecutionCompletionFromNominalUs
    = slotSchedulingLatenessUs + perSlotFanoutCompletionUs

HARD GATE — for EVERY emitted logical slot in measurement:

  slotExecutionCompletionFromNominalUs < 20,000
```

```text
Prevents:
  scheduler late 15ms + fanout 8ms = 23ms total
  while each component alone appears acceptable

Stricter and more machine-auditable than slotDeadlineMissCount alone;
both gates apply (deadlineMiss=0 AND derived < 20ms).
```

---

## Measurement integrity gate (frozen)

```text
Benchmark MUST NOT PASS by silently skipping logical slots.

logicalSlotsAttempted during measurement MUST match expected count from
monotonic measurement window within documented boundary tolerance:

  ExpectedLogicalSlots = MeasurementDurationSec × LogicalMediaRate
                       = 600 × 50 = 30,000

Tolerance:
  only start/end boundary partial slots at phase transitions
  MUST NOT silent skip of interior slots

If logicalSlotsEmitted << ExpectedLogicalSlots without documented boundary
explanation → measurement integrity FAIL (cannot claim deadlineMiss=0 PASS).
```

```text
Record and report:
  logicalSlotsAttempted
  logicalSlotsEmitted
  expected vs actual variance with boundary annotation
```

---

## Tier 2 — Capacity health gates (frozen)

Violations → **G-RES-3 FAIL** unless noted as evidence-only.

### Memory stability

```text
HARD (stability, not fixed MB):
  no monotonic heap growth across measurement window
  no monotonic native-memory growth across measurement window
  no OOM or allocation failure during measurement
  no sustained GC-induced slot timing gate failures
    (GC events that cause hard gate violation above → already FAIL)

RECORD (evidence; no fixed MB ceiling in C3):
  heap start / end
  native start / end
  GC count / GC time
```

```text
C3 does NOT freeze "< X MB" — device-dependent and not architecture-stable.
```

### CPU saturation

```text
HARD (health, not absolute percentage):
  NO sustained CPU saturation
  NO monotonic send backlog growth attributable to CPU starvation

C3 does NOT freeze:
  ProcessCpuMean < 30%
  ProcessCpuP95 < fixed %

Rationale:
  Android multi-core "CPU %" reporting is inconsistent (process may show >100%).
  Architecture contract targets saturation/backlog behavior, not cross-device %.

RECORD (evidence):
  process CPU samples normalized if collection supports
    "whole-device compute capacity = 100%" semantics
  if normalization unavailable → document raw metric + saturation observation
```

### Thermal

```text
HARD on V1 target evidence device:
  thermalStatus < SEVERE throughout measurement phase
    (Android THERMAL_STATUS_SEVERE or platform-equivalent)

no thermal throttling that causes any Tier-1 hard gate failure

If thermal status API unavailable on device under test:
  THERMAL_STATUS = UNKNOWN
  → capacity evidence INCOMPLETE for that device
  → cannot claim G-RES-3 PASS on that run without observability waiver (C4)
```

```text
10-minute sustained 450 send/s may increase temperature.
PASS requires throttling does not break contract gates, not zero heating.
```

---

## PASS summary table (frozen)

```text
WarmupDurationSec              = 60
MeasurementDurationSec         = 600
CooldownDurationSec            = 30

ExpectedLogicalSlots           = 30,000
ExpectedLegSendAttempts        = 270,000

── Hard correctness gates (all required) ──
slotDeadlineMissCount          = 0
catchUpEmissionCount           = 0
totalPerLegSendFailureCount    = 0

fanoutCompletionP99Us          <= 5,000
fanoutCompletionMaxUs          <= 10,000

∀ emitted slot:
  slotSchedulingLatenessUs + perSlotFanoutCompletionUs < 20,000

measurement integrity:
  logical slot count matches expected ± boundary tolerance

── Capacity health gates (all required on target device) ──
no monotonic memory growth (heap + native)
no OOM / allocation failure
no sustained CPU saturation / monotonic backlog growth
thermalStatus < SEVERE (or evidence incomplete if unobservable)
```

---

## What C3 does NOT gate

```text
NOT in C3 hard gates:
  receiver UDP delivery rate
  end-to-end media playout quality
  Opus encode latency
  multicast optimization performance
  7×24 soak / multi-hour thermal soak
  absolute CPU % or fixed RAM MB limits
```

---

## Relationship to C2 observations

| C2 observation | C3 gate |
|----------------|---------|
| `perSlotFanoutCompletionUs` | P99 ≤5ms; MAX ≤10ms |
| `slotSchedulingLatenessUs` | derived sum < 20ms per slot |
| `slotDeadlineMissCount` | = 0 |
| `catchUpEmissionCount` | = 0 |
| `perLegSendFailureCount` | sum = 0 |
| `logicalSlotsEmitted` | integrity vs 30,000 |
| CPU / memory / thermal | health gates (T1) |

---

## Explicit non-decisions in C3

```text
NOT in C3:
  number of repeat runs for ProvenNMax          → C4
  multi-device matrix requirements              → C4 evidence plan
  sink harness vs field topology approval       → C4
  thermal UNKNOWN waiver policy detail          → C4
  post-failure product decision (shrink N_max)  → C4 / ADR
```

---

## Invariants (frozen)

```text
G3-C3-I1  Hard gates and health gates are distinct tiers; both required for PASS.

G3-C3-I2  MeasurementDurationSec = 600; ExpectedLogicalSlots = 30,000.

G3-C3-I3  End-to-end slot gate: schedulingLateness + fanoutCompletion < 20ms
          for every emitted slot.

G3-C3-I4  catchUpEmissionCount = 0 is non-negotiable (P02 alignment).

G3-C3-I5  totalPerLegSendFailureCount = 0 for local/socket failures.

G3-C3-I6  CPU/memory use stability/saturation gates, not fixed %/MB ceilings.

G3-C3-I7  Thermal < SEVERE required when observable on target device.

G3-C3-I8  Measurement integrity prevents silent slot skip PASS.

G3-C3-I9  C4 MUST NOT reopen C3 performance thresholds.
```

---

## Normative contract insert

```text
G-RES-3-C3 — PASS metrics (Model T1):

600s measurement; 30k slots; 270k leg sends.
Hard: deadlineMiss=0, catchUp=0, sendFail=0, fanout P99≤5ms MAX≤10ms,
      ∀slot (schedLat+fanout)<20ms, slot count integrity.
Health: stable memory, no OOM, no sustained CPU saturation/backlog,
        thermal < SEVERE when observable.

T2–T6 rejected. G3-C3-I1..I9 frozen.
```

---

## Consequences

```text
C4: ProvenNMax >= 9 derivation; repeat run count; evidence bundle;
    thermal UNKNOWN handling; G-RES-3 closeout verdict
Capacity runs may begin once C2 harness implements C3 gates
```

---

## References

- `docs/analysis/0060-gres3-capacity-envelope-contract-outline.md`
- `docs/analysis/0060-gres3-c2-sender-capacity-test-contract-adjudication.md`
- `docs/analysis/0060-gres3-c1-product-capacity-envelope-adjudication.md`
- `docs/analysis/0058-profile-02-a6-pacing-wirepps-adjudication.md`
