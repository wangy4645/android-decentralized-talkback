# P02-A4 + P02-A5 — SRTP Context and Wire Envelope (Adjudication)

## Status

```text
P02-A4 + P02-A5: ACCEPTED — MODEL K1 + FULL A5 INHERITANCE
Date:             2026-09-03
Prerequisites:    P02-A1 ACCEPTED (Option A)
                  P02-A2+A3 ACCEPTED (M1′)
Parent:           0058-profile-02-amendment-unicast-fanout-outline.md
Scope:            SRTP sender context + protect-once fan-out + wire envelope
Next:             P02-A6 (pacing / WirePpsCeiling)
```

---

## Core questions

```text
1. For the same RTP artifact on multicast and unicast legs, is SRTP context shared?
2. Are existing Profile 02 wire envelope clauses inherited unchanged per datagram?
```

---

## P02-A4 — SRTP context models

### Options adjudicated

#### Model K1 — Source-level shared SRTP context (ACCEPTED for V1)

```text
one admitted Source
one RTP timeline                    (A2/A3)
one SRTP sender context per Source  (same Conference media incarnation + key context)
one sender ROC / packet-index state
one protected datagram artifact per slot
  → multicast leg
  → unicast leg(s)                  (byte-identical protected UDP payload)
```

#### Model K2 — Per-destination independent SRTP protect (REJECTED for V1)

Same RTP cleartext artifact, but each fan-out destination uses an independent
SRTP sender context / ROC / protect operation. **Rejected for V1:** introduces
per-receiver key/ROC/context complexity incompatible with M1′ single-timeline
fallback. MAY be reconsidered only via explicit A4 reopen if per-receiver keying
is ever adopted.

#### Model K3 — Independent RTP/SRTP streams per transport mode (REJECTED)

Multicast and unicast as separate RTP/SRTP streams. **Rejected:** violates
P02-A2/A3 single RTP timeline and mode-transition continuity.

---

## Frozen decision — sender SRTP ownership (A4)

For one admitted Source `S` under one `CURRENT_ACTIVE` media key context and one
Conference media incarnation:

### Source-level SRTP sender context

```text
Sender maintains exactly one SRTP execution context per (S, CURRENT_ACTIVE key context).

That context owns:
  Profile 01 derived master key / salt (unchanged Q5 policy)
  sender ROC / SRTP packet-index advancement for S
  AEAD protect operation for each emitted slot
```

Fan-out MUST NOT create per-destination sender SRTP contexts in V1.

### Protect-once fan-out

For each 20 ms slot `t`:

```text
1. Build one RTP identity artifact           (A2/A3)
2. SRTP-protect once under S's sender context → one ProtectedDatagramArtifact_t
3. Transmit byte-identical UDP payload on every selected leg:
     - multicast WireDestination (MediaGroupEndpoint)
     - zero or more unicast WireDestinations (PerReceiverUnicastEndpoint)
```

```text
ProtectedDatagramArtifact_t
  = complete SRTP RTP UDP payload (<= SafeUdpPayloadBytes)
  = identical octets on all legs for slot t
```

### Preconditions (all required)

Protect-once replication is legal only when **all** of the following hold:

```text
same Conference media incarnation
same admitted Source S
same CURRENT_ACTIVE media key context
same SSRC / RTP seq / timestamp for slot t   (A2/A3)
```

If any precondition fails, the sender MUST NOT replicate a protected datagram
across legs. Key epoch fence (Q5) applies uniformly: all legs stop old-key
emission together; new-key emission resumes only after new context activation.

### Per-receiver keying boundary

```text
V1 Conference members authenticate fan-out unicast with the same Profile 01
media keyContext as multicast optimization for that Conference incarnation.

Per-receiver distinct SRTP keys are FORBIDDEN in V1 without explicit P02-A4
REOPEN. Implementations MUST NOT silently migrate from shared-context protect-once
to per-destination protect.
```

---

## Frozen decision — receiver SRTP / replay scope (A4)

Receiver verification remains scoped by **P02-A1** `IngressTransportBinding`, not
by ciphertext equality alone.

```text
MULTICAST_INGRESS:
  (committed MediaGroupEndpoint, keyContextHint64) → Q5 candidate lookup → Q6 replay

UNICAST_INGRESS:
  (AdmittedIngressTransportBinding, keyContextHint64) → Q5 candidate lookup → Q6 replay
```

### Receiver rules

```text
Identical protected payload bytes on different ingress bindings are NOT proof that
packets belong to the same receiver replay evaluation scope.

Replay state is per receiver Module, per IngressTransportBinding variant, per
admitted Source SSRC — unchanged Q6 mechanics.

Ciphertext sameness MUST NOT collapse distinct remote senders into one context.
```

Example: M01 and M02 may send byte-identical protected datagrams (same Conference
key) to M03 unicast. M03 evaluates each leg under its own
`AdmittedIngressTransportBinding.remoteSenderModuleId`, not under local IP alone.

### Mode-transition replay continuity

When a receiver observes the same Source `S` / SSRC over multicast then unicast,
replay evaluation MUST continue the existing ROC/replay window for that
`(IngressTransportBinding family, S, SSRC)` — not treat unicast arrival as a new
SRTP stream. Binding variant changes (MULTICAST → UNICAST) update ingress scope
per A1; SSRC timeline continuity per A2/A3.

---

## Anti-example — split SRTP state across legs (forbidden)

```text
Source S, slot seq = 500

Multicast leg:   sender SRTP context ROC = 3
Unicast leg:     separate sender SRTP context ROC = 1   ← FORBIDDEN in V1

Same Source timeline would carry different SRTP packet-index state on different
transport legs. Fallback replay/ROC continuity becomes ambiguous and violates
M1′ + K1.

V1 requirement:
  one sender SRTP context → one ROC for slot 500 → identical protected artifact
  on all legs
```

---

## Sender / receiver diagram (frozen)

```text
SENDER (per Source S, per slot t):
  encode once → RTP identity once → SRTP protect once → ProtectedDatagramArtifact_t
    ├─→ UDP send (multicast destination)     [optional leg]
    └─→ UDP send (unicast destination × N)   [0..N legs]

RECEIVER (per local Module R):
  ingress → IngressTransportBinding → Q5 → Q6 → Profile 03
  replay state keyed by binding + SSRC, not by "same ciphertext"
```

---

## P02-A5 — Wire envelope inheritance

### Decision: full inheritance per UDP datagram (ACCEPTED)

Every fan-out leg — multicast or unicast — carries a datagram subject to the
**same** frozen Profile 02 wire envelope. Fan-out does not relax or multiply the
per-datagram budget.

```text
SafeUdpPayloadBytes              = 120     UNCHANGED
ProtectedRtpUdpBodyBytes         <= 120    UNCHANGED
RTP payload type                 = 111     UNCHANGED
RTP version                      = 2       UNCHANGED
Opus / HE / CSRC=0 / padding     per Q1/Q2 UNCHANGED
RTCP / SRTCP                     FORBIDDEN (Q7) UNCHANGED
NACK / RTP retransmission        FORBIDDEN (Q7) UNCHANGED
late-frame / catch-up semantics  per Q8     UNCHANGED
oversize > 120                   WIRE_ENVELOPE_OVERSIZE local drop UNCHANGED
```

### Per-datagram vs aggregate (explicit)

```text
120-byte envelope = constraint on EACH UDP media datagram
                  ≠ fan-out aggregate byte budget
                  ≠ sender aggregate datagram-per-second ceiling (see A6)

10 unicast legs × 1 protected datagram each = 10 datagrams, each <= 120 bytes
```

### Q5 crypto policy (unchanged)

```text
AEAD_AES_128_GCM
Profile 01 derived master key / salt
key derivation rate = 0
MKI absent
RTP encrypted, fixed header + Q2 HE as AAD
```

### New failure classes

```text
No new Profile 02 wire failure class required for A4/A5.

Fan-out send failures remain transport-layer evidence (A8).
SRTP/auth/replay failures remain existing Q5/Q6 classes.
```

---

## Profile 02 amendment text (normative insert)

### Q5 — Fan-out SRTP (amendment)

```text
P02-A4:

For one admitted Source under one CURRENT_ACTIVE media key context:
  one sender SRTP context
  one ROC / packet-index advancement per emitted slot
  one SRTP protect per slot → one ProtectedDatagramArtifact
  byte-identical replication to all selected multicast/unicast legs

Per-destination sender SRTP contexts: FORBIDDEN in V1 (K2 rejected).
Per-transport-mode separate streams: FORBIDDEN (K3 rejected).
Per-receiver distinct media keys: FORBIDDEN in V1 without A4 REOPEN.
```

### Q8 — Envelope on fan-out legs (amendment)

```text
P02-A5:

SafeUdpPayloadBytes and all Q1/Q2/Q7 wire constraints apply independently to
each fan-out UDP datagram. Unicast legs do not introduce an alternate wire profile.
```

---

## Consequences

```text
M1′ (A2/A3) + K1 (A4) compose the V1 sender production chain:
  encode once → RTP once → SRTP once → replicate protected bytes

A6 may now define WirePpsCeiling vs aggregate datagram rate without reopening
SRTP placement.

A7 overlap rules must respect identical ciphertext when both legs active.

A10 corpus must include byte-identical fan-out vectors + cross-leg replay continuity.
```

---

## References

- `docs/analysis/0058-profile-02-a1-destination-ingress-adjudication.md`
- `docs/analysis/0058-profile-02-a2a3-encode-rtp-timeline-adjudication.md`
- `docs/analysis/0058-implementation-profile-02-rtp-wire.md` (Q5, Q6, Q7, Q8)
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
