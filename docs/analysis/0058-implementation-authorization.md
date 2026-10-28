# ADR-0058 — Implementation Authorization

## Status

```text
Status:                 IMPLEMENTATION AUTHORIZATION
Authorization Decision: AUTHORIZED
Effective:              2026-08-28
Implementation:         AUTHORIZED
E2b:                    PENDING
Implementation Complete: NO
```

```text
Authorization does NOT imply:
  implementation PASS
  E2b PASS
  field/runtime PASS
  ADR-0056 superseded
  ADR-0057 modification authorization
```

## Effective scope

```text
ADR-0058 Implementation Profiles 01 / 02 / 03 only
```

## 1. Authorized

```text
A. Profile 01
   Implement the frozen control / Source / key facts
   required by ADR-0058.

B. Profile 02
   Implement the frozen RTP/SRTP wire contract:
     UDP media ingress/egress
     RTP structural handling
     SRTP AEAD/authentication
     replay admission
     replayWindowPackets = 64
     Source/SSRC binding gates
     RTCP discard boundary
     SafeUdpPayloadBytes = 120
     frozen sender/wire invariants

C. Profile 03
   Implement the frozen Media Runtime:
     AdmittedMediaSource execution boundary
     per-Source runtime lifecycle
     HARD FENCE execution exclusion
     jitter/reorder/deadline handling
     LATE_FOR_PLAYOUT
     Opus decode / bounded PLC
     Top-K selection
     bounded decoder warm-up
     mix / peak protection / playout
     decoder/jitter resource caps
     MulticastLock lifecycle
     interface/socket rebind behavior
     bounded runtime degradation evidence

D. Testability infrastructure
   Implement C-IG-01 / C-IG-02 seams and harnesses
   necessary to execute E2a R01–R16.

E. Conformance tests
   Integrate E1 corpus verification and
   E2a R01–R16 acceptance tests required for E2b.
```

Authorization is semantic scope, not arbitrary file scope. Classes, interfaces,
fixtures, fake/injectors and local wiring MAY be added/changed only as required
for the behaviors above.

## 2. Not authorized

```text
- Reopen or silently modify Profile 01 / 02 / 03
- Weaken frozen constants or acceptance criteria
- Remove or bypass ADR-0056
- Bypass ActualMediaEdgeSet / Recovery Binding authority
- Modify ADR-0057 semantics
- Modify GROUP PTT semantics
- Modify WiFi Recovery semantics
- Add application-level mesh routing
- Introduce distributed forwarding tree / new multi-Anchor design
- Convert runtime observations into new authority
- Invent new Health axes / enums / semantic mappings
- Invent new protocol failure classes merely for implementation convenience
- Make packet arrival create Source admission
- Allow rebind / packet arrival / resource recreation to resurrect HARD-FENCED execution
- Make control/authority progress synchronously depend on Native teardown
- Use RTCP feedback, retransmission, NACK/PLI/FIR,
  REMB/TWCC or congestion-control behavior forbidden by Profile 02
```

If implementation would cross any item above:

```text
STOP IMPLEMENTATION
→ classify the issue
→ explicit review
→ REOPEN / AMEND only if a real contract contradiction exists
```

Convenience is not expansion of authorization.

## 3. Implementation conditions (REQUIRED)

```text
C-IG-01    REQUIRED
C-IG-02    REQUIRED
C-SCOPE-01 REQUIRED
C-SCOPE-02 REQUIRED
C-AUTH-01  REQUIRED
C-EVID-01  REQUIRED
```

Full text of each condition is frozen in
`0058-implementation-readiness-review.md`.

```text
HARD FENCE
  = immediate logical execution exclusion

Native/resource teardown
  = may be asynchronous

Authority/control-plane progress
  = MUST NOT wait for teardown completion
```

Test seams improve testability only; they gain no production authority.

## 4. Issue classification during implementation

```text
FIXTURE_DEFECT
  fixture/harness construction is wrong
  → fix fixture
  → contract remains frozen

IMPLEMENTATION_GAP
  frozen expected behavior is clear
  implementation does not conform
  → fix implementation
  → contract remains frozen

CONTRACT_CONTRADICTION
  frozen requirements provably cannot
  simultaneously be satisfied
  → STOP affected implementation
  → explicit REOPEN / AMEND
```

`test failure → weaken frozen Profile` is forbidden.

Ordinary `IMPLEMENTATION_GAP` does **not** automatically revoke this
authorization; work may continue inside the authorized scope until E2b PASS.

## 5. E2b — Implementation Conformance Exit

Implementation landing ≠ ADR-0058 complete. Exit requires E2b:

```text
E2b PASS requires:

1. E1 regression
   Profile 02 Q9 corpus:
     5 positive + 12 negative
     byte-exact verification PASS

2. Profile 02 invariants
   frozen wire/SRTP/replay/source-binding
   invariants PASS

3. Profile 03 acceptance
   R01–R16 PASS

4. Implementation conditions
   C-IG-01 .. C-EVID-01 satisfied

5. Scope audit
   no unauthorized semantic changes to:
     ADR-0056
     ADR-0057
     GROUP PTT
     WiFi Recovery

6. Authority/runtime audit
   no Native teardown wait introduced into
   authority/control-plane progress

7. Contract status
   CONTRACT_CONTRADICTION = NONE
```

```text
E2b != PASS
→ ADR-0058 implementation is NOT conformant
→ do not declare implementation COMPLETE
```

## Formal chain state after this authorization

```text
Profile 01: FROZEN
Profile 02: FROZEN / CLOSED
Profile 03: FROZEN / CLOSED

Cross-Profile Consistency: PASS
E1: PASS
E2a: PASS
Conformance Evidence Gate: PASS
Implementation Readiness: PASS WITH IMPLEMENTATION CONDITIONS

Implementation Authorization: AUTHORIZED
Implementation: AUTHORIZED

E2b: PENDING
Implementation Complete: NO
```

Next formal gate: **E2b Implementation Conformance**.
