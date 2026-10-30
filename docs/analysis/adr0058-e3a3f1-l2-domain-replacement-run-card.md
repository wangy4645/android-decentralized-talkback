# ADR-0058 E3a.3f-1 — L2 Domain Replacement (Capability Granularity)

## Status

```text
AUTHORIZED — FIELD
Prerequisite: ADR-0060 architecture decision frozen (NOT gated by this run)
Purpose: capability ownership / cache granularity classification ONLY
Does NOT decide: multicast-only correctness (REJECTED by ADR-0060)
Media engine: NOT AUTHORIZED
```

## Question

Is M02 multicast ingress failure **device-intrinsic**, or **(device × network environment)**?

## Single variable

```text
L2 domain / AP identity only

CHANGE:
  M03 opens WiFi hotspot (new SSID / BSSID)
  M02 associates to M03 hotspot
  M01 remains on original network OR isolated per safety checklist below

UNCHANGED:
  probe group 239.255.42.99:46999 @ 50pps
  probe scripts / adjudicator
  M02 app / no force-stop unless standard pre-run hygiene
  no vendor filter interventions
  no RTP / pacing / media engine changes
```

## Execution

```text
1. Record NETWORK_FACTS (both domains)
2. M03 → M02 TEST  (receiver M02, sender M03)
3. M02 → M01 CONTROL if M01 reachable on control network; else M02 → M03 loopback control
4. Capture: firstSeq, lastSeq, terminalTailMissingPackets,
            timeToFirstReceive, timeToDropout,
            /proc/net/igmp, ip -s link wlan0
```

## Adjudication (frozen)

| TEST result | Classification |
|-------------|----------------|
| Sustained receive on new L2 (≥90% or E3a.2 GREEN gates) | `(device × network environment)` — capability MUST re-evaluate on network change; device-only cache **insufficient** |
| Still 0% join OK, CTRL VALID | **device affinity stronger** — optimization path likely unavailable regardless of AP |
| CTRL INVALID | Run INVALID — no classification |
| M03 sender instrument hang | Recover M03 force-stop; rerun TEST — do not treat as ingress proof |

## Explicit non-goals

```text
NOT proving AP bug on original SSID
NOT reopening multicast-only architecture
NOT MTK vendor filter A/B
NOT AP reboot on original mesh
```

## Post-run routing

```text
Either outcome → proceed under ADR-0060 correctness path
Update capability cache policy draft with granularity class
Stop E3a vendor deep-dive unless OEM shippable control emerges (see 3f-0)
```
