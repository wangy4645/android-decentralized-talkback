# G-RES-4-P1 — Publication Topology and Recipients (Adjudication)

## Status

```text
G-RES-4-P1: ACCEPTED — MODEL A
Date:       2026-09-03
Parent:     0060-gres4-publication-transport-contract-outline.md
            ADR-0060 (authority split; no relay topology)
Prerequisites: Profile 03 R1–R4 ACCEPTED (fact semantics)
Scope:      who receives MulticastReceiveLivenessFact; fact granularity; authority path
NOT IN SCOPE: wire channel (P2); cadence / TTL (P3); snapshot recovery (P4)
Next:       G-RES-4-P3 (cadence + PublicationFreshnessTimeout)
```

---

## Question

Receiver-owned `MulticastReceiveLivenessFact` — **to whom must it be published?**

At what **granularity** is liveness scoped in V1?

P1 freezes **topology, authority, and granularity only**. It does **not** decide
heartbeat cadence, `PublicationFreshnessTimeout`, or transport wire format.

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **A (P1-A)** | Receiver → all current admitted media senders; receiver-scoped fact | **ACCEPT** |
| **B (P1-B)** | Receiver → authority/owner → owner redistributes to senders | **REJECT** |
| **C (P1-C)** | Sender pull: sender queries receiver on demand | **REJECT** |

---

## Frozen decision — Model A

### Authority path (frozen)

```text
Each publishing receiver Module originates facts for itself only (R1).

Each current admitted media sender that may deliver media to that receiver
MUST be able to obtain that receiver's current MulticastReceiveLivenessFact
for the active conferenceMediaIncarnation.
```

```text
Example (4-member conference, all may send):

M03 (receiver) publishes liveness
  ├─ fact MUST reach M01 (sender)
  ├─ fact MUST reach M02 (sender)
  └─ fact MUST reach M04 (sender)

Shortest authority path: receiver → sender directly (logical).
No mandatory owner/relay hop.
```

Aligns with ADR-0060:

```text
receiver owns observation
sender owns delivery-mode execution
NO relay / elected replicator / new authority topology
```

### Logical fan-out requirement (frozen)

```text
P1-A is NOT "network broadcast" or "UDP multicast publication."

P1-A requires:
  each receiver's current liveness fact MUST be reachable by every
  current admitted media sender that needs it for PerReceiverTransportMode
  decisions for that receiver.

Wire realization (unicast control, existing signaling, gossip, snapshot)
is G-RES-4-P2+.
```

### Recipient set definition (frozen)

```text
current admitted media senders for receiver R =
  { S | S is admitted to send Conference media
        AND S may target R as a remote receiver
        under current Membership + conferenceMediaIncarnation }

When Membership admits a new sender S':
  S' MUST enter recipient set for all receivers' current facts (P4 recovery).

When S leaves or loses admission:
  S MUST stop relying on stale facts for mode decisions (R4/R5; P6 transport).
```

---

## Fact granularity — receiver-scoped, NOT per-sender (frozen)

### V1 capability key

```text
MulticastReceiveLiveness(receiverModuleId, conferenceMediaIncarnation)
```

```text
NOT in V1:
MulticastReceiveLiveness(receiverModuleId, senderModuleId, conferenceMediaIncarnation)
```

### Semantic (frozen)

```text
Receiver judges:
  "Do I currently have dependable conference multicast receive capability
   for this media incarnation?"

NOT:
  "Is multicast from sender S specifically alive to me?"
```

### Correctness consequence (frozen)

```text
If receiver R publishes SUSPECT or DEAD:

  EVERY admitted media sender MUST treat R as not multicast-eligible
  (R5 loss-of-proof → UNICAST_FALLBACK per sender→R binding)

MUST NOT allow:
  M01 thinks R multicast OK
  M03 thinks R multicast dead
```

```text
Conservative but correctness-simple:

M02 publishes DEAD (receiver-scoped)
  → M01→M02 unicast fallback
  → M03→M02 unicast fallback
  → M04→M02 unicast fallback
```

### Future amendment boundary

```text
(receiver × sender × path) granularity MAY be reconsidered only via explicit
future amendment + capacity evidence (e.g. per-path optimization).

V1 G-RES-4 and Profile 03 tests assume receiver-scoped facts only.
```

---

## Rejected models — rationale

### P1-B — Owner/relay redistribution (REJECT)

```text
M03 → owner → M01/M02/M04
```

```text
REJECT because:
  reintroduces central correctness dependency on owner availability
  contradicts ADR-0060 "NO relay / elected replicator / new authority topology"
  adds failure mode: owner down → senders lack liveness → ambiguous mode state
  unnecessary hop when direct receiver→sender fan-out is the authority model
```

Owner MAY exist for other Conference concerns; it is **not** the mandatory
liveness publication authority in V1.

### P1-C — Sender pull query (REJECT)

```text
Sender queries receiver on demand for current liveness
```

```text
REJECT because:
  sender-initiated pull inverts observation authority (R1)
  late join / sender restart recovery deferred inconsistently per sender
  encourages sender-side inference gaps between polls
  snapshot/recovery belongs in P4 with push+snapshot model, not ad-hoc pull
```

---

## Relationship to Profile 03 (frozen)

```text
R4 defines MulticastReceiveLivenessFact schema and sender consumption rules.
P1 defines logical publication recipients and granularity.

R10 C5 tests inject facts at consumption seam — valid without P2 wire.
G-RES-4-P7 will require end-to-end delivery PASS in addition.
```

```text
P1 does NOT reopen:
  ALIVE/SUSPECT/DEAD semantics (R2)
  earn/recovery timers (R3)
  ordering/freshness rules (R4) — only who must receive
  mode FSM (R5–R8)
```

---

## Explicit non-decisions in P1

```text
NOT in P1:
  publication heartbeat interval (every 100ms / 250ms)
  PublicationFreshnessTimeout numeric (P3)
  transport wire format / channel choice (P2)
  snapshot on join / sender restart (P4)
  duplicate suppression at wire layer (P5)
  incarnation fence wire behavior (P6)
  per-receiver publication prioritization under bandwidth pressure
```

---

## Invariants (frozen)

```text
G4-P1-I1  Facts originate at observing receiver only (R1).

G4-P1-I2  Every current admitted media sender MUST receive current fact
          for each remote receiver it may target.

G4-P1-I3  V1 fact granularity is (receiverModuleId, conferenceMediaIncarnation);
          not (sender, receiver, incarnation).

G4-P1-I4  Receiver-scoped SUSPECT/DEAD applies uniformly to all senders for that receiver.

G4-P1-I5  No owner/relay mandatory authority; no sender-pull as primary model.

G4-P1-I6  Logical fan-out requirement ≠ network broadcast publication.
```

---

## Normative contract insert

```text
G-RES-4-P1 — Publication topology (Model A):

Receiver-originated, receiver-scoped MulticastReceiveLivenessFact publication.

Recipients: all current admitted media senders that must make
PerReceiverTransportMode decisions for that receiver.

Granularity: (receiverModuleId, conferenceMediaIncarnation).

Owner relay (P1-B) and sender pull (P1-C) rejected.

Cadence, channel, recovery: P2–P6.
```

---

## Consequences

```text
P2: whether facts ride existing GROUP/control signaling or independent channel
    — primary architecture fork; recipient count = (receivers × senders) logical fan-out
P3: cadence must satisfy freshness for all recipients without per-sender facts
P4: late-joining sender must receive snapshot of all receiver facts
P7: topology acceptance — every admitted sender receives fact updates for all receivers
```

---

## References

- `docs/analysis/0060-gres4-publication-transport-contract-outline.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
- `docs/analysis/0058-profile-03-r1-liveness-observation-ownership-adjudication.md`
- `docs/analysis/0058-profile-03-r4-publication-freshness-adjudication.md`
- `docs/analysis/0058-profile-03-r10-acceptance-closeout-adjudication.md`
