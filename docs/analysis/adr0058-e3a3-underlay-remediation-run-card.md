# ADR-0058 E3a.3 — Underlay Remediation / Repeatability Run Card

## Status

```text
Prerequisite: E3a.2a probe v2 deployed (observability fields present)
E3a.1:        REACHABILITY ESTABLISHED (M01<->M02)
E3a.2:        AMBER (burst=10 / P99=129ms on M02->M01; v1 JSON missing >120ms count)
Media engine: NOT AUTHORIZED
M03 fan-out:  DEFERRED until E3a.2 GREEN on repeated baseline + remediation runs
```

## Purpose

Determine whether M01↔M02 multicast quality is **repeatable** and whether **single-variable underlay changes** improve burst / deadline metrics — without touching media contracts.

## Frozen comparison set (every run)

Record per direction:

```text
delivery %              [observability — not E3a.2 gate]
maxConsecutiveLoss      [gate]
interArrivalMsP99       [gate]
interArrivalGapOver120MsCount   [observability — v2+]
maxInterArrivalGapMs            [observability — v2+]
```

E3a.2 adjudication:

```powershell
.\scripts\adr0058-underlay-mcast-probe-e3a2-adjudicate.ps1 `
  -ForwardLogDir logs\adr0058-underlay-mcast-probe-<stamp-fwd> `
  -ReverseLogDir logs\adr0058-underlay-mcast-probe-<stamp-rev>
```

Per-run G0–G3 (legacy single-direction):

```powershell
.\scripts\adr0058-underlay-mcast-probe-adjudicate.ps1 -LogDir logs\adr0058-underlay-mcast-probe-<stamp>
```

## Phase 0 — Baseline repeatability (no underlay changes)

**Status: STOP after R2 (2026-09-03).** Repeatability satisfied; proceed to E3a.3a.

**Goal:** Confirm `burst=10 / P99=129ms` is stable vs one-off.

1. Same topology: M01 ↔ M02, `wlan0`, default multicast `239.255.42.99:46999`
2. **2–3 full bidirectional rounds** (each round = M01→M02 + M02→M01)
3. No AP / IGMP / pacing changes between rounds
4. Capture network facts each round (see Phase 1 checklist)

**Exit:**

- If burst/P99 vary widely → treat prior AMBER as noisy; continue Phase 0 until stable pattern
- If burst≥10 / P99>120 repeatable → proceed Phase 2 with **one variable at a time**

## Phase 1 — Network facts (every run)

```text
M01/M02: IP, mask, gateway, SSID, BSSID, band, RSSI, link rate
AP:      client isolation, multicast/IGMP snooping, mesh/backhaul mode
Route:   same L2 segment confirmed
```

Store in `logs/.../NETWORK_FACTS.txt` alongside `RUN_META.txt`.

## Phase 2 — Remediation (one variable per experiment)

**Order (frozen):**

| Step | Change | Rationale |
|------|--------|-----------|
| 2a | AP / mesh multicast & IGMP settings | Path/device asymmetry (M02→M01 worse) suggests forwarding |
| 2b | Client isolation / VLAN / mesh hop | Eliminate L2 filter before device tuning |
| 2c | Device placement / RSSI | Only after AP facts collected |
| 2d | Send pacing | **Last** — M01→M02 already shows short bursts; no evidence 50 pps is root cause |

**Rule:** change exactly **one** underlay variable between experiment groups. Re-run bidirectional probe after each change.

## Phase 3 — Verdict routing

| E3a.2 | Action |
|-------|--------|
| **GREEN** | Fix M03 probe; authorize 3-node fan-out probe (not media engine) |
| **AMBER** | Continue underlay tuning; media engine stays blocked |
| **RED** | ADR-0059 underlay unsuitable for real-time conference on current path |

## Execution

```powershell
cd talkback
.\scripts\adr0058-underlay-mcast-probe-start-run.ps1 -Direction M01toM02
.\scripts\adr0058-underlay-mcast-probe-start-run.ps1 -Direction M02toM01
```

Build probe v2 before field runs:

```powershell
.\gradlew :android-board-talkback:assembleDebugAndroidTest
```

## Out of scope

```text
≠ Media engine / Opus / AudioTrack
≠ TalkbackSession / Meeting UI wiring
≠ ADR-0056 cutover
≠ GROUP PTT / ADR-0057
≠ Probe behavior changes beyond E3a.2a observability
```
