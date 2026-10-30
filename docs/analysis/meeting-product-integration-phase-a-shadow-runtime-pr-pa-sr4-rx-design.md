# Phase A Shadow Runtime — PR-PA-SR4-RX Design

**PR:** `PR-PA-SR4-RX` — Shadow Multicast Receive Executor (Peer Ingress)  
**RCA:** `meeting-product-integration-phase-a-sr4-rca-runtime-execution-20260909-161221.md` (GAP-C)  
**Prerequisite:** PR-PA-SR4-TX **CLOSED / UNIT+FIELD-PROVEN** (`20260909-171232`)  
**Parent:** `meeting-product-integration-phase-a-sr4-field-run-card.md` (PA-SR4 full — still open)  
**Date:** 2026-09-09  
**Status:** **CLOSED / UNIT-PROVEN**

```text
PR-PA-SR4-TX     CLOSED / UNIT+FIELD-PROVEN
PA-SR4           VALID FAIL / RX NEXT

PR-PA-SR4-RX     CLOSED / UNIT-PROVEN
RX field         AUTHORIZED FOR RUN
PR-PA-SR4-PLAY   HOLD
ADR-0056         KEEP
Meeting cutover  NOT AUTHORIZED
```

---

## Failure locus (frozen)

TX field (`20260909-171232`) proved:

```text
Host shadow session live
→ production PCM shadow tap
→ protect/send
→ SHADOW_TX_ACTIVITY packetsSent > 0
```

Remaining SR4 gap (GAP-C):

```text
Peer receive executor absent
→ no socket poll in product lifecycle
→ admitDatagram never exercised
→ 0 × INGRESS_ACTIVITY
```

This PR closes **datagram-plane receive integration only**. It does **not** claim PA-SR4 PASS or playout.

---

## Goal

Wire an existing multicast receive loop into **shadow session scope** on the **peer**, so host TX datagrams traverse:

```text
existing multicast socket (transport scope from WIRING_SESSION_STARTED)
  → receive poll (RX executor — new product seam)
  → datagram
  → ConferenceSessionMediaWiring.admitDatagram (existing)
  → SessionBindingIngressResolver / authority.admitWire (existing)
  → INGRESS_ACTIVITY OBS
```

**Without** changing socket ownership, transport contract, admission policy, ADR-0056, or Meeting lifecycle.

---

## Non-goals (frozen)

```text
- New standalone receiver service / permanent background poll
- Rewrite MulticastSocket / MulticastLock / transport scope contract
- Admission logic inside receive loop (SSRC bind, epoch, generation)
- PA-SR4 full PASS (requires PLAY — separate track)
- PR-PA-SR4-PLAY mix-slot driver (HOLD until RX ingress proven)
- TX-B / egress gate changes
- SOURCE / B1-R2 reopen
- Host MEMBER_MEDIA_READY(M02) closure
- ADR-0056 cutover or production AudioTrack changes
- Synthetic datagram inject in field gate
```

---

## Architecture

```text
┌─────────────────────────────────────────────────────────────────┐
│ Shadow session lifecycle (existing)                              │
│   WIRING_SESSION_STARTED / transportScopeActive=true             │
│   MEMBER_MEDIA_READY → catalog has SSRC binding                  │
└────────────┬────────────────────────────────────────────────────┘
             │ arm on session live (peer)
             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Profile01ShadowMulticastReceiveSeam (RX — illustrative name)     │
│   poll: transport.receiveOnce()                                  │
│   forward: wiring.admitDatagram(sessionId, payload)              │
│   NO admission decisions in loop                                 │
└────────────┬────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Existing admission pipeline (unchanged)                          │
│   SessionBindingIngressResolver.resolveBySsrc                    │
│   → authority.admitWire → pipeline.admitProtectedDatagram        │
│   → INGRESS_ACTIVITY (Profile01ShadowRuntimeObservability)       │
└─────────────────────────────────────────────────────────────────┘

Host TX (PR-PA-SR4-TX — unchanged):
  PTT → shadow encode/protect → sendFromSource → multicast
```

Reference pattern (not product-wired today): `RealMeshE2ERunner.soakReceive` — poll → `admitDatagram` → counters.

---

## RX-1 — Executor lifecycle (frozen)

### Contract

RX executor **must** bind to existing shadow **session scope**, not process-global lifetime.

| Event | RX behavior |
|-------|-------------|
| Peer `WIRING_SESSION_STARTED` + `transportScopeActive=true` | Start receive poll for that `sessionId` |
| Session active | Poll loop runs on session-scoped transport handle |
| `SESSION_STOPPED` / wiring teardown **before** transport scope ends | **Stop poll first**, then allow existing teardown |
| Session absent / `ingressBlocked` | No poll; executor idle |

**Must:**

- One executor instance per active shadow session (or equivalent session-keyed arm/disarm).
- Idempotent arm: second `WIRING_SESSION_STARTED` for same session → no duplicate poll threads.
- Disarm before `ConferenceSessionMediaWiring.stopSession` completes transport teardown.

**Must not:**

- Create independent `Service` / always-on receiver unrelated to Meeting session.
- Poll when `ConferenceSessionMediaBridge.hasSession(sessionId)` is false.
- Outlive session scope after `onConferenceSessionStopped`.

### Suggested hook points (implementation authorization refines)

```text
arm:   after peer SHADOW_SESSION_STARTED APPLIED (same class of hook as TX arm)
disarm: ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStopped
        OR wiring stopSession prelude (before ingressBlocked)
```

Peer-only arm is expected for PA-SR4-RX field (M02 ingress). Host may omit poll unless symmetric arm is trivial and behavior-neutral.

---

## RX-2 — Socket ownership (frozen)

```text
existing multicast transport scope / socket
  → receive loop
  → datagram
  → existing ingress/admission path
```

**Must reuse:**

| Component | Role |
|-----------|------|
| `ConferenceSessionMediaWiring.startSession` | Opens transport scope (`beginScope`) — unchanged |
| `ConferenceMulticastRtpSrtpTransport` | `receiveOnce()` on scope-bound socket — unchanged API |
| `ConferenceSessionMediaWiring.admitDatagram` | SSRC resolve + `admitWire` entry — unchanged |

**Must not (this PR):**

- New socket type, bind address, or MulticastLock acquisition path
- Duplicate transport scope for receive-only
- Change ADR-0060 transport resilience contract
- Move TX `sendFromSource` ownership

---

## RX-3 — Admission authority boundary (frozen)

Receive loop is **transport I/O + forward only**.

```text
RX executor:
  receiveOnce() → ByteArray payload
  → wiring.admitDatagram(sessionId, payload)   # existing API

Inside admitDatagram (unchanged):
  SessionBindingIngressResolver.resolveBySsrc(catalog)
  → authority.admitWire(sourceIdentity, datagram, roc)
  → pipeline → INGRESS_ACTIVITY
```

**Must not in RX loop:**

- Construct `MemberBindingFact` or admit sources without catalog
- Bypass `UNKNOWN_SSRC` / epoch / generation fences
- Treat “datagram received” as “source admitted”
- Call pipeline ingress directly skipping `admitDatagram`

Rejections (`UNKNOWN_SSRC`, `SESSION_NOT_ACTIVE`, wire reject reasons) are **expected** and become shadow OBS only (RX-4).

---

## RX-4 — Failure isolation (frozen)

```text
poll timeout          → no Meeting impact; optional RX_IDLE OBS
malformed datagram    → drop + RX_REJECT OBS
UNKNOWN_SSRC          → admitDatagram reject; RX_REJECT OBS
decrypt/admit reject  → existing wire reject path; RX_REJECT OBS
executor exception    → RX_FAILED OBS; disarm session poll; NO coordinator throw
```

**Must not:**

- Propagate RX failures to `TalkbackCoordinator` session state
- Block ADR-0056 WebRTC capture/playout
- Disable or alter TX seam on RX failure
- Fail Meeting CREATE/JOIN/leave on ingress reject

Mirror TX-4 pattern: try/catch at seam boundary; record-only observability.

---

## Observability (behavior-neutral)

Extend `Profile01ShadowRuntimeObservability` (names illustrative):

```text
PROFILE01_SHADOW_RUNTIME phase=SHADOW_RX_ARMED sessionId=… outcome=APPLIED|ALREADY_ARMED
PROFILE01_SHADOW_RUNTIME phase=SHADOW_RX_DATAGRAM sessionId=… datagramsPolled=N
PROFILE01_SHADOW_RUNTIME phase=SHADOW_RX_REJECT sessionId=… reason=UNKNOWN_SSRC|…
PROFILE01_SHADOW_RUNTIME phase=SHADOW_RX_FAILED sessionId=… reason=…
PROFILE01_SHADOW_RUNTIME phase=INGRESS_ACTIVITY … packetsReceived>0   # existing — PASS evidence
```

`PLAYOUT_ACTIVITY` — observe only; **not** RX PASS criterion.

---

## Unit test matrix (minimum — authorization exit)

| ID | Scenario |
|----|----------|
| RX-U1 | Session live → arm → synthetic datagram via harness → `admitDatagram` accepted → ingress OBS |
| RX-U2 | No session / stopped → poll not armed → 0 ingress |
| RX-U3 | Unknown SSRC datagram → reject; no throw; session poll continues |
| RX-U4 | Session stop → poll disarmed before teardown; no leak |
| RX-U5 | RX exception → `SHADOW_RX_FAILED`; coordinator hooks unaffected |
| RX-U6 | TX-U1…U8 regression PASS (no TX behavior change) |

---

## Field gate pointer

Independent RX field gate: `meeting-product-integration-phase-a-sr4-rx-field-run-card.md`

**PASS requires on peer (M02):** `INGRESS_ACTIVITY packetsReceived > 0` after host PTT stimulus, with TX prerequisite green in same run.

---

## Status board

```text
PR-PA-SR4-TX     CLOSED / UNIT+FIELD-PROVEN
PA-SR4           VALID FAIL / RX NEXT
PR-PA-SR4-RX     DESIGN FROZEN
PR-PA-SR4-PLAY   HOLD
ADR-0056         KEEP
```

**Next:** Implementation authorization → Kotlin → RX field harness.
