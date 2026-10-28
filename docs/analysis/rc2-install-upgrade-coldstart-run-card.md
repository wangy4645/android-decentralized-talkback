# RC-2 — Installation / Upgrade / Cold-Start Qualification

**Status:** **PASS / CLOSED** (2026-09-16)  
**Prerequisite:** RC-1 COMPLETE (`0a74d87` · APK identity frozen)  
**Blocks:** (none — superseded by RC-4 sign-off)

```text
RC-1.0 reproducible source   CLOSED (c2fb0fa)
RC-1.1 artifact identity     CLOSED (0a74d87)
RC-1                         COMPLETE
RC-2                         PASS / CLOSED
RC-4                         PASS / CLOSED
Meeting architecture         FROZEN / DO NOT REOPEN
```

---

## Goal

Qualify the **exact Engineering RC APK artifact** for deployment lifecycle — not Meeting / multicast / media.

```text
exact APK
→ fresh install
→ cold-start (process + reboot)
→ upgrade from qualified historical baseline (if any)
→ no Talkback crash in test windows
```

---

## Frozen artifact (mandatory)

```text
Product:        Talkback 1.0.0-rc.1
versionCode:    100001
source SHA:     0a74d87142eae6176de3f33a42b306c1aa3b27e8
APK file:       talkback-1.0.0-rc.1-release.apk
APK SHA256:     056E47AC8CED10680C39A921C74064355055B6B29E9A2F8DAABB9FF43E1FD0E1
Cert SHA256:    67c049784d139897b28887c4ab6cd3b4e34c10cf8fcd008870d3e6fb920bb283
Signing:        LAB_NON_PRODUCTION (Android Debug)
```

**Preflight hard gate:** compute APK SHA256 before any install. Mismatch → `ENV_INVALID` · stop.

**Artifact invalidation:** any source change + rebuild voids this APK's RC-2 inheritance. New package requires new identity (typically `rc.2`). Do not silently replace `rc.1`.

---

## Devices

| Role | Serial | Notes |
|------|--------|-------|
| **Primary DUT** | `DSJ-2407041` (M02) | REQUIRED |
| Secondary DUT | optional | not required for CLOSED |

**Forbidden during RC-2:** observe / adjudicate multicast, cutover, Meeting media, R2-A/R2-B.

---

## Out of scope

- 3-node Meeting field
- multicast / auto-cutover adjudication
- long soak
- production signing
- inventing baseline APKs to force UPGRADE PASS
- app-internal RC-2 test hooks

---

## RC-2-A — Fresh Install (REQUIRED)

```text
adb uninstall com.talkback.appprod   // if present
→ pm path com.talkback.appprod
   MUST: package not found
→ adb install <exact rc.1 APK>
→ dumpsys package: versionName=1.0.0-rc.1 · versionCode=100001
→ grant required permissions (operator OK)
→ explicit normal product launch (MainActivity)
→ start foreground service via normal product path
→ no Talkback process FATAL in window
```

**Does NOT require** moduleId/channel retention (first init OK).

**Verdict:** `FRESH_INSTALL = PASS | FAIL`

---

## RC-2-B — Cold Start (REQUIRED)

Same DUT · same APK · no reinstall. After RC-2-A PASS.

### B1 — Package force-stop → explicit relaunch

```text
app/service running
→ am force-stop com.talkback.appprod
→ explicit normal product launch (NOT expect auto service recovery)
→ runtime/service reconstruct via normal product lifecycle
→ no Talkback crash
```

`force-stop` places the package in Android stopped state. **Do not** assert automatic service resurrection.

### B2 — Device reboot → explicit relaunch (REQUIRED)

```text
device reboot
→ package still installed · version identity retained
→ data/config retained (not wiped)
→ explicit normal product launch
→ runtime/service reconstruct via normal product lifecycle
→ no Talkback crash
```

**Do not** require boot-completed auto-start unless the product already commits to it.

```text
B1 PASS + B2 PASS → COLD_START = PASS
any FAIL → COLD_START = FAIL → RC-2 FAIL → STOP
```

No `PARTIAL` verdict.

---

## RC-2-C — Upgrade (conditional)

### Qualified baseline (minimum)

```text
applicationId        == com.talkback.appprod
versionCode          < 100001
certificate SHA256   == RC cert SHA256
APK SHA256           recorded
APK bytes            retained on disk
```

Optional (record if known, not hard gate): `versionName` · git SHA · build date.

If no qualified baseline:

```text
UPGRADE = NOT_EXECUTED_NO_QUALIFIED_BASELINE
→ non-blocking for RC-2 PASS
```

If qualified:

```text
install baseline (verify SHA256)
→ minimal launch smoke
→ adb install -r <exact rc.1 APK>
→ versionCode=100001 · versionName=1.0.0-rc.1
→ config/data not wiped unexpectedly
→ force-stop → explicit relaunch smoke
→ no Talkback crash
```

**Verdict:** `UPGRADE = PASS | FAIL | NOT_EXECUTED_NO_QUALIFIED_BASELINE`

---

## RC-2-D — Crash / Service Smoke (REQUIRED)

Scoped to **Talkback package/process** and the **active test window** only.

```text
Talkback FATAL EXCEPTION
native fatal / abort attributable to Talkback
repeated Talkback process death in window
→ FAIL
```

Other apps / historical logcat / ordinary ERROR·WARN → **not** RC-2 blockers.

---

## Closure rule

```text
FRESH_INSTALL = PASS
COLD_START    = PASS
CRASH_SMOKE   = PASS
UPGRADE       = PASS  OR  NOT_EXECUTED_NO_QUALIFIED_BASELINE

→ RC-2 PASS

any REQUIRED FAIL → RC-2 FAIL → STOP (no on-device Meeting fix)
```

---

## Driver

| Item | Path |
|------|------|
| Script | `talkback/scripts/rc2-install-qual-field-first-run.ps1` |
| Resume after reboot | `-ResumeAfterReboot` |
| Optional upgrade | `-BaselineApk <path>` (identity verified by script) |
| Logs | `talkback/logs/rc2-install-qual-<timestamp>/` |

Artifacts: `RUN_META.txt` · `PREFLIGHT.txt` · `RC2A_FRESH_INSTALL.txt` · `RC2B_COLD_START.txt` · `RC2C_UPGRADE.txt` · `RC2_RESULT.txt` · logcat excerpts.

Human confirmation fields (permissions / UI reachable / service started) are recorded by operator prompts — script does not invent product test hooks.

---

## Known baseline (lab)

When present on DUT before RC-2, an installed `versionCode=1` debug-signed APK may be pulled and registered if certificate matches. Example registration lives under `talkback/artifacts/rc2-baselines/` with recorded SHA256 in run logs.
