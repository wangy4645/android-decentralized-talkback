# RC-3 — Operational Rollback Check

**Status:** **PASS / CLOSED** (2026-09-16)  
**Prerequisite:** RC-1 COMPLETE · RC-2 PASS · RC-3 PASS/CLOSED · Track B Run #1 ADJUDICATED  
**Blocks:** (none — RC qualification chain complete)

```text
RC-1  Artifact Identity / Config Freeze   CLOSED
RC-2  Install / Cold-Start Qualification  CLOSED / PASS
RC-3  Operational Rollback Check           PASS / CLOSED
RC-4  Engineering RC Sign-off              PASS / CLOSED
```

```text
RC-1  Artifact Identity / Config Freeze   CLOSED
RC-2  Install / Cold-Start Qualification  CLOSED / PASS
RC-3  Operational Rollback Check           PASS / CLOSED
RC-4  Engineering RC Sign-off              PASS / CLOSED
Meeting GA                                 FROZEN / DO NOT REOPEN
C1Q Run #1                                 FROZEN INVALID / TOPOLOGY_DEVIATION (not consumed)
C1Q Run #2                                 PASS (valid duo) · STOP
```

---

## Goal

Verify rollback **mechanisms and ownership lifecycle closure** on the exact Engineering RC artifact — not Meeting re-qualification, not release fault injection, not deployment-package rollback unless a qualified artifact exists.

```text
code-level automatic rollback        → source-proven
normal termination ownership closure → field-proven (C1)
artifact-level package rollback      → recorded only if qualified (D)
```

**Semantic fence:** normal Meeting End validates **ownership teardown / reversion closure**, not “operational rollback” in the in-session fatal sense. In-session fatal rollback is **C2**, source-proven from existing unit evidence.

---

## Frozen artifact (mandatory reference)

```text
Product:        Talkback 1.0.0-rc.1
versionCode:    100001
source SHA:     0a74d87142eae6176de3f33a42b306c1aa3b27e8
APK SHA256:     056E47AC8CED10680C39A921C74064355055B6B29E9A2F8DAABB9FF43E1FD0E1
Cert SHA256:    67c049784d139897b28887c4ab6cd3b4e34c10cf8fcd008870d3e6fb920bb283
Signing:        LAB_NON_PRODUCTION (Android Debug)
RC-1 manifest:  talkback/artifacts/rc1/talkback-1.0.0-rc.1-release-manifest.txt
```

C1 uses the **already-installed exact rc.1 on M02** from RC-2. **Do not reinstall** unless package identity drift is detected.

---

## Devices

| Role | Serial | ModuleId | Notes |
|------|--------|----------|-------|
| **Host** | `HTUBB21B09220661` | M01 | C1Q Meeting host |
| **Primary DUT** | `DSJ-2407041` | M02 | REQUIRED for C1 / C1Q sole participant |
| **M03** | `MDX0220416001963` | M03 | **MUST NOT JOIN** C1Q Meeting |

**C1Q topology is duo-only.** No additional Meeting participant is permitted. Historical C1/C1R2 notes that “secondary peers may exist” **do not apply** to C1Q; M03 join → `INVALID / TOPOLOGY_DEVIATION`. C1Q adjudication is **M02 log scope** for Gate 2 / teardown (host multicast alone is not a substitute).

---

## Out of scope

- R2-A / R2-B re-run
- 3-node soak / media quality / human listening adjudication
- release adb debug RC1 broadcast / fault injection hooks
- new rollback tests or production code changes
- inventing anchor-only APK for D
- upgrade matrix (RC-2 closed)

**Human listening anomalies** (e.g. asymmetric audibility) → separate track; **non-blocking** for RC-3 unless accompanied by crash, ownership stuck, or rollback contract violation.

---

## RC-3-A — Mechanism Audit (REQUIRED)

Mechanical **production call graph** retention at RC source SHA. No field.

Must confirm:

```text
ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStopped
  → ReplacementCutoverRc1.onSessionTeardown
  → ownership reversion / multicast release

MeetingProductMediaShadow.runShadowHook catch
  → ReplacementCutoverRc1.onReplacementRuntimeFatal
  → automatic rollback / reversion when MULTICAST_ACTIVE

TalkbackRuntimeFactory
  → ReplacementCutoverRc1.install(AnchorAudiblePort)
  → ADR-0056 Anchor rollback path retained
```

Release debug control status: **inherit RC-1 manifest evidence** (`debugCutoverBroadcastUnreachableInRelease=YES`). Do not repeat RC-1 runtime audit.

**Verdict:** `MECHANISM_AUDIT = PASS | FAIL`

---

## RC-3-B — Source Regression (REQUIRED)

Offline unit tests at RC source. **Only** these two classes:

```text
com.talkback.core.conference.session.integration.cutover.AudibleOwnershipControllerTest
com.talkback.core.conference.session.integration.cutover.MulticastAudibleAutomaticCutoverTest
```

No expanded suite. No new tests unless B review finds a fatal-rollback evidence gap → **STOP** before field.

**Verdict:** `SOURCE_REGRESSION = PASS | FAIL`

---

## RC-3-C1 — Normal Teardown Closure (REQUIRED)

### C1 first run (20260916-095738) — preserved

```text
Mechanical verdict: FAIL
Architecture: INVALID TEST SETUP / PRECONDITION_NOT_ESTABLISHED
(wrong topology — M02 host)
```

### C1R (20260916-101052 / manual) — preserved

```text
INVALID / PRECONDITION_NOT_ESTABLISHED
reason: Meeting never started (test orchestration failure)
→ not product qualification failure
```

Both superseded for RC-3 qualification by **C1R2** (orchestration-corrected; not FAIL-to-PASS retry). Product teardown contract still **0 executions** until C1R2 enters `MULTICAST_ACTIVE`.

### RC-3-C1R2 — Orchestration-corrected replacement (AUTHORIZED, one shot)

**Purpose:** prove normal teardown ownership closure after `MULTICAST_ACTIVE`.  
**Does NOT re-prove:** other host/authority combos. R2-A topology = control config only.  
**No R2 lean driver.** Manual product UI only.

**Topology:**

```text
M01 = WebRTC host / Profile01 authority
M02 = participant / DUT (1.0.0-rc.1, no reinstall)
```

**Collection:** `Talkback:I` · `MEETING_MEDIA_SHADOW:I` · `REPLACEMENT_CUTOVER_RC1:I`

#### Phase 0 — MANUAL SETUP (does not consume C1R2 if FAIL)

```text
M01 → Start Meeting (product UI)
M02 → join as participant

MeetingExists gate (M02 objective log — not UI alone):
  SESSION_CREATED localRole=PARTICIPANT / INVITE_ACCEPT
  OR CONFERENCE_RUNTIME_PROJECTION phase=ACTIVE host=false
  OR CONFERENCE_ADMISSION_PHASE phase=READY
  OR MEETING_START + session READY equivalent

MeetingExists = FAIL → TEST_NOT_STARTED (C1R2 not consumed)
MeetingExists = PASS → enter Phase 1 (start qualification timer)
```

Do **not** require `SHADOW_SESSION_STARTED` in Phase 0.

#### Phase 1 — QUALIFICATION (timer starts only after MeetingExists PASS)

```text
WAIT (historical C1R2 wording): SHADOW_SESSION_STARTED AND MULTICAST_ACTIVE
```

**C1Q supersession note:** for C1Q field, Gate 2 is **M02-local only** — see C1Q Run #2 End gate below. Do **not** treat M01 `MULTICAST_ACTIVE`, M02 packet/Top-K/mixer activity, or post-End `SESSION_END_OWNERSHIP_RELEASED` as Gate 2 proof.

#### Adjudication

```text
MeetingExists FAIL
  → TEST_NOT_STARTED (retry Phase 0 allowed; C1R2 not consumed)

MeetingExists PASS + shadow/multicast gate FAIL
  → C1R2 = BLOCKED (see frozen classification below)
  → STOP (not mere orchestration invalid)

gate PASS → normal End
  ownership released → C1R2 PASS → RC-3 PASS / CLOSED → RC-4 UNLOCKED
  ownership not released → C1R2 FAIL / TEARDOWN_REVERSION_DEFECT → RC-3 FAIL → STOP
```

**Teardown pass (post-gate End):** FATAL none · teardown/reversion observed · multicast released · no zombie · terminal ownership valid (ANCHOR_ACTIVE OK at session end).

**Execution:** do not re-run A/B/C2. Inherit first-run PASS. One C1R2 qualification only (after MeetingExists PASS).

#### C1R2 frozen classification (2026-09-16)

```text
RC-3-C1R2 = QUALIFICATION PRECONDITION GAP
  Profile01 trust provisioning NOT ESTABLISHED on M02 after RC-2 fresh install
  RC PRODUCT INTEGRATION DEFECT = NOT ESTABLISHED

Next unblock: RC-3-C1Q Phase -1 (provisioning precondition) — see rc3-c1q-phase-minus1-run-card.md
C1Q field NOT AUTHORIZED until PHASE_MINUS_1 = PASS
```

Evidence: `talkback/logs/rc3-operational-rollback-c1r2-20260916-102453/`

**Superseded path status (post-classification):** `PHASE_MINUS_1 = PASS` · C1Q Run #1 = `INVALID / TOPOLOGY_DEVIATION` (FROZEN) · see C1Q sections below. The lines above remain the C1R2-era freeze; they are not current C1Q authorization text.

---

## RC-3-C1Q Phase -1 — Provisioning precondition

**Purpose:** establish Profile01 trust prerequisites on frozen rc.1 M02 before any C1Q Meeting/teardown attempt.

**Run card:** `talkback/docs/analysis/rc3-c1q-phase-minus1-run-card.md`

```text
PHASE_MINUS_1           = PASS (2026-09-16)
Evidence                talkback/logs/rc3-c1q-phase-minus1-20260916-110044/
Phase -1 re-run         NOT REQUIRED currently
  (inherits ONLY if trust/application state intact — see below)
```

### Phase -1 inheritance (C1Q Run #2)

May continue to use Phase -1 PASS **without re-run** iff **none** of the following occurred since Phase -1:

```text
No uninstall
No clear-data
No target replacement
No trust reset / re-provision
No relevant state-destructive operation
```

If any occurs → Phase -1 PASS **does not auto-inherit** → re-establish Phase -1 before C1Q field.

---

## RC-3-C1Q field — Normal teardown closure (post Phase -1)

**Does not redesign C1.** Same teardown contract; frozen duo topology + M02-local Gate 2.

### C1Q Run #1 — FROZEN (2026-09-16)

Evidence: `talkback/logs/rc3-c1q-field-manual-20260916-112211/C1Q_FIELD_VERDICT.txt`

```text
C1Q Run #1              = INVALID / TOPOLOGY_DEVIATION
  expected: M01 host + M02 sole participant
  observed: M01 + M02 + M03

C1                      = NOT ADJUDICATED
  (not product FAIL; teardown release on M01/M02 is positive evidence)
  (not PASS; frozen duo + M02 pre-End Gate 2 not proven)

Attempt consumed        = NO
  configuration deviation — not a FAIL-to-PASS retry slot

RC-3                    = BLOCKED ON VALID C1 QUALIFICATION
RC-4                    = BLOCKED

M03 MIXED-PATH / MEDIA-KEY OBSERVATION
  = TRACK B ADJUDICATED / CLOSED (2026-09-16)
  verdict: talkback/logs/rc3-c1q-field-manual-20260916-112211/TRACK_B_RUN1_VERDICT.txt
  classification: PROVISIONING / DEPLOYMENT-SCOPE MISMATCH
  M03 media-key RCA CLOSED; human audio asymmetry remains OPEN (separate track)
  Do NOT reopen Meeting GA / R2-P2 / Profile01 from this run
```

**Frozen inference fence (Run #1):** `SESSION_END_OWNERSHIP_RELEASED` **does not** prove M02 pre-End `AUDIBLE_OWNERSHIP_STATE=MULTICAST_ACTIVE`.

### C1Q VALID TOPOLOGY (frozen for any subsequent C1Q qualification)

```text
C1Q VALID TOPOLOGY

M01 = host
M02 = sole participant / DUT
M03 = MUST NOT JOIN

No additional Meeting participant is permitted.
```

### C1Q Run #2 — FROZEN PASS (2026-09-16)

Evidence: `talkback/logs/rc3-c1q-field-run2-20260916-115848/C1Q_FIELD_VERDICT.txt`  
Session: `cf958d93-4172-4ad3-8867-054e108c1ff1`

```text
C1Q Run #2              = PASS
  valid duo (joined=2; TopK=M01,M02)
  MeetingExists(M02) PASS
  SHADOW_SESSION_STARTED PASS
  M02 pre-End MULTICAST_ACTIVE PASS (Gate 2)
  Normal End EXECUTED
  SESSION_END_OWNERSHIP_RELEASED PASS (M01/M02)
  no zombie · no FATAL · no UNKNOWN_SIGNER_KEY

C1                      = PASS
Attempt consumed        = YES
RC-3                    = PASS / CLOSED
RC-4                    = UNLOCKED
```

Prior contaminated capture `rc3-c1q-field-run2-20260916-115451` = `INVALID / MONITOR_FALSE_STOP` (assistant group-mesh misread); **not consumed**.

```text
C1Q Run #2 execution     CLOSED (PASS) · no rerun
Product changes          NOT AUTHORIZED
M03 media-key RCA        ADJUDICATED / CLOSED (Track B Run #1 verdict)
Meeting Audio Asymmetry  OPEN / DEFERRED (Phase A COMPLETE; Phase B NOT AUTHORIZED)
```

---

## RC-3-C2 — Fatal Rollback (REQUIRED, source-proven)

**No release field fault injection.** No new hooks.

Acceptable evidence when RC-3-B PASS and `AudibleOwnershipControllerTest.fatalFailure_triggersRollbackFromMulticast` covers:

```text
MULTICAST_ACTIVE
→ onReplacementRuntimeFatal
→ ANCHOR_ACTIVE restored
→ multicast production released
```

**Verdict:** `C2_FATAL_ROLLBACK = PASS / SOURCE-PROVEN | FAIL`

If B PASS but fatal transition not covered → **STOP** · do not field · do not add hooks.

---

## RC-3-D — Operational Package Rollback (NON-BLOCKING)

Qualified **anchor-only / rollback deployment artifact** minimum:

```text
applicationId        == com.talkback.appprod
versionCode          != 100001 (or explicitly registered rollback artifact)
certificate SHA256   == RC cert SHA256 (same signing lineage)
APK bytes + SHA256   retained and preflight-verified
runtime default      disables audible multicast cutover OR documented anchor-only path
```

If none exists:

```text
D_OPERATIONAL_PACKAGE_ROLLBACK = NOT_EXECUTED / NO_QUALIFIED_ARTIFACT
→ non-blocking
```

Record as **rollback strategy** only — not a verified capability until executed.

**Verdict:** `PASS | NOT_EXECUTED / NO_QUALIFIED_ARTIFACT`

---

## Closure rule

```text
A  MECHANISM_AUDIT              = PASS
B  SOURCE_REGRESSION            = PASS
C1 / C1Q NORMAL_TEARDOWN_CLOSURE = PASS   (valid duo qualification; supersedes C1 + C1R + C1R2 gap)
C2 FATAL_ROLLBACK               = PASS / SOURCE-PROVEN

D  OPERATIONAL_PACKAGE_ROLLBACK  = PASS
   OR NOT_EXECUTED / NO_QUALIFIED_ARTIFACT

→ RC-3 PASS / CLOSED → RC-4 UNLOCKED

C1R2 TEST_NOT_STARTED                      → RC-3 BLOCKED (retry Phase 0 OK)
C1R2 QUALIFICATION PRECONDITION GAP        → RC-3 BLOCKED → C1Q Phase -1
PHASE_MINUS_1 not PASS                       → RC-3 BLOCKED → STOP
C1Q INVALID / TOPOLOGY_DEVIATION             → RC-3 BLOCKED ON VALID C1 QUALIFICATION
                                               (attempt NOT consumed; C1 NOT ADJUDICATED)
C1Q Gate 2 (M02 MULTICAST_ACTIVE) not proven → STOP before End · preserve evidence
C1 / C1Q FAIL / TEARDOWN_REVERSION_DEFECT  → RC-3 FAIL → STOP
any A/B/C2 REQUIRED FAIL                   → RC-3 FAIL → STOP
```

---

## Driver

| Item | Path |
|------|------|
| Script | `talkback/scripts/rc3-operational-rollback-field-first-run.ps1` |
| Full first run | default (A+B+C2+D+C1) — historical |
| C1R2 | `-C1R2 -PriorLogRoot <first-run-logdir>` (manual Meeting; no R2 driver) |
| Resume C1R2 after End | `-C1R2 -ResumeC1R2 -LogRoot <c1r2-logdir>` |
| Skip field C1 | `-SkipC1` |
| First C1 | `talkback/logs/rc3-operational-rollback-20260916-095738/` |
| C1R (orch fail) | `talkback/logs/rc3-operational-rollback-c1r-manual-20260916/` |
| C1R2 logs | `talkback/logs/rc3-operational-rollback-c1r2-20260916-102453/` |
| C1Q Phase -1 | `talkback/logs/rc3-c1q-phase-minus1-20260916-110044/` |
| C1Q Run #1 (FROZEN INVALID) | `talkback/logs/rc3-c1q-field-manual-20260916-112211/` |
| C1Q Run #2 (FROZEN PASS) | `talkback/logs/rc3-c1q-field-run2-20260916-115848/` |

Artifacts: `RC3C1R2_MEETING_EXISTS.txt` · `RC3C1R2_GATE.txt` · `RC3C1R2_NORMAL_TEARDOWN.txt` · `M02-logcat.txt` · `RC3_RESULT.txt` · C1Q: `C1Q_FIELD_VERDICT.txt`.

**Do not use** `-InvokeR2Driver` (removed).

---

## RC-3 sign-off ledger (for RC-4)

```text
Code-level automatic rollback      VERIFIED (C2 / B)
Normal teardown ownership closure  VERIFIED (C1Q Run #2 PASS)
C1 / C1R / C1R2                    PRESERVED / SUPERSEDED PATH → C1Q PASS
C1Q Run #1                         FROZEN INVALID / TOPOLOGY_DEVIATION (not consumed)
C1Q Run #2                         PASS · FROZEN
Artifact-level package rollback    NOT QUALIFIED unless D executed PASS
Release debug rollback control     NOT AVAILABLE (inherited RC-1)
Meeting GA                         FROZEN (M03 media-key = SEPARATE)
RC-3                               PASS / CLOSED
RC-4                               PASS / CLOSED (see rc4-engineering-rc-sign-off.md)
```

---

## References

- ADR-0062: `docs/adr/0062-replacement-cutover-rc1-audible-ownership-transition.md`
- RC-1 manifest: `artifacts/rc1/talkback-1.0.0-rc.1-release-manifest.txt`
- RC-2 run card: `rc2-install-upgrade-coldstart-run-card.md`
- C1Q Phase -1: `rc3-c1q-phase-minus1-run-card.md`
- R2-B evidence policy: `meeting-product-r2-release-smoke-run-card.md`
