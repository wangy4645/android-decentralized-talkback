# G-RES-4-P7 — Acceptance Matrix and Contract Closeout (Adjudication)

## Status

```text
G-RES-4-P7: ACCEPTED — MATRIX C1–C12 + X1–X4 FROZEN
Date:       2026-09-03
Prerequisites: G-RES-4-P1..P6 ACCEPTED
Parent:     0060-gres4-publication-transport-contract-outline.md

G-RES-4 publication transport contract:
  ADJUDICATION CHAIN COMPLETE (P1–P7 ACCEPTED)
  CONTRACT FROZEN — pending C1–C12 + X1–X4 implementation PASS

Media Engine: NOT AUTHORIZED
```

---

## Purpose

G-RES-4-P7 invents **no new publication semantics**. It freezes:

```text
1. machine-verifiable acceptance matrix C1–C12 (maps P1–P6)
2. cross-boundary combination cases X1–X4
3. corpus boundary vs Profile 02 / Profile 03 runtime internals
4. three-tier closeout verdict vs ADR-0060 G-RES gates
```

---

## Contract closeout verdict (three tiers)

### Tier 1 — Adjudication (this document)

```text
G-RES-4 P1–P7 adjudication: FROZEN / COMPLETE

MAY claim after P7 ACCEPT:
  G-RES-4 publication transport adjudication chain closed
  P1–P6 contract semantics frozen
  C1–C12 + X1–X4 acceptance matrix frozen

MUST NOT claim:
  G-RES-4 CONTRACT COMPLETE (implementation)
  G-RES-4 implementation/evidence PASS
  G-RES-2 implementation/evidence PASS
  Media Engine AUTHORIZED
  ADR-0060 G-RES-1..5 satisfied
```

### Tier 2 — Implementation acceptance

```text
G-RES-4 acceptance implementation: PENDING

G-RES-4 CONTRACT COMPLETE requires:
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
  C11 PASS
  C12 PASS
  X1 PASS
  X2 PASS
  X3 PASS
  X4 PASS

Until all PASS:
  ADJUDICATION COMPLETE — IMPLEMENTATION ACCEPTANCE PENDING
```

### Tier 3 — ADR-0060 authorization gates (orthogonal)

```text
After G-RES-4 CONTRACT COMPLETE:
  G-RES-4 semantic contract        = CLOSED
  G-RES-4 implementation/evidence  = PASS

Still required for Media Engine:
  G-RES-1  unicast correctness implementation/evidence (Profile 02 C1–C8)
  G-RES-2  Profile 03 runtime implementation/evidence (C1–C10 + X1–X3)
  G-RES-3  N_max numeric freeze + capacity evidence
  G-RES-5  E2b / Profile conformance update

Media Engine: NOT AUTHORIZED until G-RES-1..5 satisfied
```

**Next main line after P7:** G-RES-3 — freeze V1 `N_max` and worst-case sender-side
unicast fan-out capacity envelope. **NOT** further publication contract expansion.

---

## Corpus boundary (frozen)

```text
G-RES-4 tests verify:
  publication routing
  cadence
  freshness
  bootstrap
  ordering
  lineage
  fence / cache behavior

G-RES-4 tests MUST NOT retest:
  R3 media-ingress liveness algorithm
  Profile 02 SRTP bytes / wire golden vectors
  R6/R7 media-leg execution internals
  Profile 03 mode FSM in isolation (R10 consumption-seam tests)
```

### Fixture style (frozen)

```text
Mock/stub at contract seams:

ReceiverRuntime stub:
  current livenessState = ALIVE | SUSPECT | DEAD
  (R3 earn/loss not under test in G-RES-4 corpus)

SenderRuntime under test:
  receives publication messages through G-RES-4 transport
  applies R4 acceptance + P5/P6 disposition
  exposes: accepted current fact | STALE | rejection class

Profile 03 integration:
  end-to-end tests MAY stack G-RES-4 transport + P03 mode FSM
  but G-RES-4 C1–C12 MUST be passable with mode FSM stubbed
```

```text
PublicationTransportVector {
  context
    conferenceMediaIncarnation
    receiverModuleId
    senderModuleId
    admittedSenderSet[]
    clock

  injectPublications[]     // facts, duplicates, late, wrong scope
  injectBootstrap[]        // request/response/loss
  injectFences[]           // publisher restart, C1→C2, sender restart, key fence
  suppressPublications[]   // control loss windows

  expectedOutcomes
    deliveryReach?           // C1 topology
    rejectionClass?
    lastAcceptedFact?
    freshnessStatus?       // FRESH | STALE
    lineageState?
    bootstrapResponseSeq?
}
```

---

## Acceptance matrix C1–C12 (frozen)

| Case | Must prove | Primary refs |
|------|------------|--------------|
| **C1 Topology** | Receiver publication reaches all current admitted media senders; no owner/relay correctness dependency | P1 |
| **C2 Semantic isolation** | Independent Conference publication namespace; not coupled to GROUP/ADR-0057 lifecycle | P2 |
| **C3 Unicast independence** | Correctness does not depend on multicast; single message loss does not break correctness | P2, P3 |
| **C4 Periodic cadence** | Current state produces new ordered fact every 250ms; `observationSequence++` | P3 |
| **C5 Event-driven publish** | ALIVE↔SUSPECT/DEAD transition publishes without waiting for next periodic tick | P3 |
| **C6 Freshness** | Only R4-ACCEPTED fact refreshes freshness; `age >= 1000ms` → STALE | P3, P5 |
| **C7 Bootstrap** | Sender lacking current fact → bootstrap request; receiver returns new normal fact; UNICAST_FALLBACK until accept | P4 |
| **C8 Bootstrap safety** | Bootstrap does not trigger probe/earn; cached ALIVE cannot become FRESH; bootstrap loss recovers via periodic | P4, P6 |
| **C9 Gap/reorder** | Seq gap accepts newer directly; no gap repair/replay; late older does not roll back | P5 |
| **C10 Duplicate/reject** | Duplicate/stale/wrong-scope/auth-invalid never refresh freshness | P3, P5 |
| **C11 Publisher lineage** | Valid A→B: B/seq=1 replaces A/seq=900; late A permanently stale | P6 |
| **C12 Typed fences** | Conference incarnation hard-clears cache; sender restart no FRESH resurrection; key fence does not cut publication lineage | P6, R9 |

---

## Per-case acceptance criteria (normative)

### C1 — Topology

```text
PASS when:
  each receiver's publication delivered to every admitted media sender in fixture
  removal of owner/relay does not block sender knowledge update
  no correctness dependency on central redistribution hop

Negative:
  publication only via owner relay → REJECT (P1-B)
```

### C2 — Semantic isolation

```text
PASS when:
  facts use dedicated Conference Liveness Publication namespace
  GROUP bootstrap pause / ADR-0057 channel unavailability does not block
    publication correctness path (P2 C1)

Negative:
  fact embedded only in GROUP_JOIN lifecycle → REJECT (P2-C2)
```

### C3 — Unicast independence

```text
PASS when:
  publication transport uses unicast path independent of multicast media
  single publication datagram loss → sender eventually STALE, not permanent wrong mode
  correctness fail-safe to UNICAST_FALLBACK, not stuck on stale ALIVE

Negative:
  multicast required to publish liveness → REJECT (P2-C4)
```

### C4 — Periodic cadence

```text
PASS when:
  unchanged state still publishes every PublicationCadenceMs (250)
  each periodic publish observationSequence strictly increases
  sender accepts periodic refresh as freshness proof
```

### C5 — Event-driven publish

```text
PASS when:
  state transition between periodic ticks triggers immediate publish
  sender receives transition fact before next 250ms slot in fixture
  downgrade eligibility not delayed to next periodic only

Negative:
  transition waits for periodic tick only → REJECT (P3-F2)
```

### C6 — Freshness

```text
PASS when:
  accepted fact sets freshness clock; age < 1000ms → FRESH
  age >= 1000ms with no new accept → STALE
  stale → no multicast eligibility proof (R5 integration stub or E2E adjunct)

Negative:
  rejected fact refreshes freshness → REJECT (P3-F4)
```

### C7 — Bootstrap

```text
PASS when:
  sender with no FRESH fact sends PublicationBootstrapRequest
  receiver responds with NEW MulticastReceiveLivenessFact (seq++)
  sender R4-accepts → FRESH; before accept → UNICAST_FALLBACK

Negative:
  bootstrap returns replayed old seq → REJECT (P4)
```

### C8 — Bootstrap safety

```text
PASS when:
  bootstrap request does not change receiver R3 state (stub observes no earn trigger)
  persisted sender cache cannot become FRESH without new accept
  lost bootstrap + continuing periodic → eventual FRESH recovery

Negative:
  bootstrap triggers probe/ALIVE earn → REJECT (P4-S5)
  persisted ALIVE → FRESH on restart → REJECT (P4-S3, P6-L6)
```

### C9 — Gap/reorder

```text
PASS when:
  seq 100 then 104 accepted; no request for 101..103
  seq 104 SUSPECT then late 102 ALIVE → reject; no rollback

Vectors: P5 X1/X2 aligned
```

### C10 — Duplicate/reject

```text
PASS when:
  duplicate seq → REJECT_DUPLICATE; freshness unchanged
  wrong scope → REJECT_STALE_SCOPE; no C2 refresh from C1
  auth invalid → reject; no freshness refresh
```

### C11 — Publisher lineage

```text
PASS when:
  valid B/SUSPECT/1 replaces A/ALIVE/900
  late A/ALIVE/901 → REJECT_STALE_LINEAGE
  seq not compared across incarnations

Vector: P6 X1
```

### C12 — Typed fences

```text
PASS when:
  C1→C2 clears all C1 publication cache; late C1 rejected
  sender restart → empty FRESH; bootstrap recovery
  K1→K2 key fence → publication lineage unchanged; no synthetic SUSPECT/DEAD

Vectors: P6 X2, X3
```

---

## Cross-boundary combination cases X1–X4 (frozen)

### X1 — Control loss fail-safe

```text
ALIVE seq=100 accepted
→ all subsequent publications lost
→ age >= 1000ms
→ STALE
→ sender requires UNICAST_FALLBACK
```

### X2 — Gap + state transition

```text
ALIVE seq=100 accepted
→ seq 101..103 lost
→ SUSPECT seq=104 arrives
→ accept immediately
→ freshness refresh
→ downgrade engaged
→ no gap repair
```

### X3 — Restart lineage race

```text
A/ALIVE/900 accepted
→ receiver restart
→ B/SUSPECT/1 accepted
→ late A/ALIVE/901 arrives
→ REJECT_STALE_LINEAGE
→ UNICAST_FALLBACK remains
```

### X4 — Conference hard fence

```text
C1/B/ALIVE/120 accepted
→ conferenceMediaIncarnation C1→C2
→ old publication knowledge cleared
→ late C1/B/121 rejected (REJECT_STALE_SCOPE)
→ no current C2 fact
→ UNICAST_FALLBACK
→ C2 bootstrap or new push required for knowledge
```

---

## Matrix PASS gate (contract closeout)

```text
G-RES-4 CONTRACT COMPLETE requires:

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
  C11 PASS
  C12 PASS
  X1 PASS
  X2 PASS
  X3 PASS
  X4 PASS
```

Until implementation PASS:

```text
ADJUDICATION COMPLETE — IMPLEMENTATION ACCEPTANCE PENDING
```

---

## Adjudication chain summary (P1–P7)

| Phase | Verdict | Document |
|-------|---------|----------|
| P1 Publication topology | ACCEPT Model A | `0060-gres4-p1-publication-topology-adjudication.md` |
| P2 Transport channel | ACCEPT Model C1 | `0060-gres4-p2-transport-channel-adjudication.md` |
| P3 Cadence + freshness | ACCEPT Model F1 | `0060-gres4-p3-cadence-freshness-adjudication.md` |
| P4 Bootstrap recovery | ACCEPT Model S1 | `0060-gres4-p4-snapshot-bootstrap-adjudication.md` |
| P5 Loss/reorder/gap | ACCEPT Model G1 | `0060-gres4-p5-loss-reorder-gap-adjudication.md` |
| P6 Fence integration | ACCEPT Model L1 | `0060-gres4-p6-fence-lineage-integration-adjudication.md` |
| P7 Acceptance + closeout | ACCEPT C1–C12 + X1–X4 | this document |

---

## Invariants (frozen)

```text
G4-P7-I1  C1–C12 map to P1–P6; P7 adds no new publication semantics.

G4-P7-I2  X1–X4 required for CONTRACT COMPLETE; not optional stress tests.

G4-P7-I3  G-RES-4 corpus MUST NOT retest R3 ingress, P02 SRTP, R6/R7 leg internals.

G4-P7-I4  P7 ACCEPT ≠ G-RES-4 CONTRACT COMPLETE; ≠ Media Engine AUTHORIZED.

G4-P7-I5  Profile 03 R10 tests consumption seam; G-RES-4 P7 tests transport delivery.

G4-P7-I6  Next main line after P7: G-RES-3 N_max / capacity envelope — not P8.
```

---

## Normative contract insert

```text
G-RES-4-P7 — Acceptance matrix + closeout:

Frozen acceptance: C1–C12 + X1–X4.
G-RES-4 P1–P7 adjudication COMPLETE.
CONTRACT COMPLETE: pending all matrix PASS.
P7 ACCEPT does not authorize Media Engine.
Next: G-RES-3 evidence collection (C1–C4 COMPLETE).
```

---

## References

- `docs/analysis/0060-gres4-publication-transport-contract-outline.md`
- `docs/analysis/0060-gres4-p1-publication-topology-adjudication.md` … `p6-fence-lineage-integration-adjudication.md`
- `docs/analysis/0058-profile-03-r10-acceptance-closeout-adjudication.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
