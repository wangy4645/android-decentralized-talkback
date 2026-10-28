# ADR-0058 Conformance Evidence Gate

## Status

```text
Stage:                  IMPLEMENTATION AUTHORIZED · E2b AUDIT COMPLETE
Contract design:        COMPLETE
Cross-Profile Review:   PASS
E1 Profile 02 corpus:   PASS
E2a Profile 03 specs:   PASS
E2b Implementation:     OPEN (14 PASS · 2 GAP · 0 CONTRADICTION)
Implementation Readiness: PASS WITH IMPLEMENTATION CONDITIONS
Implementation Authorization: AUTHORIZED
Implementation:         AUTHORIZED
```

**E2b audit:** [0058-e2b-r01-r16-conformance-audit.md](./0058-e2b-r01-r16-conformance-audit.md)

Conformance Evidence Gate at evidence-spec level:

```text
E1 PASS + E2a PASS
  = authorization prerequisites satisfied at evidence-spec level
```

Implementation Authorization:

```text
AUTHORIZED
  doc: 0058-implementation-authorization.md
  ≠ E2b PASS
  ≠ Implementation Complete
```

This gate produces **conformance evidence** for already frozen Profiles 01–03.
It MUST NOT open a new design Grill, invent parameters, invent failure classes,
or authorize product multicast code.

## Result model

Every evidence item returns exactly one of:

```text
PASS
  fixture + observed behavior conforms to frozen contract

FIXTURE_DEFECT
  evidence construction is wrong
  -> fix fixture
  -> MUST NOT reopen contract

IMPLEMENTATION_GAP
  frozen contract is clear; implementation does not conform
  -> record gap
  -> MUST NOT reopen contract

CONTRACT_CONTRADICTION
  frozen requirements cannot simultaneously hold
  -> explicit REOPEN / AMEND only
```

Evidence failure is not automatically a design problem.

## Authorization-safe split for Profile 03

```text
E2a — Evidence Specification
  R01–R16 fixtures / test cases fully specified
  executable harness shape established
  = implementation-authorization prerequisite

E2b — Implementation Conformance
  actual product implementation passes R01–R16
  = post-authorization / post-implementation evidence
```

Because `Implementation: NOT AUTHORIZED`, E2b MUST NOT be required to open
authorization. Q10's “R01–R16 fixtures/tests prepared and executable” means
**E2a**, not product R01–R16 PASS.

## Order

```text
Cross-Profile Consistency Review     PASS
        |
E1  Profile 02 Q9 hex corpus
E2a Profile 03 R01–R16 evidence specs
        |
Conformance Evidence Gate            PASS (E1 + E2a)
        |
Implementation Readiness Review
        |
Explicit Implementation Authorization
        |
PRODUCT CODE
        |
E2b R01–R16 implementation conformance
+ field / runtime verification
```

---

## E1 — Profile 02 Q9 Hex Corpus

### Deliverables

```text
docs/analysis/0058-profile-02-q9-golden-vectors.json
docs/analysis/0058-profile-02-q9-corpus-manifest.md
talkback/scripts/adr0058_p02_q9_vectors.py   (or equivalent verifier)
```

Each fixture MUST use the frozen schema:

```text
GoldenOrNegativeVector {
  context
  datagramBytes
  expectedOutcome
}
```

### Required coverage

```text
G1    valid normal                         ACCEPT
G2    valid exact 120-byte boundary        ACCEPT
G3+   >=2 valid distinct Opus/VBR lengths  ACCEPT

N1    121-byte oversize
        REJECT @ Q8 / WIRE_ENVELOPE_OVERSIZE
N2    truncated RTP header                 REJECT @ Q2
N3    invalid RTP version                  REJECT @ Q2
N4    malformed CSRC/extension structure   REJECT @ Q2
N5    structural length inconsistency
        REJECT @ earliest authoritative seam
N6    RTCP-shaped shared-ingress datagram
        REJECT @ Q7 / RTCP_PACKET_DISCARDED
N7    SRTP auth/tag corruption             REJECT @ Q5
N8a   wrong SRTP key                       REJECT @ Q5
N8b   wrong Source/SSRC binding
        REJECT @ owning frozen seam (V1: Q3)
N9    replay duplicate
        REJECT @ Q6 / SRTP_REPLAY_DUPLICATE
N10   replay too-old
        REJECT @ Q6 / SRTP_REPLAY_TOO_OLD
N11   finite fuzz-derived regression cases
        per-vector expectedOutcome
```

### Context requirements

Every vector, including G1/G2/G3+, MUST include whatever external facts are
required to reproduce the verdict, at least as applicable:

```text
SRTP master key / salt
SSRC / Source binding / AdmittedMediaSource identifiers
ROC / replay state
MediaGroupEndpoint
Profile constants
replayWindowPackets = 64
MaxPlayoutDelayMs / MaxReorderPackets are NOT SRTP-admission inputs
```

Negatives SHOULD be one controlled mutation from a known-good golden, with the
mutation reason recorded.

### Verification criteria

```text
deterministic under identical context
byte-exact datagramBytes
expectedOutcome names only frozen seams / classes
replayWindowPackets = 64 in context and N9/N10 construction
no invented failure-class namespace
independent cryptography / parser verification PASS
```

### E1 gate verdict

```text
E1 PASS
  corpus: docs/analysis/0058-profile-02-q9-golden-vectors.json
  manifest: docs/analysis/0058-profile-02-q9-corpus-manifest.md
  generator/verifier: talkback/scripts/adr0058_p02_q9_vectors.py
  independent verification: PASS
    5 positive + 12 negative
    G2 udpPayloadLength == 120
    N1 udpPayloadLength == 121 → WIRE_ENVELOPE_OVERSIZE
    replayWindowPackets == 64 (N9/N10)
    RFC 7714 §8.1 IV smoke vector PASS
  no CONTRACT_CONTRADICTION encountered during generation
```

---

## E2a — Profile 03 R01–R16 Evidence Specification

### Deliverables

```text
docs/analysis/0058-profile-03-r01-r16-acceptance-spec.md
  one section per R01–R16:
    setup
    frozen constants asserted
    steps
    expected observations
    non-expectations (no authority mutation, etc.)

optional harness shape note:
  unit / instrumented fake AdmittedMediaSource + PCM hooks
  MUST NOT require live multicast product path before authorization
```

### Matrix (frozen; do not enlarge in this gate)

```text
R01  normal Top-K / decode / mix
R02  >4 simultaneous V=1
R03  V=0 replacement
R04  reorder within window
R05  late >120ms → LATE_FOR_PLAYOUT
R06  PLC 1..5
R07  PLC >5
R08  warm-up <=40ms allowance
R09  HARD FENCE + late packets
R10  key epoch fence
R11  decoder #5 transition only; mix <=4
R12  jitter cap 10
R13  MulticastLock lifecycle
R14  interface rebind
R15  AudioTrack / playout failure evidence
R16  resource exhaustion evidence
```

### E2a completeness criteria

```text
every R0x has a fully specified fixture/test case
harness shape is executable without product authorization
asserts frozen constants:
  MaxPlayoutDelayMs=120
  MaxReorderPackets=4
  TopK=4
  TopKHoldMs=200
  TopKHysteresisLevel=3
  MaxConsecutivePlcFrames=5
  DecoderWarmupMs=40
  MixMaxSources=4
  OutputPeakAbs<=0.89 FS
  MaxLiveDecoders=5
  MaxJitterSources=10
  HealthAggregationWindowMs=1000 (evidence window only)
no new Health / failure classes
no product-contract modification
```

### E2a gate verdict

```text
E2a PASS
  spec: docs/analysis/0058-profile-03-r01-r16-acceptance-spec.md
  R01–R16 specification completeness: PASS
  Harness executability specification: PASS
  CONTRACT_CONTRADICTION: NONE
  IMPLEMENTATION_GAP: IG-01 / IG-02 (non-blocking)
  ≠ product implementation PASS
```

---

## E2b — deferred

E2b runs only after explicit implementation authorization and product code
exists. Failures default to `IMPLEMENTATION_GAP` unless a true
`CONTRACT_CONTRADICTION` is proven.

## Conformance Evidence Gate PASS (evidence-spec level)

```text
E1 PASS + E2a PASS
  -> Conformance Evidence Gate PASS at evidence-spec level
  -> proceed to Implementation Readiness Review

Still NOT:
  Implementation AUTHORIZED
  product multicast code permitted
  E2b product R01–R16 PASS
```

## Explicit exclusions

```text
no Profile 04
no new Grill questions
no DSCP / AEC algorithm freeze
no Gate 0 lab signing from this document
no ADR-0056 deletion
```
