# RC-3-C1Q Phase -1 — Provisioning Qualification Precondition

**Status:** **FROZEN** (2026-09-16) · Phase -1 execution **ATTEMPTED** · verdict **BLOCKED** (see `logs/rc3-c1q-phase-minus1-20260916-104319/`)  
**Parent:** `rc3-operational-rollback-run-card.md`  
**Blocks:** RC-3-C1Q Phase 0..2 · RC-3 closure · RC-4

```text
RC-3-C1R2  QUALIFICATION PRECONDITION GAP (NOT product defect)
RC-3       BLOCKED pending PHASE_MINUS_1 = PASS
C1Q field  NOT AUTHORIZED until this card PASS
```

---

## Goal

Establish and **objectively verify** Profile01 trust provisioning prerequisites on the **frozen rc.1 DUT (M02)** before any C1Q Meeting/teardown qualification run.

Phase -1 is **environment preparation**, not C1 test content. Success removes the C1R2 explanation space (“provisioning not done”); failure to materialize after Phase -1 PASS would reach **RC product integration defect** threshold.

**Does NOT re-prove:** Meeting GA · R2-A · RC-1/RC-2 · teardown contract.

---

## Frozen artifact (M02 DUT — mandatory)

```text
Product:        Talkback 1.0.0-rc.1
versionCode:    100001
applicationId:  com.talkback.appprod
APK SHA256:     056E47AC8CED10680C39A921C74064355055B6B29E9A2F8DAABB9FF43E1FD0E1
Cert SHA256:    67c049784d139897b28887c4ab6cd3b4e34c10cf8fcd008870d3e6fb920bb283
RC-1 manifest:  talkback/artifacts/rc1/talkback-1.0.0-rc.1-release-manifest.txt
```

M02 retains RC-2-installed rc.1 release unless identity drift is detected. **Do not reinstall target** except to restore exact rc.1 bytes if drifted (same SHA256).

---

## Devices

| Role | Serial | ModuleId | Phase -1 scope |
|------|--------|----------|----------------|
| Host / authority | `HTUBB21B09220661` | M01 | FTPH + establishment duo batch |
| **DUT** | `DSJ-2407041` | M02 | FTPH + establishment + P-1A POST identity |

Follow **existing operator duo batch** requirements (see references). Do **not** invent an “M02-only provisioning” variant unless the authoritative procedure explicitly allows it.

---

## Execution order (frozen)

```text
P-1A PRE   artifact identity (M02)
    ↓
P-1B-0     Signing FTPH (M01 + M02)
    ↓
P-1B-1     Establishment operator (M01 + M02)
    ↓
P-1C-4     M02 reverse pre4RemoteLookup(M01, v=1)
    ↓
P-1C       gates P-1C-1 .. P-1C-5
    ↓
P-1A POST  artifact identity (M02)
    ↓
PHASE_MINUS_1 verdict
```

---

## Hard STOP (NOT AUTHORIZED)

```text
installProdDebug / replacing target with non-rc.1 debug build
target uninstall (except documented drift recovery to exact rc.1 artifact)
target pm clear / adb wipe that clears qualification state outside FTPH procedure
non-rc.1 target APK replacement
manual trust injection (prefs, run-as writes, adb push to gbc-trust bypassing procedure)
run-as evidence extraction on release DUT (use Option A instrumentation transport)
new debug hooks / new instrumentation / new automation framework for RC-3
C1Q Meeting field before PHASE_MINUS_1 = PASS
product source changes
```

**C1Q-specific:** ops checklist §0 `installProdDebug` is **explicitly forbidden** for M02 DUT. If androidTest is missing, install **release androidTest only** (`installReleaseAndroidTest` or equivalent `adb install -r *-release-androidTest.apk`) without replacing the rc.1 release target.

---

## P-1A — Compatibility + artifact identity

### P-1A compatibility (read-only before run)

| Item | Expected |
|------|----------|
| target applicationId | `com.talkback.appprod` |
| test applicationId | `com.talkback.appprod.test` |
| target/test signing | Same debug keystore lineage as rc.1 manifest (LAB cert above) |
| Operator scripts | `ep-establishment-operator-*.ps1` / `adr0057-field-test-provision-device.ps1` do **not** `adb install` target APK |

### P-1A PRE — before any provisioning step

Capture on **M02** → `P1A_ARTIFACT_PRE.txt`:

```text
versionName=1.0.0-rc.1
versionCode=100001
certSha256=67c049784d139897b28887c4ab6cd3b4e34c10cf8fcd008870d3e6fb920bb283
apkSha256=056E47AC8CED10680C39A921C74064355055B6B29E9A2F8DAABB9FF43E1FD0E1  (if path known)
verdict=PASS|FAIL
```

**Mechanical checks (operator):**

```powershell
adb -s DSJ-2407041 shell dumpsys package com.talkback.appprod | findstr versionName versionCode
# Cert: apksigner verify --print-certs <rc.1 apk path>  OR dumpsys signing lineage
```

PRE FAIL → **STOP** · do not provision · fix artifact first.

### P-1A POST — after P-1B + P-1C probes

Repeat identical capture → `P1A_ARTIFACT_POST.txt`.

**PASS:** POST matches PRE on all three identity fields (versionName, versionCode, certSha256).

POST FAIL → **PHASE_MINUS_1 = BLOCKED** · instrumentation/procedure altered target · **do not** enter C1Q field.

---

## P-1B — Existing provisioning procedure (reference only)

Use authoritative lab scripts; **do not reimplement** in RC-3.

### P-1B-0 — Signing FTPH (both devices)

```powershell
cd d:\workspace\project\talkback\talkback\scripts
.\adr0057-field-test-provision-device.ps1 -Serial HTUBB21B09220661 -FtphOutPath "<logDir>\ftp-h\M01_FTPH_RESULT.txt"
.\adr0057-field-test-provision-device.ps1 -Serial DSJ-2407041 -FtphOutPath "<logDir>\ftp-h\M02_FTPH_RESULT.txt"
```

Per device, retain **transported** `FTPH_RESULT` (Option A — no `run-as` on release DUT):

```text
transportKind=FTPH_RESULT
payloadBase64=...   → byte-equivalent FTPH_RESULT.txt on workstation
```

**Note:** FTPH resets `files/gbc-trust/*` for that device when executed. Run **before** establishment operator.

**Release DUT transport:** `rc3-c1q-release-dut-evidence-transport-amendment.md` (Option A). Do **not** use `run-as` for evidence extraction.

### P-1B-1 — Establishment operator duo batch

```powershell
.\ep-establishment-operator-provision-all.ps1 `
    -EstablishmentKeyVersion 1 `
    -TaskProfileRevision 20 `
    -M01Serial HTUBB21B09220661 `
    -M02Serial DSJ-2407041 `
    -M03Serial ""
```

Authoritative steps / failure matrix: `meeting-profile01-establishment-operator-provisioning-ops-checklist.md`.

**Outputs (copy or link into evidence bundle):**

```text
<batchDir>/PROVISIONING_MANIFEST.txt
<batchDir>/PRE_CHECK.txt
```

Any step FAIL → **STOP** at failed step · **do not** patch product · **do not** enter P-1C.

---

## P-1C — Five objective gates

Do **not** treat `PRE_CHECK VERDICT=PASS` alone as Phase -1 PASS.

### P-1C-1 — Artifact identity (POST)

`P1A_ARTIFACT_POST.txt verdict=PASS` (see P-1A).

### P-1C-2 — Signing trust proxy (M02)

```text
REQUIRED:
  FTPH current Phase -1 batch PASS on M02
  AND FTPH binding assertion includes M01 @ signerKeyVersion=1
  AND accepted-local-trust-state.bin store presence sanity on M02

Interpretation:
  FTPH PASS + binding assertion = trust evidence
  store presence (STORE_SANITY)   = persistence sanity only (NOT trust proof alone)
```

**Trust evidence (M02):** transported `FTPH_RESULT` from P-1B-0 M02 run:

```text
success=true
acceptedBinding.M01.signerKeyVersion=1
acceptedBinding.M01.fingerprint=...
message=FTPH PASS
```

**Store presence sanity (M02)** — instrumentation probe (same pattern as PRE-1 / Option A3):

```powershell
. .\ep-field-test-instrumentation-transport.ps1
Invoke-FieldTestStoreSanityProbe `
  -Serial DSJ-2407041 `
  -ExpectedArtifact accepted-local-trust-state.bin `
  -TestMethod com.talkback.appprod.fieldtest.EpEstablishmentOperatorProvisionInstrumentedTest#probeSigningTrustStorePresent
# PASS adjudication: transportKind=STORE_SANITY · artifact=accepted-local-trust-state.bin · present=true
```

Do **not** use `run-as` for store presence on release DUT. Do **not** treat `EP_OP_PROBE outcome=READY` as signing PASS.

Write adjudication snippet → `P1C2_SIGNING_TRUST_PROXY.txt`.

### P-1C-3 — Establishment operator batch

```text
PRE_CHECK.txt VERDICT=PASS
PROVISIONING_MANIFEST.txt complete (signerKeyVersion=1, M01/M02 establishmentKeyVersion=1)
PRE-1 M01/M02 establishment_store=True
PRE-2 M01/M02 keystore=True version=1
PRE-3 manifest_complete=True
epProvisioningValid=YES   (derived: P-1C-2 AND P-1C-3 AND P-1C-4 all PASS)
```

Write → `P1C3_ESTABLISHMENT_BATCH.txt` (paste PRE_CHECK body + manifest key lines).

### P-1C-4 — M02 reverse establishment lookup (existing instrumentation)

PRE_CHECK PRE-4 only covers `M01_lookup_M02`. C1R2 prerequisite also requires **M02 can resolve M01 establishment key** at bind time.

Run **existing** `pre4RemoteLookup` on **M02** (not new probe):

```powershell
adb -s DSJ-2407041 shell am force-stop com.talkback.appprod
adb -s DSJ-2407041 shell am instrument -w -r `
  -e establishmentKeyVersion 1 `
  -e peerModuleId M01 `
  -e class com.talkback.appprod.fieldtest.EpEstablishmentOperatorProvisionInstrumentedTest#pre4RemoteLookup `
  com.talkback.appprod.test/androidx.test.runner.AndroidJUnitRunner
```

**PASS:** exit code 0 · logcat contains `EP_PRE_PROBE` / `lookupOutcome=Found` for peer M01.

Write → `P1C4_M02_LOOKUP_M01.txt`.

**Gap note:** No named field gate exists for `Profile01ModuleTrustLookup.lookupSigningKey(M01,1)`; P-1C-2 FTPH proxy closes the C1R2 signing failure class without new instrumentation.

### P-1C-5 — Aggregate

```text
P1C5_AGGREGATE.txt

P-1C-1 = PASS|FAIL
P-1C-2 = PASS|FAIL
P-1C-3 = PASS|FAIL
P-1C-4 = PASS|FAIL
P-1C-5 = PASS iff C-1..C-4 all PASS
```

---

## Evidence bundle layout

```text
talkback/logs/rc3-c1q-phase-minus1-<timestamp>/
  RUN_META.txt
  P1A_ARTIFACT_PRE.txt
  P1A_ARTIFACT_POST.txt
  P1C2_SIGNING_TRUST_PROXY.txt
  P1C3_ESTABLISHMENT_BATCH.txt
  P1C4_M02_LOOKUP_M01.txt
  P1C5_AGGREGATE.txt
  PHASE_MINUS_1_VERDICT.txt
  ftp-h/
    M01_FTPH_RESULT.txt
    M02_FTPH_RESULT.txt
  establishment-batch/          ← copy or path-ref to ep-establishment-operator-<stamp>/
    PROVISIONING_MANIFEST.txt
    PRE_CHECK.txt
  M02-pre4RemoteLookup-logcat.txt (optional excerpt)
```

`RUN_META.txt` minimum:

```text
mode=RC3_C1Q_PHASE_MINUS_1
timestamp=<stamp>
m01Serial=HTUBB21B09220661
m02Serial=DSJ-2407041
establishmentBatchDir=<path>
priorBlocker=RC3-C1R2_QUALIFICATION_PRECONDITION_GAP
```

---

## PHASE_MINUS_1 adjudication

```text
PHASE_MINUS_1 = PASS iff
  P-1A PRE PASS
  AND P-1B completed without STOP
  AND P-1C-1 .. P-1C-4 all PASS
  AND P-1A POST PASS
  AND M02 establishmentKeyVersion = 1
  AND M02 FTPH signing proxy PASS (P-1C-2)
  AND M02 pre4RemoteLookup(M01, v=1) PASS (P-1C-4)
  AND frozen rc.1 artifact identity unchanged (P-1A POST)
```

| Verdict | Meaning | Next |
|---------|---------|------|
| **PASS** | Provisioning prerequisite established on rc.1 DUT | **May authorize** C1Q Phase 0..2 (separate authorization) |
| **FAIL** | Procedure executed but a P-1C gate failed | STOP · fix at failed gate · no C1Q field |
| **BLOCKED** | P-1A POST identity drift · or hard STOP violated | STOP · do not C1Q field · investigate artifact/instrumentation |

### Downstream adjudication (after Phase -1 PASS only — not authorized here)

```text
Phase 0  M01 Start Meeting → M02 participant → MeetingExists PASS
Phase 1  SHADOW_SESSION_STARTED + MULTICAST_ACTIVE
Phase 2  Normal End → ownership release/reversion

C1Q PASS  → RC-3 PASS/CLOSED → RC-4 UNLOCKED

If Phase -1 PASS but bind-drain still UNKNOWN_SIGNER_KEY:
  → RC PRODUCT INTEGRATION DEFECT
  → rc.1 qualification STOP
  → new artifact identity (normally 1.0.0-rc.2)

If provisioning cannot complete:
  → RC-3 ENVIRONMENT / DEPLOYMENT PREREQUISITE BLOCKED
```

---

## C1R2 context (frozen — do not re-adjudicate)

```text
RC-3-C1R2 FIRST CAUSAL DIVERGENCE @ SESSION_BIND_DRAIN replay:
  CONTROL: signer trusted → INCOMPLETE_PENDING / APPLIED
  C1R2:    signer not trusted → NOT_PUBLISHED / UNKNOWN_SIGNER_KEY

Classification: QUALIFICATION PRECONDITION GAP
  (RC-2 fresh install cleared M02 trust; RC-3 did not re-establish)

ABSENT @ replay (narrow):
  required accepted binding for signed fact's (M01, signerKeyVersion)
  was unavailable — NOT a claim that snapshot has zero M01 entries
```

Downstream symptoms **not** re-investigated: `DECRYPT_SEAM_UNAVAILABLE` · `NO_SESSION_FACT` · `SHADOW_TX_FENCED` · no `MULTICAST_ACTIVE`.

---

## References (implementation — do not duplicate)

| Item | Path |
|------|------|
| RC-3 run card | `talkback/docs/analysis/rc3-operational-rollback-run-card.md` |
| FTPH launcher | `talkback/scripts/adr0057-field-test-provision-device.ps1` |
| Establishment operator | `talkback/scripts/ep-establishment-operator-provision-all.ps1` |
| PRE check | `talkback/scripts/ep-establishment-operator-pre-check.ps1` |
| Ops checklist | `talkback/docs/analysis/meeting-profile01-establishment-operator-provisioning-ops-checklist.md` |
| RC-1 manifest | `talkback/artifacts/rc1/talkback-1.0.0-rc.1-release-manifest.txt` |
| Release DUT transport | `rc3-c1q-release-dut-evidence-transport-amendment.md` |
| Transport helpers | `talkback/scripts/ep-field-test-instrumentation-transport.ps1` |
| Canonical batch (historical) | `talkback/logs/ep-establishment-operator-20260908-155431/` |

---

## Authorization ledger

```text
Run-card writing              DONE / FROZEN
Transport amendment (A1/A2/A3)  PASS
P-1C-2 STORE_SANITY alignment   PASS (2026-09-16)
Phase -1 run-card               ZERO run-as evidence deps
Phase -1 execution              NOT AUTHORIZED (await separate auth)
C1Q field                       NOT AUTHORIZED
Product changes                 NOT AUTHORIZED
```
