# P03-R1 — MulticastReceiveLiveness Observation Ownership (Adjudication)

## Status

```text
P03-R1: ACCEPTED
Date:   2026-09-03
Parent: 0058-profile-03-resilience-amendment-outline.md
        ADR-0060 Decision 2 (authority split)
Scope:  who may originate MulticastReceiveLiveness; what inputs are forbidden
        NO timer numerics; NO ALIVE/SUSPECT/DEAD transition rules (R2)
Next:   P03-R2
```

---

## Question

Who has authority to originate `MulticastReceiveLiveness`, and from what evidence
class?

---

## Decision summary

```text
ACCEPTED:
  Only the receiving Module may originate MulticastReceiveLiveness for itself.
  Origin input class: actual local multicast media ingress observation only.

REJECTED:
  sender-side inference of receiver multicast liveness
  proxy/substitute signals (IGMP, MulticastLock, sendto, AP, joinSucceeded)
  treating liveness as Profile 01 Fact or Health projection
```

---

## Frozen ownership

### Originator

```text
MulticastReceiveLiveness for Module R
  MAY be originated ONLY by Module R (self)
  MUST be derived ONLY from R's local observation of multicast media ingress
```

```text
Remote Module S (sender) MUST NOT originate MulticastReceiveLiveness on behalf of R.
No Module MAY publish liveness for another Module's receive path.
```

### Observation input class (R1 — structural only)

```text
Qualifying observation (structure frozen; thresholds in R2/R3):

  Local receipt of Conference media packets on the multicast optimization ingress
  path for the active Conference media incarnation, after Profile 02 admission
  (MULTICAST_INGRESS binding + successful media-pipeline admission for a Source).

R1 does NOT freeze:
  exact packet count / gap / percentage thresholds
  ALIVE vs SUSPECT vs DEAD transition rules (R2)
  timer values (R3)
```

Observation is **continuous** over active transport (ADR-0060). R1 freezes the
**evidence class**, not the evaluation algorithm.

---

## Forbidden inputs (non-exhaustive — REJECT if used as liveness proof)

The following MUST NOT be used to set or infer `MulticastReceiveLiveness` for a
receiver (by receiver or sender):

```text
Sender-side:
  multicast sendto success / socket write success
  "I am sending to the group therefore R is ALIVE"
  remote IGMP / socket state visible to sender

Receiver-side substitutes for actual media ingress:
  MulticastSocket.join / joinSucceeded alone
  WifiManager.MulticastLock held
  /proc/net/igmp group membership present without media receive
  WiFi associated / RSSI / screen on
  AP reachability / mesh control-plane health

Control-plane:
  Membership snapshot / roster ONLINE
  Profile 01 Facts
  Source Health / Conference Health projection inputs

Session-start:
  one-shot probe pass at join (ADR-0060 forbidden as sole qualification)
```

### E3a-aligned anti-patterns (documented)

```text
M02: joinSucceeded=true, kernel IGMP present, sustained 0% app receive
     → MUST NOT yield ALIVE from join/IGMP alone

M03→M02 sender TX healthy while M02 ingress 0%
     → MUST NOT allow M01 to infer M02 ALIVE from send path
```

---

## Fact classification (frozen)

```text
MulticastReceiveLiveness
  = transport optimization eligibility observation
  ≠ Profile 01 Membership Fact
  ≠ Source Health
  ≠ Membership Health
  ≠ Conference readiness / degraded state
  ≠ PerReceiverTransportMode (sender execution — ADR-0060)
```

```text
Publication of MulticastReceiveLiveness:
  informs sender optimization eligibility consumption
  MUST NOT mutate conferenceEpoch, membershipVersion, mediaKeyEpoch
  MUST NOT trigger Membership leave/prune semantics
  MUST NOT alone downgrade Conference-level readiness for other members
```

One receiver publishing DEAD means **that receiver** is not a reliable multicast
optimization target until recovery publication — not that the Conference failed.

---

## Sender boundary (pointer — detailed in R5)

```text
Sender MUST NOT compute MulticastReceiveLiveness for remote receivers.

Sender consumes receiver-published liveness only (R4 freshness rules).
Sender derives PerReceiverTransportMode locally (ADR-0060).
```

R1 does not define consumption FSM — R5.

---

## Receiver boundary vs Profile 03 runtime

```text
Observation seam sits at:
  Profile 02 admitted multicast media ingress → local receive evidence

Profile 03 jitter/decode/Top-K:
  consumes admitted media
  MUST NOT be the authority that declares remote multicast optimization eligibility
  MAY supply local "last qualifying receive" timestamps as R3 evidence inputs
    (algorithm in R2/R3 — not R1)
```

`MulticastReceiveLiveness` is **adjacent to** receive runtime, not a replacement for
`AdmittedMediaSource` authority.

---

## Options considered

| Option | Verdict |
|--------|---------|
| **R1-A** Receiver-only from actual multicast media ingress | **ACCEPT** |
| **R1-B** Sender may infer ALIVE from send success | **REJECT** |
| **R1-C** Receiver publishes imperative "use unicast" command | **REJECT** (mode is sender-owned) |
| **R1-D** Liveness folded into Membership/Health Facts | **REJECT** |

---

## Profile 03 amendment text (normative insert)

```text
P03-R1 — MulticastReceiveLiveness observation ownership:

Only the local receiving Module MAY originate MulticastReceiveLiveness for itself.

Origin evidence class: actual local multicast media ingress observation on the
multicast optimization path after Profile 02 admission.

Forbidden as liveness proof: joinSucceeded, IGMP, MulticastLock, sendto success,
AP/WiFi association, Membership/Health facts, session-start-only probe.

MulticastReceiveLiveness is transport optimization eligibility observation only.
```

---

## Consequences

```text
R2 may define ALIVE/SUSPECT/DEAD meaning without reopening ownership
R3 may attach timers to state transitions defined in R2
E3a evidence informs test design; does not gate R1
Publication wire (R4) and sender FSM (R5) build on this ownership boundary
```

---

## References

- `docs/adr/0060-conference-media-transport-resilience-contract.md`
- `docs/analysis/0058-profile-03-resilience-amendment-outline.md`
- `docs/analysis/0058-profile-02-a1-destination-ingress-adjudication.md`
- `docs/analysis/adr0058-e3a-underlay-multicast-status.md` (E3a.3b, 3d)
