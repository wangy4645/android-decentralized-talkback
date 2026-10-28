# ADR-0058 E3a.3e — M02 Multicast RX Minimal Intervention A/B

## Status

```text
Prerequisite: E3a.3d CLOSED for pure observation
Frozen: M02 device-side WiFi multicast RX/filter path abnormal — HIGH
Exact trigger: NOT YET PROVEN
Media engine: NOT AUTHORIZED
Rule: ONE variable per experiment
```

## Question

**Does one device-side multicast/filter/power-save control restore M03→M02 ingress?**

## Frozen verdict

| Outcome | Classification |
|---------|----------------|
| 0% → sustained full-window receive | that control is causal |
| brief recover then dropout | state transition / aging |
| no change | exclude this variable; next single variable |
| TX control also dies | INVALID — do not attribute |

## Record every run

```text
firstSeq lastSeq terminalTailMissingPackets
timeToFirstReceive timeToDropout
/proc/net/igmp  ip -s link wlan0  power_save  iface flags
```

Pair: M03→M02 TEST then M02→M01 CONTROL.

## Experiment order (one at a time)

| ID | Variable | Scope |
|----|----------|-------|
| **A1** | `iw dev wlan0 set power_save off` | M02 only |
| A2 | `ip link set wlan0 allmulticast on` | M02 only — only if A1 no change |
| A3 | stop | do not stack; no AP / RTP / pacing |

## Constraints

- No AP reboot / IGMP / SSID / band change
- No M02 reboot / WiFi toggle / process-reset-as-intervention
- No Profile 03 / media engine / Opus / AudioTrack
- Probe harness `SkipForceStopModules M02`
