# RCA-003 IC gray — adjudication (`20260811-062506`)

**Date:** 2026-08-11  
**LogDir:** `logs/rca003-ic-uvcp-gray-20260811-062506/`  
**APK:** #157 (`f79b277`)  
**Observer report:** M03 UI 上 M02 一直 degraded  
**IC status after this run:**

```text
Implementation       PASS
Field validation     NOT YET EXERCISED   (Case 3 missing)
Current failure      NOT UVCP projection
UVCP Regression      NOT FOUND
```

This run is an **exclusion test** for #157 (proves unrecovered path still paints degraded), not an IC FAIL.  
Superseded for Case-3 closure by gray-2 [`063722`](./rca-003-ic-uvcp-gray-adjudication-20260811-063722.md) — Case 3 does **not** require `EDGE_RECOVERED`.

## Verdict (portfolio)

| Track | Verdict |
|-------|---------|
| UVCP residency decoupling (Case 3 core) | **NOT YET EXERCISED** |
| Case 2 (true unrecovered → not fake healthy) | **PASS / SUPPORTIVE** on M03↔M02 |
| Case 1 (recover → pill healthy) on M03↔M02 | **NOT MET** — mesh edge unrecovered (orthogonal) |
| Host M01↔M02 protocol recovery | **EDGE_RECOVERED observed** |
| Mesh M03→M02 sticky degrade | **EXPECTED** given current RECONNECTING — **PARK** ownership/mesh note |

**Do not write:** `#157 FAIL` / `UVCP still maps residency` / `Recovery broken` / reopen Phase-2.

## Timeline (M03 observer)

| Time | Fact |
|------|------|
| ~06:25:50–06:26:25 | M02 `media=CONNECTED` · `mediaUnavailable=false` · pill clear |
| 06:26:25 | M02 `MEDIA_LIFECYCLE … DEGRADED ice=DISCONNECTED` → CHECKING |
| 06:26:26–06:26:40 | pill `M02 reconnecting...` · rprobe `media=RECONNECTING mediaUnavailable=true` |
| 06:26:41 | `RECOVERY_PENDING → FAILED_MEDIA_RECOVERY` trigger=`EXPLICIT_ABORT:NO_MEDIA_ACTION_OWNER` |
| 06:26:41–06:27:55 | pill **`M02 degraded...` sticky** · rprobe stable: `media=RECONNECTING` · `ice=CHECKING` · `mediaUnavailable=true` · `edgeRecoveryPhase=FAILED_MEDIA_RECOVERY` · `receivePathLive=true` · `finalPresence=DEGRADED` |
| 06:27:56 | M02 lifecycle → IDLE/CLOSED (session churn / leave) — **never CONNECTED after flap** |

M03: `RECOVERY_EDGE_RECOVERED` for M02 = **0**.

## Why pill stayed degraded (correct for current path)

Sticky window dominant combo (61 samples):

```text
media=RECONNECTING
ice=CHECKING
mediaUnavailable=true
phase=FAILED_MEDIA_RECOVERY
→ DEGRADED
```

`mediaUnavailable=true` here is explained by **current** `MediaState.RECONNECTING` (`currentUnavailable`), not by “CONNECTED + residency alone”.

Case 3 accept criterion was:

```text
FAILED_MEDIA residency=true + iceConnected + receivePathLive + media CONNECTED → healthy
```

That conjunction **did not occur** on M03↔M02 after the flap. Sticky receivePathLive=true with ICE stuck CHECKING is **not** Case 3 PASS evidence.

## Cross-device asymmetry (parked — not IC)

| Edge / view | After flap |
|-------------|------------|
| M01 → M02 | `RECOVERY_EDGE_RECOVERED` @ 06:26:23; M01 pill mostly clear (`connectingHint=null` ×119) |
| M02 → M01 | EDGE_RECOVERED @ 06:26:27; media CONNECTED |
| M02 → M03 | media returned CONNECTED @ 06:26:29 |
| **M03 → M02** | ICE stuck CHECKING; FAILED_MEDIA_RECOVERY; **no** EDGE_RECOVERED; pill degraded until end |

So the user-visible sticky on **M03** matches **unrecovered mesh edge toward M02**, not proven UVCP residency mis-consumption.

Abort reason of note (do not fold into #157):

```text
EXPLICIT_ABORT:NO_MEDIA_ACTION_OWNER → FAILED_MEDIA_RECOVERY
```

Park under mesh / ownership handoff observation if revisited — **not** UVCP IC reopen, **not** WiFi protocol last-mile reopen without a dedicated case.

## Case matrix score

| Case | Result | Note |
|------|--------|------|
| 1 Recover → healthy | **MISS on M03↔M02** | No MEDIA CONNECTED return; IC N/A |
| 2 Unrecovered → degraded | **OK** | Did not paint healthy while RECONNECTING |
| 3 Residue + CONNECTED → healthy | **NOT EXERCISED** | Need CONNECTED + residency still latched |

## What to do about the degrade (routing)

```text
Pill degraded on M03 for M02  =  correct current-state projection
                                ≠  UVCP bug
                                ≠  reason to reopen WiFi protocol / Phase-2 / #157

Park as: mesh-peer recovery asymmetry
  M03→M02 NO_MEDIA_ACTION_OWNER / ICE stuck CHECKING
  while M01↔M02 and M02→M03 recovered

Open a dedicated case ONLY if reproduced with intent to fix media-action
ownership on participant↔participant edges — not under RCA-003 IC.
```

## Next

```text
1. RCA-003 IC: Implementation PASS; only Case-3 point experiment remains
2. Mesh degrade: PARK / WATCH — do not soak or patch from this log
3. Case-3 PASS → close RCA-003
```