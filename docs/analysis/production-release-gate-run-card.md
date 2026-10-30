# Production Release Gate — Run Card (Signing-Only Path)

**Status:** **ARCHIVED · PR-FINAL PASS / CLOSED · Talkback 1.0.0 PRODUCTION RELEASE AUTHORIZED** (2026-09-16)  
**Posture:** qualification chain COMPLETE — no further gates / Meeting tests / insurance runs on this artifact identity  
**Prerequisite:** P0 contract FROZEN · P1 `codeDeltaAssessment=ZERO` FROZEN · Engineering RC COMPLETE (RC-1..RC-4 on `1.0.0-rc.1`)  
**Path:** signing-only / artifact-transition from `0a74d87` — **not** delta impact adjudication

```text
P0 Contract              FROZEN
P1 Delta Assessment      ZERO / FROZEN
versionName              1.0.0   FROZEN
versionCode              100002  FROZEN
PR-1                     PASS / FROZEN
  evidence: talkback/logs/production-pr1-20260916-135514/PR1_IDENTITY_RUNTIME_AUDIT.txt
  manifest: artifacts/production/talkback-1.0.0-pr1-presign-manifest.txt
PR-SIGN                  PASS / CLOSED
  evidence: talkback/logs/production-pr-sign-20260916-150300/PR_SIGN_VERDICT.txt
  manifest: artifacts/production/talkback-1.0.0-release-manifest.txt
  final APK SHA256: 5E5F5BE758A046A5B67E4A3B952E5EC8FEB67A1F776D894B2A06CE22B1A0A146
  cert SHA256: 6F430BEF82F1F3CD164DEC1FE44DBC88FAEF811BEEF526D5351D23D7982BC378
PR-SIGN-P0               PASS WITH OPERATIONAL RISK
  evidence: talkback/logs/production-pr-sign-p0-20260916-145541/PR_SIGN_P0_IDENTITY.txt
  offline backup: PENDING / NON-BLOCKING
PR-SIGN-P0B              WITHDRAWN (backup hard gate removed 2026-09-16)
PR-2                     PASS / CLOSED
  DUT: M02 DSJ-2407041
  evidence: talkback/logs/production-pr2-20260916-150500/PR2_VERDICT.txt
  UPGRADE: NOT_EXECUTED / NO_QUALIFIED_PRODUCTION_LINEAGE_BASELINE / NON-BLOCKING
PR-INH                   PASS / CLOSED
  evidence: talkback/logs/production-pr-inh-20260916-150800/PR_INHERITANCE_LEDGER.txt
PR-FINAL                 PASS / CLOSED
  evidence: talkback/logs/production-pr-final-20260916-151000/PRODUCTION_RELEASE_SIGN_OFF.txt
  Talkback 1.0.0 = PRODUCTION RELEASE AUTHORIZED (ENTERPRISE_SIDELOAD)
```

**Does NOT authorize:** inventing LAB→PRODUCTION by debug cert · N>9 · ADR-0056 deletion · closing Audio Asymmetry · Play App Signing dual-key model

---

## Goal

Qualify a **production-signed `1.0.0` candidate** derived from qualified rc.1 **source** with **ZERO production behavior delta** — using **artifact-delta-driven** gates only.

```text
Qualified rc.1 lineage (0a74d87)
  + production signing / version identity / new APK bytes
  → PR-1 .. PR-FINAL
  → Production Release 1.0.0 sign-off
```

**Not the goal:** re-prove Meeting GA field campaigns · re-run RC-3 C1Q full chain · close Audio Asymmetry human layer

---

## Terminology fence (FROZEN)

Do **not** conflate these gates in prose or verdicts:

```text
ADR-0058 Meeting GA
  = architecture / product-path authorization
  = frozen integration + field evidence @ source baseline
  ≠ Production Release sign-off

Production Release 1.0.0
  = artifact release authorization
  = PR-1 .. PR-FINAL on production-signed candidate bytes
  ≠ Meeting GA re-litigation
```

```text
RC-4 Engineering RC sign-off (rc.1)
  ≠ PR-FINAL Production Release sign-off
```

---

## Gate chain (FROZEN order)

```text
PR-1     Production Artifact Identity & Runtime Audit
   ↓
PR-SIGN  Production Signing Gate
   ↓
PR-2     Production Artifact Install / Cold-start
   ↓
PR-INH   Qualification Inheritance Ledger
   ↓
PR-FINAL Production Release Sign-off
```

Each gate produces a **frozen verdict artifact**. PR-FINAL MUST NOT combine gates without PR-INH ledger complete.

---

## Parent lineage (reference — frozen)

**Engineering RC:** `talkback/artifacts/rc1/talkback-1.0.0-rc.1-release-manifest.txt`

```text
versionName:        1.0.0-rc.1
versionCode:        100001
source SHA:         0a74d87142eae6176de3f33a42b306c1aa3b27e8
rc.1 APK SHA256:    056E47AC8CED10680C39A921C74064355055B6B29E9A2F8DAABB9FF43E1FD0E1
rc.1 cert SHA256:   67c049784d139897b28887c4ab6cd3b4e34c10cf8fcd008870d3e6fb920bb283
Signing class:      LAB_NON_PRODUCTION (Android Debug)
RC-4:               PASS / CLOSED
P1 assessment:      ZERO (PRODUCTION_P1_DELTA_ASSESSMENT.txt)
```

---

## Preconditions (all gates)

```text
codeDeltaAssessment = ZERO (frozen — any later source/config divergence → STOP → delta adjudication)

Production build input tree:
  git commit = 0a74d87142eae6176de3f33a42b306c1aa3b27e8
  gitDirty   = false on production input paths
  NO untracked alternate AARs in build tree
  NO workspace docs/scripts/androidTest-only inputs in build capture

Open observations (carry forward — non-blocking unless policy changes):
  Meeting Audio Asymmetry = OPEN / DEFERRED
  RC-2 lab UPGRADE        = NOT_EXECUTED (rc.1 lineage)
```

**Build tree hygiene (hard rule for PR-1):**

```text
FORBIDDEN in production build workspace:
  untracked libs/pnsrd-inst-1-stream-webrtc-android.aar
  untracked libs/*.partial-arm64
  any AAR not tracked at 0a74d87 for prod path

REQUIRED:
  libs/talkback-production-stream-webrtc-android.aar @ rc.1 tracked content
  clean checkout or equivalent clean capture of 0a74d87 production inputs
  (mirror rc.1 build discipline: dedicated worktree acceptable)
```

---

## Version identity (FROZEN — 2026-09-16, pre-PR-1 execution)

```text
versionName = 1.0.0
versionCode = 100002

Decision artifact:
  PRODUCTION_VERSION_IDENTITY.txt in PR-1 log root
```

`100002` is monotonic vs rc.1 `100001`. Version identity transition is **P0 artifact-transition** — does **not** change `codeDeltaAssessment=ZERO`.

---

## PR-1 — Production Artifact Identity & Runtime Audit

**Purpose:** Freeze **production candidate** four-layer identity + confirm runtime defaults on **production build outputs** (pre- or post-sign per pipeline — manifest records final signed bytes after PR-SIGN if build/sign split).

### PR-1-A — Source / input capture (REQUIRED)

```text
Record:
  parentEngineeringRc=1.0.0-rc.1
  parentSourceSha=0a74d87142eae6176de3f33a42b306c1aa3b27e8
  codeDeltaAssessment=ZERO
  buildGitDirty=false (production paths)
  buildInputTreeRef=<worktree path or clean checkout id>
  webrtcAarSha256=<tracked prod AAR hash at build time>

Preflight:
  production input paths match 0a74d87 (no diff)
  untracked alternate AARs absent from build tree
```

Fail → `PR-1 = FAIL / INPUT_TREE_INVALID` · STOP

### PR-1-B — Build identity assignment (REQUIRED)

Apply only artifact-transition edits authorized by ZERO path:

```text
versionName = 1.0.0
versionCode = <frozen in PRODUCTION_VERSION_IDENTITY.txt>
applicationId = com.talkback.appprod (unchanged unless explicitly authorized)
```

### PR-1-C — Release build (REQUIRED at execution — not authorized now)

```text
assembleRelease --offline (or repo-equivalent frozen pipeline)
Record: APK size · unsigned or signed intermediate per pipeline
```

### PR-1-D — Runtime audit on production candidate bytes (REQUIRED)

Re-run RC-1 runtime audit checklist on **production candidate** (not rc.1 APK):

```text
multicastAudibleEnabledDefault          = true
adr0058MulticastMeetingDefaultPath      = YES
adr0056AnchorRollbackRetained           = YES
debugCutoverBroadcastUnreachableInRelease = YES
testHooksUnreachableInRelease           = YES
```

**Inheritance invalidation rule:**

```text
If ANY audit item diverges from rc.1 equivalent:
  STOP
  → invalidate ZERO inheritance assumption
  → delta adjudication required
  → do NOT proceed to PR-SIGN / PR-INH inherit path
```

### PR-1-E — Production manifest draft (REQUIRED)

Produce: `talkback/artifacts/production/talkback-1.0.0-release-manifest.txt` (path TBD at execution)

Minimum fields:

```text
versionName · versionCode · applicationId
source SHA · gitDirty
parentEngineeringRc · parentSourceSha · codeDeltaAssessment
APK SHA256 (updated after PR-SIGN if signing follows audit on unsigned)
cert SHA256 (after PR-SIGN)
runtimeAuditVerdict
signingClassification=PRODUCTION
```

**Verdict:** `PR-1 = PASS | FAIL | BLOCKED_ON_VERSION_IDENTITY`

---

## PR-SIGN — Production Signing Gate

**Purpose:** Verify **final production-signed bytes** — not merely that a keystore can sign.

**Input:** PR-1 PASS · production manifest draft

### PR-SIGN checks (REQUIRED)

```text
1. Production keystore custody recorded (classification PRODUCTION)
2. Certificate DN + SHA-256 fingerprint registered in manifest
3. apksigner verify --print-certs = PASS on final APK
4. Debug certificate ABSENT from signing lineage
     (cert SHA256 MUST NOT equal rc.1 debug 67c04978…)
5. Manifest identity matches PR-1:
     versionName · versionCode · applicationId · source SHA
6. Final signed APK SHA256 recorded in manifest
7. APK size recorded
```

```text
FORBIDDEN as PASS evidence:
  "gradle signed successfully" without apksigner verify on final bytes
  rc.1 debug certificate
  manifest referencing rc.1 APK hash as production hash
```

**Verdict:** `PR-SIGN = PASS | FAIL | BLOCKED`

```text
PASS    = production-signed bytes verified
FAIL    = signing executed and verification/provenance failed
BLOCKED = prerequisite not established (identity/material/backup);
          verification not executed — not a product defect
```

BLOCKED / FAIL → do not PR-2 · do not PR-FINAL

### PR-SIGN-P0 (prerequisite — 2026-09-16)

```text
Distribution: ENTERPRISE_SIDELOAD
Alias:        talkback-production-release
Format:       PKCS12
Keystore:     %USERPROFILE%\.talkback-signing\talkback-production-release.p12
Local read:   PASS
Offline backup: PENDING
Recovery ownership: PENDING (role=Release Signing Custodian)
P0 verdict:   INCOMPLETE / BACKUP PENDING
```

P0 PASS is required before PR-SIGN may sign `1.0.0`. Do not treat P0 incomplete as PR-SIGN FAIL.

---

## PR-2 — Production Artifact Install / Cold-start

**Purpose:** Qualify **production-signed APK** deployment lifecycle — **not** Meeting media.

**Input:** PR-SIGN PASS · exact signed APK SHA256 frozen in manifest

**Primary DUT:** M02 (`DSJ-2407041`) recommended (inherit RC-2 discipline)

### Signing lineage fence (FROZEN)

```text
rc.1 (Debug cert) → production candidate (Production cert)
  = DIFFERENT signing lineage
  = NOT a normal in-place upgrade qualification path

FORBIDDEN:
  treat adb install -r over rc.1 lab APK as PR-2 UPGRADE PASS
  treat rc.1 → production as default upgrade proof
```

### PR-2-A — Fresh install (REQUIRED)

```text
adb uninstall com.talkback.appprod (if present)
→ pm path com.talkback.appprod MUST be absent
→ adb install <exact production-signed APK>
→ preflight APK SHA256 == manifest
→ dumpsys package: versionName/versionCode match manifest
→ grant permissions · normal launch · foreground service smoke
→ no Talkback FATAL in window
```

**Verdict:** `FRESH_INSTALL = PASS | FAIL`

### PR-2-B — Cold start (REQUIRED)

Same DUT · same production APK · no reinstall. After PR-2-A PASS.

```text
B1 force-stop → explicit relaunch → service/runtime OK
B2 device reboot → package retained → explicit relaunch → service OK
```

**Verdict:** `COLD_START = PASS | FAIL` · `CRASH_SMOKE = PASS | FAIL`

### PR-2-C — Upgrade (conditional)

Execute **only if** a **qualified historical production artifact** exists:

```text
Qualified production baseline requires:
  same applicationId (com.talkback.appprod)
  PRODUCTION signing lineage (compatible cert)
  lower versionCode than production candidate
  registered APK SHA256 + cert fingerprint preflight-verified
```

If none exists (expected for first production release):

```text
UPGRADE = NOT_EXECUTED / NO_QUALIFIED_PRODUCTION_LINEAGE_BASELINE
disposition = NON_BLOCKING
```

Becomes **blocking** only if product release policy explicitly promises upgrade from a named historical production version.

**Verdict:** `PR-2 = PASS` when A+B(+D smoke) PASS and C PASS or NOT_EXECUTED non-blocking

---

## PR-INH — Qualification Inheritance Ledger

**Purpose:** Explicit gate separating **inherited qualified lineage** from **production artifact measured gates** — required before PR-FINAL.

**Input:** PR-1 PASS (runtime audit equivalent) · PR-SIGN PASS · PR-2 PASS · P1 ZERO frozen

### Inheritance basis (uniform — FROZEN)

Each `INHERITED` row requires **both**:

```text
codeDeltaAssessment = ZERO
+
production runtime audit = equivalent to rc.1 (PR-1-D PASS)
```

If PR-1-D diverges → **no INHERITED rows** · STOP at delta adjudication

### Ledger template

| ID | Domain | Disposition | Basis | rc.1 / frozen reference |
|----|--------|-------------|-------|-------------------------|
| INH-01 | ADR-0058 Meeting GA architecture | INHERITED | ZERO + runtime equivalent | `meeting-product-ga-authorization-review.md` |
| INH-02 | G1 scale field evidence | INHERITED | ZERO + runtime equivalent | frozen G1 logs |
| INH-03 | G2 real-mesh field evidence | INHERITED | ZERO + runtime equivalent | frozen G2 logs |
| INH-04 | G3a stability field evidence | INHERITED | ZERO + runtime equivalent | frozen G3a logs |
| INH-05 | R2-A release field smoke | INHERITED | ZERO + runtime equivalent | RC-1 manifest / R2 evidence |
| INH-06 | R2-B kill-switch | INHERITED | ZERO + runtime equivalent | RC-1 manifest / unit evidence |
| INH-07 | RC-3-A mechanism audit | INHERITED | ZERO + runtime equivalent | `0a74d87` call graph evidence |
| INH-08 | RC-3-B source regression | INHERITED | ZERO + runtime equivalent | cutover unit tests @ RC SHA |
| INH-09 | RC-3-C1 normal teardown contract | INHERITED | ZERO + runtime equivalent | C1Q Run #2 verdict |
| INH-10 | RC-3-C2 fatal rollback | INHERITED | ZERO + runtime equivalent | source-proven tests |
| INH-11 | Track B M03 media-key adjudication | INHERITED | ZERO (contract) | TRACK_B_RUN1_VERDICT.txt |
| INH-12 | Audio Asymmetry Phase A (software path) | INHERITED | ZERO (evidence conclusion) | AUDIO_ASYMMETRY_PHASE_A_RCA.txt |

### Not inherited (explicit — FROZEN)

```text
NOT INHERITED — measured only on production artifact or separate lineage:

rc.1 artifact identity (APK hash · versionName 1.0.0-rc.1 · versionCode 100001)
rc.1 certificate / LAB_NON_PRODUCTION signing
RC-4 artifact-level sign-off on rc.1 (lineage reference only)
RC-2 install/cold-start result on rc.1 APK (does not prove production APK)
PR-1 production manifest identity
PR-SIGN production certificate + signed APK hash
PR-2 install/cold-start on production-signed APK
Meeting Audio Asymmetry human-audible layer (remains OPEN / DEFERRED)
```

### Executed on production artifact (PR-INH cross-reference)

| ID | Gate | Disposition | Evidence |
|----|------|-------------|----------|
| EX-01 | PR-1 Identity + runtime audit | EXECUTED | production manifest |
| EX-02 | PR-SIGN | EXECUTED | apksigner verify log |
| EX-03 | PR-2 Install / cold-start | EXECUTED | PR-2 log root |

**Verdict:** `PR-INH = COMPLETE` when all rows adjudicated · no row left `PENDING`

Output artifact: `PR_INHERITANCE_LEDGER.txt` in production release log root

---

## PR-FINAL — Production Release Sign-off

**Purpose:** Authorize **Production Release 1.0.0** artifact for delivery within frozen envelope — **not** Meeting GA re-litigation.

**Input:** PR-1 PASS · PR-SIGN PASS · PR-2 PASS · PR-INH COMPLETE

### Mandatory

```text
1. Production manifest FROZEN (signed APK identity)
2. PR-SIGN PASS on final bytes
3. PR-2 FRESH_INSTALL + COLD_START (+ CRASH_SMOKE) PASS
4. PR-INH ledger COMPLETE (INHERITED vs EXECUTED explicit)
5. codeDeltaAssessment=ZERO recorded in manifest
6. Open observations ledger explicit:
     Meeting Audio Asymmetry = OPEN / DEFERRED (allowed)
7. Authorized envelope unchanged:
     N<=9 · ADR-0058 Meeting default path · ADR-0056 rollback retained
```

### Explicit non-implications

```text
PR-FINAL does NOT:
  Close Meeting Audio Asymmetry human layer
  Re-open Meeting GA field gates
  Transfer RC-4 rc.1 sign-off to production artifact
  Authorize production keystore rotation without new PR cycle
  Authorize N>9 or ADR-0056 deletion
```

**Verdict:** `PR-FINAL = PASS / CLOSED` → Production Release 1.0.0 artifact authorized for delivery planning

Output: `PRODUCTION_RELEASE_SIGN_OFF.txt` + frozen production manifest

---

## Closure rule

```text
PR-1   PASS
PR-SIGN PASS
PR-2   PASS (UPGRADE NOT_EXECUTED OK if no production baseline)
PR-INH COMPLETE
→ PR-FINAL PASS / CLOSED

any REQUIRED FAIL → STOP · no PR-FINAL
PR-1 runtime audit divergence → STOP · delta adjudication
PR-SIGN debug cert detected → FAIL · STOP
PR-2 treating rc.1→production as upgrade PASS → INVALID · STOP
```

---

## Out of scope (all PR gates)

```text
Meeting GA G1/G2/G3a field re-run
R2-A full 3-node smoke re-run
RC-3 C1Q field re-run
Track B / Phase A re-run
Meeting Audio Asymmetry Phase B field
Production code changes
Provisioning experiments
WiFi recovery / RC-3 reopen
Closing deferred open observations by policy silence
```

---

## Log artifacts (at execution — paths TBD)

```text
talkback/logs/production-release-<timestamp>/
  PRODUCTION_VERSION_IDENTITY.txt
  PR1_IDENTITY_RUNTIME_AUDIT.txt
  PR_SIGN_VERIFY.txt
  PR2_RESULT.txt
  PR_INHERITANCE_LEDGER.txt
  PRODUCTION_RELEASE_SIGN_OFF.txt

talkback/artifacts/production/
  talkback-1.0.0-release-manifest.txt
```

---

## Mechanical scope review (run-card self-check — COMPLETE)

| Check | Result |
|-------|--------|
| Gate order PR-1→SIGN→2→INH→FINAL frozen | PASS |
| Signing-only path conditioned on P1 ZERO | PASS |
| PR-1 clean `0a74d87` tree / no untracked AAR rule | PASS |
| PR-SIGN verifies final bytes (apksigner), not keystore alone | PASS |
| PR-2 forbids rc.1 debug→production as upgrade PASS | PASS |
| PR-INH explicit ledger gate (not PR-FINAL prose only) | PASS |
| Meeting GA vs Production Release terminology separated | PASS |
| Audio Asymmetry OPEN/DEFERRED preserved | PASS |
| versionCode TBD until pre-PR-1 execution freeze | PASS |
| No build/sign/install/field execution authorized | PASS |
| Inheritance invalidation on runtime audit divergence | PASS |

**Run-card scope review:** PASS · ready for operator review before PR-1 execution authorization

---

## Authorization boundary

```text
AUTHORIZED:
  This run-card (planning)
  Mechanical scope review (above)

NOT AUTHORIZED:
  PR-1 build / manifest freeze execution
  PR-SIGN / keystore use
  PR-2 install / field
  PR-INH / PR-FINAL execution
  versionCode final freeze (until pre-PR-1 decision)
  code change · Meeting GA reopen
```

**Next decision point:** operator review this run-card → authorize **PR-1 execution** (includes versionCode freeze + clean build tree)

---

## References

| Document | Role |
|----------|------|
| `production-release-planning-p0-artifact-transition-contract.md` | Inheritance rules |
| `production-release-planning-p1-delta-assessment.md` | ZERO assessment |
| `PRODUCTION_P1_DELTA_ASSESSMENT.txt` | Frozen P1 verdict |
| `talkback-1.0.0-rc.1-release-manifest.txt` | rc.1 parent |
| `RC4_ENGINEERING_RC_SIGN_OFF.txt` | Engineering RC complete |
| `rc2-install-upgrade-coldstart-run-card.md` | PR-2 pattern |
| `rc4-engineering-rc-sign-off.md` | RC-4 boundary |
| `AUDIO_ASYMMETRY_PHASE_A_RCA.txt` | Deferred observation |
