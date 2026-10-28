# Phase A Shadow Runtime — PR-PA-SR4-TX Implementation Authorization

**PR:** `PR-PA-SR4-TX` — Shadow Multicast Transmit (Host Session + Capture Tap)  
**Design:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-tx-design.md`  
**RCA:** `meeting-product-integration-phase-a-sr4-rca-runtime-execution-20260909-161221.md`  
**Prerequisite:** PA-SR3 **CLOSED** · PA-SR4 RCA **CLOSED** · B1-R2 **CLOSED**  
**Signed:** 2026-09-09  
**Status:** **IMPLEMENTATION AUTHORIZED** (TX scope only)

```text
Prerequisite
  PA-SR3            CLOSED / FIELD-PROVEN
  PA-SR4 RCA        CLOSED
  B1-R2             CLOSED
  PR-PA-SR4-TX design   DESIGN FROZEN

Authorization status
  IMPLEMENTATION AUTHORIZED — PR-PA-SR4-TX only
  PR-PA-SR4-RX      DESIGN NEXT — NOT AUTHORIZED
  PR-PA-SR4-PLAY    HOLD — NOT AUTHORIZED

PA-SR4 full gate    VALID FAIL (unchanged until RX)
ADR-0056            KEEP
cutover             NOT AUTHORIZED
```

---

## Goal (authorized)

Close SR4 RCA **GAP-A** (host shadow session) and **GAP-B** (capture → shadow multicast egress) in one PR, proving:

```text
Host authoritative CREATION + supplement READY
  → shadow SESSION_STARTED APPLIED (WIRING_SESSION_STARTED)
Production capture start (existing PTT path)
  → parallel PCM tap → encode/protect → multicast send
  → SHADOW_TX_ACTIVITY packetsSent > 0
```

**Without** changing audible production path (ADR-0056 WebRTC remains owner).

---

## Authorized implementation scope

### AUTHORIZED

```text
PR-PA-SR4-TX

TX-A  Host Local Session Fact Projection (HLSP)
      - maybeProjectHostShadowSession(sessionId) idempotent
      - ingestCreationSignedFact from creationStateBySession cached bytes
      - replaySessionStarted → ConferenceSessionMediaCoordinatorDelegate
      - hooks: onHostCreationAuthoritativeCommit + onMediaSupplementReady

TX-B  Profile01ShadowMulticastTransmitSeam
      - ProcessLocalMicFrameSource tap (PCM pre-encode)
      - arm/disarm from startSessionCapture/stopSessionCapture (CONFERENCE only)
      - Opus encode + SourceScopedSrtpEgress + sendFromSource

TX-3  Egress gate (design § TX-3) — all six gates frozen

TX-4  Failure isolation — shadow errors contained; no coordinator propagation

OBS   HOST_SESSION_PROJECTED, SHADOW_TX_ACTIVITY (behavior-neutral)
      Profile01ShadowRuntimeObservability

Unit  TX-U1 … TX-U9 (design matrix)

Field PA-SR4-TX gate harness + adjudicator (transmit-only)
```

### NOT AUTHORIZED

```text
- PR-PA-SR4-RX (receive executor, admitDatagram poll loop)
- PR-PA-SR4-PLAY (mix slot driver — HOLD until ingress proven)
- PA-SR4 full gate PASS claims from TX field alone
- New PTT / floor / Meeting state semantics
- Shadow microphone ownership or independent AudioRecord
- WebRTC RTP reverse-tap
- ADR-0056 cutover or production AudioTrack changes
- SOURCE / B1-R2 changes
- Host MEMBER_MEDIA_READY(M02) binding closure
- RX/PLAY bundled into this PR
```

---

## Hard constraints (non-negotiable)

### TX-HC1 — No new PTT semantics

```text
Shadow TX arms only from existing startSessionCapture/stopSessionCapture
for SessionType.CONFERENCE. No new user-facing transmit API.
```

### TX-HC2 — Audible owner unchanged

```text
dispatchConferenceCaptureControl / WebRtcAudioEngine path MUST remain
bit-for-bit authoritative for user-hearable audio. Shadow is parallel fan-out only.
```

### TX-HC3 — No wire self-ingress

```text
Host session fact projection uses origin cached CREATION bytes →
Profile01ConferenceMediaFactIngress.ingestCreationSignedFact.
MUST NOT loop CREATION through signaling receive path.
```

### TX-HC4 — Byte-identical registry semantics

```text
HLSP uses the same validate/decode/publish path as peer WIRE_INGRESS factType=1.
No alternate host registry writer or fact adapter.
```

### TX-HC5 — Egress fence on drift

```text
session stop · epoch mismatch · generation mismatch · missing local member binding
→ immediate disarm + drop; no cross-generation send queue.
```

### TX-HC6 — Failure isolation

```text
Any shadow encode/protect/send exception or rejection MUST NOT:
  - fail startSessionCapture / stopSessionCapture
  - alter floor / mute / Meeting lifecycle
  - surface as user-visible audio failure
```

### TX-HC7 — PCM tap only

```text
Tap ProcessLocalMicFrameSource / LocalOutboundPcmSink only.
FORBIDDEN: WebRTC encoded packet extraction.
```

---

## TX field gate (authorized — independent)

**Run card:** `meeting-product-integration-phase-a-sr4-tx-field-run-card.md`  
**Adjudicator:** `phase-a-sr4-tx-field-adjudicate.ps1` (to implement with PR)

### PASS chain

```text
PRE-G / PRE-I / PRE-J + J0          PASS (reuse SR4 harness prereqs)
Same session hostSessionId=H
Host SHADOW_SESSION_STARTED           APPLIED
Host WIRING_SESSION_STARTED           transportScopeActive=true
M01 PTT stimulus (3×)                 captureON on session=H
SHADOW_TX_ACTIVITY                    packetsSent > 0
ADR-0056 fence                        no shadow AudioTrack ownership violation
```

### Verdicts

| Verdict | Meaning |
|---------|---------|
| `PASS_PA_SR4_TX` | TX field gate proven |
| `FAIL_PA_SR4_TX` | Valid product fail — TX scope |
| `INVALID_*` | Harness / env (same classes as SR4) |

### On PASS

```text
PR-PA-SR4-TX    FIELD-PROVEN
PA-SR4          VALID FAIL / RX NEXT
Next            PR-PA-SR4-RX design + authorization (separate)
```

**Explicit:** `PASS_PA_SR4_TX` does **not** close PA-SR4, authorize RX implementation by default, or authorize PLAY.

---

## RX / PLAY posture (frozen)

```text
PR-PA-SR4-RX
  Status: DESIGN NEXT
  Entry:  only after PR-PA-SR4-TX FIELD-PROVEN
  Scope:  socket poll → SSRC resolve → admitDatagram → INGRESS_ACTIVITY

PR-PA-SR4-PLAY
  Status: HOLD
  Entry:  only after RX produces real INGRESS_ACTIVITY and playout still absent
  Note:    do not preset PLAY driver — ingress may naturally drive playout metrics
```

---

## Implementation order (recommended)

```text
1. TX-A HLSP + unit tests TX-U1…U3
2. OBS HOST_SESSION_PROJECTED + host field smoke (no TX yet)
3. TX-B seam + egress gate + TX-U4…U8
4. Coordinator hooks (thin) + TX-U9
5. SHADOW_TX_ACTIVITY OBS + unit closure
6. PA-SR4-TX field harness + adjudicator
7. Field run → closure doc
```

---

## Status board

```text
PA-SR1–SR3      CLOSED / FIELD-PROVEN
PA-SR4          VALID FAIL / RCA CLOSED
PR-PA-SR4-TX    IMPLEMENTATION AUTHORIZED
PR-PA-SR4-RX    DESIGN NEXT — NOT AUTHORIZED
PR-PA-SR4-PLAY  HOLD
B1-R2           UNCHANGED / CLOSED
ADR-0056        KEEP
cutover         NOT AUTHORIZED
```

---

## Evidence pointers (baseline)

- SR4 RCA: `meeting-product-integration-phase-a-sr4-rca-runtime-execution-20260909-161221.md`
- Host gap field: `talkback/logs/phase-a-sr4-field-20260909-161221/`
- Design: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-tx-design.md`
