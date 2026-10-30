# PA-SR4 — Field Authorization (Real Shadow Media Activity Gate)

**Gate name:** `PA-SR4 — Real Shadow Media Activity Gate`  
**OBS audit:** `meeting-product-integration-phase-a-sr4-obs-sufficiency-audit.md` (**SUFFICIENT**)  
**Run card:** `meeting-product-integration-phase-a-sr4-field-run-card.md`  
**Parent:** `meeting-product-integration-phase-a-shadow-runtime-gate-authorization.md`  
**Prerequisite:** PA-SR3 **CLOSED / FIELD-PROVEN**  
**Signed:** 2026-09-09  
**Status:** **FIELD AUTHORIZED** (PA-SR4 scope only)

```text
PR-PA-SR-B1     CLOSED / UNIT+FIELD-PROVEN
B1-R1           CLOSED / UNIT-PROVEN
B1-R2           CLOSED / UNIT+FIELD-PROVEN
PA-SR1          FIELD-PROVEN
PA-SR2          FIELD-PROVEN
PA-SR3          CLOSED / FIELD-PROVEN
PA-SR4          FIELD AUTHORIZED

PA-SR5          HOLD
PA-SR6          HOLD
ADR-0056        KEEP
Meeting cutover NOT AUTHORIZED
```

---

## Goal (authorized)

Prove under a **real Meeting** session (same topology as SR3):

```text
control/fact/binding correctness (SR1–SR3 — prerequisite in same run)
        ↓
real shadow media runtime execution correctness (SR4 — this gate)
```

**SR4 first proves:** the established media plane **actually runs media** — ingress receives/decodes and shadow playout metrics advance — while **ADR-0056 retains production AudioTrack ownership**.

---

## Frozen PASS chain (PA-SR4 only)

Within one `hostSessionId=H` run, after SR3 prerequisite green:

```text
PROFILE01_SHADOW_RUNTIME phase=INGRESS_ACTIVITY sessionId=H
  packetsReceived>0 OR ingressAccepted>0
  activeJitterSources>=1 OR liveDecoders>=1

PROFILE01_SHADOW_RUNTIME phase=PLAYOUT_ACTIVITY sessionId=H
  successfulPlayoutWrites>0
  audioTrackOwner=SHADOW_METRICS_ONLY
```

**Hard fence (FAIL):** shadow `INGRESS_ACTIVITY` / `PLAYOUT_ACTIVITY` with `audioTrackOwner=PRODUCTION*`.

**Expected (not FAIL):** `AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE` from production seam (ADR-0056).

---

## Authorized scope

### AUTHORIZED

```text
PA-SR4 field first-run (harness + adjudicator)
Reuse PRE-G / PRE-I / PRE-J + J0 prerequisite + SR3 establishment in same session
Shadow observation window + PTT stimulus (behavior-neutral harness)
phase-a-sr4-field-adjudicate.ps1 (SR4 gate-critical; SR5/SR6 observe-only)
Field result doc on PASS or FAIL_PA_SR4
```

### NOT AUTHORIZED

```text
Product code changes (unless post-field RCA + new authorization)
SOURCE / B1 / B1-R1 / B1-R2 reopen
PA-SR5 leave/rejoin closure
PA-SR6 teardown closure
ADR-0056 / Meeting media cutover
M01 reverse MEMBER_MEDIA_READY(M02) as SR3 regression
Manual Profile01 / shadow fact inject
Harness-only republish to force SR4 PASS
```

---

## Hard constraints

| Id | Constraint |
|----|------------|
| **SR4-HC1** | PASS requires **both** qualifying `INGRESS_ACTIVITY` **and** `PLAYOUT_ACTIVITY` on session `H` |
| **SR4-HC2** | `audioTrackOwner=SHADOW_METRICS_ONLY` on playout (and ingress) lines |
| **SR4-HC3** | PRE-G/I/J failure → `INVALID_*` · `PA-SR4=NOT_TESTED` |
| **SR4-HC4** | SR3 not established in same run → `FAIL_PA_SR4` prerequisite, not `INVALID_*` |
| **SR4-HC5** | SR5/SR6 driver phases **SKIPPED** on this run — observe-only if incidental lines appear |
| **SR4-HC6** | `FAIL_PA_SR4` ≠ J0 / Profile01 / SR3 regression unless **new independent evidence** in same run |

---

## Prerequisite checks (same run)

Adjudicator must confirm before SR4 verdict:

```text
PRE-G / PRE-I / PRE-J PASS
J0 continuation: CREATION APPLIED (peer)
PA-SR3 proxy in-session:
  SHADOW hook=MEMBER_MEDIA_READY session=H module=M02 outcome=APPLIED
  (primary witness direction: peer consumed host SOURCE — frozen SR3)
```

SR1/SR2 may be recorded as corroboration; **SR3 APPLIED is the binding prerequisite for SR4 media path.**

---

## Verdict model (PA-SR4 run)

| Verdict | Meaning |
|---------|---------|
| **PASS_PA_SR4** | Prerequisites green · SR4 ingress + playout PASS · no shadow AudioTrack fence violation |
| **FAIL_PA_SR4** | Prerequisites green · SR4 activity missing or fence violated |
| **INVALID_*** | PRE/capture/harness/device insufficiency |

```text
FAIL_PA_SR4 ≠ PA-SR3 regression   (unless same-run SR3 chain also breaks)
FAIL_PA_SR4 → RCA runtime execution / multicast / playout seam — not SOURCE republication
```

---

## State transitions

### On field authorization (this document)

```text
PA-SR4          FIELD AUTHORIZED
PA-SR5/SR6      HOLD
```

### On PA-SR4 field PASS (target)

```text
PA-SR4          CLOSED / FIELD-PROVEN
PA-SR5          AUTHORIZED FOR FIELD (separate run card)
```

### On PA-SR4 field FAIL (target)

```text
PA-SR4          VALID FAIL / RCA OPEN
PA-SR3          UNCHANGED_CLOSED
B1-R2           UNCHANGED_CLOSED
```

---

## Sign-off record

| Item | Value |
|------|-------|
| **Authorized gate** | PA-SR4 |
| **Authorization date** | 2026-09-09 |
| **OBS verdict** | SUFFICIENT — no pre-field OBS impl |
| **Product code** | **NOT AUTHORIZED** at sign-off |
| **Field** | **Authorized** per run card |
