# G-RES-4 — Publication Transport Contract (Outline)

## Status

```text
IN PROGRESS — INDEPENDENT CONTRACT (NOT Profile 03 R11)
ADJUDICATION COMPLETE (P1–P7 ACCEPTED)
IMPLEMENTATION ACCEPTANCE PENDING (C1–C12 + X1–X4)
Parent:     ADR-0060 ACCEPTED — FROZEN
Prerequisite: Profile 03 R1–R10 adjudication COMPLETE (fact semantics frozen in R4)
Scope:      how MulticastReceiveLivenessFact travels receiver → sender
            reliably, timely, recoverably
NOT IN SCOPE: Profile 03 liveness observation / mode FSM / transition semantics
              Profile 02 wire / SRTP bytes
              Media Engine authorization
Media Engine: NOT AUTHORIZED
```

---

## Authority split (frozen boundary)

```text
Profile 03 owns:
  receiver observation
  → ALIVE / SUSPECT / DEAD
  → MulticastReceiveLivenessFact semantics (R4)
  → sender knowledge consumption rules (R4/R5)
  → PerReceiverTransportMode FSM (R5–R8)

G-RES-4 owns:
  publication topology (who receives facts)
  transport channel vs correctness dependency
  cadence + PublicationFreshnessTimeout numerics
  snapshot / late join / sender restart recovery
  loss / reorder / duplicate / gap wire semantics
  incarnation / publisher restart transport integration (R9 transport layer)
  acceptance matrix + closeout
```

```text
Profile 03 tests inject facts at consumption seam (R10).
G-RES-4 tests prove facts arrive through the real transport contract.
```

---

## Problem statement

Profile 03 defines **what** a receiver may publish and **how** a sender must
interpret it. G-RES-4 defines **how that fact reliably reaches every sender that
must make a transport-mode decision for that receiver**.

Without G-RES-4:

```text
R4 freshness semantics exist but no delivery contract
R5/R6/R7 mode FSM has no wire to consume facts
G-RES-2 implementation evidence cannot close
```

---

## Adjudication map (P1–P7)

```text
G-RES-4-P1  Publication topology / recipients                    ACCEPTED (Model A)
G-RES-4-P2  Transport channel vs correctness dependency         ACCEPTED (Model C1)
G-RES-4-P3  Cadence + PublicationFreshnessTimeout numerics      ACCEPTED (Model F1)
G-RES-4-P4  Snapshot / late join / sender restart recovery      ACCEPTED (Model S1)
G-RES-4-P5  Loss / reorder / duplicate / gap semantics          ACCEPTED (Model G1)
G-RES-4-P6  Incarnation / publisher restart / fence integration ACCEPTED (Model L1)
G-RES-4-P7  Acceptance matrix + closeout                        ACCEPTED (C1–C12 + X1–X4)
```

**Process:** each Pi ends ACCEPT / REJECT with frozen contract text. P1 before P2
(topology drives channel and capacity model). P3 owns cadence/TTL numerics — not P1/P2.

---

## G-RES-4-P1 — Publication topology / recipients

**Status: ACCEPTED — MODEL A**
(see `0060-gres4-p1-publication-topology-adjudication.md`)

```text
Receiver-originated, receiver-scoped liveness publication
Each current admitted media sender MUST receive current fact for that receiver
V1 granularity: (receiverModuleId, conferenceMediaIncarnation) — NOT per-sender
No owner/relay authority (P1-B REJECT); no sender pull query (P1-C REJECT)

Logical fan-out requirement; wire mechanism deferred to P2
```

---

## G-RES-4-P2 — Transport channel vs correctness dependency

**Status: ACCEPTED — MODEL C1**
(see `0060-gres4-p2-transport-channel-adjudication.md`)

```text
Independent Conference Liveness Publication semantic channel
MAY reuse generic authenticated unicast control substrate
MUST NOT embed in GROUP/ADR-0057 semantic lifecycle
MUST NOT publish via multicast/broadcast

Refreshable state: no single-message / exactly-once correctness
Loss → STALE → UNICAST_FALLBACK (R4/R5)
Transport send success ≠ R4 fact acceptance

C2 GROUP semantic | C3 mandated reliable TCP | C4 multicast pub REJECTED
G4-P2-I1..I7 frozen
```

---

## G-RES-4-P3 — Cadence + PublicationFreshnessTimeout

**Status: ACCEPTED — MODEL F1**
(see `0060-gres4-p3-cadence-freshness-adjudication.md`)

```text
PublicationCadenceMs = 250; PublicationFreshnessTimeoutMs = 1000
Separate from R3 300/1000ms observation timers

Dual publish: periodic 250ms + immediate on livenessState transition
Each refresh: observationSequence++ (unchanged state = new fact)
Only R4-accepted fact refreshes freshness; age >= 1000ms → STALE
No startup grace (P4 owns snapshot)

F2 periodic-only | F3 event-only | F4 reject-refresh | F5=R3 300ms REJECTED
G4-P3-I1..I7 frozen
```

---

## G-RES-4-P4 — Snapshot / late join / sender restart recovery

**Status: ACCEPTED — MODEL S1**
(see `0060-gres4-p4-snapshot-bootstrap-adjudication.md`)

```text
PublicationBootstrap: unified late join / sender restart / cache loss / channel recovery
Sender → PublicationBootstrapRequest; receiver → NEW MulticastReceiveLivenessFact (seq++)
Steady-state push unchanged (P1); bootstrap = recovery assist only
No FRESH until R4-accepted; UNICAST_FALLBACK until then
No cache resurrection; bootstrap cannot trigger probe/earn ALIVE

S2 periodic-only | S3 persisted FRESH | S4 separate snapshot | S5 probe REJECTED
G4-P4-I1..I6 frozen
```

---

## G-RES-4-P5 — Loss / reorder / duplicate / gap semantics

**Status: ACCEPTED — MODEL G1**
(see `0060-gres4-p5-loss-reorder-gap-adjudication.md`)

```text
Current-state replication, NOT event-log; gap = missed refreshes, no repair
Strictly newer fact within valid lineage → ACCEPT (even seq 100→104)
Reorder/duplicate older → REJECT; no rollback; no freshness refresh on reject
Gap does NOT synthesize SUSPECT/DEAD; absence → P3 STALE only
publisherIncarnation supersession legality → P6

G2 gap-fill | G3 gap→SUSPECT | G4 old refresh | G5 dup refresh REJECTED
G4-P5-I1..I6 + X1/X2 frozen
```

---

## G-RES-4-P6 — Incarnation / publisher restart / fence integration

**Status: ACCEPTED — MODEL L1**
(see `0060-gres4-p6-fence-lineage-integration-adjudication.md`)

```text
R9 fences → publication sender cache/lineage plane (not redefining R9)
publisherIncarnation A→B: valid B replaces A; A late packets stale; seq per-lineage only
conferenceMediaIncarnation C1→C2: hard scope clear; C1 never affects C2
sender restart: no FRESH resurrection (P4)
key K1→K2: publication lineage unchanged; no fabricated liveness loss

L2 auto-newer | L3 cross-incarnation seq | L4 C1 cache | L5 key lineage | L6 persisted FRESH REJECTED
G4-P6-I1..I7 + X1/X2/X3 frozen
```

---

## G-RES-4-P7 — Acceptance matrix + closeout

**Status: ACCEPTED — MATRIX C1–C12 + X1–X4 FROZEN**
(see `0060-gres4-p7-acceptance-closeout-adjudication.md`)

```text
C1–C12 map P1–P6; X1–X4 cross-boundary (control loss, gap+transition, lineage race, C fence)
G-RES-4 tests transport; MUST NOT retest R3 ingress / P02 SRTP / R6-R7 legs

Closeout:
  P1–P7 adjudication     → COMPLETE
  C1–C12 + X1–X4 PASS    → CONTRACT COMPLETE (PENDING)
  P7 ACCEPT              ≠ Media Engine AUTHORIZED

Next: G-RES-3 N_max + capacity envelope
```

---

## Closeout target

```text
G-RES-4 CONTRACT COMPLETE requires P1–P7 adjudication ACCEPT + P7 matrix PASS

Then:
  G-RES-4 publication transport     CLOSED
  G-RES-2 evidence may proceed      (with Profile 03 C1–C10 + P02 C1–C8)
  Media Engine                      still NOT AUTHORIZED (G-RES-1/3/5 remain)
```

---

## References

- `docs/adr/0060-conference-media-transport-resilience-contract.md`
- `docs/analysis/0060-gres4-p7-acceptance-closeout-adjudication.md`
- `docs/analysis/0060-gres4-p6-fence-lineage-integration-adjudication.md`
- `docs/analysis/0060-gres4-p5-loss-reorder-gap-adjudication.md`
- `docs/analysis/0060-gres4-p4-snapshot-bootstrap-adjudication.md`
- `docs/analysis/0060-gres4-p3-cadence-freshness-adjudication.md`
- `docs/analysis/0060-gres4-p2-transport-channel-adjudication.md`
- `docs/analysis/0060-gres4-p1-publication-topology-adjudication.md`
- `docs/analysis/0058-profile-03-r4-publication-freshness-adjudication.md`
- `docs/analysis/0058-profile-03-r10-acceptance-closeout-adjudication.md`

## Next action

**G-RES-4-P7 ACCEPTED.** G-RES-4 adjudication chain **COMPLETE**.
Implementation acceptance C1–C12 + X1–X4 **PENDING**.
**Next main line:** G-RES-3 evidence collection (C1–C4 adjudication COMPLETE).
