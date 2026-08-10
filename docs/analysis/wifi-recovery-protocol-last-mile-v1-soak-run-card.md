# WiFi recovery — protocol last-mile v1 soak run card

**Status:** AUTHORIZED (stability verification)  
**Parent freeze:** [wifi-recovery-last-mile-freeze-20260810.md](./wifi-recovery-last-mile-freeze-20260810.md)  
**Tag intent:** `recovery-protocol-last-mile-v1` = protocol COMPLETE; **not** behavior exhausted  
**PR:** [#150](https://github.com/wangy4645/android-decentralized-talkback/pull/150)

## Purpose

Bounded soak after last-mile merge. **Not** debug. **Not** recovery-rate chasing.

```text
PASS = observation chain intact + legal terminal
FAIL = silent hole / illegal terminal / frozen-semantics violation
```

`UI_CLEAR` is **observe-only**. Missing UI clear → candidate [RCA-003](./rca-003-presentation-convergence-entry.md), **not** recovery FAIL.

## Devices / SSID

| Role | Module | Serial |
|------|--------|--------|
| Host/Peer | M01 | `HTUBB21B09220661` |
| Peer | M02 | `2d73067a` |
| DUT | M03 | `MDX0220416001963` |

SSID: **`happy`** only

## Markers (every run)

Record presence/absence only:

```text
NETWORK_LOST
REATTACH
OBTAINED
RECONNECT
EDGE_RECOVERED
UI_CLEAR          # observe-only; not recovery PASS gate
```

Optional delivery detail (when EXPIRED path expected):

```text
ARMED → EXPIRED → REACQUISITION_ELIGIBLE → REEVALUATE → second ARMED → OBTAINED
```

## Matrix

| ID | Stimulus | Purpose | Runs |
|----|----------|---------|------|
| S1 | Single-device flap (M02 or M03), ~2s | Short blackhole / baseline | 3–5 |
| S2 | Single-device flap, ≥10s | Long blackhole / reacquire path | 3–5 |
| S3 | Dual-device sequential flap | Already field-verified class | 2–3 |
| S4 | Same DUT, 3 consecutive flaps | State cleanup / no sticky protocol | 2–3 |
| S5 | 3-party conference flap (one peer) | Multi-edge regression guard | 2–3 |
| S6 | Anchor/topology change (if operable) | Route to ADR-0039 if owner conflict | 0–2 |

Target band: **~10–20 runs total**, not marathon.

## Verdict rules

### Recovery PASS (per run)

```text
NETWORK_LOST present
REATTACH armed/sent (or documented skip)
OBTAINED (or documented no-reattach-needed path)
RECONNECT / same-session accept when invite present
EDGE_RECOVERED (or other legal recovery terminal — not silent)
```

### Recovery FAIL (per run) — open narrow note, do not patch casually

```text
EXPIRED with no REACQUISITION_ELIGIBLE when obligation still OPEN
opportunity WAITING forever with gate open
blind BUSY reject of same-session rejoin
EXPIRED treated as RETRY_REQUIRED / forced retry storm
completion / membership / RNA-5 predicate drift
```

### Presentation note (not recovery FAIL)

```text
EDGE_RECOVERED + media CONNECTED + pill still recovering=*
→ log as RCA-003 candidate; continue matrix
```

### Out-of-scope routing

| Observation | Route |
|-------------|-------|
| owner conflict blocks recovery | ADR-0039 (independent) |
| membership not converged but completion succeeds | RCA-0036 / ADR-0038 |
| intent terminal without fact | RNA-6 |
| UI sticky only | RCA-003 |

## Do not change during soak

```text
WiFi listener · retry counts · ICE timeout · membership epoch
recovery / completion predicate · RNA-5/6 · blind BUSY→ACCEPT
EXPIRED⇒RETRY_REQUIRED · timeout budget enlargement
```

## Collectors

Prefer existing last-mile scripts:

```powershell
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$LogDir = "talkback\logs\lastmile-soak-$stamp"
.\scripts\conf-same-session-rejoin-start-run.ps1 -LogDir $LogDir
# flap per matrix row; annotate T0
.\scripts\conf-same-session-rejoin-stop-run.ps1 -LogDir $LogDir
```

## Close criteria

```text
No recovery FAIL in S1–S5 band
Any UI sticky instances filed under RCA-003 (count only)
Then: stability stage HOLD — Appendix B passive observation
```
