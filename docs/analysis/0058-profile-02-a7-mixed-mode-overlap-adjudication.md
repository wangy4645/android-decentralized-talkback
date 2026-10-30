# P02-A7 — Mixed-Mode Delivery and Transition Overlap (Adjudication)

## Status

```text
P02-A7: ACCEPTED — MODEL O1
Date:   2026-09-03
Prerequisites: P02-A1..A6 ACCEPTED
Parent: 0058-profile-02-amendment-unicast-fanout-outline.md
Scope:  steady-state authoritative mode + bounded transition overlap + duplicate handling
        mode selection policy / hysteresis numerics → Profile 03 (partial pointer only)
Next:   P02-A8 (per-receiver send failure isolation)
```

---

## Core question

May the same Source slot be delivered to the **same receiver** on both multicast and
unicast — and if so, under what constraints?

**Frozen answer:** Not in steady state. During **bounded transition** only, with
duplicate delivery semantics — never dual-path long-term dependency.

---

## Authority boundary

```text
PerReceiverTransportMode selection     ADR-0060 / Profile 03
TransitionOverlapMaxMs numeric bound   Profile 03 (TBD — not frozen here)

P02-A7 freezes:
  wire-layer legality of overlap during transition
  receiver duplicate-delivery disposition
  steady-state single authoritative mode invariant
```

---

## PerReceiverTransportMode (reference)

```text
MULTICAST
UNICAST_FALLBACK
```

Profile 02 does not own mode selection. It constrains wire behavior **given** a
sender execution directive per receiver per slot.

---

## Options adjudicated

### Model O1 — Single authoritative mode + bounded transition overlap (ACCEPTED)

```text
steady-state:   exactly one authoritative delivery mode per receiver
transition:     MAY allow finite, observable, bounded overlap
duplicate:      same RTP identity on two legs → duplicate delivery, decode ≤ once
```

### Model O2 — Zero overlap; stop old path before starting new (REJECTED)

Hard switch with no concurrent legs. **Rejected:** likely audio gap during mode
transition; incompatible with ADR-0060 mid-session downgrade without user rejoin.

### Model O3 — Long-term multicast + unicast redundancy (REJECTED)

Steady-state dual-path delivery to the same receiver. **Rejected:** doubles
`FanoutDatagramRate`; treats optimization path as implicit correctness redundancy;
violates single authoritative mode.

---

## Frozen decision — steady state

```text
For each receiver R and each admitted remote Source S:

  steady-state MUST have exactly one authoritative PerReceiverTransportMode
  for delivery of S's media.

  The sender MUST NOT rely on both multicast and unicast for the same (S, R)
  in steady state.

  Steady-state duplicate delivery to the same receiver for the same slot
  is FORBIDDEN.
```

```text
steady-state ≠ "no multicast group send while any receiver is on unicast"

Global multicast leg MAY remain active for other receivers.
Per-receiver authoritative mode is the frozen unit.
```

---

## Frozen decision — transition overlap

During an explicit transport-mode transition for receiver `R`, the sender MAY
deliver the same `ProtectedDatagramArtifact` for slot `t` on **both**:

```text
multicast leg (if still active for transition), and
unicast leg to R (PerReceiverUnicastEndpoint)
```

### Transition properties (all required)

```text
MUST be finite          (bounded duration — numeric bound Profile 03)
MUST be observable      (transport execution evidence records mode transition)
MUST NOT create a second Source / SSRC / RTP timeline
MUST NOT become steady-state dual-path dependency
```

```text
TransitionOverlapMaxMs = TBD in Profile 03

P02-A7 freezes only: a finite bound MUST exist; no 40/120/200 ms value here.
```

### Downgrade transition (MULTICAST → UNICAST_FALLBACK)

```text
1. Sender begins unicast fan-out leg for R (same RTP/SRTP artifact per A2–A4)
2. Bounded overlap MAY exist (multicast + unicast to R for same slots)
3. UNICAST_FALLBACK becomes authoritative for R
4. Sender MUST NOT rely on multicast delivery to R after transition completes
```

### Upgrade transition (UNICAST_FALLBACK → MULTICAST)

```text
Stricter than downgrade:

1. Fresh receiver-published ALIVE evidence required (ADR-0060 — Profile 03)
2. Explicit transport-mode transition decision MUST precede termination of unicast
3. Bounded overlap MAY exist
4. MULTICAST becomes authoritative for R
5. Sender stops receiver-specific unicast leg after transition completes

FORBIDDEN:
  observing one or two multicast packets and immediately stopping unicast
  (E3a.3b establish-then-die class — hysteresis is Profile 03)

P02-A7 freezes:
  packet arrival alone is insufficient to end upgrade overlap / stop unicast
  overlap termination requires explicit transport-mode transition decision
```

---

## Frozen decision — duplicate delivery during overlap

Because A4 freezes byte-identical `ProtectedDatagramArtifact` on all legs, overlap
may cause the **same receiver** to receive the same `(Source, SSRC, seq, timestamp,
protected payload)` via two ingress paths.

This is **duplicate delivery of one RTP packet**, not two independent media streams.

### Receiver disposition (frozen outcome)

```text
same admitted Source S
+ same SSRC / sequence / timestamp
+ same protected artifact (or byte-identical ciphertext)
+ received via two transport legs during bounded transition

→ classify as duplicate delivery
→ SRTP replay duplicate discard OR equivalent earliest-seam duplicate disposition
→ admit to media pipeline at most ONCE per slot
```

### MUST NOT occur

```text
decode twice for the same slot
PLC / jitter accounting twice for the same slot
Source activity / voiceActive counted twice
mint a second Source identity or SSRC
treat the second leg as a new stream because IngressTransportBinding differs
```

### Ingress binding note (A1 + A4)

```text
MULTICAST_INGRESS and UNICAST_INGRESS remain distinct lookup scopes (A1).

During overlap, duplicate disposition MUST unify on RTP packet identity
(Source admission + SSRC + seq [+ timestamp]) across bindings for the same
remote sender — not merely per-binding independent accept paths to decode.
```

Replay continuity across mode change (A4) and duplicate discard during overlap
(A7) are complementary: timeline continues; slot is consumed once.

---

## WirePpsCeiling interaction (A6)

```text
Overlap delivery of slot t to the same receiver on two legs
  = one logical media emission for WirePpsCeiling (A6)
  = two UDP datagrams for FanoutDatagramRate
```

---

## Anti-examples

### Anti-example 1 — steady-state dual dependency

```text
M03 on UNICAST_FALLBACK but sender continues relying on multicast to M03
every slot "just in case" for minutes.

REJECT (O3 class). Violates steady-state single authoritative mode.
```

### Anti-example 2 — upgrade on first multicast packet

```text
M03 was on UNICAST_FALLBACK. One multicast packet arrives. Sender stops unicast.

REJECT. Overlap termination requires explicit mode transition decision;
hysteresis against false recovery is Profile 03; A7 forbids packet-arrival-only cutover.
```

### Anti-example 3 — double decode on overlap

```text
Slot seq=201 arrives on multicast and unicast to M03 during downgrade overlap.
Decoder runs twice; jitter buffer holds two copies.

REJECT. Duplicate delivery — decode at most once.
```

---

## Profile 02 amendment text (normative insert)

```text
P02-A7 — Mixed-mode and transition overlap:

Steady-state: for each (remote Source S, local receiver R), exactly one
authoritative PerReceiverTransportMode. No steady-state duplicate path dependency.

Transition: bounded overlap MAY deliver the same ProtectedDatagramArtifact to R
on multicast and unicast concurrently. Overlap MUST be finite (TransitionOverlapMaxMs
defined in Profile 03). Overlap MUST be observable.

Duplicate delivery during overlap: same RTP packet identity received on two legs
→ duplicate discard → at most one media-pipeline admission per slot.

Long-term dual-path redundancy (O3): FORBIDDEN.
Zero-overlap hard switch (O2): REJECTED for V1.

Upgrade overlap end: explicit transport-mode transition decision required;
packet arrival alone insufficient (Profile 03 owns hysteresis numerics).
```

---

## Consequences

```text
O1 accepted; O2/O3 rejected
A8 may assume per-leg send failure without duplicate-decode ambiguity in steady state
A9 FanoutDatagramRate: steady-state per-receiver single leg; overlap is transient
A10 corpus: overlap duplicate vectors + steady-state single-leg vectors
Profile 03 must define TransitionOverlapMaxMs and upgrade/downgrade FSM
```

---

## References

- `docs/analysis/0058-profile-02-a6-pacing-wirepps-adjudication.md`
- `docs/analysis/0058-profile-02-a4a5-srtp-wire-envelope-adjudication.md`
- `docs/analysis/0058-profile-02-a1-destination-ingress-adjudication.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
