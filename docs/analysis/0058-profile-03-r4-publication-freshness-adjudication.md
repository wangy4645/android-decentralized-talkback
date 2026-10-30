# P03-R4 — Publication Fact Identity and Freshness (Adjudication)

## Status

```text
P03-R4: ACCEPTED — MODEL P1
Date:   2026-09-03
Prerequisites: P03-R1..R3 ACCEPTED
Parent: 0058-profile-03-resilience-amendment-outline.md
        ADR-0060 Decision 5 (publication contract surface)
Scope:  what receiver publishes; how sender accepts/rejects; ordering vs freshness
        PublicationFreshnessTimeout numeric value NOT frozen (R5 or transport sub-ruling)
Next:   P03-R5
```

---

## Question

What does the receiver **publish**, and how does the sender determine whether a
received fact is **current and dependable**?

R4 freezes **fact shape, identity dimensions, ordering rules, and freshness
semantics**. It does **not** name `capabilityEpoch` or freeze publication transport wire.

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **P1** | `publisherIncarnation` + monotonic `observationSequence` + sender freshness | **ACCEPT** |
| **P2** | Wall-clock timestamp alone determines newer/stale | **REJECT** |
| **P3** | Monotonic sequence only; resets on restart without incarnation | **REJECT** |
| **P4** | Last ALIVE valid until explicit DEAD | **REJECT** |

---

## Published fact (frozen minimum schema)

```text
MulticastReceiveLivenessFact {
  receiverModuleId
  conferenceMediaIncarnation
  livenessState              // ALIVE | SUSPECT | DEAD  (R2)
  publicationOrderingIdentity
  observedAtMonotonicTime    // receiver-local; auxiliary only
}
```

```text
publicationOrderingIdentity {
  publisherIncarnation       // restart-safe publisher instance id
  observationSequence        // monotonic within publisherIncarnation
}
```

**Naming note:** This is **not** `capabilityEpoch` (ADR-0060 deferred naming). R4
freezes abstract requirements; UUID / random 64-bit / boot nonce is implementation.

---

## Three identity dimensions (frozen)

### WHO — `receiverModuleId`

```text
Fact authority: the publishing Module only (R1).
Sender indexes facts by remote receiverModuleId.
```

### WHICH MEDIA INCARNATION — `conferenceMediaIncarnation`

```text
Binds publication to one Conference media execution scope.

MUST include sufficient identity to distinguish:
  conference / media-group incarnation relevant to multicast optimization
  (exact field composition — implementation; MUST NOT span incarnations)

Facts from a prior incarnation MUST NOT affect sender mode for current incarnation.
```

### WHICH NEWER FACT — `publicationOrderingIdentity`

```text
Ordering decides "newer vs older" within an incarnation.
Restart-safe: new publisherIncarnation after receiver restart MUST NOT be
confusable with pre-restart sequence space.

Contract requirement:
  (publisherIncarnation, observationSequence) totally orders facts from one
  receiver for one conferenceMediaIncarnation at the sender.

observationSequence:
  strictly increases (+1) within a publisherIncarnation for each published state
  transition or material re-publish required by implementation
  (exact cadence — publication transport; ordering must hold)
```

### Anti-pattern — restart without incarnation (P3 rejected)

```text
M02 old process: observationSequence = 900
M02 reboot:      observationSequence = 1

Sender with sequence-only ordering permanently rejects seq=1 as stale.

REJECT P3. publisherIncarnation MUST disambiguate post-restart publications.
```

---

## Sender acceptance rules (frozen)

Sender maintains per key:

```text
lastAcceptedLivenessFact[receiverModuleId, conferenceMediaIncarnation]
  = full MulticastReceiveLivenessFact last applied
```

On receive:

```text
1. wrong conferenceMediaIncarnation for current sender context
     → REJECT_STALE_SCOPE

2. publicationOrderingIdentity not strictly newer than last accepted
     (same incarnation: seq must increase;
      new publisherIncarnation: accepted if scope matches and ordering rules define
      new incarnation as successor — see below)
     → REJECT_STALE_ORDER

3. duplicate / equal ordering
     → REJECT_DUPLICATE (no state change)

4. otherwise
     → accept as new current fact for (receiver, incarnation)
```

```text
New publisherIncarnation after receiver restart:
  MUST be accepted as a valid ordering epoch reset for that
  (receiverModuleId, conferenceMediaIncarnation) when scope still current.
  observationSequence restarts at implementation baseline (e.g. 1).
  Sender MUST NOT compare seq across different publisherIncarnation values.
```

---

## Ordering vs time (frozen principle)

```text
ordering  decides NEWER vs OLDER
time      decides EXPIRED vs STILL-DEPENDABLE (freshness only)

FORBIDDEN (P2):
  observedAtMonotonicTime or wall-clock alone as sole stale/new comparator
```

`observedAtMonotonicTime` MAY assist diagnostics; MUST NOT override ordering rules.

---

## Sender knowledge: FRESH vs STALE (frozen)

Separate layer from receiver-owned `livenessState`:

```text
Receiver-owned (published):
  ALIVE | SUSPECT | DEAD

Sender-side knowledge status (NOT a fourth liveness state):
  FRESH | STALE
```

```text
FRESH:
  last accepted fact for (receiver, incarnation) is within PublicationFreshnessTimeout
  of sender's receive clock (monotonic at consumer)

STALE:
  no acceptable new fact received within PublicationFreshnessTimeout
  sender MUST NOT treat last fact as current eligibility proof
```

```text
PublicationFreshnessTimeout:
  semantic frozen in R4
  numeric value NOT frozen in R4 (R5 or publication cadence sub-ruling)

R3 timers (300ms / 1000ms) govern RECEIVER local ingress observation.
R4 freshness governs SENDER trust in last PUBLICATION received.
These are intentionally separate clocks.
```

### Stale semantics (critical)

```text
stale ALIVE ≠ ALIVE for eligibility purposes

stale anything → MUST NOT prove multicast optimization eligible

STALE does NOT mean sender may publish or assume receiver DEAD (R4-I5).
STALE means: sender lacks fresh receiver-owned fact; eligibility proof absent.
```

### Consumption matrix (frozen)

| Sender knowledge | Published state | Multicast eligibility input |
|------------------|-----------------|----------------------------|
| FRESH | ALIVE | ELIGIBLE (per R2) |
| FRESH | SUSPECT | NOT_PROVEN |
| FRESH | DEAD | NOT_ELIGIBLE |
| STALE | any | **no eligibility proof** (treat as NOT_PROVEN for upgrade; R5 details) |

R5 adjudicates sender `PerReceiverTransportMode` actions on STALE rows.

---

## Invariants (frozen)

### R4-I1 — Receiver is fact authority

```text
Receiver originates livenessState in MulticastReceiveLivenessFact.
Sender MAY accept or reject; MUST NOT rewrite receiver state.
Sender MUST NOT infer ALIVE and publish on behalf of receiver.
```

### R4-I2 — Incarnation binding

```text
Every fact MUST bind receiverModuleId + conferenceMediaIncarnation.
Cross-incarnation application → REJECT_STALE_SCOPE.
```

### R4-I3 — Restart-safe ordering identity

```text
publicationOrderingIdentity MUST survive publisher restart without collision
with pre-restart sequence space.
```

### R4-I4 — No regression

```text
Older ordering, wrong incarnation, or duplicate fact MUST NOT replace
newer accepted fact for same (receiver, incarnation).
```

### R4-I5 — Stale does not forge DEAD

```text
Sender MUST NOT convert STALE knowledge into receiver-owned DEAD publication.
Sender MUST NOT cache stale ALIVE as perpetual ELIGIBLE proof (P4 rejected).
```

---

## Explicit non-decisions in R4

```text
NOT in R4:
  PublicationFreshnessTimeout numeric value
  publication wire format / channel (control-plane seam)
  publication cadence / re-publish interval
  PerReceiverTransportMode FSM (R5)
  downgrade on STALE vs hold mode (R5/R6)
  conferenceMediaIncarnation field bit layout
  capabilityEpoch naming (ADR-0060 abstract satisfied by publicationOrderingIdentity)
```

---

## Profile 03 amendment text (normative insert)

```text
P03-R4 — MulticastReceiveLivenessFact publication:

Minimum fields: receiverModuleId, conferenceMediaIncarnation, livenessState,
publicationOrderingIdentity { publisherIncarnation, observationSequence },
observedAtMonotonicTime (non-authoritative for ordering).

Sender: lastAccepted per (receiver, incarnation); REJECT_STALE_SCOPE,
REJECT_STALE_ORDER, REJECT_DUPLICATE.

Ordering = newer; freshness = dependable (FRESH/STALE sender knowledge).
stale ALIVE not ELIGIBLE; stale does not imply receiver DEAD.

P2/P3/P4 rejected. P1 accepted.
```

---

## Consequences

```text
R5 may define PerReceiverTransportMode FSM using FRESH + livenessState matrix
R6/R7 downgrade/upgrade triggered by fresh DEAD / fresh ALIVE respectively
R9 defines when conferenceMediaIncarnation and publisherIncarnation reset
R10 corpus: ordering regression, restart incarnation, stale ALIVE negative
```

---

## References

- `docs/analysis/0058-profile-03-r3-liveness-timers-hysteresis-adjudication.md`
- `docs/analysis/0058-profile-03-r2-liveness-state-semantics-adjudication.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md` (Decision 5)
