# Production Release Planning — P1 Delta Assessment

**Status:** **FROZEN** (2026-09-16) — read-only assessment  
**Prerequisite:** P0 contract FROZEN · Engineering RC COMPLETE (`1.0.0-rc.1` @ `0a74d87`)  
**Reference:** `talkback/artifacts/rc1/talkback-1.0.0-rc.1-release-manifest.txt`

```text
codeDeltaAssessment = ZERO

NEXT (planning only): Production gate run-card (signing-only path)
NOT AUTHORIZED: build · sign · install · field qualification
```

**Concise verdict:** `talkback/artifacts/rc1/PRODUCTION_P1_DELTA_ASSESSMENT.txt`

---

## Assessment method

P1 compares **production artifact inputs** at the rc.1 source baseline — not whole-repo dirty status.

```text
Reference:  git 0a74d87142eae6176de3f33a42b306c1aa3b27e8
Workspace:  talkback/talkback HEAD = 0a74d87 (same commit)
Method:     git diff on production input paths + AAR tracking check
            classify workspace-only changes as NON_ARTIFACT_INPUT
```

rc.1 was built from clean baseline capture (`d:\wt-rc1`, manifest). Production build should anchor the same way.

---

## 1. Candidate intent

```text
Product behavior delta:     NONE (planned)

Planned production 1.0.0:
  artifact transition from qualified rc.1
  signing-only path (no authorized production-code fixes)

Excluded from candidate:
  Meeting Audio Asymmetry fix (OPEN / DEFERRED)
  Track B / media-key changes
  ad-hoc workspace fixes

Expected intentional artifact differences (future PR-1):
  versionName     1.0.0-rc.1 → 1.0.0
  versionCode     100001 → TBD (not frozen in P1)
  signing         LAB_NON_PRODUCTION → PRODUCTION
  APK SHA256      new (consequence of above)
```

---

## 2. Input delta

### Production paths — vs rc.1 @ `0a74d87`

| Input class | Paths | Delta vs rc.1 |
|-------------|-------|---------------|
| Production Java/Kotlin | `android-board-talkback/src/main/**` · `talkback-app/src/main/**` | **NONE** |
| AndroidManifest (main) | under `src/main/` | **NONE** |
| Resources (main) | under `src/main/res/` | **NONE** |
| Native (production) | via tracked WebRTC AAR JNI; library cmake targets androidTest only | **NONE** |
| AAR dependency | `libs/talkback-production-stream-webrtc-android.aar` (tracked) | **NONE** (git clean) |
| Gradle production config | `talkback-app/build.gradle.kts` · root `build.gradle.kts` · `gradle.properties` | **NONE** at 0a74d87 |
| BuildConfig-affecting | `buildFeatures.buildConfig=true`; defaults unchanged | **NONE** |
| Runtime defaults | `ReplacementCutoverRc1.multicastAudibleEnabled=true`; RC-1 audit set | **NONE** |

```text
git diff 0a74d87 -- android-board-talkback/src/main talkback-app/src/main
                 *.gradle.kts gradle.properties libs/talkback-production-stream-webrtc-android.aar
= empty
```

### NON_ARTIFACT_INPUT (recorded — do not flip ZERO)

**Tracked workspace modifications (41 files):**

```text
docs/** · scripts/** · CONTEXT.md
android-board-talkback/src/test/** (1 unit test file)
→ NON_ARTIFACT_INPUT
```

**Untracked workspace content (representative):**

```text
docs/analysis/** · scripts/** · artifacts/** · config/**
androidTest/** · talkback-app/src/androidTest/**
webrtc-instrumentation/** (not included in settings.gradle.kts)
→ NON_ARTIFACT_INPUT
```

**Workspace hygiene (build precondition — not behavior delta):**

```text
Untracked: libs/pnsrd-inst-1-stream-webrtc-android.aar
Untracked: libs/talkback-production-stream-webrtc-android.aar.partial-arm64

build.gradle.kts selects tracked prod AAR first.
Production build MUST NOT pick alternate untracked AARs.
Use clean 0a74d87 production input tree for PR-1 execution.
```

### Planned PR-1 inputs (not yet in tree)

```text
production signingConfig / keystore
versionName 1.0.0 · versionCode TBD
→ ARTIFACT_TRANSITION (P0) — not codeDeltaAssessment=DELTA
```

---

## 3. Classification

```text
codeDeltaAssessment = ZERO
```

**Strict meaning:** No production runtime behavior input delta vs qualified rc.1 at `0a74d87`.

Version / signing / APK byte change alone **does not** constitute `DELTA`.

Workspace dirty state **does not** override `ZERO` — all tracked diffs are `NON_ARTIFACT_INPUT`; production paths match rc.1 commit.

---

## 4. Qualification consequence

### ZERO branch — **AUTHORIZED NEXT (planning)**

Write **Production gate run-card** (signing-only / artifact-transition):

```text
PR-1              Production identity + runtime audit manifest
PR-SIGN           Production signing & certificate gate
PR-2              Install / cold-start (+ upgrade if in scope)
INHERITANCE LEDGER per P0
PR-FINAL          Production release sign-off
```

**May inherit (P0, conditioned on ZERO recorded in production manifest):**

```text
Meeting GA G1/G2/G3a · R2-A/B · RC-3 A/B/C2/C1Q contracts
Track B · Audio Asymmetry Phase A software-path conclusions
RC-4 rc.1 lineage (reference only)
```

**Must execute on production bytes (P0 — not inherited by default):**

```text
PR-1 · PR-SIGN · PR-2 · runtime audit on production-signed APK
```

### DELTA branch — **NOT TAKEN**

No impact adjudication required. Do not open rc.2 path from this assessment.

---

## Build execution preconditions (for future PR-1 — not authorized here)

```text
1. git checkout 0a74d87 (or equivalent clean tag) for production input capture
2. Record codeDeltaAssessment=ZERO in production manifest
3. Apply only PR-1 artifact-transition edits (version / signing)
4. Exclude untracked alternate AARs and operator tooling from build tree
5. versionCode policy frozen in PR-1 run-card — not in P1
```

---

## Open observations (unchanged)

```text
Meeting Audio Asymmetry   OPEN / DEFERRED / NON-BLOCKING
                          Phase A COMPLETE · human layer not adjudicated
                          does not change codeDeltaAssessment=ZERO
```

---

## Authorization boundary

```text
P1 Delta Assessment              COMPLETE / FROZEN
Production gate run-card         AUTHORIZED (production-release-gate-run-card.md)
PR-1..PR-FINAL execution         NOT AUTHORIZED
build · sign · install · field   NOT AUTHORIZED
```

---

## References

| Artifact | Role |
|----------|------|
| `production-release-planning-p0-artifact-transition-contract.md` | P0 inheritance rules |
| `talkback-1.0.0-rc.1-release-manifest.txt` | rc.1 baseline |
| `RC4_ENGINEERING_RC_SIGN_OFF.txt` | Engineering RC complete |
| `PRODUCTION_P1_DELTA_ASSESSMENT.txt` | Frozen P1 verdict |
| `production-release-gate-run-card.md` | PR gate run-card |
