# ADR-0057 Track C — Post-IGIE Group Invite Delivery Residual Audit

## Status

**CONFIRMED — IMPLEMENTATION GAP AUDITED** (2026-08-31)

```text
F4 / Outbound GIE:        FIELD PASS / CLOSED FOR THIS DEFECT
F5 / IGIE:                FIELD PASS / CLOSED FOR THIS DEFECT (M02 answerer)
F6 / GIDR:                CONFIRMED IMPLEMENTATION GAP

Layer A — Datagram miss:  OBSERVED EVENT / trigger (not architecture defect)
Layer B — Obligation      CONFIRMED IMPLEMENTATION GAP / root residual
  prematurely quiesced:

M03 IGIE @ 165729:        NOT EXERCISED (GROUP_INVITE received = 0)

Case A evidence (IGIE run):
  talkback/logs/adr0057-track-c-case-a-20260831-165729/

Contrast (GIE run — M03 did receive):
  talkback/logs/adr0057-track-c-case-a-20260831-163552/

Implementation cut:       AUTHORIZED — IMPLEMENTATION CONFORMANCE PASS (unit)
Field:                   PENDING
```

**Parents:**

- [Case A field adjudication](./adr0057-track-c-case-a-field-adjudication.md)
- [Inbound IGIE authorization](./adr0057-inbound-group-invite-execution-narrow-authorization.md)
- [Outbound GIE authorization](./adr0057-group-invite-execution-narrow-authorization.md)

---

## Audit question

**Why do M01/M02 report `GROUP_INVITE` sent to M03 while M03 ingress is zero, and does any delivery obligation remain open after handoff success?**

M03 is **NOT EXERCISED** for IGIE adjudication this run (`GROUP_INVITE received = 0` → inbound accept never entered). This audit is strictly upstream of IGIE / ICE / `transmitReady`.

---

## Field summary (20260831-165729)

| Node | `GIE_GROUP_INVITE_WIRE_SENT` → M03 | `SIGNAL_DATAGRAM_SENT` GROUP_INVITE → `.24` | M03 ingress `GROUP_INVITE` |
|------|-------------------------------------|---------------------------------------------|----------------------------|
| M01  | 2 (log dup)                         | **1**                                       | 0                          |
| M02  | 2 (log dup)                         | **1**                                       | 0                          |
| M03  | —                                   | —                                           | **0** (entire window)      |

M03 **did** receive from both peers on the same socket epoch: `HELLO`, `HEARTBEAT`, `FACT_REQUEST`, `FACT_RESPONSE_CANDIDATE` on `socketId=4` / `rebindGeneration=3`.

Contrast: prior GIE run (`163552`) shows **16** `GROUP_INVITE` references on M03, including transport-level `SIGNAL_DATAGRAM_RECEIVED signalType=GROUP_INVITE bytes=2040` from M01. Delivery is **path-capable**; this run is a **reachability miss**, not a permanent addressing misconfiguration.

---

## Q1 — What does `GROUP_INVITE SENT` / `GIE_GROUP_INVITE_WIRE_SENT` mean?

**Answer: local UDP handoff success only — not peer delivery, not ACK, not ingress observed.**

### Code chain

```text
dispatchGroupInviteAfterEngineProvision
  → engine ready
  → sendSignalHandoff(peer, GROUP_INVITE envelope)
       → PeerControlSignalingAdmission gate (PEER_EDGE_READY)
       → signalingChannel.send()  // UdpSignalingChannel
            → DatagramSocket.send(packet) without exception
  → if handoff true:
       recordInvitingAfterSuccessfulGroupInviteHandoff  // INVITING + OutboundGroupInviteAttempt
       log GIE_GROUP_INVITE_WIRE_SENT
```

`sendSignalHandoff` is explicitly documented:

```18156:18160:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
    /**
     * Signaling handoff success = local send completed without exception.
     * Not peer ACK; not "sendSignal was invoked".
     * Q8 Hard gate: new peer-scoped control signaling requires PEER_EDGE_SIGNALING_READY.
     */
```

`UdpSignalingChannel.sendInternal` success = `DatagramSocket.send` returned; logs `SIGNAL_DATAGRAM_SENT`.

### Field correlation (M01 → M03)

```text
16:58:32.781  SIGNAL_DATAGRAM_SENT socketId=2 dstIp=192.168.10.24 signalType=GROUP_INVITE bytes=2040
16:58:32.784  Group invite sent -> M03-E03
16:58:32.784  GIE_GROUP_INVITE_WIRE_SENT ch=CH-01 peer=M03 session=grp:CH-01
```

**Conclusion Q1:** `SENT` = **local write to UDP socket succeeded**. It does **not** assert datagram arrival, decode, or handler entry on M03.

---

## Q2 — M03 cold-start socket rebind: old endpoint, ingress drop, or never arrived?

**Answer: not “sent to pre-rebind endpoint.” Invite was sent after M03 transport was bidirectional-ready. M03 ingress for that datagram is zero — most consistent with UDP non-arrival (loss) at transport layer, not post-decode drop.**

### M03 rebind timeline

```text
16:58:28.573  SIGNAL_SOCKET_BOUND socketId=1 rebindGeneration=1
16:58:28.621  SIGNAL_SOCKET_BOUND socketId=2 rebindGeneration=2
16:58:29.569  SIGNAL_SOCKET_BOUND socketId=4 rebindGeneration=3
16:58:30.319  SIGNAL_BIDIRECTIONAL_CONFIRMED rebindGeneration=3
16:58:31.844  PEER_EDGE_READY remote=M01 generation=3
```

### M01 → M03 invite (after rebind)

```text
16:58:32.604  PEER_EDGE_READY remote=M03 generation=2   (M01 view)
16:58:32.781  SIGNAL_DATAGRAM_SENT GROUP_INVITE → 192.168.10.24:50000 (2040B)
```

### M03 receive window (±200ms)

```text
16:58:32.672  DATAGRAM_RECEIVED src=M02 signalType=HELLO
16:58:32.881  DATAGRAM_RECEIVED src=M01 signalType=HELLO   ← same peer/path, 100ms after invite send
              (no GROUP_INVITE datagram at any timestamp)
```

Observations:

| Check | Result |
|-------|--------|
| Invite before M03 `BIDIRECTIONAL_CONFIRMED`? | **No** (~2.5s after) |
| M03 listening socket at invite time? | **Yes** (`socketId=4`, gen=3) |
| Same src/dst path works for other types? | **Yes** (HELLO/HEARTBEAT seconds later) |
| M03 `SIGNAL_DATAGRAM_DECODE_FAILED` / verify fail? | **0** in window |
| M03 transport received 2040B GROUP_INVITE? | **0** (entire run) |

**Conclusion Q2:** Evidence **does not** support “invite went to stale socket/endpoint because M03 was still rebinding.” It **does** support “sender handoff succeeded once; receiver never logged datagram arrival.” Whether that is pure UDP loss vs environment-specific drop cannot be distinguished from logs alone, but it is **not** explained by M03 application-layer ingress gate (no datagram logged).

---

## Q3 — Why do HELLO / HEARTBEAT / FACT_* reach M03 but GROUP_INVITE does not?

**Answer: same target resolution and UDP path; differences are (a) send-side hard gate history, (b) payload size, (c) post-handoff send suppression — not a separate socket queue or addressing scheme.**

### Same path

- Target: `resolvePeerForModule` → `discoveredByModule` / HELLO-learned `192.168.10.24:50000`
- Transport: `UdpSignalingChannel` port `50000`, same `socketId` on each side
- M03 receives M01/M02 HELLO and FACT_* on `socketId=4` after invite window

### Send-side asymmetry (hard gate)

`GROUP_INVITE` is `PeerControlSignalingAdmission` hard-gated; `HELLO` / `HEARTBEAT` / `FACT_*` are not.

Field (blocked attempts before edge ready):

```text
M01  16:58:29.525  PEER_EDGE_CONTROL_BLOCKED type=GROUP_INVITE peer=M03 reason=NEVER_OBSERVED
M02  16:58:31.136  PEER_EDGE_CONTROL_BLOCKED type=GROUP_INVITE peer=M03 reason=NEVER_OBSERVED
```

First successful M01 wire only after `PEER_EDGE_READY remote=M03` at `16:58:32.604`.

### Payload size

| Type | Typical size this run |
|------|------------------------|
| HELLO | ~525–592 B |
| HEARTBEAT | ~299 B |
| FACT_* | ~430–915 B |
| GROUP_INVITE | **~2040 B** (SDP embedded) |

Larger single-datagram payload increases exposure to loss/fragmentation; no send-side fragmentation or retry layer is present.

### Post-success suppression

After the one successful handoff, `OutboundGroupInviteAttempt` is active (`remoteSignalingInFlight = true`). Further `GROUP_LATE_PEER_DISCOVERED` events (**258×** on M01) do **not** produce additional `SIGNAL_DATAGRAM_SENT GROUP_INVITE` to M03 (count stays **1**).

**Conclusion Q3:** Not a different routing plane. INVITE is gated + larger + single-shot; control/discovery traffic uses the same UDP path and continued to flow after the miss.

---

## Q4 — After rebind / handoff, is there a recoverable delivery obligation?

**Answer: semantic obligation likely still open (no GROUP_ACCEPT, peer not admitted), but operational layer treats handoff as completion and does not offer a timely delivery retry.**

### What closes on handoff success

On `sendSignalHandoff` success for `GROUP_INVITE`:

```text
OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff  → handoffSucceeded=true, terminalReason=null
meshParticipant.invite = INVITING
dispatchBootstrapAdmissionAfterSuccessfulGroupInvite (if bootstrap semantic)
```

`remoteSignalingInFlight` returns **true** → GIE dedup gate `SkipWireInFlight` on subsequent `dispatchGroupInviteAfterEngineProvision`.

### What does **not** reopen delivery

| Mechanism | Late-peer M03 behavior |
|-----------|-------------------------|
| `GROUP_LATE_PEER_DISCOVERED` → `tryReinviteGroupPeerPairwise` | Fires hundreds of times; **no second datagram** after first handoff |
| `isBootstrapAdmissionInviteRetry` | Requires intent `PENDING` / `WAITING_EDGE_READY`; M03 late-peer path does not show `GROUP_BOOTSTRAP_INVITE_ISSUED` |
| `evaluateEdgeReadyRetry` | Not eligible once bootstrap intent is `INVITE_SENT` (N/A for M03 this run) |
| `OUTBOUND_GROUP_INVITE_ATTEMPT_TERMINAL` / 10s stale | **Never logged** for M03 over ~5 min idle — stale expiry only runs when `remoteSignalingInFlight()` is consulted; late-peer reconcile returns early without consulting it once M03 is already in `memberModules` / `pendingInviteeEndpoints` |
| Peer `GROUP_ACCEPT` / mesh link complete | Did not occur (M03 never received invite) |

### `dispatchGroupInviteAfterEngineProvision` return value

Returns `true` when engine provision is **queued**, before wire send. Field shows:

```text
16:58:29.444  Pairwise re-invited M03 into group mesh sent=1
16:58:29.525  PEER_EDGE_CONTROL_BLOCKED … NEVER_OBSERVED   ← wire did not go out
16:58:32.665  Pairwise re-invited M03 sent=1
16:58:32.781  actual SIGNAL_DATAGRAM_SENT GROUP_INVITE
```

`sent=1` is **not** a reliable wire-success indicator.

### Pattern match (user hypothesis)

```text
wire handoff success
  → InviteIntent / OutboundGroupInviteAttempt treated as satisfied locally
  → no peer-ingress confirmation
  → no execution opportunity to re-send while obligation still open downstream
```

This mirrors F4/GIE/IGIE **obligation vs operational attempt** separation, but on the **delivery** boundary:

```text
semantic: late peer still needs GROUP_INVITE delivery + accept
operational: one UDP handoff closed the outbound attempt surface
```

**Conclusion Q4:** Delivery obligation is **not provably closed** (M03 never admitted), but the runtime **acts as if send obligation is satisfied** and does not schedule a delivery retry on lifecycle events that fire continuously (`GROUP_LATE_PEER_DISCOVERED`). The 10s stale timeout is **ineffective** in this topology because nothing re-enters the attempt evaluator for that peer.

---

## Classification (frozen)

```text
F6 — Group Invite Delivery Recovery (GIDR)

Classification:
  CONFIRMED IMPLEMENTATION GAP
  NOT IGIE defect
  NOT outbound GIE execution defect (wire handoff occurred)

Layer A — Datagram miss (OBSERVED EVENT / trigger):
  UDP loss of one ~2040B datagram is not an architecture defect
  sender DATAGRAM_SENT ×1 per peer; receiver GROUP_INVITE ingress = 0
  same-path HELLO/FACT_* continue (path-capable; see run 163552)

Layer B — Delivery obligation prematurely quiesced (ROOT RESIDUAL):
  LOCAL_HANDOFF_SUCCESS != PEER_DELIVERY != PEER_ACCEPTANCE
  handoff → remoteSignalingInFlight / INVITING
  no GROUP_ACCEPT; late-peer reconcile continues
  no new wire opportunity; stale timeout path not exercised
  obligation effectively stranded

Diagnostic:
  Pairwise re-invited M03 sent=1 ≠ wire success ≠ delivery
```

### Frozen root chain

```text
GROUP_INVITE delivery obligation OPEN
→ valid invite constructed
→ DatagramSocket.send() succeeds locally
→ handoff success
→ remoteSignalingInFlight / INVITING
→ packet happens not to reach peer
→ no GROUP_ACCEPT
→ late-peer reconciliation continues
→ no new wire opportunity
→ stale timeout path not exercised
→ obligation effectively stranded
```

---

## Not in scope

- IGIE inbound accept lifecycle (closed on M02)
- ICE / `transmitReady`
- Membership / `waitingForPrimary` policy
- CSO / ACQ / generation
- Generic reliable UDP / transport ACK protocol
- Reopening F4/F5 implementation cuts

---

## Next step

[GIDR narrow authorization](./adr0057-group-invite-delivery-recovery-narrow-authorization.md) — GIDR-T1..T4, GIDR-EG1..EG6. Implementation **not yet authorized**.
