# P02-A1 — Destination Contract and Ingress Lookup Scope (Adjudication)

## Status

```text
P02-A1: ACCEPTED — OPTION A
Date:   2026-09-03
Parent: 0058-profile-02-amendment-unicast-fanout-outline.md
        ADR-0060 ACCEPTED — FROZEN
Scope:  destination contract + ingress lookup scope ONLY
        SRTP keying / protect-once (A4) NOT decided here
Next:   P02-A2 + P02-A3
```

---

## Question

What wire destination types does Profile 02 freeze for Conference media delivery,
and how does pre-auth key-context lookup scope ingress on the receiver?

---

## Options adjudicated

### Option A — PerReceiverUnicastEndpoint + IngressTransportBinding (ACCEPTED)

Introduce an explicit unicast destination tuple for sender egress. Separate
**transport addressing** from **ingress lookup authority**. Unicast ingress lookup
uses a local admitted transport/source binding — not the local destination IP alone.

### Option B — Mechanical `(destinationIp, keyContextHint64)` extension (REJECTED)

Extend the existing multicast lookup tuple by substituting unicast destination IP
for group IP. **Rejected:** local destination IP on a receiver is not a sender or
Source identity scope (see anti-example below).

### Option C — Per-peer SSRC / wire identity for unicast (REJECTED)

Invent per-destination RTP or wire identity at the destination layer to
disambiguate senders. **Rejected:** prematurely invades P02-A2/A3 RTP identity;
does not fix lookup scope correctly; would fracture Source incarnation semantics.

---

## Frozen decision — destination contract

Profile 02 recognizes **two** normative wire destination forms. Both use the same
`mediaPort` from the Conference `MediaGroupEndpoint` for a given media-group
incarnation unless a future amendment explicitly reopens port binding.

### Multicast optimization path

```text
MulticastWireDestination {
  endpoint: MediaGroupEndpoint {
    underlayScopeId
    ipFamily
    multicastAddress      // group L3 destination
    mediaPort
    hopLimit / scope
    trafficProfileId
  }
}
```

Sender `sendto` target: `(multicastAddress, mediaPort)` per existing Q8.

### Unicast correctness / fallback path

```text
PerReceiverUnicastEndpoint {
  receiverModuleId        // binding identity; NOT inferred from IP alone
  moduleFixedIp           // actual L3 destination (underlay fixed Module IP)
  mediaPort               // same Conference mediaPort unless amended
}
```

```text
receiverModuleId
  = admitted Module identity for the fan-out target
  = prevents treating "an IP address" as receiver identity

moduleFixedIp
  = underlay fixed Module IP used as UDP destination
  = transport addressing only
```

Sender `sendto` target for one fan-out leg:
`(moduleFixedIp, mediaPort)` with an execution-side binding to `receiverModuleId`.

Profile 02 freezes the **wire tuple** and the **binding identity field**. How
`receiverModuleId` is resolved from Membership / underlay facts is Profile 01 /
control-plane input — not redefined here.

### Shared rules

```text
transport              = UDP only (unchanged)
one media-group incarnation = one IP family (unchanged)
Profile 02 MUST NOT invent overlay routing (unchanged)
destination fields     = transport addressing + admitted receiver binding
destination fields   ≠ sender identity
destination fields   ≠ Source / SSRC authority
destination fields   ≠ SRTP key-context authority (A4 deferred)
```

---

## Frozen decision — ingress lookup scope

Pre-auth key-context lookup is **not** a single tuple for all ingress kinds.
Profile 02 introduces `IngressTransportBinding` as the lookup scope discriminator.

### IngressTransportBinding

```text
IngressTransportBinding =
  | MULTICAST_INGRESS {
      committedDestination: MediaGroupEndpoint    // group + port + family scope
      keyContextHint64
    }
  | UNICAST_INGRESS {
      admittedTransportBinding: AdmittedIngressTransportBinding
      keyContextHint64
    }
```

```text
AdmittedIngressTransportBinding {
  conferenceId
  conferenceEpoch
  mediaGroupEndpointId          // ties ingress to one Conference media incarnation
  remoteSenderModuleId          // which admitted Module sent this fan-out leg
  localReceiverModuleId         // this Module (self)
}
```

**Normative meaning:**

```text
MULTICAST_INGRESS
  lookup scope = locally observed committed group destination + hint
  (preserves existing Q5 multicast semantics)

UNICAST_INGRESS
  lookup scope = locally admitted transport binding for a specific remote sender
                 + hint
  NOT lookup scope = (localDestinationIp, hint) alone
```

### Why local destination IP is insufficient (anti-example)

```text
Conference on M03 (receiver). Fallback unicast fan-out:

  M01 → M03 unicast   destinationIp = M03.moduleFixedIp
  M02 → M03 unicast   destinationIp = M03.moduleFixedIp

If pre-auth lookup scope were:

  (destinationIp, keyContextHint64)

then both senders share the same destinationIp on M03. The destination tuple
cannot express which remote sender's key context is in scope. A receiver would
be forced to trial-verify against an ambiguous candidate set or mis-associate
packets from different senders.

Therefore:
  destination answers WHERE the datagram arrived (transport)
  AdmittedIngressTransportBinding answers WHICH admitted remote sender leg
    this ingress belongs to (lookup scope)
```

Remote source IP on the UDP header is **not** a Profile 02 lookup input (same as
existing Q5: "Remote source IP/MAC and packet arrival order are not selection
inputs"). Unicast fan-out does not reopen source-IP-based admission.

### Lookup algorithm (unchanged mechanics, scoped input)

Within each `IngressTransportBinding` variant, the existing Q5 candidate bound
applies unchanged:

```text
zero CURRENT_ACTIVE candidates     → UNKNOWN_KEY_CONTEXT_HINT
one candidate                      → at most one AEAD attempt
two candidates (hint collision)    → lexicographic digest sort; ≤1 attempt each
more than two                      → KEY_CONTEXT_HINT_FANOUT_EXCEEDED
```

The amendment changes **what indexes the candidate set**, not the collision /
verification rules.

### Sender-side mirror (wire only)

For each 20 ms slot and each `PerReceiverUnicastEndpoint` selected by execution:

```text
emit one UDP datagram to (moduleFixedIp, mediaPort)
bound to receiverModuleId at the fan-out execution layer
```

Profile 02 does **not** freeze how execution chooses unicast vs multicast for a
receiver (ADR-0060 / Profile 03). It freezes that when unicast is chosen, the
destination contract above is mandatory.

---

## Explicit deferrals (not decided in A1)

```text
A4  SRTP context / keying / protect-once vs protect-N
A2  encode-once graph
A3  SSRC / seq / timestamp sharing across fan-out copies
A7  simultaneous multicast + unicast to same receiver
```

A1 MUST NOT be read as permitting different SRTP keys per `receiverModuleId`.
That is A4.

---

## Profile 02 amendment text (normative insert)

The following replaces / extends the cited baseline clauses when this amendment
is merged into `0058-implementation-profile-02-rtp-wire.md`.

### Q8 — Transport and addressing (amendment)

```text
Wire destinations (V1, post-amendment):

  Multicast optimization:
    MediaGroupEndpoint.multicastAddress + mediaPort

  Unicast fan-out:
    PerReceiverUnicastEndpoint.moduleFixedIp + mediaPort
    with receiverModuleId binding at fan-out execution

Both forms share the Conference MediaGroupEndpoint.mediaPort for the incarnation
unless explicitly reopened by amendment.
```

### Q5 — Candidate lookup scope (amendment)

Replace:

```text
(locally observed committed destination MediaGroupEndpoint, keyContextHint64)
```

With:

```text
IngressTransportBinding as defined in P02-A1:
  MULTICAST_INGRESS  → (committed MediaGroupEndpoint, keyContextHint64)
  UNICAST_INGRESS    → (AdmittedIngressTransportBinding, keyContextHint64)

Local UDP destination IP alone MUST NOT be used as the unicast ingress lookup key.
Remote source IP/MAC and packet arrival order remain non-inputs.
```

---

## Consequences

```text
Sender may address unicast fan-out legs by fixed Module IP + shared mediaPort.
Receiver distinguishes concurrent unicast senders by AdmittedIngressTransportBinding,
  not by local destination IP.
Option B and C rejected; A2/A3 remain unconstrained by A1 beyond shared port.
Q9 corpus (A10) must include UNICAST_INGRESS binding in fixture context.
```

---

## References

- `docs/analysis/0058-profile-02-amendment-unicast-fanout-outline.md`
- `docs/analysis/0058-implementation-profile-02-rtp-wire.md` (Q5, Q8)
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
- `docs/adr/0058-conference-multicast-media-group.md` (`MediaGroupEndpoint`)
