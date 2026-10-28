# RC-4 — Engineering RC Sign-off

**Engineering RC stage:** **COMPLETE / FROZEN** (2026-09-16)  
**Production release planning:** **P0 contract FROZEN** · **P1 delta assessment ZERO** — see `production-release-planning-p0-artifact-transition-contract.md` · `production-release-planning-p1-delta-assessment.md`  
**Prerequisite:** RC-1 COMPLETE · RC-2 PASS · RC-3 PASS/CLOSED · Track B Run #1 ADJUDICATED  
**Artifact:** Talkback `1.0.0-rc.1` (`versionCode=100001`)  
**Does NOT:** add qualification · change rc.1 bytes · reopen Meeting GA · close Meeting product open tracks

```text
RC-1  Artifact Identity / Config Freeze   CLOSED
RC-2  Install / Cold-Start Qualification  CLOSED / PASS
RC-3  Operational Rollback Check           CLOSED / PASS
RC-4  Engineering RC Sign-off              PASS / CLOSED

Track B — M03 media-key / mixed-path     ADJUDICATED / CLOSED
Meeting Audio Asymmetry                    OPEN / DEFERRED (Phase A COMPLETE; non-blocking)
Meeting product work                     ≠ COMPLETE
```

---

## Purpose

Sign off **release qualification already completed** for the fixed Engineering RC artifact.

```text
This sign-off means:
  The Engineering RC qualification chain for 1.0.0-rc.1 is complete.

This sign-off does NOT mean:
  Meeting product has no open issues.
  Production-signed GA 1.0.0 is released.
  All human observations are adjudicated.
```

RC-4 adds **no new field runs, unit suites, or provisioning experiments.**

---

## 1. Artifact Identity

**Authoritative manifest:** `talkback/artifacts/rc1/talkback-1.0.0-rc.1-release-manifest.txt`

```text
Product:        Talkback / 蜂鸟对讲
Release:        1.0.0-rc.1
versionCode:    100001
applicationId:  com.talkback.appprod

Source SHA:     0a74d87142eae6176de3f33a42b306c1aa3b27e8
gitDirty:       false
baselineChain:  faf8884 → 1ab10ae → c2fb0fa → 0a74d87

APK file:       talkback-1.0.0-rc.1-release.apk
APK SHA256:     056E47AC8CED10680C39A921C74064355055B6B29E9A2F8DAABB9FF43E1FD0E1
APK size:       52581770 bytes

Signing:        LAB_NON_PRODUCTION
certificate:    Android Debug
Cert SHA256:    67c049784d139897b28887c4ab6cd3b4e34c10cf8fcd008870d3e6fb920bb283
productionSigningFrozen: false

Build:          assembleRelease --offline (2026-09-16T01:13:02Z)
Runtime audit:  PASS
```

**Delivery classification:** Engineering Release Candidate only. **Not** a production-signed GA `1.0.0` artifact.

**Identity invalidation:** any source change + rebuild voids this sign-off. Successor artifact requires new RC identity (typically `rc.2`) and corresponding qualification.

---

## 2. Qualification Gates

RC-4 **inherits** frozen PASS evidence. No gate is re-run at sign-off.

### RC-1 — Artifact Identity & Configuration Freeze

```text
RC-1.0 reproducible baseline     CLOSED (c2fb0fa)
RC-1.1 identity + runtime audit  CLOSED (0a74d87)
RC-1                             COMPLETE
```

### RC-2 — Installation / Upgrade / Cold-Start

**Run card:** `rc2-install-upgrade-coldstart-run-card.md`  
**Primary DUT:** M02 (`DSJ-2407041`)

| Gate | Verdict | Evidence |
|------|---------|----------|
| FRESH_INSTALL | PASS | `talkback/logs/rc2-install-qual-manual-20260916-093257/` |
| B1 force-stop cold start | PASS | `talkback/logs/rc2-b1-20260916-093918/` |
| B2 device reboot cold start | PASS | `talkback/logs/rc2-b2-20260916-094450/` |
| COLD_START (combined) | PASS | `RC2_COLD_START_RESULT.txt` |
| CRASH_SMOKE | PASS | same chain |
| UPGRADE | NOT_EXECUTED / NO_QUALIFIED_BASELINE | non-blocking |

```text
RC-2 = PASS / CLOSED
```

### Meeting integration qualification (pre-RC chain — inherited)

From RC-1 manifest and frozen GA/integration records:

```text
meetingGaIntegration           COMPLETE
r2aRealReleaseFieldSmoke       PASS
r2bKillSwitch                  PASS
rc10ReproducibleBaseline       CLOSED (c2fb0fa)
```

GA authorization review: `meeting-product-ga-authorization-review.md` — **FROZEN / AUTHORIZED** (2026-09-15). RC-4 does not re-litigate G1/G2/G3a.

### RC-3 — Operational Rollback Check

**Run card:** `rc3-operational-rollback-run-card.md`

| Gate | Verdict | Evidence |
|------|---------|----------|
| A MECHANISM_AUDIT | PASS | `talkback/logs/rc3-operational-rollback-20260916-095738/RC3A_MECHANISM_AUDIT.txt` |
| B SOURCE_REGRESSION | PASS | `talkback/logs/rc3-operational-rollback-20260916-095738/RC3B_SOURCE_REGRESSION.txt` |
| C2 FATAL_ROLLBACK | PASS / SOURCE-PROVEN | B + `AudibleOwnershipControllerTest.fatalFailure_triggersRollbackFromMulticast` |
| C1Q Phase -1 | PASS | `talkback/logs/rc3-c1q-phase-minus1-20260916-110044/` |
| C1Q Run #2 normal teardown | PASS | `talkback/logs/rc3-c1q-field-run2-20260916-115848/C1Q_FIELD_VERDICT.txt` |
| D package rollback | NOT_EXECUTED / NO_QUALIFIED_ARTIFACT | non-blocking |

Preserved non-qualifying attempts (not consumed):

```text
C1 first run          FAIL / INVALID TEST SETUP
C1R                   INVALID / PRECONDITION_NOT_ESTABLISHED
C1R2                  QUALIFICATION PRECONDITION GAP → superseded by C1Q Phase -1
C1Q Run #1            INVALID / TOPOLOGY_DEVIATION (not consumed)
```

```text
RC-3 = PASS / CLOSED
```

### Track B — Run #1 (contract alignment; not an RC gate)

```text
M03 media-key / SKIPPED_LOOKUP / mixed-path
= ADJUDICATED / CLOSED
classification = PROVISIONING / DEPLOYMENT-SCOPE MISMATCH
verdict = talkback/logs/rc3-c1q-field-manual-20260916-112211/TRACK_B_RUN1_VERDICT.txt
contract memo = meeting-product-track-b-m03-media-key-contract-alignment-memo.md
```

Track B close **does not** add an RC-4 gate. It removes an open contract blocker for Engineering RC sign-off.

---

## 3. Authorized Production Envelope

Inherited from RC-1 manifest + GA authorization review. RC-4 **does not expand** envelope.

```text
Default Meeting media path:     ADR-0058 multicast (when RC1 cutover preconditions met)
Rollback path retained:         ADR-0056 Anchor/WebRTC
Node count:                     N <= 9 admitted sources
Underlay:                       real same-L3 mesh (>=3 nodes field-proven for GA path)
Stability minimum (GA gate):    >=15 min continuous production multicast
Extended soak G3b >=2h:         NOT a release requirement (retirement track only)
N > 9:                          NOT AUTHORIZED
ADR-0056 deletion:              NOT AUTHORIZED
Production signing:             NOT AUTHORIZED (LAB_NON_PRODUCTION artifact)
Release debug rollback control: NOT AVAILABLE (inherited RC-1)
```

**Deployment prerequisite (contract clarification — Track B P1–P4):**

Any Module in operational `DeploymentScope` requiring ADR-0058 multicast media eligibility **must** have Profile01 establishment bindings provisioned before runtime. Operator/deployment responsibility — not runtime auto-provision.

**Correctly provisioned deployment** is required for intended GA multicast steady-state. ADR-0056 retention does **not** authorize mixed ADR-0056/ADR-0058 operation as the intended steady-state for such deployments.

---

## 4. Known / Non-blocking Risks & Open Observations

These items are **explicitly recorded**. They do **not** block RC-4 sign-off for `1.0.0-rc.1`.

### Meeting Audio Asymmetry — OPEN / DEFERRED (Phase A COMPLETE)

```text
Observed in:     invalid-topology C1Q Run #1
                 talkback/logs/rc3-c1q-field-manual-20260916-112211/
Human note:      "only M03 audible"

Phase A RCA:     COMPLETE (read-only)
  verdict:       talkback/logs/rc3-c1q-field-manual-20260916-112211/AUDIO_ASYMMETRY_PHASE_A_RCA.txt

Software multicast playout (M01/M02):
  TX → RX → jitter → Top-K/decode → mix → AudioTrack writes
  = HEALTHY / ESTABLISHED in available evidence

M01/M02 multicast playout defect     = NOT ESTABLISHED
Human-audible root cause             = NOT ADJUDICATED
Product audio defect                 = NOT ESTABLISHED

Status:          OPEN / NON-BLOCKING / DEFERRED (NOT CLOSED)
Phase B:         NOT AUTHORIZED

M03 media-key / mixed-path cause:
  separately adjudicated and CLOSED as
  PROVISIONING / DEPLOYMENT-SCOPE MISMATCH
  (Track B Run #1 verdict)

Audio observation itself:
  NOT proven equivalent to media-key cause
  NOT a blocker for Engineering RC sign-off or production release planning

Future Phase B (when triggered on legal GA topology):
  operator listening position · device output state · stimulus matrix
  do NOT re-litigate network/crypto/Top-K
```

### R2-A human listening note (RC-1 manifest)

```text
During R2-A, human listening was inconsistent with
instrumented multicast playout evidence on M01/M02.
Disposition: non-blocking at RC-1; unchanged at RC-4.
```

### RC-2 upgrade path

```text
UPGRADE = NOT_EXECUTED / NO_QUALIFIED_BASELINE
Disposition: non-blocking for RC-2 and RC-4
```

### RC-3 artifact-level package rollback

```text
D_OPERATIONAL_PACKAGE_ROLLBACK = NOT_EXECUTED / NO_QUALIFIED_ARTIFACT
Disposition: non-blocking; rollback strategy recorded only
Operational fallback remains ADR-0056 in-session + redeploy policy
```

### Confirmed defects requiring rc.1 code change

```text
NONE at sign-off time
```

If future work establishes a production code defect, successor artifact (typically `rc.2`) + targeted qualification is required. rc.1 sign-off does not auto-carry forward.

---

## 5. Out-of-scope / Deferred Work

RC-4 sign-off **does not authorize or schedule**:

```text
New qualification for rc.1
Production keystore / production-signed GA 1.0.0 release
Meeting GA re-litigation (G1/G2/G3a)
G3b >=2h extended soak (retirement track only)
ADR-0056 deletion / retirement review
N > 9 qualification envelope
RC-3 reopen
Track B re-litigation (CLOSED)
Meeting Audio Asymmetry Phase A RCA          COMPLETE (deferred)
Meeting Audio Asymmetry Phase B field        NOT AUTHORIZED
Hostile-RF / G-RES-2 execution track
ADR-0039 owner conflict (independent track)
WiFi recovery investigation reopen
Synthetic upgrade baseline fabrication for RC-2-C
```

**Meeting product posture:**

```text
ADR-0058 GA default path     = FROZEN / AUTHORIZED (architecture)
Meeting product completion   ≠ implied by RC-4
Open product observation     = Meeting Audio Asymmetry only (at sign-off)
```

---

## Sign-off verdict

```text
RC-4 ENGINEERING RC SIGN-OFF = PASS / CLOSED

Signed artifact:
  Talkback 1.0.0-rc.1
  versionCode 100001
  source 0a74d87142eae6176de3f33a42b306c1aa3b27e8
  APK SHA256 056E47AC8CED10680C39A921C74064355055B6B29E9A2F8DAABB9FF43E1FD0E1

Meaning:
  Engineering RC release qualification chain COMPLETE for this artifact identity.
  Authorized for lab Engineering RC deployment within §3 envelope.
  LAB_NON_PRODUCTION signing — not production GA delivery.

Not implied:
  Meeting product has zero open issues.
  Meeting Audio Asymmetry is closed.
  Production GA 1.0.0 is released.
```

**Concise artifact:** `talkback/artifacts/rc1/RC4_ENGINEERING_RC_SIGN_OFF.txt`

---

## References

| Document | Role |
|----------|------|
| `talkback/artifacts/rc1/talkback-1.0.0-rc.1-release-manifest.txt` | RC-1 identity |
| `rc2-install-upgrade-coldstart-run-card.md` | RC-2 gates |
| `rc3-operational-rollback-run-card.md` | RC-3 gates + ledger |
| `meeting-product-ga-authorization-review.md` | GA envelope |
| `meeting-product-track-b-m03-media-key-contract-alignment-memo.md` | P1–P4 + Track B contract |
| `talkback/logs/rc3-c1q-field-manual-20260916-112211/TRACK_B_RUN1_VERDICT.txt` | Track B close |
| `talkback/logs/rc3-c1q-field-run2-20260916-115848/C1Q_FIELD_VERDICT.txt` | RC-3 C1Q PASS |
