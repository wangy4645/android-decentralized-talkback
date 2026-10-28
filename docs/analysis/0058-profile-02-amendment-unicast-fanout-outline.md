# ADR-0058 Profile 02 Amendment — Unicast Fan-Out (Outline)

## Status

```text
AMENDMENT ADJUDICATION: COMPLETE (P02-A1..A10 ACCEPTED)
CONTRACT:              FROZEN — pending C1–C8 hex corpus implementation PASS
Parent:                ADR-0060 ACCEPTED — FROZEN
Base:                  0058-implementation-profile-02-rtp-wire.md (pre-amendment CLOSED)
Closeout:              0058-profile-02-a10-acceptance-corpus-closeout.md
Media Engine:          NOT AUTHORIZED
Next main line:        Profile 03 resilience amendment
```

## Amendment boundary (frozen)

This amendment answers **only**:

```text
How does one legal media unit (one Source / one 20 ms slot)
traverse the wire when the sender must deliver via per-receiver unicast fan-out?
```

**IN SCOPE (Profile 02):**

```text
destination semantics (multicast vs per-receiver unicast)
encode-once / protect-send semantics
RTP identity under mixed delivery
SRTP context / keying / ROC / replay at fan-out
wire envelope inheritance (120 B, PT=111, HE, no RTCP, …)
fan-out pacing vs NominalPps / WirePpsCeiling
mixed-mode duplication rules at wire layer
per-receiver send failure isolation
fan-out execution envelope vs N_max
acceptance corpus extensions
```

**OUT OF SCOPE (deferred to Profile 03 / publication contract):**

```text
MulticastReceiveLiveness observation
ALIVE / SUSPECT / DEAD state machine
liveness publication / consumption protocol
sender PerReceiverTransportMode selection policy
when to downgrade / upgrade (ADR-0060 decisions — not wire semantics)
capability freshness / ordering identity scheme
```

Profile 02 MUST NOT absorb negotiation or fallback policy. It defines **what bytes
may legally leave the sender** once execution has already chosen a delivery mode.

---

## Amendment process

```text
Order:
  P02-A1  ACCEPTED — Option A
  P02-A2 + P02-A3  ACCEPTED — M1′
  P02-A4 + P02-A5  ACCEPTED — K1 + full A5 inheritance
  P02-A6  ACCEPTED
  P02-A7  ACCEPTED — O1
  P02-A8  ACCEPTED — F1
  P02-A9  ACCEPTED — C1
  P02-A10 (NEXT — final corpus plan)
  P02-A4 + P02-A5  (merged — SRTP context + wire envelope inheritance)
  P02-A6
  P02-A7
  P02-A8
  P02-A9
  P02-A10  (after A1–A9 frozen)

Rule:
  Each item ends in ACCEPT / REJECT / DEFER with frozen contract text.
  No implementation until A10 corpus plan is accepted.
```

---

## P02-A1 — Destination semantics

**Status: ACCEPTED — OPTION A** (see `0058-profile-02-a1-destination-ingress-adjudication.md`)

**Question:** What is the normative wire destination for correctness-path delivery,
and what is the ingress pre-auth lookup scope?

### Adjudication summary

```text
ACCEPTED — Option A
REJECTED — Option B: (destinationIp, keyContextHint64) mechanical extension
REJECTED — Option C: per-peer SSRC/wire identity at destination layer
```

### Frozen destination contract

```text
Multicast:
  MediaGroupEndpoint(multicastAddress, mediaPort, …)

Unicast:
  PerReceiverUnicastEndpoint(receiverModuleId, moduleFixedIp, mediaPort)
  receiverModuleId = binding identity
  moduleFixedIp      = L3 transport address only
```

### Frozen ingress lookup scope

```text
IngressTransportBinding
  MULTICAST_INGRESS → (committed MediaGroupEndpoint, keyContextHint64)
  UNICAST_INGRESS   → (AdmittedIngressTransportBinding, keyContextHint64)

AdmittedIngressTransportBinding {
  conferenceId, conferenceEpoch, mediaGroupEndpointId,
  remoteSenderModuleId, localReceiverModuleId
}

FORBIDDEN: (localDestinationIp, keyContextHint64) as unicast lookup scope
```

### Anti-example (why Option B rejected)

```text
M01 → M03 unicast and M02 → M03 unicast share destinationIp = M03.fixedIp
(destinationIp, hint) cannot scope which remote sender's key context applies
```

A4 keying / SRTP protect semantics: **NOT decided in A1.**

---

## P02-A2 + P02-A3 — Encode-once / protect-send + RTP identity

**Status: ACCEPTED — MODEL M1′** (see `0058-profile-02-a2a3-encode-rtp-timeline-adjudication.md`)

**Core question:** Same encoded media unit under mixed fan-out — single RTP timeline?
SRTP once vs per-destination? (**SRTP deferred to A4.**)

### Adjudication summary

```text
ACCEPTED — M1′
  encode once → one RTP identity per slot (SSRC/seq/ts/payload)
  fan-out = transport legs only; seq advances once per slot
  mode transition continues timeline (no seq reset)

REJECTED — M2: per-destination RTP seq/SSRC
REJECTED — M3: per-receiver encode/RTP stream
```

### Frozen principle

```text
one admitted media Source = one RTP timeline
transport destination change ≠ new Source identity
```

### Transition anti-example

```text
multicast seq 100..200 → unicast MUST continue 201.. (same SSRC)
FORBIDDEN: unicast restarts seq=0
```

### Explicit deferral to A4

```text
SRTP protect-once → identical ciphertext copies     NOT decided
SRTP protect per destination from same RTP artifact NOT decided
```

---

## P02-A4 + P02-A5 — SRTP context / keying + wire envelope inheritance

**Status: ACCEPTED — MODEL K1 + FULL A5 INHERITANCE**
(see `0058-profile-02-a4a5-srtp-wire-envelope-adjudication.md`)

### A4 adjudication

```text
ACCEPTED — K1: Source-level shared SRTP context; protect once; identical
              protected datagram on all multicast/unicast legs

REJECTED — K2: per-destination independent SRTP context (V1)
REJECTED — K3: separate RTP/SRTP streams per transport mode
```

```text
one Source → one RTP timeline (A2/A3) → one SRTP sender context → one ROC
→ one ProtectedDatagramArtifact per slot → replicate to legs
```

Receiver replay scope: `IngressTransportBinding + keyContextHint64` (A1).
Ciphertext equality MUST NOT merge distinct remote senders.

Per-receiver distinct keys: FORBIDDEN without explicit A4 REOPEN.

### A5 adjudication

```text
SafeUdpPayloadBytes=120, PT=111, HE, CSRC=0, padding OFF — UNCHANGED per datagram
RTCP/SRTCP, NACK/retransmit — UNCHANGED forbidden
120 bytes = EACH UDP datagram, not aggregate fan-out budget
```

---

## P02-A6 — Fan-out pacing and WirePpsCeiling semantics

**Status: ACCEPTED** (see `0058-profile-02-a6-pacing-wirepps-adjudication.md`)

### Frozen metrics (three-way distinction)

```text
NominalPps = 50
  → normal Source media-time cadence (unchanged)

WirePpsCeiling = 60
  → logical media emissions/s per Source timeline
  → NOT aggregate UDP sendto across fan-out legs

FanoutDatagramRate
  → logicalMediaPps × activeDeliveryLegs
  → capacity/transport load evidence only (A9 / G-RES-3)
  → NOT a wire cadence gate
```

### Worked example

```text
50 slots/s × 9 legs = 450 datagrams/s
  LogicalMediaEmissionPps = 50  (WirePpsCeiling OK)
  FanoutDatagramRate = 450      (capacity metric)
  REJECT: WirePps = 450 violates ceiling
```

### Fan-out scheduling

```text
FORBIDDEN: catch-up burst of missed slots × N legs
REQUIRED:  fan-out only multiplies destinations for current eligible artifact
```

---

## P02-A7 — Mixed-mode duplication at wire layer

**Status: ACCEPTED — MODEL O1**
(see `0058-profile-02-a7-mixed-mode-overlap-adjudication.md`)

```text
steady-state:  exactly one authoritative PerReceiverTransportMode per receiver
transition:    bounded finite overlap MAY exist (TransitionOverlapMaxMs → Profile 03)
duplicate:     same Source/seq/artifact on two legs → duplicate discard, decode ≤ once

ACCEPTED  O1 — single authoritative mode + bounded overlap + duplicate handling
REJECTED  O2 — zero overlap hard switch
REJECTED  O3 — long-term dual-path redundancy

upgrade end: explicit mode transition decision; packet arrival alone insufficient
```

---

## P02-A8 — Failure isolation

**Status: ACCEPTED — MODEL F1**
(see `0058-profile-02-a8-send-failure-isolation-adjudication.md`)

```text
Failure isolation = per delivery leg

LogicalMediaEmissionOutcome   EMITTED (per slot, independent of leg failures)
PerLegDeliveryOutcome         SENT | DROPPED_LOCAL | SEND_FAILED | SEND_NOT_ATTEMPTED
                              (amendment: 0058-profile-02-a8-send-not-attempted-amendment.md)

M03 sendto FAIL:
  → local to M03 leg only
  → no seq/ROC rollback, no re-protect, no block other legs, no catch-up

ACCEPTED  F1 — per-leg independent outcome
REJECTED  F2 — whole-slot fail/retry
REJECTED  F3 — per-receiver retransmit queue

Capability DEAD on sustained failure → Profile 03 (out of A8 scope)
```

---

## P02-A9 — Fan-out execution envelope vs `N_max`

**Status: ACCEPTED — MODEL C1**
(see `0058-profile-02-a9-fanout-envelope-adjudication.md`)

```text
MaxUnicastFanOutTargetsPerSource  → Profile 02 execution hard cap
N_max                             → ADR-0060 declared envelope requirement

MaxUnicastFanOutTargetsPerSource >= N_max >= (declaredConferenceMembers - 1)

ActiveUnicastFanOutTargets = distinct receivers with unicast leg this slot
  (NOT multicast leg, NOT double-count overlap)

requiredTargets > cap → CAPACITY_NOT_ADMISSIBLE
  no silent drop, no relay

Numeric values → capacity evidence (not frozen in A9)

ACCEPTED C1 | REJECTED C2 best-effort | REJECTED C3 relay overflow
```

---

```text
Order:
  P02-A1 .. P02-A10  ALL ACCEPTED
```

---

## P02-A10 — Acceptance corpus and amendment closeout

**Status: ACCEPTED — CORPUS MATRIX C1–C8 FROZEN**
(see `0058-profile-02-a10-acceptance-corpus-closeout.md`)

| Case | Must prove |
|------|------------|
| **C1** Multicast regression | Pre-amendment Q9 multicast golden bytes unchanged |
| **C2** Unicast-only | `PerReceiverUnicastEndpoint`; single Source RTP timeline |
| **C3** Mixed-mode | Same slot: multicast + N unicast; identical `ProtectedDatagramArtifact` |
| **C4** Seq/ROC continuity | Mode transition no SSRC/seq/ROC reset; one advance/slot |
| **C5** Failure isolation | `SEND_FAILED` on one leg; others unaffected; no catch-up |
| **C6** Envelope/capacity | ≤120B/datagram; over cap → `CAPACITY_NOT_ADMISSIBLE` |
| **C7** Pacing | 50×N datagrams ≠ WirePpsCeiling violation; 61 slots does; no catch-up |
| **C8** Transition duplicate | Overlap: duplicate identity → admit ≤ once |

```text
K1 proof: protectedBytes(multicast) == protectedBytes(unicast R*) per slot

Amendment PASS ≠ Media Engine AUTHORIZED
C1–C8 hex corpus implementation PASS → CONTRACT COMPLETE
```

---

## Risk register (amendment — CLOSED)

| ID | Risk | Mitigation |
|----|------|------------|
| R1 | Silent SRTP copy assumption | Decide A2–A4 before any code |
| R2 | WirePpsCeiling misread blocks fan-out | A6 explicit Q8 amendment text |
| R3 | Profile 02 absorbs fallback FSM | Boundary section — defer to Profile 03 |
| R4 | Per-receiver RTP identity drift | A3.1 default: shared SSRC/seq/ts |
| R5 | Q5 lookup scope break on unicast | A1 ACCEPTED |

All risks mitigated by adjudication chain or deferred to Profile 03.

---

## Adjudication documents

```text
A1   0058-profile-02-a1-destination-ingress-adjudication.md
A2/3 0058-profile-02-a2a3-encode-rtp-timeline-adjudication.md
A4/5 0058-profile-02-a4a5-srtp-wire-envelope-adjudication.md
A6   0058-profile-02-a6-pacing-wirepps-adjudication.md
A7   0058-profile-02-a7-mixed-mode-overlap-adjudication.md
A8   0058-profile-02-a8-send-failure-isolation-adjudication.md
A9   0058-profile-02-a9-fanout-envelope-adjudication.md
A10  0058-profile-02-a10-acceptance-corpus-closeout.md
```

## Next action

**Profile 02 amendment adjudication CLOSED.** Implement C1–C8 corpus; begin
**Profile 03 resilience amendment** (liveness → publication → sender mode).
E3a remains optimization evidence only.
