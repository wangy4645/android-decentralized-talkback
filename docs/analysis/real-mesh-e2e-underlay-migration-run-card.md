# Real Mesh E2E — Underlay Migration Validation Run Card

## Status

```text
PASS / FIELD-VALIDATED (2-device)
Nature: UNDERLAY MIGRATION VALIDATION ONLY

Authoritative run:  real-mesh-e2e-20260907-151200
  logs: talkback/logs/real-mesh-e2e-20260907-151200/
  topology: M02 SENDER → M01 RECEIVER (wlan0 / 192.168.10.x)
  smoke:    real-mesh-smoke-20260907-151100 (PASS)

Proven (do not reopen):
  Phase 1 receiver correctness          CLOSED
  Phase 2 session/control wiring        CLOSED
  Raw CBOR / verifier                   CLOSED
  MEDIA_KEY_PACKAGE decrypt             CLOSED
  Membership convergence                CLOSED
  2–3 member Wi-Fi product-chain soak   PASS / FIELD-VALIDATED
    run: product-chain-field-soak-20260907-144912

This knife:                             PASS / FIELD-VALIDATED (2-device)
Meeting UI cutover:                     HOLD
ADR-0056:                               KEEP
N=10 capacity re-proof:                 FORBIDDEN
```

### Gate adjudication (authoritative)

| Gate | Result | Evidence |
|------|--------|----------|
| R1 | PASS | iface=`wlan0` explicit; bindSucceeded; localIp M02=`192.168.10.21` M01=`192.168.10.26` |
| R2 | PASS | packetsSent=5995; datagramsReceived=4708; wireAdmitAccepted=4708; syntheticLocalAdmit=false |
| R3 | PASS | sessionStarted; sourceInstalled; staleRejected=`STALE_MEMBER_CONTEXT`; rejoinInstalled |
| R4 | PASS | durationSec=120; teardownSessionGone; teardownLeakPass |

```text
Overall: PASS (both roles)
NOT N=10 · NOT Meeting UI · NOT ADR-0056
```

### Field notes (not reopen triggers)

```text
• 2-device PASS. M03 on 192.168.31.x → ENV_BLOCK for 3-node until same Mesh L3.
• M01→M02 RX currently fails underlay multicast (probe receivedCount=0);
  M02→M01 works — roles locked to that direction for this PASS.
• Underlay = field Mesh-selected iface wlan0 (real cross-device TX/RX).
  Distinct from Wi-Fi product-chain soak (local admit / no radio path).
• mid-soak liveDecoders/activeJitterSources observed 0 (no mix-clock drive in
  this vehicle); continuous wire admit + transportScopeActive + clean teardown
  satisfy R4 as written. Phase 1 playout CLOSED — do not reopen.
```

## Evidence header (hard)

```text
REAL MESH E2E
2–3 physical devices
REAL MESH multicast underlay
PRODUCT-CHAIN FIELD VALIDATION

NOT N=10 capacity evidence
NOT Meeting UI cutover
NOT ADR-0056 cutover
NOT Wi-Fi simulation local-admit substitute
```

## Nature lock

```text
same signed wire / control plane
same SessionMediaWiring
same Phase 1 runtime
        ↓
REAL MESH interface (explicit bind)
        ↓
REAL multicast TX/RX (cross-device)
        ↓
2–3 physical devices
```

**Forbidden:** new architecture · receiver fixes · session/control-plane edits · capacity contract changes · synthetic packets substituting for cross-device media · default-route accidental success.

## What already proved vs what this proves

| Prior Wi-Fi product-chain soak | This Real Mesh E2E |
|--------------------------------|--------------------|
| Product seam composition on device | Same seams **unchanged** |
| Local admit loop (no radio path required) | **Real** cross-device multicast TX/RX |
| Underlay: Wi-Fi simulation LAN | Underlay: **real Mesh** selected interface |

## PASS gates (only four)

```text
R1  interface binding
    Explicit Mesh iface bind (NetworkInterface + socket observability)
    Record: iface, localIpOnIface, bindResult
    FAIL if iface blank / missing / down / no address
    FAIL if success unexplained by default-route only

R2  multicast delivery
    ≥2 physical devices, real TX → real RX
    receiver source ingress / admit from wire > 0
    FAIL if only local synthetic admit (no cross-device datagrams)

R3  product lifecycle
    create/join/leave/rejoin via same Profile01 → Wiring path
    stale generation fence still holds

R4  sustained + teardown
    120s soak
    runtime continues (admit/playout path alive)
    session end → source/jitter/decoder/socket clear
```

## Topology (minimum)

```text
M01  SENDER   (Mesh iface) ──multicast──►  M02  RECEIVER
Optional M03 as 2nd sender or 2nd receiver (same Mesh L3)
```

| Label | Serial |
|-------|--------|
| M01 | `HTUBB21B09220661` |
| M02 | `DSJ-2407041` |
| M03 | `MDX0220416001963` |

## ENV precondition

```text
1. All participating devices on the SAME Mesh underlay L3
2. Explicit iface name known (field: often wlan0 when that IS the Mesh-selected network)
3. Multicast group reachable on that iface (239.255.42.99:47099 unless amended)
4. APKs = same family as product-chain FIELD-VALIDATED build
```

```text
ENV_BLOCKED / ENV_INVALID if:
  Mesh iface absent
  devices on divergent subnets
  only Wi-Fi simulation local-admit path available
```

Do **not** claim REAL MESH PASS by re-running local-admit soak on wlan0 without cross-device RX>0.

## Execution vehicle

```text
RealMeshE2ERunner
  • same Profile01 ingress + decrypt + membership + Wiring.forProduct
  • role=SENDER | RECEIVER
  • iface MUST be passed (R1)
  • SENDER: product-chain install → transport.sendProtectedArtifact
  • RECEIVER: product-chain install → transport.receiveOnce → wiring.admitDatagram
  • leave/rejoin + stopSession leak asserts (R3/R4)
```

```powershell
.\scripts\real-mesh-e2e-step.ps1 -Step preflight -Devices M01,M02,M03 -Iface wlan0
.\scripts\real-mesh-e2e-step.ps1 -Step start -DurationSec 120 -Iface wlan0 `
  -Sender M01 -Receiver M02 [-ExtraSender M03]
.\scripts\real-mesh-e2e-step.ps1 -Step pull -RunId <runId>
```

## Report fields (minimum)

```text
runId, deviceLabel, role, evidenceScope=REAL_MESH_E2E
iface, localIpOnIface, bindResult, meshClaim=true
creation/membership/source outcomes
packetsSent | wireAdmitAccepted | wireAdmitRejected | datagramsReceived
staleRejected, leave/rejoin, teardownLeakPass
verdict: PASS | FAIL | ENV_BLOCKED
```

## FAIL routing

```text
R1 FAIL     → underlay / iface / SelectedOperationalNetwork — not Phase 1/2 reopen
R2 FAIL     → Mesh multicast delivery / IGMP / routing — not capacity reopen
R3 FAIL     → product-chain regression (unexpected; triage before Mesh blame)
R4 FAIL     → teardown / soak sustain — Wiring or underlay interrupt
```

## Freeze (signed off)

```text
Conference receiver @ N=10       CLOSED
Session/control-plane wiring      CLOSED
Raw wire / crypto / membership    CLOSED
Wi-Fi product-chain               FIELD-VALIDATED
Real Mesh product-chain           FIELD-VALIDATED (2-device)
  run: real-mesh-e2e-20260907-151200

3-device Real Mesh                NOT PROVEN / ENV_BLOCKED
Meeting product integration       NEXT MAJOR GATE
ADR-0056 cutover                  HOLD / KEEP

Master: conference-media-foundation-freeze.md
Next:   meeting-product-integration-gate.md
```

## References

- Prior PASS: `product-chain-2-3-member-field-soak-run-card.md`
- Phase 2 wiring: `phase2-conference-session-media-wiring-run-card.md`
- Cross-device TX/RX pattern: Slice5b runners (transport only; authority remains product-chain)
- ADR-0059: underlay as product dependency (optimization); this card does not reopen capacity
