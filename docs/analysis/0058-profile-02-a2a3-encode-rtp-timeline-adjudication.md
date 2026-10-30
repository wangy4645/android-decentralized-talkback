# P02-A2 + P02-A3 — Encode-Once and Single RTP Timeline (Adjudication)

## Status

```text
P02-A2 + P02-A3: ACCEPTED — MODEL M1′
Date:             2026-09-03
Prerequisite:     P02-A1 ACCEPTED — Option A
Parent:           0058-profile-02-amendment-unicast-fanout-outline.md
Scope:            encode-once + RTP identity across fan-out / mode transition
                  SRTP protect-once vs per-destination DEFERRED to P02-A4
Next:             P02-A4 + P02-A5
```

---

## Core question

For one admitted media Source and one 20 ms media-time slot, under mixed
multicast / unicast fan-out:

```text
Does RTP identity remain a single Source timeline?
Is SRTP protection executed once or per destination?
```

**This adjudication answers the first.** The second is **explicitly deferred** to A4.

---

## Options adjudicated

### Model M1′ — Encode once + single RTP timeline (ACCEPTED)

```text
Opus encode once per slot
→ build one RTP packet identity per slot
   (same SSRC, sequence number, timestamp, cleartext RTP header + HE + Opus payload)
→ fan-out to multicast and/or unicast destinations carries that one identity
→ SRTP protection placement (once vs per-destination) decided in A4 only
```

### Model M2 — Encode once, per-destination RTP identity (REJECTED)

Each fan-out destination receives a distinct `SSRC` and/or `sequence number` for
the same Source slot. **Rejected:** fractures one admitted Source into multiple RTP
timelines; breaks jitter, ROC/replay, Source binding, and Q1 continuity semantics.

### Model M3 — Per-receiver encode / RTP stream (REJECTED)

Independent Opus encode and RTP stream per destination. **Rejected:** fan-out
becomes a multi-stream model; violates encode-once capacity envelope; incompatible
with ADR-0058 single-Source semantics.

---

## Frozen principle

```text
one admitted media Source
=
one RTP timeline
```

Transport destination changes (multicast group vs per-receiver unicast) MUST NOT
mint a new media Source identity, new SSRC, or reset sequence/timestamp merely
because the delivery mode changed.

---

## Frozen decision — production graph (A2)

For one admitted Source `S` and one 20 ms media-time slot `t`:

### Step 1 — Encode (once)

```text
Exactly one Opus frame is encoded for slot t.
Fan-out count N MUST NOT cause N encodes for the same (S, t).
```

### Step 2 — RTP assembly (once)

```text
Exactly one RTP packet identity is assembled for slot t:

  SSRC           = S's bound SSRC (unchanged across fan-out legs)
  sequenceNumber = next value in S's single timeline
  timestamp      = per Q1 20 ms media-time grid for slot t
  marker         = per Q1 talkspurt rules
  header + HE + Opus payload = one cleartext RTP packet body for slot t
```

This cleartext RTP packet is the **RTP identity artifact** for slot `t`. All
fan-out legs for this slot MUST derive from this single artifact.

### Step 3 — SRTP (deferred to A4)

```text
SRTP AEAD protection is NOT placement-frozen in A2/A3.

A4 will decide whether the sender:
  (a) protects once → potentially identical ciphertext copies, or
  (b) protects per destination from the same RTP identity artifact

A2/A3 forbid assuming (a) or (b) without A4.
```

### Step 4 — Wire fan-out (transport only)

```text
For each selected delivery leg at slot t (multicast and/or unicast):
  emit one UDP datagram derived from the slot-t RTP identity
  (post-SRTP form per A4)

N transport legs MUST NOT imply N Opus encodes or N RTP sequence advances.
```

---

## Frozen decision — RTP identity (A3)

### Identity fields shared across all legs of slot `t`

```text
SSRC
sequenceNumber
timestamp
cleartext RTP header + HE + Opus payload (pre-SRTP)
```

Multicast optimization and unicast fan-out legs for the same `(S, t)` MUST carry
the **same** values for all fields above.

### Q1 sequence advance rule (amended interpretation)

Baseline Q1:

```text
Sequence advances only for an RTP packet that is actually emitted.
```

Amendment:

```text
For fan-out, one media-time slot t constitutes exactly ONE logical RTP emission
for sequence/timestamp purposes, regardless of how many transport legs carry it.

sequenceNumber advances by exactly 1 for slot t
  even when t is delivered on:
    - multicast only
    - unicast to N receivers
    - multicast + unicast to different receivers (mixed-mode; overlap rules = A7)

Fan-out leg count N is NOT N sequence advances.
```

### SSRC continuity across delivery-mode transition

```text
Changing PerReceiverTransportMode (multicast ↔ unicast) for a receiver
MUST NOT change SSRC, reset sequence, or reset timestamp for the Source.

Mode transition is a transport execution change, not a Source incarnation change.
```

---

## Anti-example — mode transition must not reset timeline

```text
Receiver M03 receives Source S from M01:

Phase 1 — multicast optimization:
  receives seq = 100 .. 200

Phase 2 — M03 publishes MulticastReceiveLiveness = DEAD
  sender switches M03 leg to unicast fallback

WRONG (forbidden):
  unicast leg starts at seq = 0 (or new SSRC)
  → M03 jitter / replay / ROC sees false new stream or backward sequence

CORRECT (required):
  unicast leg continues seq = 201, 202, …
  same SSRC, same Q1 timestamp grid residue class
```

This follows from **one Source = one RTP timeline** and is independent of whether
A4 chooses protect-once or per-destination SRTP.

---

## Relationship to P02-A1

```text
A1 defines WHERE each leg is addressed (MediaGroupEndpoint vs PerReceiverUnicastEndpoint)
A2/A3 define WHAT RTP identity is carried on every leg for slot t
A1 destination change does not create new RTP identity
```

---

## Relationship to P02-A4 (explicit deferral)

The following are **NOT** decided here:

```text
SRTP protect once → byte-identical ciphertext copies to all destinations
SRTP protect per destination from same RTP identity artifact
sender ROC / packet-index ownership across destinations
whether all receivers share one keyContext for fan-out
```

A2/A3 neither require nor forbid byte-identical post-SRTP datagrams.

```text
FORBIDDEN TO ASSUME WITHOUT A4:
  "Take one SRTP-protected UDP payload and sendto() it to N IPs."
```

---

## Profile 02 amendment text (normative insert)

To be merged into `0058-implementation-profile-02-rtp-wire.md` when amendment closes.

### Q1 — Fan-out production (amendment)

```text
Fan-out amendment (P02-A2/A3):

For one admitted Source and one 20 ms media-time slot:
  exactly one Opus encode
  exactly one RTP packet identity (SSRC, seq, timestamp, cleartext RTP body)
  sequence advances once per slot regardless of fan-out leg count

Multicast, unicast, and mixed-mode delivery are transport fan-out of the same
RTP identity. They MUST NOT create per-destination RTP timelines for one Source.

Delivery-mode transition (multicast ↔ unicast) MUST NOT reset SSRC, sequence,
or timestamp. Unicast fallback continues the existing Source timeline.

SRTP protection placement is defined in P02-A4, not here.
```

### Q6 — Replay scope note (amendment pointer)

```text
Receiver replay evaluation remains per (IngressTransportBinding, SSRC) per Q6.
Because A2/A3 freeze one SSRC timeline per Source, a receiver observing the same
Source over multicast then unicast MUST use continuous replay state for that SSRC
— not treat unicast arrival as a new stream.
```

---

## Consequences

```text
M1′ accepted: encode once + single RTP timeline across all fan-out legs
M2, M3 rejected
A4 must choose SRTP protect-once vs per-destination without reopening RTP identity
A7 (overlap / duplicate path) remains independent; A3 does not authorize steady-state
  duplicate decode — see A7
A10 corpus must prove seq continuity across simulated mode transition
```

---

## References

- `docs/analysis/0058-profile-02-a1-destination-ingress-adjudication.md`
- `docs/analysis/0058-profile-02-amendment-unicast-fanout-outline.md`
- `docs/analysis/0058-implementation-profile-02-rtp-wire.md` (Q1, Q6)
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
