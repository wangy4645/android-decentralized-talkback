# ADR-0058 E3a.3b — M02 Ingress Single-Variable Investigation

## Status

```text
Prerequisite: E3a.3 classification CLOSED
Primary suspect: M02 receiver ingress / membership / AP per-client multicast downlink
Media engine: NOT AUTHORIZED
Probe changes: NONE (use existing v3a.1 + M03 activity host)
```

## Frozen evidence

```text
M01→M03 = 93% PASS     → M01 sender OK
M03→M02 = 0%            → M02 receiver FAIL (join OK)
M01→M02 = 0%            → same M02 ingress signature
M02→M01 = GREEN         → M02 sender OK (control)
```

## Per-step probe pair (every intervention)

| Probe | Direction | Purpose |
|-------|-----------|---------|
| **Test** | M03→M02 | M02 ingress under test |
| **Control** | M02→M01 | M02 sender still healthy |

Record: delivery, burst, P99, `interArrivalGapOver120MsCount`, transport facts.

## Intervention order (one variable per step)

| Step | Action | Scope | If PASS → classify as |
|------|--------|-------|------------------------|
| **0** | Baseline re-confirm | none | establish current 0% |
| **1** | M02 WiFi off/on | M02 only | stale WiFi/multicast membership |
| **2** | M02 app/test process reset | M02 only | stale socket/join state |
| **3** | M02 reboot | M02 only | driver / Android network stack |
| **4** | AP/IGMP state reset | AP only | per-client AP forwarding |
| **5** | — | stop | do not stack variables |

**Rules:**
- Do NOT restart M01/M03 unless control run fails
- Do NOT change AP until steps 0–3 exhausted
- Do NOT touch media contracts / pacing / probe code

## Step 1 — WiFi toggle (M02 only)

```powershell
# Disable WiFi on M02
adb -s DSJ-2407041 shell svc wifi disable
Start-Sleep -Seconds 5
adb -s DSJ-2407041 shell svc wifi enable
# Wait for association (SSID KKX-HOME-OLD / 192.168.10.x)
Start-Sleep -Seconds 15
```

Then: M03→M02 test + M02→M01 control.

## Step 2 — Process reset (M02 only)

```powershell
adb -s DSJ-2407041 shell am force-stop com.talkback.appprod
adb -s DSJ-2407041 shell am force-stop com.talkback.test
```

Then: M03→M02 test + M02→M01 control.

## Execution

```powershell
cd talkback
.\scripts\adr0058-underlay-mcast-probe-start-run.ps1 -Direction M03toM02 -SkipBuild -SkipInstall -Iface wlan0
.\scripts\adr0058-underlay-mcast-probe-start-run.ps1 -Direction M02toM01 -SkipBuild -SkipInstall -Iface wlan0
```

Artifacts: `logs/adr0058-e3a3b-m02-ingress-<step>-<stamp>/`

## Exit routing

| Outcome | Next |
|---------|------|
| M03→M02 recovers after WiFi toggle | document stale membership; optional repeatability |
| Recovers only after reboot | M02 stack / driver hypothesis |
| Recovers only after AP reset | AP per-client forwarding hypothesis |
| Never recovers | escalate ADR — not media engine |
