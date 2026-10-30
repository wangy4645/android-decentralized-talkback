# Production Release Planning — P0 Artifact Transition & Qualification Inheritance Contract

**Status:** **P0 FROZEN** (2026-09-16) — planning contract only  
**Prerequisite:** Engineering RC qualification **COMPLETE** (RC-1..RC-4 CLOSED on `1.0.0-rc.1`)  
**Mode transition:**

```text
ENGINEERING RC QUALIFICATION     COMPLETE (frozen)
PRODUCTION RELEASE PLANNING      ACTIVE (P0 contract)
```

**Does NOT authorize:** build · production signing · field run · Meeting GA re-litigation · RC-3 reopen · code change

---

## Purpose

Define what must happen to move from **qualified Engineering RC** (`1.0.0-rc.1`, `LAB_NON_PRODUCTION`) to a **production-signed GA `1.0.0` candidate** — without treating RC-4 on rc.1 as production artifact qualification.

```text
RC-4 sign-off on rc.1
  ≠ production artifact qualified
  ≠ production GA 1.0.0 released
```

---

## Core principle (FROZEN)

> If the production candidate has **identical production code and runtime configuration** to rc.1 — only **signing / version identity / artifact packaging** differ — qualification MUST be **artifact-delta-driven**. Do **not** mechanically re-run the full Meeting GA / R2-A / RC-3 field chain.

Conversely:

> Any **production-code or runtime-config delta** before release forms a **new code baseline**. Qualification scope MUST be re-cut from the actual delta — not assumed inherited from rc.1.

**Delta assessment is mandatory** before executing production gates (P0 → P1 execution phase, not authorized here).

---

## Qualified baseline (reference — frozen)

**Engineering RC artifact:** `talkback/artifacts/rc1/talkback-1.0.0-rc.1-release-manifest.txt`

```text
versionName:     1.0.0-rc.1
versionCode:     100001
source SHA:      0a74d87142eae6176de3f33a42b306c1aa3b27e8
APK SHA256:      056E47AC8CED10680C39A921C74064355055B6B29E9A2F8DAABB9FF43E1FD0E1
Signing:         LAB_NON_PRODUCTION (Android Debug)
Cert SHA256:     67c049784d139897b28887c4ab6cd3b4e34c10cf8fcd008870d3e6fb920bb283
RC-4:            PASS / CLOSED
```

**Open / deferred (carry forward — not auto-closed, not auto-blocker):**

```text
Meeting Audio Asymmetry   Phase A COMPLETE · human layer OPEN / DEFERRED
RC-2 UPGRADE              NOT_EXECUTED / NO_QUALIFIED_BASELINE (lab)
RC-3-D package rollback   NOT_QUALIFIED
```

---

## P0-1 — Production artifact identity / version policy

### Identity layers (same model as RC-1)

Every production candidate MUST freeze four layers before qualification execution:

```text
Source identity      git SHA (+ dirty=false policy)
Build identity       versionCode + versionName
Binary identity      APK SHA-256 + size + applicationId
Runtime identity     release defaults / feature gates (RC-1 runtime audit set)
```

### Version policy (proposed — requires operator confirm at P1)

```text
Engineering RC:       1.0.0-rc.1 · versionCode=100001 · LAB signing
Production candidate: 1.0.0      · versionCode=TBD* · PRODUCTION signing

* versionCode policy options (pick one at P1 — not decided in P0):
  A) 100002 — production build distinct from rc.1 codepoint (recommended if both may coexist)
  B) 100001 — only if rc.1 lab APK will never ship and upgrade path is explicitly out of scope
```

### Naming / lineage rules

```text
Production manifest MUST reference:
  parentEngineeringRc=1.0.0-rc.1
  parentSourceSha=0a74d87142eae6176de3f33a42b306c1aa3b27e8
  codeDeltaAssessment=<ZERO | enumerated commits/files>

Any source change → new parent chain → NOT a signing-only transition
Any APK byte change → new binary identity → rc.1 RC-4 does not auto-transfer
```

### Invalidation

```text
Rebuild after source/config change     → new candidate identity
Production signing cert rotation       → new binary identity (even if source unchanged)
RC-4 on rc.1                           → never substitutes production manifest sign-off
```

---

## P0-2 — Production signing & certificate gate

`productionSigningFrozen: false` on rc.1 — production keystore **not yet** part of qualified envelope.

### PR-SIGN gate (new — required before production artifact qualification)

Must establish and record:

```text
1. Production keystore custody + classification (PRODUCTION vs LAB)
2. Certificate DN + SHA-256 fingerprint registered in production manifest
3. Build pipeline uses production signing config exclusively for GA candidate
4. APK signature verify (apksigner / jarsigner) matches registered fingerprint
5. applicationId unchanged unless explicitly authorized (com.talkback.appprod)
6. No debug keystore leakage into production candidate build
```

### Install / upgrade implications

```text
rc.1 (Debug cert) → production candidate (Production cert)
  = different signing lineage
  = NOT an in-place upgrade from rc.1 without explicit upgrade qualification

Lab devices with rc.1 installed:
  fresh install or documented migration path required
  RC-2-C upgrade from rc.1 → production is a NEW gate if offered
```

### Runtime re-audit on production bytes

Even with zero code delta, **re-run RC-1-style runtime audit** on production-signed APK:

```text
multicastAudibleEnabledDefault
adr0058MulticastMeetingDefaultPath
adr0056AnchorRollbackRetained
debugCutoverBroadcastUnreachableInRelease
testHooksUnreachableInRelease
```

Signing change must not alter embedded defaults; audit confirms on **production bytes**, not rc.1 bytes.

---

## P0-3 — Qualification inheritance from rc.1

Inheritance applies **only when codeDeltaAssessment=ZERO** (same `gitCommit`, same runtime config).

### May inherit (by reference — no re-run required)

| Domain | rc.1 evidence | Inheritance condition |
|--------|---------------|------------------------|
| Meeting GA architecture | `meeting-product-ga-authorization-review.md` FROZEN | source unchanged |
| G1/G2/G3a field evidence | frozen logs @ integration baseline | source unchanged |
| R2-A / R2-B integration | RC-1 manifest + frozen field/unit | source unchanged |
| RC-3-A mechanism audit | `0a74d87` call graph | source unchanged |
| RC-3-B source regression | cutover unit tests @ RC SHA | source unchanged |
| RC-3-C2 fatal rollback | source-proven | source unchanged |
| RC-3-C1Q teardown contract | C1Q Run #2 PASS | source unchanged |
| Track B M03 media-key | TRACK_B_RUN1_VERDICT | contract adjudication |
| Audio Asymmetry Phase A | AUDIO_ASYMMETRY_PHASE_A_RCA | software-path conclusion |
| RC-4 Engineering RC | rc.4 sign-off on rc.1 | lineage reference only |

### Inheritance mechanics

```text
Inherited gates MUST cite:
  parentArtifact=1.0.0-rc.1
  parentSourceSha=0a74d87…
  codeDeltaAssessment=ZERO
  inheritanceVerdict=INHERITED / NOT RE-RUN

Inherited ≠ assumed for future code deltas.
```

---

## P0-4 — Qualification required on production artifact

Even with **ZERO code delta**, production artifact is a **new binary identity**. The following MUST execute against **production-signed candidate bytes** (not inherited by default):

### Required (production artifact gates)

| Gate | Rationale | rc.1 analogue |
|------|-----------|---------------|
| **PR-1 Identity freeze** | New APK SHA256 + production cert + manifest | RC-1.1 |
| **PR-1 Runtime audit** | Confirm defaults on production bytes | RC-1 runtime audit |
| **PR-2 Install / cold-start** | Signing affects package identity / install path | RC-2 A/B/D |
| **PR-SIGN** | Production certificate gate | *new* |
| **Delta assessment record** | Explicit ZERO vs non-ZERO cut | *new* |

### Conditional (required if policy demands)

| Gate | When required |
|------|----------------|
| **PR-2-C Upgrade** | If shipping path includes upgrade from any prior production baseline OR from rc.1 lab |
| **PR-3 Rollback spot-check** | Lightweight confirmation on production bytes — not full C1Q rerun if ZERO delta; scope cut at P1 |
| **PR-3-C1Q teardown** | Re-run only if delta touches cutover/teardown/Profile01/playout ownership |

### Must NOT mechanically re-run (ZERO code delta)

```text
Meeting GA G1/G2/G3a field campaigns
R2-A full 3-node smoke
RC-3 full C1Q field qualification chain
Track B / Phase A RCA
Phase -1 reprovisioning (unless trust scope changes)
```

### Non-ZERO code delta

If any production-code or runtime-config change is included before `1.0.0`:

```text
STOP inheritance-by-default
Produce delta-driven qualification matrix:
  which inherited gates remain valid
  which gates reopen (unit / field / provisioning)
  whether new Engineering RC cycle required (rc.2 path)
```

Examples that **reopen** qualification (non-exhaustive):

```text
Meeting/media production code change     → integration + targeted field
Cutover/rollback/ownership change        → RC-3 B + C subset
Profile01/establishment behavior change  → provisioning + media-key gates
Audio/playout pipeline change            → targeted media qualification
versionCode-only with code change        → RC-2 + delta matrix
```

---

## P0-5 — Production release final sign-off conditions

Production GA `1.0.0` release sign-off (**PR-FINAL** — distinct from RC-4) requires:

### Mandatory

```text
1. PR-1  Production artifact identity manifest FROZEN
         (source + version + APK SHA256 + production cert)

2. PR-SIGN Production signing gate PASS

3. DELTA  codeDeltaAssessment documented
         ZERO → inheritance matrix applied
         non-ZERO → delta qualification matrix PASS

4. PR-2  Install / cold-start PASS on production artifact

5. PR-1  Runtime audit PASS on production bytes

6. INHERITANCE LEDGER complete
         each gate marked INHERITED | EXECUTED | NOT APPLICABLE

7. OPEN OBSERVATIONS ledger explicit
         Meeting Audio Asymmetry = OPEN / DEFERRED (allowed)
         no silent downgrade of deferred items

8. Authorized envelope unchanged or explicitly amended
         N<=9 · ADR-0058 default · ADR-0056 rollback retained
```

### Explicit non-requirements (ZERO delta path)

```text
PR-FINAL does NOT require:
  Closing Meeting Audio Asymmetry human layer
  Re-running full Meeting GA field
  Re-running RC-3 C1Q as production blocker
  rc.1 lab APK remaining installable in production fleet
```

### Explicit prohibitions

```text
PR-FINAL MUST NOT:
  Treat RC-4 on rc.1 as PR-FINAL
  Ship production candidate without production manifest
  Inherit qualification without recorded codeDeltaAssessment
  Auto-close deferred Audio Asymmetry because of production planning
  Auto-elevate Audio Asymmetry to blocker without new evidence + ADR
```

---

## Suggested gate map (P1 execution — NOT AUTHORIZED)

Planning reference only; execution requires separate authorization:

```text
ENGINEERING RC (COMPLETE)
  RC-1 .. RC-4 on 1.0.0-rc.1

PRODUCTION RELEASE (NEXT — when authorized)
  PR-SIGN   Production signing & cert registration
  PR-1      Production identity + runtime audit manifest
  DELTA     Code/config delta assessment
  PR-2      Install / cold-start (+ upgrade if in scope)
  PR-3*     Artifact-delta rollback spot-check (if required by delta cut)
  PR-FINAL  Production release sign-off
```

---

## References

| Document | Role |
|----------|------|
| `talkback/artifacts/rc1/talkback-1.0.0-rc.1-release-manifest.txt` | Engineering RC identity |
| `talkback/artifacts/rc1/RC4_ENGINEERING_RC_SIGN_OFF.txt` | RC-4 frozen verdict |
| `rc4-engineering-rc-sign-off.md` | Engineering RC sign-off |
| `rc2-install-upgrade-coldstart-run-card.md` | Install qualification pattern |
| `rc3-operational-rollback-run-card.md` | Rollback qualification pattern |
| `meeting-product-ga-authorization-review.md` | GA envelope |
| `AUDIO_ASYMMETRY_PHASE_A_RCA.txt` | Deferred open observation |

---

## P0 authorization boundary

```text
AUTHORIZED:     Production Release Planning P0 contract (this document)
NOT AUTHORIZED: build · sign · field · code · provisioning · PR gate execution
```

**Engineering RC stage final freeze point preserved.** P1 delta assessment **ZERO**. Production Release chain **COMPLETE**: PR-1 · PR-SIGN · PR-2 · PR-INH · **PR-FINAL PASS** — Talkback **1.0.0 PRODUCTION RELEASE AUTHORIZED** (ENTERPRISE_SIDELOAD). See `production-release-gate-run-card.md` · `artifacts/production/talkback-1.0.0-release-manifest.txt` · `logs/production-pr-final-20260916-151000/PRODUCTION_RELEASE_SIGN_OFF.txt`.
