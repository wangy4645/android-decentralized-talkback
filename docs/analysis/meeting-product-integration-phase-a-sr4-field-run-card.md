# PA-SR4 — Field Run Card (Real Shadow Media Activity Gate)

**Authorization:** `meeting-product-integration-phase-a-sr4-field-authorization.md`  
**OBS audit:** `meeting-product-integration-phase-a-sr4-obs-sufficiency-audit.md`  
**Adjudication (parent):** `meeting-product-integration-phase-a-shadow-runtime-adjudication-criteria.md` § PA-SR4  
**Status:** **AUTHORIZED FOR FIELD**

```text
PA-SR3          CLOSED / FIELD-PROVEN
PA-SR4          AUTHORIZED FOR FIELD (this run card)
PA-SR5          HOLD — driver SKIP leave/rejoin
PA-SR6          HOLD — driver SKIP meeting end
ADR-0056        KEEP
cutover         NOT AUTHORIZED
```

---

## 1. Question this gate answers

After SR1–SR3 establish the Meeting media **control plane**, does the **shadow media runtime** receive real ingress and produce shadow playout activity **without** taking production AudioTrack ownership?

```text
SR1–SR3 prerequisite (same session)
→ host PTT / real multicast stimulus
→ INGRESS_ACTIVITY (packets + jitter/decoders)
→ PLAYOUT_ACTIVITY (successfulPlayoutWrites > 0, SHADOW_METRICS_ONLY)
→ ADR-0056 production AudioTrack unchanged
```

**Not in scope:** PA-SR5 leave/rejoin · PA-SR6 teardown · cutover · reverse-direction MEMBER_MEDIA_READY closure.

---

## 2. Hard boundaries

| # | Boundary |
|---|----------|
| **1** | PRE-G / PRE-I / PRE-J + J0 prerequisite must PASS → else `INVALID_*` |
| **2** | SR3 chain must reach `MEMBER_MEDIA_READY module=M02 outcome=APPLIED` in same run → else `FAIL_PA_SR4` prerequisite |
| **3** | **PA-SR5 / PA-SR6** — driver **SKIPS** leave/end phases; incidental OBS recorded observe-only |
| **4** | No manual replay / harness inject of Profile01 facts |
| **5** | M01 `MEMBER_MEDIA_READY(M02)=DEFERRED_NO_BINDING` — **out of scope** unless SR3 prerequisite also fails |

---

## 3. Prerequisites

| Prereq | Requirement |
|--------|-------------|
| PA-SR3 | CLOSED / FIELD-PROVEN |
| PR-PA-SR-B1 + B1-R2 | CLOSED / UNIT+FIELD-PROVEN |
| Meeting J0 | CLOSED / FIELD-PROVEN |
| Topology | M01 host · M02 peer |
| `MeetingProductMediaShadow.enabled` | true |
| Build | includes B1-R2 post-bind SOURCE republication |

---

## 4. Driver plan (to implement)

**Entry script (new):** `talkback/scripts/phase-a-sr4-field-first-run.ps1`

```powershell
# Wrapper → p1c-package-field-first-run.ps1 -DriverMode PaSr4
# Adjudicator → phase-a-sr4-field-adjudicate.ps1
```

**Orchestration reuse:** `p1c-package-field-first-run.ps1` with `DriverMode=PaSr4`:

| Phase | Behavior |
|-------|----------|
| Build + install | Same as PA-SR3 |
| PRE-1…EP probe | Same |
| PRE-G / PRE-I / PRE-J | Same |
| J0 continuation wait | Same (`PositiveWaitSec`) |
| **PA-SR4 observation** | `ShadowObservationSec` (default 45s) + **3× PTT burst** on M01 (`btnPtt`) |
| PA-SR5 leave | **SKIPPED** (`driverLeavePhase=SKIPPED`) |
| PA-SR5 rejoin | **SKIPPED** (`driverRejoinPhase=SKIPPED`) |
| PA-SR6 end | **SKIPPED** (`driverEndPhase=SKIPPED`) |

**Log capture:** `talkback/logs/phase-a-sr4-field-<timestamp>/` · M01 + M02 logcat · `SHADOW_RUNTIME_GATE.txt` · manifest gates.

---

## 5. Primary witness direction

```text
M01 host: PTT transmits → multicast ingress on peer shadow path
M02 peer: INGRESS_ACTIVITY + PLAYOUT_ACTIVITY for sessionId=H
          (either device log acceptable per adjudicator)
```

Host may also show activity; **either M01 or M02** log satisfying SR4 parsers is sufficient.

---

## 6. Frozen observable chain

```text
(Prerequisite — SR3)
PROFILE01_ORIGIN_EMIT factType=9 outcome=EMITTED
→ peer WIRE_INGRESS factType=9 outcome=APPLIED
→ SHADOW hook=MEMBER_MEDIA_READY session=H module=M02 outcome=APPLIED

(SR4 — gate-critical)
PROFILE01_SHADOW_RUNTIME phase=INGRESS_ACTIVITY sessionId=H
  packetsReceived>0 OR ingressAccepted>0
  activeJitterSources>=1 OR liveDecoders>=1
  audioTrackOwner=SHADOW_METRICS_ONLY

PROFILE01_SHADOW_RUNTIME phase=PLAYOUT_ACTIVITY sessionId=H
  successfulPlayoutWrites>0
  audioTrackOwner=SHADOW_METRICS_ONLY
```

**Fence (expected):** `AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE` may appear — not FAIL.

---

## 7. Adjudicator plan (to implement)

**Script (new):** `talkback/scripts/phase-a-sr4-field-adjudicate.ps1`

| Check | Routing |
|-------|---------|
| GROUP / INVITE / JOIN gates | `INVALID_*` |
| CREATION APPLIED | `INVALID_PREREQUISITE` if missing |
| SR3 `MEMBER_MEDIA_READY M02 APPLIED` | `FAIL_PA_SR4` if missing |
| SR4 ingress + playout parsers | reuse `Test-IngressActivityLine` / `Test-PlayoutActivityLine` from `phase-a-shadow-runtime-adjudicate.ps1` |
| Shadow `audioTrackOwner=PRODUCTION*` | `FAIL_PA_SR4` fence |
| SR5 / SR6 lines | write `PA_SR4_OBSERVE_ONLY.txt` — no PASS/FAIL |

**Output:** `ADJUDICATION.txt` · `PA_SR4_IDENTITY.txt` (sessionId, conferenceId, ingress/playout line refs)

---

## 8. Verdict routing

| Outcome | Next |
|---------|------|
| `PASS_PA_SR4` | PA-SR4 field closure doc · authorize PA-SR5 run card |
| `FAIL_PA_SR4` | Runtime execution RCA — **not** SOURCE/B1 reopen |
| `INVALID_*` | Fix harness/device · re-run |

---

## 9. Execution command

```powershell
.\talkback\scripts\phase-a-sr4-field-first-run.ps1
```

**Harness status (2026-09-09):** implemented.

| Script | Role |
|--------|------|
| `p1c-package-field-first-run.ps1 -DriverMode PaSr4` | Orchestration · 45s obs · 3× M01 PTT stimulus · SR5/SR6 SKIP |
| `phase-a-sr4-field-first-run.ps1` | Wrapper |
| `phase-a-sr4-field-adjudicate.ps1` | SR4 gate-critical · SR5/SR6 observe-only |

Synthetic self-test fixtures: `talkback/scripts/fixtures/phase-a-sr4-field/` (`pass-synthetic` · `fail-no-activity` · `fail-ownership` · `invalid-preg` · `invalid-no-sr3`) — **SELFTEST_OK**.
---

## 10. References

- Synthetic PASS fixture: `talkback/scripts/fixtures/phase-a-shadow-runtime/pass-synthetic/`
- Prior SR4 FAIL (SR3 open): `meeting-product-integration-phase-a-shadow-runtime-field-result-20260909-111359.md`
- SR3 closure: `meeting-product-integration-phase-a-sr3-field-closure.md`
