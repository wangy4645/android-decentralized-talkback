# Phase A — PA-SR3 Adjudication Criteria (PR-PA-SR-B1 Field Gate)

**Run card:** `meeting-product-integration-phase-a-sr3-field-run-card.md`  
**Status:** **FROZEN** (2026-09-09)

---

## Verdict model

| Verdict | Exit | Meaning |
|---------|------|---------|
| **PASS** | 0 | PA-SR3 chain field-proven |
| **FAIL_PA_SR3** | 1 | PRE green · SOURCE chain broken |
| **INVALID_*** | 2 | `PA-SR3=NOT_TESTED` |

---

## Class 0 — Preconditions (INVALID if any fail)

```text
PRE-G     GROUP_GATE verdict ≠ INVALID · PRE_G4_createAllowed=true
PRE-I     INVITE_JOIN_GATE · PRE_I6_peerBindH=PASS
PRE-J     JOIN_GATE · PRE_J6_packageAdjudicationAllowed=true
J0 prereq peer WIRE_INGRESS factType=1 outcome=APPLIED (session=hostSessionId)
```

Missing M01 or M02 log → `INVALID_CAPTURE`.

---

## Class 1 — PA-SR3 chain (primary: M01 → M02)

All steps required on correlated identity keys.

### SR3-A — Local SOURCE originated

**Observer:** M01 (host)

```text
PROFILE01_ORIGIN_EMIT session=<hostSessionId> factType=9 moduleId=M01 outcome=BUILT
PROFILE01_ORIGIN_EMIT session=<hostSessionId> factType=9 moduleId=M01 target=M02 outcome=EMITTED
```

Extract and lock: `conferenceId`, `sourceGeneration`, `factDigest`.

### SR3-B — Peer ingress apply

**Observer:** M02 (peer)

```text
WIRE_INGRESS session=<hostSessionId> factType=9 outcome=APPLIED
```

Must occur **after** SR3-A EMITTED (log order). `senderModuleId` implied M01 (wire `from` module).

### SR3-C — Registry member exists (proxy)

No direct `registry.member` OBS in v1. **Proxy gate:**

```text
SHADOW hook=MEMBER_MEDIA_READY session=<hostSessionId> module=M01 outcome=APPLIED
```

on **M02**, timestamp **after** SR3-B.

Optional corroboration: `PROFILE01_SHADOW_RUNTIME phase=SHADOW_MEMBER_INSTALLED sessionId=<hostSessionId> moduleId=M01`.

### SR3-D — Binding consumption (not stale)

```text
Same senderModuleId + sourceGeneration (+ factDigest when logged)
MEMBER_MEDIA_READY APPLIED is not explained by pre-SOURCE registry residue
```

**FAIL** if: only `DEFERRED_NO_BINDING` observed for `module=M01` on peer with no subsequent APPLIED after SR3-B.

---

## Class 2 — Pre-bind republication (conditional)

If **any** line matches:

```text
PRE_BIND_NOT_RETAINED session=<hostSessionId> factType=9
```

Then **additionally require** post-bind automatic byte-identical republication with same
`sourceGeneration` AND `factDigest` as SR3-A BUILT/EMITTED — **either**:

```text
PROFILE01_ORIGIN_EMIT factType=9 outcome=EMITTED target=M02   (pre-B1-R2 / transport replay)
```

**or** (B1-R2 authoritative):

```text
PROFILE01_SOURCE_POST_BIND_REPUBLISH … target=M02 … outcome=EMITTED
```

**Without** manual replay evidence (`manualContinuationDrain=OFF` in manifest; no operator inject markers).

Two `PROFILE01_ORIGIN_EMIT` EMITTED lines with identical `factDigest` to the same target also satisfy
byte-identical republication (B1-HC3).

---

## Identity correlation block (required output)

Adjudicator writes `PA_SR3_IDENTITY.txt`:

```text
conferenceId=…
sessionId=…
senderModuleId=…
sourceGeneration=…
factDigest=…
membershipVersion=…
mediaKeyEpoch=…
ssrc=…|NOT_IN_OBS_V1
sourceInstanceId=…|NOT_IN_OBS_V1
republicationRequired=true|false
republicationObserved=true|false
```

---

## Class 3 — Observe only (PA-SR4…SR6)

Record in `PA_SR3_OBSERVE_ONLY.txt` — **never** flip PA-SR3 verdict:

```text
PA-SR4  INGRESS_ACTIVITY / PLAYOUT_ACTIVITY present?
PA-SR5  leave/rejoin hooks
PA-SR6  SESSION_STOPPED / TEARDOWN_EMPTY
```

---

## INVALID routing

| Condition | Verdict |
|-----------|---------|
| GROUP / invite / join gate FAIL | `INVALID_GROUP_*` / `INVALID_INVITE_*` / `INVALID_JOIN_*` |
| Missing logs | `INVALID_CAPTURE` |
| CREATION APPLIED not observed | `INVALID_PREREQUISITE` |
| B1 build not present (no factType=9 ORIGIN_EMIT at all) | `INVALID_BUILD` (wrong artifact) |

---

## Script

`talkback/scripts/phase-a-sr3-field-adjudicate.ps1`
