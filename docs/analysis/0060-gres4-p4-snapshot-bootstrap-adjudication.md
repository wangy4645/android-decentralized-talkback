# G-RES-4-P4 — Snapshot, Late Join, and Sender Restart Recovery (Adjudication)

## Status

```text
G-RES-4-P4: ACCEPTED — MODEL S1
Date:       2026-09-03
Parent:     0060-gres4-publication-transport-contract-outline.md
Prerequisites: G-RES-4-P1 ACCEPTED (Model A — push topology)
        G-RES-4-P2 ACCEPTED (Model C1)
        G-RES-4-P3 ACCEPTED (Model F1)
        Profile 03 R4/R5/R9 (fact acceptance; no cache resurrection)
Scope:  PublicationBootstrap recovery; snapshot as latency optimization
NOT IN SCOPE: loss/reorder/duplicate wire policy (P5)
              fence transport integration (P6)
              bootstrap retry cadence numerics (implementation; P3 periodic suffices)
Next:       G-RES-4-P6 (incarnation / publisher restart / fence integration)
```

---

## Question

When a sender **late-joins**, **restarts**, or **recovers publication channel**
without a fresh current fact, how does it obtain receiver liveness knowledge
without waiting for the next 250ms periodic refresh alone?

P4 freezes **bootstrap recovery** without changing P1 steady-state push topology
or inventing a second fact type / ordering protocol.

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **S1** | Explicit bootstrap request + receiver emits new normal fact + periodic fallback | **ACCEPT** |
| **S2** | Periodic refresh only as recovery | **REJECT V1** (timing-dependent latency) |
| **S3** | Sender restores FRESH from persisted/cached ALIVE | **REJECT** |
| **S4** | Separate snapshot payload / ordering protocol | **REJECT** |
| **S5** | Bootstrap request triggers receiver probe / re-earn ALIVE | **REJECT** |

---

## Core principle (frozen)

```text
Snapshot is latency optimization for knowledge recovery,
not a correctness prerequisite.

Bootstrap delivers current fact faster when sender lacks knowledge.
Correctness still depends on R4-accepted facts + P3 periodic push if bootstrap fails.
```

```text
Snapshot = fast path to current MulticastReceiveLivenessFact
NOT sender inference or reconstruction of receiver R3 state
```

---

## Frozen decision — Model S1

### Steady state unchanged (P1)

```text
Normal operation:
  receiver push → all current admitted media senders (P1 Model A)
  periodic 250ms + immediate on state change (P3 F1)

Bootstrap does NOT replace push topology.
```

### PublicationBootstrap (unified recovery mechanism)

```text
Single mechanism — NOT separate LateJoinSnapshot / SenderRestartRecovery / ReconnectSync.

PublicationBootstrap covers:
  sender newly admitted to current conferenceMediaIncarnation
  sender runtime restart
  sender publication knowledge cache lost
  publication control channel recovered but sender has no FRESH current fact
```

### Bootstrap flow (frozen)

```text
Sender bootstrap / restart / knowledge gap detected
  → sender sends PublicationBootstrapRequest to each admitted receiver
       (for which sender lacks FRESH current fact)

Receiver receives request
  → MUST respond with NEW current-state MulticastReceiveLivenessFact
       (observationSequence++, current R3-owned livenessState)
  → MUST NOT replay cached fact with old ordering identity

Sender receives response
  → normal R4 acceptance (scope, order, duplicate, freshness)
  → if ACCEPTED: FRESH knowledge per P3
  → if not yet ACCEPTED: UNICAST_FALLBACK remains authoritative (R5)
```

```text
Until R4-accepted current fact for (receiver, incarnation):
  PerReceiverTransportMode = UNICAST_FALLBACK (R5 initial / loss-of-proof)
```

---

## Bootstrap request — wire shape (frozen minimum)

```text
PublicationBootstrapRequest {
  senderModuleId
  conferenceMediaIncarnation
}
```

```text
MUST NOT include:
  "last seen seq" for gap repair / event-log replay
  sender-requested desired liveness state
  probe or earn-ALIVE triggers
```

This is **current-state protocol**, not event replay.

---

## Bootstrap response — reuse R4 fact (frozen — S4 REJECT)

```text
Receiver responds with standard MulticastReceiveLivenessFact only:

MulticastReceiveLivenessFact {
  receiverModuleId
  conferenceMediaIncarnation
  livenessState              // current R3-owned state
  publicationOrderingIdentity { publisherIncarnation, observationSequence }
  observedAtMonotonicTime
}
```

```text
FORBIDDEN separate payload:
  LivenessSnapshot { state, timestamp, ... }   // second acceptance semantics
```

### New fact on every bootstrap response (frozen)

```text
Snapshot response MUST be a newly ordered publication fact, not replay.

Example:
  receiver current state = ALIVE
  last periodic publication seq = 120

  bootstrap request arrives
  → publish ALIVE seq = 121 (new observationSequence)
```

```text
Ordering / freshness unified with P3:
  no second snapshot ordering rules
  bootstrap fact refreshes sender freshness same as periodic/event publish
```

---

## Bootstrap vs P1 pull topology (frozen — G4-P4-I1)

P1 **rejected** sender pull as steady-state publication model (P1-C).

P4 bootstrap is **recovery assist only**:

```text
Steady state:     receiver push → all admitted senders
Knowledge gap:    sender → bootstrap request → receiver → fresh fact

Bootstrap does NOT convert system to polling/pull authority model.
Sender MUST NOT rely on periodic bootstrap polling instead of receiver push.
```

```text
Sender initiates bootstrap only when explicitly lacking FRESH current fact
for a (receiver, conferenceMediaIncarnation) binding.
```

---

## Receiver behavior on bootstrap (frozen — G4-P4-I5)

```text
Bootstrap request:
  ≠ probe
  ≠ liveness earn trigger
  ≠ ALIVE command
  ≠ R3 timer reset

Receiver answers ONLY current R3-owned livenessState.
Receiver MUST NOT recompute or manufacture liveness for bootstrap.
```

### Receiver restart counterexample (R9 closure)

```text
After receiver restart:
  local MulticastReceiveLiveness = SUSPECT
  publisherIncarnation = new
  R3 earn windows cleared

Sender immediately sends bootstrap request
  → receiver responds SUSPECT, new publisherIncarnation, seq = baseline+
  → sender MUST NOT treat as inherited ALIVE
  → UNICAST_FALLBACK until earned ALIVE published via normal R3 path
```

Snapshot **cannot bypass** R3 earn/recovery rules.

---

## Sender restart — no cache resurrection (frozen — G4-P4-I3/I4)

```text
Sender restart:
  publication knowledge = no accepted current fact / STALE
  PerReceiverTransportMode = UNICAST_FALLBACK
  → PublicationBootstrap to each admitted receiver
```

```text
FORBIDDEN (S3):
  locally cached or persisted ALIVE becomes FRESH without new R4 acceptance
  historical cache used as eligibility proof in current runtime

V1:
  persisted last fact MAY exist as diagnostic hint only
  MUST NOT satisfy FRESH or MULTICAST eligibility until re-accepted
```

---

## Bootstrap loss — no ACK correctness (frozen — G4-P4-I6)

```text
PublicationBootstrapRequest / response MAY be lost (P2 loss-tolerant model).

On loss:
  sender remains UNICAST_FALLBACK until some R4-accepted fact arrives
  P3 periodic push (250ms) + event-driven publish remain recovery path
  MUST NOT require bootstrap ACK for correctness
```

```text
Bounded bootstrap retry MAY be implemented; cadence NOT frozen in P4.
P3 periodic publication provides eventual recovery upper bound.
```

---

## Relationship to P3 (frozen)

```text
P3: no StartupFreshnessGraceMs — P4 provides active recovery instead
P4: bootstrap reduces latency; does not replace 250ms periodic obligation
Receiver MUST continue periodic + event-driven publish during bootstrap gaps
```

---

## P5 preview (non-normative pointer)

```text
P5 expected direction (not decided in P4):
  no gap repair / event replay on publication transport
  accept strictly newer current-state facts only
  gap in observationSequence is not an error condition
```

---

## Explicit non-decisions in P4

```text
NOT in P4:
  bootstrap retry interval / max attempts numerics
  multicast fan-out of bootstrap request (unicast per P2)
  Membership admission wire for "sender newly admitted" trigger
  concurrent bootstrap storm rate limiting
  P5 duplicate/reorder disposition at wire layer
  P6 incarnation fence bootstrap invalidation details
```

---

## Invariants (frozen)

```text
G4-P4-I1  Steady-state remains receiver-push; bootstrap does not change P1 topology.

G4-P4-I2  Bootstrap response is a newly ordered normal MulticastReceiveLivenessFact.

G4-P4-I3  Until a current R4-accepted fact exists, sender stays UNICAST_FALLBACK.

G4-P4-I4  Sender restart never resurrects cached ALIVE as FRESH.

G4-P4-I5  Bootstrap cannot manufacture/recompute receiver liveness.

G4-P4-I6  Bootstrap loss does not break correctness; periodic publication remains recovery path.
```

---

## Normative contract insert

```text
G-RES-4-P4 — PublicationBootstrap (Model S1):

Unified recovery: late join, sender restart, cache loss, channel recovery.
Sender → PublicationBootstrapRequest; receiver → new MulticastReceiveLivenessFact (seq++).
Normal R4 acceptance; no FRESH until accepted; UNICAST_FALLBACK until then.
Recovery assist only; steady-state push unchanged. No cache resurrection.

S2/S3/S4/S5 rejected. G4-P4-I1..I6 frozen.
```

---

## Consequences

```text
P5: gap/reorder/duplicate on lossy channel; no event replay; current-state only
P6: bootstrap scope invalidation on incarnation fence
P7: tests — late join, sender restart, bootstrap loss, receiver restart SUSPECT response
```

---

## References

- `docs/analysis/0060-gres4-publication-transport-contract-outline.md`
- `docs/analysis/0060-gres4-p3-cadence-freshness-adjudication.md`
- `docs/analysis/0060-gres4-p1-publication-topology-adjudication.md`
- `docs/analysis/0060-gres4-p2-transport-channel-adjudication.md`
- `docs/analysis/0058-profile-03-r4-publication-freshness-adjudication.md`
- `docs/analysis/0058-profile-03-r9-restart-generation-fences-adjudication.md`
