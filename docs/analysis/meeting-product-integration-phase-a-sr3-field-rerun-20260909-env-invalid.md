# PA-SR3 Field Re-run — NOT TESTED / ENV_INVALID (M02 adb offline)

**Attempted:** 2026-09-09 ~13:38–13:50 (local)  
**Driver:** `phase-a-sr3-field-first-run.ps1`  
**Build:** B1-R1 unit-proven APK (`talkback-app-debug.apk`, assembleDebug SUCCESS)  
**Devices:** M01 `HTUBB21B09220661` · M02 `DSJ-2407041`

---

## Verdict

```text
VERDICT=INVALID_ENV_ADB_OFFLINE
PA-SR3=NOT TESTED
PR-PA-SR-B1=CLOSED / UNIT-PROVEN · FIELD FAIL (prior) — no new field evidence
B1-R1=CLOSED / UNIT-PROVEN — unchanged

Not FAIL_PA_SR3 — SOURCE chain never reached.
Not PASS — product chain not observed.
```

---

## Attempt log

| Attempt | Result |
|---------|--------|
| #1 ~13:38 | Build+install M01 OK; M02 `device offline` during PRE-1 / install → abort |
| #2 ~13:40 | Build OK; M01 install OK; M02 install hung (`abb_exec … Error: closed`) → killed |
| Recovery | `adb kill-server` / reconnect; M02 briefly `device`, then install hung again |
| Final | M02 stuck `offline`; M01 online with APK `lastUpdateTime=2026-09-09 13:40:06` |

**No LogDir produced** for a complete PaSr3 observation window. Adjudicator not run on a finished capture.

---

## Status board (unchanged on product gates)

```text
PR-PA-SR-B1     CLOSED / UNIT-PROVEN · FIELD FAIL (prior 20260909-121252)
B1-R1           CLOSED / UNIT-PROVEN
PA-SR3          AUTHORIZED FOR FIELD RE-RUN · NOT TESTED this attempt

PA-SR4…SR6      HOLD
ADR-0056        KEEP
cutover         NOT AUTHORIZED
```

---

## Next

1. Physically reconnect / power-cycle M02 (`DSJ-2407041`) until `adb devices` shows `device` (not `offline`).
2. Install APK on M02:  
   `adb -s DSJ-2407041 install -r -d talkback\talkback-app\build\outputs\apk\debug\talkback-app-debug.apk`  
   (or `push` + `pm install -r -d` if streamed install hangs).
3. Confirm both serials `device`, then re-run:  
   `.\talkback\scripts\phase-a-sr3-field-first-run.ps1 -SkipBuildInstall`  
   (only after M02 has the B1-R1 APK).
4. Adjudicate only the frozen PA-SR3 chain; do not treat this ENV_INVALID as SOURCE RCA.
