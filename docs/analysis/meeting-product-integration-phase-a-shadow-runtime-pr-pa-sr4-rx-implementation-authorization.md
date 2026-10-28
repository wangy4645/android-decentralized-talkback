# Phase A Shadow Runtime — PR-PA-SR4-RX Implementation Authorization

**PR:** `PR-PA-SR4-RX` — Shadow Multicast Receive Executor (Peer Ingress)  
**Design:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-rx-design.md`  
**RCA:** `meeting-product-integration-phase-a-sr4-rca-runtime-execution-20260909-161221.md` (GAP-C)  
**Prerequisite:** PR-PA-SR4-TX **CLOSED / UNIT+FIELD-PROVEN** (`talkback/logs/phase-a-sr4-tx-field-20260909-171232/`)  
**Signed:** 2026-09-09  
**Status:** **CLOSED / UNIT-PROVEN** (RX Kotlin delivered)

```text
PR-PA-SR4-TX     CLOSED / UNIT+FIELD-PROVEN
PA-SR4           VALID FAIL / RX NEXT

PR-PA-SR4-RX     CLOSED / UNIT-PROVEN
RX field         AUTHORIZED FOR RUN
PR-PA-SR4-PLAY   HOLD — NOT AUTHORIZED
ADR-0056         KEEP
Meeting cutover  NOT AUTHORIZED
```

**Authorizes:** product shadow receive executor — session-scoped poll → `admitDatagram` → existing admission pipeline → `INGRESS_ACTIVITY`.

**Does NOT authorize:** PLAY driver · PA-SR4 full PASS · socket/transport rewrite · admission policy change · TX changes · cutover.

---

## Goal (authorized)

Close GAP-C:

```text
Peer WIRING_SESSION_STARTED (transportScopeActive=true)
  → RX executor alive (session-scoped poll)
  → datagram received > 0
  → existing admitDatagram path exercised
  → INGRESS_ACTIVITY packetsReceived > 0
```

Host TX (PR-PA-SR4-TX) remains the stimulus source in field; RX does not reopen TX.

---

## Authorized implementation scope

### AUTHORIZED

```text
RX-A  Profile01ShadowMulticastReceiveSeam (name illustrative)
      - session-scoped arm/disarm tied to shadow session lifecycle
      - poll: ConferenceMulticastRtpSrtpTransport.receiveOnce()
      - forward: ConferenceSessionMediaWiring.admitDatagram(sessionId, payload)
      - NO admission logic in poll loop

RX-B  Lifecycle wiring
      - arm: peer WIRING_SESSION_STARTED / equivalent shadow hook
      - disarm: session stop BEFORE transport teardown completes
      - TalkbackRuntimeFactory + thin coordinator/delegate hooks

RX-C  Failure isolation (RX-4)
      - try/catch at seam; RX_FAILED / RX_REJECT OBS only

RX-D  Observability (behavior-neutral)
      - SHADOW_RX_ARMED / SHADOW_RX_DATAGRAM / SHADOW_RX_REJECT / SHADOW_RX_FAILED
      - reuse existing INGRESS_ACTIVITY emission on successful admitWire

Unit  RX-U1 … RX-U6 (design matrix)
      TX-U1 … TX-U8 regression

Field PA-SR4-RX field harness + adjudicator (separate script authorization in run card)
```

### NOT AUTHORIZED

```text
- PR-PA-SR4-PLAY (mix slot / playout driver)
- PA-SR4 full gate PASS claims from RX field alone
- New MulticastSocket / MulticastLock / transport scope implementation
- SessionBindingIngressResolver / admitWire policy changes
- Direct pipeline.admitProtectedDatagram bypass from receive loop
- Standalone background receiver service
- TX-B / six-gate egress / HLSP / TX-A1 changes
- SOURCE / B1-R2 changes
- Host MEMBER_MEDIA_READY(M02) binding closure
- ADR-0056 cutover
- Synthetic datagram inject in field gate
```

---

## Hard constraints (non-negotiable)

### RX-HC1 — Session-scoped lifecycle

```text
RX poll starts only after peer shadow session wiring is live
  (WIRING_SESSION_STARTED / transportScopeActive=true).

RX poll stops before session transport teardown completes.

No process-global immortal receive thread.
```

### RX-HC2 — Socket ownership unchanged

```text
Receive uses the transport instance already opened by ConferenceSessionMediaWiring.startSession.

Do NOT create parallel socket, scope, or bind for RX-only.
```

### RX-HC3 — Admission authority not in receive loop

```text
Receive loop may only call wiring.admitDatagram(sessionId, payload).

SSRC→source binding, generation, epoch, key/context fences remain in
SessionBindingIngressResolver + authority.admitWire — unchanged.

"Datagram received" != "source admitted".
```

### RX-HC4 — Failure isolation

```text
RX failures (timeout, bad packet, UNKNOWN_SSRC, admit reject, executor throw)
  → shadow OBS only
  → MUST NOT propagate to TalkbackCoordinator / Meeting state / ADR-0056 / TX seam
```

### RX-HC5 — TX prerequisite frozen

```text
Field RX gate assumes PR-PA-SR4-TX UNIT+FIELD-PROVEN baseline.
RX field run does not re-litigate TX PASS; uses host PTT as stimulus only.
```

### RX-HC6 — PLAY not in RX PASS

```text
RX field PASS = INGRESS_ACTIVITY packetsReceived > 0 on peer.
PLAYOUT_ACTIVITY observe-only; PLAY authorization separate if still zero after RX.
```

---

## Implementation order (recommended)

```text
1. RX-A receive seam (harness unit tests RX-U1…U3)
2. RX-B lifecycle arm/disarm + RX-U4
3. RX-C/D isolation + OBS + RX-U5
4. Factory wiring (peer session arm)
5. TX regression RX-U6
6. Unit closure doc
7. PA-SR4-RX field harness (scripts — run card §4)
8. Field run → closure doc
```

---

## Existing code (reuse)

| Component | Role |
|-----------|------|
| `ConferenceMulticastRtpSrtpTransport.receiveOnce` | Non-blocking / timeout poll |
| `ConferenceSessionMediaWiring.admitDatagram` | SSRC resolve + admitWire entry |
| `SessionBindingIngressResolver` | Catalog SSRC → source identity |
| `ConferenceMulticastMediaPipeline` | Post-admit decode path → INGRESS_ACTIVITY |
| `Profile01ShadowRuntimeObservability.maybeLogIngressActivity` | PASS OBS |
| `RealMeshE2ERunner.soakReceive` | Reference loop pattern (not product path) |
| `Profile01ShadowMulticastTransmitSeam` | Lifecycle mirror for arm/disarm style |

---

## Field gate (authorized — harness not yet implemented)

**Run card:** `meeting-product-integration-phase-a-sr4-rx-field-run-card.md`

| Verdict | Meaning |
|---------|---------|
| `PASS_PA_SR4_RX` | Peer INGRESS_ACTIVITY `packetsReceived > 0` + prerequisites |
| `FAIL_PA_SR4_RX` | Prerequisites green; no ingress despite host TX stimulus |
| `INVALID_*` | PRE / TX prerequisite / harness |

### On PASS

```text
PR-PA-SR4-RX    FIELD-PROVEN
PA-SR4          VALID FAIL / PLAY or full SR4 re-field NEXT
```

**Explicit:** `PASS_PA_SR4_RX` does **not** close PA-SR4 full gate (no PLAYOUT requirement met).

---

## RX / PLAY posture (frozen)

```text
PR-PA-SR4-PLAY
  Status: HOLD
  Entry:  only after RX produces real INGRESS_ACTIVITY
  Note:   if PLAYOUT_ACTIVITY rises naturally after RX, PLAY may be OBS-only closure;
          if still zero, authorize PLAY driver separately
```

---

## Status board

```text
PR-PA-SR4-TX     CLOSED / UNIT+FIELD-PROVEN
PA-SR4           VALID FAIL / RX NEXT
PR-PA-SR4-RX     IMPLEMENTATION AUTHORIZED
PR-PA-SR4-PLAY   HOLD
ADR-0056         KEEP
Meeting cutover  NOT AUTHORIZED
```

**Next:** Implement RX-A…D + unit closure; then RX field harness + field run.
