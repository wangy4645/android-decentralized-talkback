# P03-R10 — Acceptance Matrix and Amendment Closeout (Adjudication)

## Status

```text
P03-R10: ACCEPTED — MATRIX C1–C10 + X1–X3 FROZEN
Date:    2026-09-03
Prerequisites: P03-R1..R9 ACCEPTED
Parent:  0058-profile-03-resilience-amendment-outline.md

Profile 03 resilience amendment:
  ADJUDICATION CHAIN COMPLETE (R1–R10 ACCEPTED)
  CONTRACT AMENDMENT FROZEN — pending C1–C10 + X1–X3 implementation PASS

Publication transport (G-RES-4): semantic contract CLOSED (P1–P7); evidence PENDING (C1–C12 + X1–X4)
Media Engine: NOT AUTHORIZED
```

---

## Purpose

P03-R10 invents **no new states, timers, or publication wire semantics**. It freezes:

```text
1. machine-verifiable acceptance matrix C1–C10 (maps R1–R9 contract)
2. cross-boundary combination cases X1–X3
3. fixture style and Profile 02 separation boundary
4. three-tier closeout verdict vs Media Engine / G-RES authorization
```

---

## Amendment closeout verdict (three tiers)

### Tier 1 — Adjudication (this document)

```text
R1–R10 adjudication: FROZEN / COMPLETE

MAY claim after R10 ACCEPT:
  Profile 03 resilience amendment adjudication chain closed
  R1–R9 contract semantics frozen
  C1–C10 + X1–X3 acceptance matrix frozen

MUST NOT claim:
  Profile 03 CONTRACT COMPLETE (implementation)
  G-RES-2 implementation/evidence PASS
  G-RES-4 closed
  Media Engine AUTHORIZED
  ADR-0060 G-RES-1..5 satisfied
```

### Tier 2 — Implementation acceptance

```text
R10 acceptance implementation: PENDING

Profile 03 resilience amendment CONTRACT COMPLETE requires:
  C1 PASS
  C2 PASS
  C3 PASS
  C4 PASS
  C5 PASS
  C6 PASS
  C7 PASS
  C8 PASS
  C9 PASS
  C10 PASS
  X1 PASS
  X2 PASS
  X3 PASS

Until all PASS:
  ADJUDICATION COMPLETE — IMPLEMENTATION ACCEPTANCE PENDING
```

### Tier 3 — Remaining gates (orthogonal)

```text
Publication transport (G-RES-4): semantic contract CLOSED (P1–P7); evidence PENDING (C1–C12 + X1–X4)

G-RES-2 split:
  contract semantics     = CLOSED (Profile 03 R1–R10)
  implementation/evidence = PENDING (C1–C10 + X1–X3 PASS)

G-RES-1  correctness fan-out implementation     = PENDING (Profile 02 C1–C8)
  G-RES-3  contract CLOSED (C1–C4); evidence 3×PASS@N9 PENDING
G-RES-5  E2b conformance update                   = OPEN

Media Engine: NOT AUTHORIZED
```

**Next main line after R10:** G-RES-3 evidence collection (C1–C4 COMPLETE).

---

## Corpus boundary — Profile 03 vs Profile 02 (frozen)

```text
Profile 03 tests MUST NOT re-verify Profile 02 SRTP bytes, wire golden vectors,
or per-leg ciphertext identity.

Profile 03 fixtures MOCK or STUB Profile 02 execution seams:

  P02 fixture provides (inputs):
    admitted multicast ingress event(s)
    unicast leg activated / send failed / capacity rejected
    key fence / incarnation scope signals from upper layers

  P03 under test decides (outputs):
    MulticastReceiveLiveness state
    MulticastReceiveLivenessFact publication
    sender freshness / ordering rejection
    PerReceiverTransportMode
    downgrade / upgrade / transition lifecycle
    fence invalidation behavior
```

```text
FORBIDDEN in P03 corpus:
  byte-exact SRTP golden comparison
  re-opening A4 protect-once replication proofs
  inventing publication transport wire in acceptance fixtures
    (inject facts at R4 consumption seam instead)
```

This preserves the authority stack:

```text
P02  WHAT bytes / legs / timeline / cap
P03  WHEN liveness / mode / transition / fence
```

---

## Fixture style (frozen)

```text
RuntimePolicyVector {
  context
    conferenceMediaIncarnation
    receiverModuleId
    sourceModuleId
    publisherIncarnation          // when testing R4/R9
    keyGenerationId               // when testing R9 key fence
    clock                         // monotonic; injectable for timer tests

  ingressEvents[]               // admitted multicast only (R1/R3)
    timestamp, rtpPacketIdentity  // distinctness for count rules

  publicationInjections[]       // optional: inject at sender consumption seam
    MulticastReceiveLivenessFact

  fenceEvents[]                 // publisher restart | incarnation | key fence

  p02Stubs                      // leg activation outcomes, send results

  expectedOutcomes
    receiverLivenessState
    publishedFact?              // if receiver under test
    senderRejection?            // REJECT_STALE_SCOPE | ORDER | DUPLICATE
    senderKnowledge?            // FRESH | STALE
    perReceiverTransportMode
    transitionLifecycle?        // R8 observability
    overlapDeadlineMs?          // assert <= 120 from transitionStart
}
```

```text
Timer tests:
  inject ingress gaps / bursts at synthetic timestamps
  MUST use R3 frozen numerics (1000/40/300/1000) — no new constants in R10

Transition tests:
  assert transitionStart at sender commit instant (R8)
  assert overlap termination <= TransitionOverlapMaxMs = 120
```

---

## Acceptance matrix C1–C10 (frozen)

| Case | Must prove | Primary refs |
|------|------------|--------------|
| **C1 Initial safe default** | New receiver / new `conferenceMediaIncarnation`: receiver `SUSPECT`; sender `UNICAST_FALLBACK`; MUST NOT default multicast | R2, R5 |
| **C2 Earn ALIVE** | Only admitted multicast ingress with `span >= 1000ms AND distinct >= 40` → ALIVE; single packet / duplicate burst ≠ ALIVE | R1, R2, R3 |
| **C3 Loss progression** | ALIVE, no ingress ≥300ms → SUSPECT; sustained ≥1000ms → DEAD; isolated gap ≠ DEAD | R2, R3 |
| **C4 Recovery hysteresis** | SUSPECT/DEAD → ALIVE only via full 1000ms + 40 distinct recovery evidence; DEAD→ALIVE direct allowed | R2, R3, R7 |
| **C5 Publication ordering/freshness** | Wrong scope / old order / duplicate rejected; publisher restart new lineage accepted; stale ALIVE ≠ eligibility | R4, R5 |
| **C6 Sender FSM** | FRESH+ALIVE → MULTICAST eligible only; SUSPECT/DEAD/STALE/no fact → UNICAST_FALLBACK required | R5 |
| **C7 Downgrade execution** | Loss-of-proof: no wait for DEAD/ACK/Membership; unicast activated immediately; then commit fallback; failure surfaced | R6 |
| **C8 Upgrade execution** | Fresh ALIVE = eligibility only; sender explicit upgrade; eligibility lost before commit → abort; stay unicast | R7 |
| **C9 Transition bound** | Overlap ≤120ms; at most one active transition per (Source, receiver); downgrade preempts upgrade; upgrade timeout → unicast | R8 |
| **C10 Typed fences** | Publisher restart / incarnation / key fence each invalidate only frozen scope; old-scope fact/transition MUST NOT commit post-fence | R9 |

---

## Per-case acceptance criteria (normative)

### C1 — Initial safe default

```text
PASS when:
  new (receiver, conferenceMediaIncarnation) binding:
    MulticastReceiveLiveness = SUSPECT
    PerReceiverTransportMode = UNICAST_FALLBACK
  no multicast-only correctness dependency before earned ALIVE + upgrade

Negative:
  default MULTICAST for new receiver → REJECT (M4)

Proves: R5 initial binding + R2 initial SUSPECT
```

### C2 — Earn ALIVE

```text
PASS when:
  only admitted multicast ingress counts
  ALIVE earned iff span >= AliveEvidenceWindowMs (1000)
    AND distinct valid packets >= AliveMinValidPackets (40)

Negative:
  single packet → remains SUSPECT
  40 duplicates same RTP identity in <1000ms → NOT ALIVE
  sender-side sendto success without ingress → no ALIVE (R1)

Proves: R3 T1 earn dual condition
```

### C3 — Loss progression

```text
PASS when:
  from ALIVE: no ingress >= 300ms → SUSPECT
  from ALIVE: no ingress >= 1000ms → DEAD
  brief isolated gap < 300ms → remains ALIVE

Negative:
  one missed packet → DEAD → REJECT (T2/T3)

Proves: R3 SuspectAfterNoIngressMs / DeadAfterNoIngressMs
```

### C4 — Recovery hysteresis

```text
PASS when:
  from SUSPECT or DEAD:
    ALIVE only after RecoveryEvidenceWindowMs (1000)
      AND RecoveryMinValidPackets (40) distinct admitted ingress
  DEAD → ALIVE direct path allowed (no mandatory intermediate SUSPECT)

Negative:
  single fresh packet after DEAD → NOT ALIVE

Proves: R3 recovery symmetry + R7 eligibility input
```

### C5 — Publication ordering/freshness

```text
PASS when:
  wrong conferenceMediaIncarnation → REJECT_STALE_SCOPE
  same publisherIncarnation, non-increasing seq → REJECT_STALE_ORDER
  duplicate ordering → REJECT_DUPLICATE
  new publisherIncarnation after restart → accepted; seq restarts per R4
  stale last fact (freshness timeout) → sender treats as no eligibility proof
  stale ALIVE ≠ MULTICAST eligible

Negative:
  wall-clock alone determines newer → REJECT (P2)
  stale ALIVE grants eligibility → REJECT

Proves: R4 P1 + R5 consumption matrix
```

### C6 — Sender FSM

```text
PASS when:
  FRESH + ALIVE → MULTICAST eligible (not auto-commit without R7)
  FRESH + SUSPECT → UNICAST_FALLBACK required
  FRESH + DEAD → UNICAST_FALLBACK required
  STALE any state → UNICAST_FALLBACK required
  no acceptable fact → UNICAST_FALLBACK required

Negative:
  FRESH+ALIVE alone forces immediate MULTICAST without upgrade → REJECT (M3)

Proves: R5 M1 eligibility table
```

### C7 — Downgrade execution

```text
PASS when:
  on any loss of MULTICAST eligibility proof (R5):
    unicast leg activation begins immediately
    PerReceiverTransportMode commits UNICAST_FALLBACK when leg admitted
  no wait for DEAD-only, Membership, ACK, or multicast teardown

Negative:
  stop multicast then start unicast → REJECT (D3)
  unicast fail → explicit failure; MUST NOT retain MULTICAST as correctness (D1)

Proves: R6 D1 immediate activation + authoritative commit
```

### C8 — Upgrade execution

```text
PASS when:
  fresh ALIVE grants eligibility only
  sender explicit upgrade decision required
  before MULTICAST commit: eligibility lost → ABORT_UPGRADE → UNICAST_FALLBACK
  after commit: bounded overlap per R8 (C9 adjunct)

Negative:
  auto-upgrade on fresh ALIVE → REJECT (U2)
  packet arrival bypasses upgrade FSM → REJECT (U3)

Proves: R7 U1 earned re-entry
```

### C9 — Transition bound

```text
PASS when:
  TransitionOverlapMaxMs = 120 enforced from transitionStart (sender commit)
  overlap duplicate delivery terminates by deadline
  at most one active transition per (Source, receiver, incarnation)
  downgrade during upgrade → upgrade aborted; downgrade wins
  upgrade timeout without MULTICAST commit → STABLE_UNICAST

Negative:
  overlap > 120ms without new transition → REJECT (B1)
  upgrade timeout forces MULTICAST → REJECT (B3)
  downgrade blocked until overlap ends → REJECT (R8-I2)

Proves: R8 B1 lifecycle + re-entrancy rules
```

### C10 — Typed fences

```text
PASS when:
  publisher restart:
    receiver → SUSPECT; R3 windows cleared; new publisherIncarnation
    sender → lineage A invalid; loss-of-proof → UNICAST_FALLBACK
    NOT conferenceMediaIncarnation change

  conferenceMediaIncarnation C1→C2:
    C1 liveness/publication/mode/transition invalidated
    C2 starts SUSPECT / UNICAST_FALLBACK
    C1 active transition CANCELLED; no post-fence commit

  key-generation K1→K2:
    Source-wide atomic; no slot with mixed K1/K2 legs
    old-key transition cannot commit
    R3 liveness NOT auto-reset (unless incarnation also changes)

Negative:
  restart → publish ALIVE without evidence → REJECT (R9)
  C1 ALIVE resurrects in C2 → REJECT (F3)
  key fence → liveness DEAD → REJECT (R9-I5)

Proves: R9 F1 typed fences
```

---

## Cross-boundary combination cases X1–X3 (frozen)

Isolated C1–C10 PASS is insufficient. These prove R5/R6/R7/R8/R9 intersections.

### X1 — STALE during downgrade; fresh ALIVE during downgrade

```text
Scenario:
  MULTICAST stable
  → publication becomes STALE (sender knowledge)
  → downgrade starts (TRANSITION_TO_UNICAST)
  → fresh ALIVE fact arrives during downgrade transition

PASS when:
  MUST finish fallback transition (no instant reverse — R8 B4)
  commit UNICAST_FALLBACK; overlap terminates <= 120ms
  later MAY initiate new upgrade from STABLE_UNICAST (R7)

Negative:
  fresh ALIVE during downgrade aborts fallback → REJECT
  returns to MULTICAST before fallback complete → REJECT

Proves: R5 stale + R6 + R7 + R8 no-instant-reverse
```

### X2 — Incarnation fence during upgrade pending

```text
Scenario:
  upgrade pending (TRANSITION_TO_MULTICAST)
  → conferenceMediaIncarnation C1→C2

PASS when:
  C1 transition CANCELLED immediately
  C2 scope: receiver SUSPECT; sender UNICAST_FALLBACK
  old C1 ALIVE facts cannot resurrect MULTICAST in C2
  no post-fence commit of C1 transition

Negative:
  C1 upgrade completes after C2 fence → REJECT (R9-I3)

Proves: R7 + R8 + R9 incarnation hard fence
```

### X3 — Key fence during mixed-mode overlap

```text
Scenario:
  mixed-mode overlap active (multicast + unicast to same receiver)
  → key-generation fence K1→K2

PASS when:
  no logical slot has some legs on K1 and others on K2 (R9-I4)
  old-key transition cannot commit after fence
  execution resumes under K2 atomically for Source
  R3 liveness preserved if incarnation unchanged

Negative:
  per-receiver key split on same slot → REJECT (A4 / R9-I4)
  key fence forces DEAD liveness → REJECT (R9-I5)

Proves: R8 overlap + R9 key fence + P02 A4 boundary (stubbed)
```

---

## Matrix PASS gate (amendment closeout)

```text
Profile 03 resilience amendment CONTRACT COMPLETE requires:

  C1 PASS
  C2 PASS
  C3 PASS
  C4 PASS
  C5 PASS
  C6 PASS
  C7 PASS
  C8 PASS
  C9 PASS
  C10 PASS
  X1 PASS
  X2 PASS
  X3 PASS
```

Until implementation PASS:

```text
ADJUDICATION COMPLETE — IMPLEMENTATION ACCEPTANCE PENDING
```

---

## Adjudication chain summary (R1–R10)

| Item | Verdict | Document |
|------|---------|----------|
| R1 Observation ownership | ACCEPT | `0058-profile-03-r1-liveness-observation-ownership-adjudication.md` |
| R2 ALIVE/SUSPECT/DEAD semantics | ACCEPT | `0058-profile-03-r2-liveness-state-semantics-adjudication.md` |
| R3 Timers / hysteresis (T1) | ACCEPT | `0058-profile-03-r3-liveness-timers-hysteresis-adjudication.md` |
| R4 Publication + freshness (P1) | ACCEPT | `0058-profile-03-r4-publication-freshness-adjudication.md` |
| R5 Sender mode FSM (M1) | ACCEPT | `0058-profile-03-r5-sender-transport-mode-fsm-adjudication.md` |
| R6 Downgrade execution (D1) | ACCEPT | `0058-profile-03-r6-downgrade-execution-adjudication.md` |
| R7 Upgrade execution (U1) | ACCEPT | `0058-profile-03-r7-upgrade-execution-adjudication.md` |
| R8 Transition overlap (B1) | ACCEPT | `0058-profile-03-r8-transition-overlap-adjudication.md` |
| R9 Typed fences (F1) | ACCEPT | `0058-profile-03-r9-restart-generation-fences-adjudication.md` |
| R10 Acceptance + closeout | ACCEPT C1–C10 + X1–X3 | this document |

---

## Invariants (frozen)

```text
R10-I1  C1–C10 map 1:1 to R1–R9 contract; R10 adds no new semantics.
R10-I2  X1–X3 required for CONTRACT COMPLETE; not optional stress tests.
R10-I3  Profile 03 acceptance MUST NOT re-test Profile 02 wire bytes.
R10-I4  Publication transport semantics CLOSED in G-RES-4 P1–P7; wire evidence in G-RES-4 P7 corpus.
R10-I5  R10 ACCEPT ≠ Media Engine AUTHORIZED; ≠ G-RES-2 implementation PASS.
R10-I6  Next line after Profile 03 closeout: G-RES-4 publication transport.
```

---

## Profile 03 amendment text (normative insert)

```text
P03-R10 — Acceptance matrix + closeout:

Frozen acceptance: C1–C10 + X1–X3.
Profile 03 tests mock P02 execution; verify liveness/publication/mode/transition/fence only.

Adjudication R1–R10: COMPLETE.
CONTRACT COMPLETE: pending all matrix PASS.

G-RES-2 semantics CLOSED; G-RES-2 evidence PENDING.
G-RES-4 OPEN. Media Engine NOT AUTHORIZED.
```

---

## References

- `docs/analysis/0058-profile-03-resilience-amendment-outline.md`
- `docs/analysis/0058-profile-02-a10-acceptance-corpus-closeout.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
- `docs/analysis/0058-profile-03-r1-liveness-observation-ownership-adjudication.md` … `r9-restart-generation-fences-adjudication.md`
