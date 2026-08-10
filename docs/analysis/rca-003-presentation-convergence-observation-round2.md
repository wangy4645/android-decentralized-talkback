# RCA-003 Round-2 — degraded lifecycle observation

**Status:** AUTHORIZED — **observe only / no code**  
**Parent:** [rca-003-presentation-convergence-entry.md](./rca-003-presentation-convergence-entry.md)  
**Round-1:** [rca-003-round1-adjudication-20260810-210401.md](./rca-003-round1-adjudication-20260810-210401.md)  
**Do not touch:** recovery last-mile · Phase-2 · Delivery · Ownership · Reattach · ICE timeout · hangup mid-window

## Goal (one question)

> Is `degraded` a final state, or a middle state?

Not: reopen recovery. Not: fix UI yet.

## Cases

### Case D — transient

```text
reconnect accepted
    ↓
recovering cleared
    ↓
degraded
    ↓
≥90–120s later → healthy
```

Meaning: UI refresh / projection delay. Hold code.

### Case E — durable

```text
reconnect accepted
    ↓
recovering cleared
    ↓
degraded
    ↓
remains for full window
```

Meaning: next desk seam = **Media lifecycle → presentation projection** (still not recovery chain).

Also record if `EDGE_RECOVERED` appears (may upgrade later toward Case B); Round-2 **does not require** it to answer D vs E.

## Procedure

```text
1. 3-party conference stable (SSID happy); clean pills
2. Start collectors (16M, clear log)
3. Flap M02 ~3s; annotate T0
4. DO NOT hangup / USER_LEAVE
5. Wait ≥120s after reconnect accepted (or after recovering clears)
6. Stop collectors
```

## Record every 15–30s (or continuous log)

| # | Marker |
|---|--------|
| 1 | `EDGE_RECOVERED` (yes/no + time) |
| 2 | recovering pill (`recovering=[…]` vs `[]`) |
| 3 | degraded pill (`connectingHint=*degraded*` / clear) |
| 4 | `MEDIA_LIFECYCLE` DEGRADED / HEALTHY (esp. M03→M02) |
| 5 | heartbeat / HELLO continuity M02↔M03 |

Watch **M01 and M03** separately — Round-1 showed host may clear while peer still degraded.

## Collectors

```powershell
cd talkback
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$LogDir = "logs\rca003-pres-conv-r2-$stamp"
.\scripts\conf-same-session-rejoin-start-run.ps1 -LogDir $LogDir -LogBuffer 16M
# flap M02; wait ≥120s; no hangup
.\scripts\conf-same-session-rejoin-stop-run.ps1 -LogDir $LogDir
```

## Exit

```text
Case D or Case E with timestamps
If hangup before 120s → ENV_INVALID / window cut (retry)
No production code in Round-2
```
