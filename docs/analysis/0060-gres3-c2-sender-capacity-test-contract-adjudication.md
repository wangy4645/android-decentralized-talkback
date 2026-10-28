# G-RES-3-C2 — Sender Capacity Test Contract (Adjudication)

## Status

```text
G-RES-3-C2: ACCEPTED — MODEL B1
Date:       2026-09-03
Parent:     0060-gres3-capacity-envelope-contract-outline.md
Prerequisites: G-RES-3-C1 ACCEPTED (N_max = 9)
        Profile 02 A4 (protect-once replication)
        Profile 02 A6 (50 logical slots/s)
        Profile 02 A5 (120-byte envelope)
Scope:  benchmark workload, procedure, raw observations
        PASS thresholds NOT in C2 (C3)
Next:       G-RES-3 evidence collection (3×VALID PASS @ N=9)
```

---

## Question

How is **sender-side unicast fan-out capacity** measured for `ProvenNMax` evidence?

C2 freezes **what to run and what to record**. It does **not** freeze PASS/FAIL
thresholds (C3) or claim implementation already passes.

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **B1** | Single-Source worst-case 9-leg UDP fan-out; protect-once; multicast=0; real sends | **ACCEPT** |
| **B2** | Mixed-mode benchmark (multicast + unicast) | **REJECT** |
| **B3** | Multicast-assisted capacity measurement | **REJECT** |
| **B4** | Mock socket / memcpy-only replication | **REJECT** |
| **B5** | Include Opus encode + AudioRecord in capacity gate | **REJECT** (scope pollution) |
| **B6** | Require receiver ACK for completion | **REJECT** |
| **B7** | Same endpoint repeated ×9 as capacity surrogate | **REJECT** |

---

## Frozen benchmark workload — Model B1

```text
ActiveSources              = 1
UnicastFallbackTargets     = 9
MulticastDeliveryLegs      = 0
LogicalMediaRate           = 50 slots/s
SlotPeriodMs               = 20
ProtectedDatagramBytes     = 120          (worst-case legal wire artifact per slot)
Duration                   = sustained     (phase structure below; MeasurementDuration → C3)
PayloadArtifact            = protect-once; byte-identical replication to all 9 legs
```

```text
ProtectedDatagramBytes = 120:
  worst-case legal EmittedProtectedRtpUdpBodyBytes per slot (P02-A5)
  NOT average Opus payload size
  proves frozen envelope upper bound, not typical codec size
```

Aligns with G-RES-3-C1 reference load: `50 × 9 = 450` datagram attempts/s.

---

## What C2 measures vs excludes (frozen)

### IN SCOPE — transport replication capacity

```text
logical slot scheduling
one ProtectedDatagramArtifact per slot
enumerate 9 admitted unicast targets
9 real UDP send operations per emitted slot
per-slot / per-leg sender-side observations
```

### OUT OF SCOPE — excluded from C2 gate

```text
Opus encode performance
AudioRecord / capture pipeline
real-time media decode at receivers
receiver delivery ACK / RTCP
multicast leg contribution
mixed-mode overlap scenarios
```

```text
C2 may use pre-generated legal 120B artifacts (P02 protect-once frozen).
Benchmark tests transport replication capacity, NOT full Media Engine capacity.
```

---

## Execution path (frozen — must match runtime seams)

```text
logical slot tick (20ms nominal)
  → one ProtectedDatagramArtifact for active Source
  → enumerate 9 distinct admitted PerReceiverUnicastEndpoint targets
  → for each target: real UDP send() on production sender path
  → record per-slot / per-leg observations
```

```text
MUST use same key execution seams as future runtime fan-out:
  admission / target enumeration
  byte-identical artifact replication
  actual socket send path (not stubbed)

FORBIDDEN (B4):
  for (i=0..8) memcpy only without real send
  mock send that never hits kernel/socket layer
```

```text
Benchmark MUST NOT be more ideal than runtime:
  no shortcut that skips pacing/slot discipline enforced in production path
  no artificial batching absent from runtime contract
```

---

## Nine distinct receiver legs (frozen — G3-C2-I9)

```text
9 targets MUST be 9 distinct admitted receivers:

  9 distinct receiverModuleId values
  9 distinct PerReceiverUnicastEndpoint bindings
    (distinct destination identity per P02-A1)

FORBIDDEN (B7):
  same IP:port sent 9 times counted as 9-target capacity
```

```text
Evidence topology note:
  laboratory MAY use sink harness / emulated endpoints if they present
  distinct admitted receiver identities to sender execution
  real multi-device vs sink harness choice → evidence plan (not C2 PASS threshold)

Logical capacity target remains 9 distinct admitted unicast legs.
```

---

## UDP send completion — no receiver ACK (frozen — G3-C2-I7)

```text
Sender-side leg observation completes at UDP send return (success or failure).

send() success ≠ receiver received

C2 MUST NOT require:
  receiver ACK
  end-to-end delivery confirmation
  RTCP feedback for capacity PASS

Supplementary receiver receive-rate evidence MAY be collected separately;
MUST NOT alter sender capacity contract completion definition.
```

---

## No catch-up / late slot policy (frozen — G3-C2-I8)

```text
Profile 02 / A6: late media expendable; current slot only.

If logical slot N missed or late:
  MUST NOT emit slot N together with N+1 as catch-up burst
  MUST NOT replay historical slots

catchUpEmissionCount:
  count of emissions for logical slots already superseded by later slot time
  strong observation; C3 likely requires = 0
```

---

## Timing observations — separate metrics (frozen — G3-C2-I6)

```text
MUST measure separately:

  slotSchedulingLateness
    = delay from nominal 20ms slot boundary to fan-out start for that slot

  fanoutCompletionDuration (perSlotFanoutCompletionUs)
    = from fan-out start for slot to last leg send() return

Do not conflate into single "25ms delay" without attribution.
```

---

## Raw observations (frozen — collection required; thresholds → C3)

| Observation | Definition |
|-------------|------------|
| `logicalSlotsAttempted` | Nominal 20ms slots that should have been emitted |
| `logicalSlotsEmitted` | Slots that entered fan-out execution |
| `perSlotFanoutCompletionUs` | Fan-out start → last leg send return per slot |
| `slotSchedulingLatenessUs` | Nominal slot boundary → fan-out start per slot |
| `slotDeadlineMissCount` | Fan-out not complete before next logical slot boundary |
| `perLegSendAttemptCount` | Send attempts per receiver leg |
| `perLegSendFailureCount` | Socket/local send failures per leg |
| `maxConsecutiveDeadlineMisses` | Longest streak of slot deadline misses |
| `catchUpEmissionCount` | Historical slot re-emissions (expect 0) |
| `actualDatagramsAttempted` | Total send attempts; expect ≈ `logicalSlotsEmitted × 9` |
| CPU / memory / allocation / thermal | Resource evidence; PASS ceilings → C3 |

```text
C2 freezes observation definitions and collection obligation.
C2 does NOT freeze PASS numeric thresholds.
```

---

## Benchmark procedure phases (frozen structure)

```text
C2 requires three phases; MeasurementDuration numeric → C3.

1. warmup phase
     stabilize scheduler, sockets, allocations before scored measurement

2. measurement phase
     sustained B1 workload at full 50 slots/s × 9 legs
     MUST be long enough to expose GC, scheduler jitter, socket queues,
     thermal drift, allocation leaks (minimum duration frozen in C3)

3. cooldown / terminal snapshot
     final resource readings; no scored catch-up after measurement end
```

```text
C2 does NOT freeze "5 minute PASS" or specific soak length.
C3 adjudicates MeasurementDuration and PASS percentiles.
```

---

## Multicast exclusion (frozen — G3-C2-I2)

```text
During measurement:
  MulticastDeliveryLegs = 0
  no multicast send counted toward capacity
  no mixed-mode shortcut

G-RES-3 worst case = all receivers on UNICAST_FALLBACK (C1).
B2/B3 rejected.
```

---

## Relationship to C1 / C3 / C4

```text
C1: RequiredNMax = 9; workload dimensions reference C1 envelope
C2: how to run benchmark; what to record
C3: PASS thresholds (deadline miss, latency percentiles, CPU, soak duration)
C4: ProvenNMax >= 9 from C2+C3 evidence bundle
```

---

## Explicit non-decisions in C2

```text
NOT in C2:
  PASS/FAIL numeric thresholds
  MeasurementDuration minimum
  device matrix (M01/M02/M03 selection)
  sink harness vs field topology approval
  percentile definitions for latency (p99 etc.) → C3
  thermal throttle abort policy → C3
```

---

## Invariants (frozen)

```text
G3-C2-I1   Benchmark exercises exactly 1 active Source and 9 unicast fallback targets.

G3-C2-I2   Multicast contributes zero delivery capacity during measurement.

G3-C2-I3   Each logical slot produces at most one protected artifact,
           replicated byte-identically to all legs.

G3-C2-I4   Benchmark performs real UDP send operations for all active legs.

G3-C2-I5   Codec/audio capture performance excluded from transport-capacity benchmark.

G3-C2-I6   Scheduling lateness and fanout completion duration measured separately.

G3-C2-I7   No receiver ACK required for sender-side capacity completion.

G3-C2-I8   Missed historical slots never replayed as catch-up bursts.

G3-C2-I9   Targets are distinct admitted receiver identities/endpoints.

G3-C2-I10  C2 defines observations/procedure only; PASS thresholds remain C3.
```

---

## Normative contract insert

```text
G-RES-3-C2 — Sender capacity test (Model B1):

1 Source × 9 distinct unicast fallback legs × 50 slots/s × 120B worst-case artifact.
Real UDP sender path; multicast=0; protect-once replication.
Pre-generated artifact permitted; no Opus/AudioRecord in gate.
Warmup / measurement / cooldown phases; observations frozen; thresholds → C3.

B2–B7 rejected. G3-C2-I1..I10 frozen.
```

---

## Consequences

```text
C3: freeze slotDeadlineMissCount, fanoutCompletionUs percentiles, catchUp=0,
    MeasurementDuration, CPU/thermal ceilings
Evidence runs: implement B1 harness before claiming ProvenNMax
```

---

## References

- `docs/analysis/0060-gres3-capacity-envelope-contract-outline.md`
- `docs/analysis/0060-gres3-c1-product-capacity-envelope-adjudication.md`
- `docs/analysis/0058-profile-02-a4a5-srtp-wire-envelope-adjudication.md`
- `docs/analysis/0058-profile-02-a6-pacing-wirepps-adjudication.md`
- `docs/analysis/0058-profile-02-a9-fanout-envelope-adjudication.md`
- `docs/analysis/0058-profile-02-a1-destination-ingress-adjudication.md`
