# ADR-0058 E3a.3c — M02 Ingress State Ownership Classification

## Status

```text
Prerequisite: E3a.3b CLOSED — M02 Multicast Ingress State-Loss
Media engine: NOT AUTHORIZED
Probe changes: NONE (observability note only in adjudicator)
```

## Frozen classification (E3a.3b)

> **E3a.3b — M02 Multicast Ingress State-Loss**
>
> CONFIRMED: M02 multicast ingress can establish but is non-persistent; once lost,
> WiFi toggle / process reset do not recover; reboot once triggered brief recovery.
>
> ROOT CAUSE: NOT YET PROVEN device-side vs AP/per-client forwarding-side.
>
> Step 3 fingerprint (authoritative — not "19.3% random loss"):
>   seq 0 receive start → ~12s window → terminal tail loss 2407 pkts (~48.1s @ 50pps)
>   interior maxConsecutiveLoss=1 (does NOT include terminal tail)

## Observability note (NOT a frozen gate change)

```text
interiorConsecutiveLoss  = maxConsecutiveLoss (holes between firstSeq..lastSeq)
terminalTailMissingPackets = lastSeqSent - lastSeq (after last received seq)
```

Adjudicator emits both as OBS lines. Terminal dropout / zero-delivery = reachability/liveness,
not PLC burst gate.

## Question (single)

**Does lost multicast ingress state follow M02 device state or AP per-client forwarding state?**

## Preconditions

- Keep current fault scene: **M03→M02 = 0%** after Step 3R
- **Do NOT reboot M02**
- Do NOT toggle M02 WiFi / process reset
- Do NOT change M01/M03 beyond routine probe force-stop

## Execution order

| Step | Action | Scope |
|------|--------|-------|
| **0** | Capture AP/client observability | AP + M02 MAC `d2:63:e0:dd:b4:54` |
| **1** | Rebuild AP/mesh multicast / per-client forwarding only | AP only |
| **2** | M03→M02 test (no M02 intervention) | probe |
| **3** | M02→M01 control | probe |

### Step 0 — Observability (no intervention)

Record if available:

```text
M02: MAC, IP, BSSID, ifindex, /proc/net/igmp (wlan0 groups)
AP:  client table entry for d2:63:e0:dd:b4:54
     IGMP snooping / multicast forwarding / mesh mode
     per-client multicast membership (vendor-specific)
```

Store: `logs/adr0058-e3a3c-ap-state-ownership-<stamp>/AP_STATE_PRE.txt`

### Step 1 — AP-side reset (one variable)

Preferred (granular, vendor-dependent):

- Clear / refresh IGMP snooping table for group `239.255.42.99`
- Remove + re-add client multicast forwarding for M02 MAC
- Mesh node multicast state refresh (not full power cycle if avoidable)

Last resort: **AP/mesh reboot** (still AP-only; M02 stays associated if possible)

**Do NOT** reboot M02.

### Steps 2–3 — Probe pair

```powershell
cd talkback
.\scripts\adr0058-underlay-mcast-probe-start-run.ps1 -Direction M03toM02 -SkipBuild -SkipInstall -Iface wlan0
.\scripts\adr0058-underlay-mcast-probe-start-run.ps1 -Direction M02toM01 -SkipBuild -SkipInstall -Iface wlan0
```

Record: delivery, firstSeq, lastSeq, terminalTailMissingPackets, burst, P99, transport facts.

## Verdict routing

| Outcome | Classification |
|---------|----------------|
| AP reset → M03→M02 sustained recovery | Strong: AP/per-client multicast forwarding state |
| AP reset → still 0% | AP soft-state weakened; M02 driver/firmware ingress stronger |
| AP reset → brief recovery then tail dropout | AP↔client membership timeout / state transition (matches Step 3 ~12s window) |
| Both directions fail after AP reset | New state change — preserve scene, stop interventions |

## Explicit non-scope

- pacing / RTP / SRTP / Opus / AudioTrack / Meeting / Profile 03 constants
- M02 reboot / WiFi toggle / process reset during E3a.3c
