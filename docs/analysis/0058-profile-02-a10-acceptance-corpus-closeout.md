# P02-A10 — Acceptance Corpus and Amendment Closeout (Adjudication)

## Status

```text
P02-A10: ACCEPTED — CORPUS MATRIX C1–C8 FROZEN
Date:    2026-09-03
Prerequisites: P02-A1..A9 ACCEPTED
Parent:  0058-profile-02-amendment-unicast-fanout-outline.md

Profile 02 resilience transport amendment:
  ADJUDICATION CHAIN COMPLETE (A1–A10 ACCEPTED)
  CONTRACT AMENDMENT FROZEN — pending Q9-style hex corpus implementation PASS

Media Engine: NOT AUTHORIZED (ADR-0060 G-RES-2/4, G-RES-3 numerics remain open)
```

---

## Purpose

P02-A10 invents **no new wire semantics**. It freezes:

```text
1. machine-verifiable acceptance corpus classes C1–C8
2. fixture style and anti-patterns
3. amendment closeout verdict vs Media Engine authorization boundary
```

---

## Amendment closeout verdict

When corpus classes **C1–C8** all **PASS** against an implementation under test:

```text
MAY claim:
  Profile 02 resilience transport amendment FROZEN / CONTRACT COMPLETE

MUST NOT claim:
  Media Engine AUTHORIZED
  ADR-0060 G-RES-1..5 satisfied
  Profile 03 complete
  N_max / MaxUnicastFanOutTargetsPerSource numerics evidenced
  E3a optimization qualification closed
```

```text
Profile 02 amendment PASS
  ≠ Media Engine AUTHORIZED

Remaining before Media Engine cutover (ADR-0060):
  G-RES-2  continuous liveness + mid-session downgrade (Profile 03)
  G-RES-3  N_max numeric freeze + capacity evidence (A9 variables defined; values open)
  G-RES-4  liveness publication / consumption contract
  G-RES-5  E2b conformance update for resilience semantics
```

**Next main line:** Profile 03 resilience amendment (receiver liveness → publication →
sender mode execution). E3a continues as optimization evidence only.

---

## Fixture style (frozen)

Continues pre-amendment Profile 02 Q9 conventions:

```text
golden vectors:
  byte-exact where deterministic
  same ProtectedDatagramArtifact bytes across fan-out legs (K1)

negative vectors:
  exact rejection seam + frozen failure class name

fan-out vectors:
  assert artifact identity equality across legs:
    protectedBytes(multicast) == protectedBytes(unicast R*) per slot

mutation tests:
  permitted only where they do not invent new semantics
```

### Schema extension (frozen)

```text
GoldenOrNegativeVector {
  context
    ... existing Q9 context fields ...
    ingressTransportBinding     // A1: MULTICAST | UNICAST admitted binding
    deliveryDirective           // wire execution: legs active for slot t
    activeUnicastFanOutTargets  // A9 count
    maxUnicastFanOutTargetsPerSource  // A9 cap under test

  datagramBytes                 // byte-exact per leg or per artifact as specified
  expectedOutcome
    ACCEPT | REJECT @ seam [+ failure class]
  legOutcomes[]                 // optional: PerLegDeliveryOutcome per leg (A8)
}
```

```text
deliveryDirective (examples):
  MULTICAST_ONLY
  UNICAST_TO[moduleId...]
  MIXED_MULTICAST_PLUS_UNICAST_TO[moduleId...]
  TRANSITION_OVERLAP_TO[moduleId]   // C8 only; no TransitionOverlapMaxMs value
```

### Anti-pattern (forbidden in corpus design)

```text
FORBIDDEN:
  separate golden ciphertext per unicast destination for the same slot
  (would contradict A4 K1 protect-once byte-identical replication)

REQUIRED for C3/C8:
  protectedBytes(multicast) == protectedBytes(unicast M02) == protectedBytes(unicast M03)
  destination/socket metadata MAY differ; protected UDP payload MUST be identical
```

---

## Acceptance matrix C1–C8 (frozen)

| Case | Must prove | Primary adjudication refs |
|------|------------|---------------------------|
| **C1 Multicast regression** | Pre-amendment Profile 02 Q9 multicast golden bytes **unchanged** | baseline Q9 |
| **C2 Unicast-only** | Legal datagram via `PerReceiverUnicastEndpoint`; RTP identity matches single Source timeline | A1, A2/A3 |
| **C3 Mixed-mode** | Same slot: multicast + multiple unicast legs carry **identical** `ProtectedDatagramArtifact` | A2–A4, K1 |
| **C4 Seq / ROC continuity** | multicast↔unicast mode change does not reset SSRC/seq/sender ROC; one advance per slot | A2/A3, A4, A7 |
| **C5 Failure isolation** | One unicast leg `SEND_FAILED`; other legs `SENT`; no timeline rollback; no old-slot retry | A8 |
| **C6 Envelope / capacity** | Each datagram ≤120B; target count > cap → `CAPACITY_NOT_ADMISSIBLE` (not `SEND_FAILED`) | A5, A9 |
| **C7 Pacing semantics** | 50 logical slots × N legs does **not** trip `WirePpsCeiling=60`; 61 logical slots does; no old-slot catch-up | A6 |
| **C8 Transition duplicate** | Same Source/SSRC/seq/timestamp/artifact on multicast + unicast to same receiver → duplicate delivery; media admit ≤ once | A7 |

---

## Per-case acceptance criteria (normative)

### C1 — Multicast regression

```text
PASS when:
  entire pre-amendment Q9 multicast golden corpus verifies unchanged
  no byte drift in ACCEPT vectors
  no new failure classes on baseline negatives

Proves: amendment did not regress optimization-path wire contract
```

### C2 — Unicast-only

```text
PASS when:
  sender emits via PerReceiverUnicastEndpoint(receiverModuleId, moduleFixedIp, mediaPort)
  receiver pre-auth uses UNICAST_INGRESS + AdmittedIngressTransportBinding (A1)
  SSRC/seq/timestamp match single Source timeline for fixture context
  golden ACCEPT at Q5/Q6 seams

Proves: correctness-path destination + ingress binding wire-legal
```

### C3 — Mixed-mode

```text
PASS when:
  one slot, one ProtectedDatagramArtifact
  multicast leg + ≥2 unicast legs to distinct receivers
  protected UDP payload bytes identical on all legs
  LogicalMediaEmissionPps counts one emission (C7 adjunct)

Proves: K1 replication + fan-out without per-destination ciphertext drift
```

### C4 — Seq / ROC continuity

```text
PASS when:
  fixture simulates MULTICAST → UNICAST_FALLBACK and reverse upgrade path
  seq continues (e.g. 200 → 201), SSRC unchanged, sender ROC continuous
  receiver replay does not treat unicast leg as new stream (A4)
  sequence advances once per slot across fan-out (A2/A3)

Negative:
  unicast restart at seq=0 after multicast → REJECT implementation

Proves: mode transition does not fracture timeline
```

### C5 — Failure isolation

```text
PASS when:
  injected SEND_FAILED on one unicast leg
  other legs SENT with byte-identical artifact
  LogicalMediaEmissionOutcome = EMITTED; seq advanced
  slot t+1 does not include retried slot t to failed leg

Negative:
  leg failure holds seq / triggers whole-slot retry → REJECT (F2)

Proves: A8 F1 per-leg isolation
```

### C6 — Envelope / capacity

```text
PASS when:
  all fan-out leg datagrams EmittedProtectedRtpUdpBodyBytes <= 120
  ActiveUnicastFanOutTargets > MaxUnicastFanOutTargetsPerSource
    → CAPACITY_NOT_ADMISSIBLE
    → NOT classified as SEND_FAILED
    → no silent subset delivery

Proves: A5 per-datagram envelope + A9 overflow class
```

### C7 — Pacing semantics

```text
PASS when:
  50 logical emissions/s with N unicast legs (e.g. N=9) → WirePpsCeiling NOT violated
  FanoutDatagramRate observability = 50×N (+ multicast if applicable) — metric only
  61 logical emissions in 1s → WirePpsCeiling anomaly detected
  scheduled catch-up of expired slots × legs → REJECT

Proves: A6 metering object clarification
```

### C8 — Transition duplicate

```text
PASS when:
  same slot delivered multicast + unicast to SAME receiver (overlap scenario)
  identical protected artifact on both legs
  second arrival → duplicate disposition (SRTP_REPLAY_DUPLICATE or equivalent)
  at most one admission to media pipeline for that slot

MUST NOT freeze in C8:
  TransitionOverlapMaxMs numeric bound
  capability FSM / ALIVE hysteresis (Profile 03)

Proves: A7 duplicate identity semantics testable at wire seam
```

---

## Corpus PASS gate (amendment closeout)

```text
Profile 02 resilience amendment CONTRACT CLOSEOUT requires:

  C1 PASS
  C2 PASS
  C3 PASS
  C4 PASS
  C5 PASS
  C6 PASS
  C7 PASS
  C8 PASS

All vectors byte-exact per Q9.1 where deterministic.
```

Until hex corpus is generated and verification PASS, status remains:

```text
ADJUDICATION COMPLETE — IMPLEMENTATION CORPUS PENDING
```

---

## Adjudication chain summary (A1–A10)

| Item | Verdict | Document |
|------|---------|----------|
| A1 Destination + ingress binding | ACCEPT Option A | `0058-profile-02-a1-destination-ingress-adjudication.md` |
| A2/A3 Encode + RTP timeline | ACCEPT M1′ | `0058-profile-02-a2a3-encode-rtp-timeline-adjudication.md` |
| A4/A5 SRTP + wire envelope | ACCEPT K1 + full inheritance | `0058-profile-02-a4a5-srtp-wire-envelope-adjudication.md` |
| A6 Pacing / WirePpsCeiling | ACCEPT | `0058-profile-02-a6-pacing-wirepps-adjudication.md` |
| A7 Mixed-mode overlap | ACCEPT O1 | `0058-profile-02-a7-mixed-mode-overlap-adjudication.md` |
| A8 Send failure isolation | ACCEPT F1 | `0058-profile-02-a8-send-failure-isolation-adjudication.md` |
| A9 Fan-out envelope | ACCEPT C1 | `0058-profile-02-a9-fanout-envelope-adjudication.md` |
| A10 Acceptance corpus | ACCEPT C1–C8 matrix | this document |

---

## References

- `docs/analysis/0058-profile-02-amendment-unicast-fanout-outline.md`
- `docs/analysis/0058-implementation-profile-02-rtp-wire.md` (Q9, Q10)
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
