# G-RES-3-C1 — Product Capacity Envelope (Adjudication)

## Status

```text
G-RES-3-C1: ACCEPTED — MODEL N1
Date:       2026-09-03
Parent:     0060-gres3-capacity-envelope-contract-outline.md
            ADR-0060 Decision 4, G-RES-3
Prerequisites: Profile 02 A9 ACCEPTED (symbols + CAPACITY_NOT_ADMISSIBLE)
        Profile 02 A6 (50 logical slots/s nominal)
Scope:  freeze V1 product member envelope and required N_max contract
        NOT capacity benchmark execution (C2/C3)
Next:       G-RES-3 evidence collection (3×VALID PASS @ N=9)
```

---

## Question

What is the **V1 declared Conference member envelope**, and what **`N_max`**
capacity requirement does correctness impose — before any benchmark is run?

C1 freezes **contract values** only. It does **not** claim current implementation
can serve 9 legs.

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **N1** | 10-member V1 envelope; `N_max = 9`; `MaxUnicastFanOutTargetsPerSource = 9` | **ACCEPT** |
| **N2** | `N_max = 7` for implementation convenience (8-member effective envelope) | **REJECT** (silent product shrink) |
| **N3** | `N_max` derived from multicast availability / mixed-mode typical case | **REJECT** |
| **N4** | No frozen numeric; benchmark-first | **REJECT** (no PASS/FAIL contract) |

---

## Frozen V1 product envelope (Model N1)

```text
V1MaxConferenceMembers = 10
```

```text
Product target (unchanged from ADR-0059 / ADR-0060 framing):
  8–10 person Conference supported at architecture level
  V1 correctness contract signs for maximum declared envelope = 10 members
```

---

## Frozen capacity symbols (Model N1)

```text
N_max = 9

MaxUnicastFanOutTargetsPerSource = 9
```

```text
Relationship:

  N_max
    = V1MaxConferenceMembers - 1
    = 9

  MaxUnicastFanOutTargetsPerSource >= N_max   (A9 closure; equality at V1 freeze)
```

```text
Per active Source, worst-case correctness demand:

  WorstCaseUnicastTargetsPerSource = M - 1

  M = 10  →  9 targets
  M = 8   →  7 targets (within envelope; still requires N_max >= 9 at cap)
```

---

## Required vs proven capacity (frozen)

```text
RequiredNMax = 9     FROZEN (contract — this document)

ProvenNMax   = UNKNOWN until G-RES-3 capacity evidence (C2/C3/C4)
```

```text
Freezing N_max = 9 means:

  V1 correctness path MUST be proven for 9 simultaneous unicast fan-out targets
    per active Source under worst-case fallback

Freezing N_max = 9 does NOT mean:

  current implementation already supports 9
  benchmark PASS is assumed
```

### Evidence closure rule (pointer to C4)

```text
ProvenNMax >= 9   → G-RES-3 capacity PASS (for V1 envelope)

ProvenNMax = 7    → G-RES-3 FAIL for 10-member V1 envelope
                    → explicit product/architecture decision required:
                        A. optimize sender implementation
                        B. shrink V1 envelope (e.g. 8 members) via new ADR
                        C. new ADR for replicator/relay topology
                    → MUST NOT silently change N_max to 7 post-failure
```

---

## Worst-case correctness mode (frozen)

```text
G-RES-3 capacity evidence MUST target:

  all remote receivers lose multicast eligibility
  → sender serves each receiver on UNICAST_FALLBACK

NOT:

  "normal case" mostly multicast
  average mixed-mode overlap
  multicast availability reducing required unicast targets
```

```text
Multicast contribution to correctness capacity accounting: ZERO

Optimization path availability does not reduce RequiredNMax.
```

Aligns with ADR-0060: multicast = optimization only; unicast fan-out = correctness.

---

## Overflow admission (frozen)

```text
requiredTargets > MaxUnicastFanOutTargetsPerSource (9)
  → CAPACITY_NOT_ADMISSIBLE   (P02-A9)

MUST NOT:
  silent subset delivery
  relay without new ADR
  degrade to multicast-only correctness
```

```text
10-member Conference at cap:
  9 remote receivers → admissible at limit
  10th member scenario beyond V1MaxConferenceMembers → out of V1 envelope (admission layer)
```

---

## Capacity envelope — wire load reference (frozen arithmetic)

Single active Source, worst-case 9 fallback receivers, Profile 02 nominal rates:

```text
LogicalMediaRate (nominal)     = 50 slots/s        (A6; per Source)

UnicastTargets (worst case)    = 9

WorstCaseUnicastDatagrams      = 50 × 9 = 450 datagrams/s

MaxEmittedProtectedPayloadBytes per datagram = 120   (A5 envelope)

MaxProtectedPayloadRate        = 120 × 450 = 54,000 B/s  (~432 kbps UDP payload)
```

```text
This is per speaking Source under full fallback.
UDP payload upper bound — NOT full L2/L3 wire bitrate.
NOT sole PASS criterion (see C3).
```

### What scales linearly vs not (frozen guidance for C2/C3)

```text
Profile 02 protect-once (A4): encode once → RTP once → SRTP protect once → replicate

Should NOT scale linearly with N:
  Opus encode
  SRTP protect
  RTP seq / ROC advancement per logical slot

Scales linearly with N:
  UDP send operations
  socket / kernel work
  network bytes (replicated payload)
  per-leg bookkeeping

G-RES-3 evidence MUST cover scheduling, send-loop timing, socket behavior,
allocation, and media deadline — not bandwidth alone.
```

---

## TopK boundary (frozen — do not conflate)

```text
TopK = 4 is receiver decode/mix policy:
  receiver mixes up to 4 remote Sources

TopK is NOT:
  sender simultaneously emitting 4 independent Sources per capacity gate

Single-Source sender capacity gate (C2 default):
  50 × 9 = 450 datagrams/s

If a Module may origin multiple concurrent independent Sources, that is a
separate source-concurrency capacity dimension — NOT inferred from TopK.
```

---

## Relationship to Profile 02 A9 (frozen)

```text
A9 froze symbols and CAPACITY_NOT_ADMISSIBLE; deferred numerics to G-RES-3.

C1 now freezes:
  N_max = 9
  MaxUnicastFanOutTargetsPerSource = 9

Closure:
  MaxUnicastFanOutTargetsPerSource >= N_max >= V1MaxConferenceMembers - 1
  9 >= 9 >= 9   ✓
```

---

## Explicit non-decisions in C1

```text
NOT in C1:
  benchmark scenario details          → C2
  PASS metrics / percentiles / soak   → C3
  ProvenNMax measurement method       → C2/C3
  field device matrix (M01/M02/M03)   → evidence plan
  admission UX for 11th member        → product layer
  relay / replicator                  → rejected V1 (ADR-0060)
```

---

## Invariants (frozen)

```text
G3-C1-I1  V1MaxConferenceMembers = 10.

G3-C1-I2  N_max = 9 = V1MaxConferenceMembers - 1.

G3-C1-I3  MaxUnicastFanOutTargetsPerSource = 9.

G3-C1-I4  RequiredNMax is contract; ProvenNMax is evidence-only.

G3-C1-I5  Capacity worst case = all receivers on UNICAST_FALLBACK; multicast ZERO.

G3-C1-I6  requiredTargets > 9 → CAPACITY_NOT_ADMISSIBLE.

G3-C1-I7  Benchmark failure MUST NOT retroactively shrink N_max without ADR.

G3-C1-I8  TopK does not multiply single-Source sender fan-out requirement.
```

---

## Normative contract insert

```text
G-RES-3-C1 — Product capacity envelope (Model N1):

V1MaxConferenceMembers = 10; N_max = 9; MaxUnicastFanOutTargetsPerSource = 9.
RequiredNMax FROZEN; ProvenNMax from evidence only.
Worst-case: 1 Source → 9 unicast legs; multicast does not reduce requirement.
Reference load: 450 dgram/s, ~54 KB/s protected payload per speaking Source.

N2/N3/N4 rejected. G3-C1-I1..I8 frozen.
```

---

## Consequences

```text
C2: 9-leg sustained fallback benchmark contract
C3: slot deadline / fan-out latency / resource metrics
C4: ProvenNMax >= 9 gate
ADR-0060 "Exact N_max numeric value" deferral closed at 9
Profile 02 runtime MUST set MaxUnicastFanOutTargetsPerSource >= 9 at implementation
```

---

## References

- `docs/analysis/0060-gres3-capacity-envelope-contract-outline.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
- `docs/analysis/0058-profile-02-a9-fanout-envelope-adjudication.md`
- `docs/analysis/0058-profile-02-a6-pacing-wirepps-adjudication.md`
- `docs/analysis/0058-profile-02-a4a5-srtp-wire-envelope-adjudication.md`
