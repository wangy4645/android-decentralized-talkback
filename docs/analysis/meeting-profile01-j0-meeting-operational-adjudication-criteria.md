# Meeting Profile01 — J0 Adjudication Criteria

**Parent run card:** `meeting-profile01-j0-meeting-operational-run-card.md`  
**Harness script:** `talkback/scripts/j0-meeting-operational-adjudicate.ps1`  
**Status:** **FROZEN** (2026-09-09)

---

## 1. Scope

Machine-checkable criteria for **Profile01 Meeting operational correctness** — continuation + `CREATION APPLIED` under real Meeting lifecycle.

**In scope:** J0-1…J0-7 · PRE-G/I/J · identity fence · product-auto continuation  
**Out of scope:** C-F7 negative fence · Meeting media cutover · ADR-0056 · Phase A completion

---

## 2. Preconditions (INVALID if any fail)

### PRE-G / PRE-I / PRE-J

Identical semantics to P1-C field gates. Adjudicator reads `GROUP_GATE.txt`, `INVITE_JOIN_GATE.txt`, `JOIN_GATE.txt`.

| File verdict | Adjudicator exit |
|--------------|------------------|
| `INVALID_GROUP_PRECONDITION` | `VERDICT=INVALID` · `reason=group_precondition` · `J0_CHAIN=NOT_TESTED` |
| `INVALID_INVITE_JOIN_PRECONDITION` | `VERDICT=INVALID` · `reason=invite_join_precondition` · `J0_CHAIN=NOT_TESTED` |
| `INVALID_JOIN_PRECONDITION` | `VERDICT=INVALID` · `reason=join_precondition` · `J0_CHAIN=NOT_TESTED` |

PRE failure **must not** downgrade P1-C/R1 status.

### Capture / provisioning INVALID

```text
missing M01 or M02 logcat
missing establishmentKeyVersion in PRECONDITIONS / manifest
hostSessionId absent after PRE-J
```

---

## 3. Identity anchor

Extract from **host** `PROFILE01_ORIGIN_EMIT` where `factType=1` and `outcome=EMITTED`:

```text
anchor.sessionId
anchor.conferenceId
anchor.creationFactDigest    (factDigest field)
anchor.mediaKeyEpoch         (from peer INCOMPLETE_RETAIN or package lines)
```

All J0-2…J0-7 checks must reference this anchor. Mismatched `conferenceId`, `sessionId`, `mediaKeyEpoch`, or `factDigest` → **FAIL** on affected step (not INVALID).

### Digest fence (hard)

```text
package.membershipFactDigest == anchor.creationFactDigest
INCOMPLETE_RETAIN.digest == anchor.creationFactDigest
```

---

## 4. Step criteria

### J0-1 — Host CREATION originated

| Check | Source | PASS |
|-------|--------|------|
| J0-1a | M01 `PROFILE01_ORIGIN_EMIT` | `factType=1` `outcome=EMITTED` |
| J0-1b | same line | `conferenceId` present |
| J0-1c | same line | `factDigest` present (hex) |
| J0-1d | same line | `session=` matches `JOIN_GATE.hostSessionId` |

**FAIL:** no EMITTED CREATION · digest missing · session mismatch

---

### J0-2 — Pre-bind CREATION retained

| Check | Source | PASS |
|-------|--------|------|
| J0-2a | M02 `WIRE_INGRESS` | `outcome=DEFERRED_NO_SESSION_RETAINED` |
| J0-2b | same line | `factType=1` |
| J0-2c | same line | `session=` == `anchor.sessionId` |
| J0-2d | ordering | retain timestamp **before** peer bind markers (`INVITE_ACCEPT` / `SESSION_BIND`) |

**FAIL:** only `DEFERRED_NO_SESSION` (silent drop) · retain after bind · wrong session

---

### J0-3 — Same-session join

Delegated to gate files:

```text
PRE-G1..G4 = PASS
PRE-I1..I6 = PASS
PRE-J1..J6 = PASS
PRE-J5 sameSessionId
hostSessionId == peerSessionId == anchor.sessionId
```

**FAIL (product):** N/A — harness failure is INVALID

---

### J0-4 — INCOMPLETE_PENDING after bind

| Check | Source | PASS |
|-------|--------|------|
| J0-4a | M02 `INCOMPLETE_RETAIN` | `conference=` == anchor.conferenceId |
| J0-4b | same line | `digest=` == anchor.creationFactDigest |
| J0-4c | same line | `session=` == anchor.sessionId |
| J0-4d | M02 `WIRE_INGRESS` | `factType=1` `outcome=INCOMPLETE_PENDING` `detail=MISSING_MEDIA_SUPPLEMENT` |
| J0-4e | ordering | J0-4 lines **after** bind · **before** supplement READY |

**FAIL:** direct APPLIED without INCOMPLETE_PENDING (Path B — supplement-first) on J0 topology · wrong digest · no retain line

---

### J0-5 — Package leg (continuation prerequisite only)

Not a re-proof of P1-C. Minimum for J0:

| Check | Source | PASS |
|-------|--------|------|
| J0-5a | M01 post-bind | `PROFILE01_MEDIA_KEY_PACKAGE_POST_BIND_REPUBLISH` or `publicationOutcome=POST_BIND_EMITTED` |
| J0-5b | R1 path | `productAuto=True` · `harnessOnly=False` (same rule as P1-C-R1) |
| J0-5c | M02 ingest | `PROFILE01_MEDIA_KEY_INGEST outcome=APPLIED` matching anchor ids |
| J0-5d | M02 supplement | `PROFILE01_MEDIA_SUPPLEMENT state=READY` |
| J0-5e | digest fence | `membershipFactDigest` on package lines == anchor.creationFactDigest |

**FAIL:** harness-only republish · READY without APPLIED · digest mismatch

---

### J0-6 — P1-E automatic drain

| Check | Source | PASS |
|-------|--------|------|
| J0-6a | M02 | `SESSION_INCOMPLETE_DRAIN conference=<anchor> mediaKeyEpoch=<anchor> count>=1` |
| J0-6b | ordering | drain timestamp **after** READY · **before** J0-7 APPLIED |
| J0-6c | forbidden scan | no `drainIncompleteAfterSupplementReadyForTest` in either log |
| J0-6d | product path | drain follows `PROFILE01_MEDIA_KEY_INGEST outcome=APPLIED` on factType=11 production path |

**FAIL:** no drain · drain before READY · test-only drain marker · count=0

---

### J0-7 — CREATION APPLIED

| Check | Source | PASS |
|-------|--------|------|
| J0-7a | M02 `WIRE_INGRESS` | `factType=1` `outcome=APPLIED` |
| J0-7b | same line | `session=` == anchor.sessionId |
| J0-7c | ordering | APPLIED timestamp **after** `SESSION_INCOMPLETE_DRAIN` |
| J0-7d | mailbox | no `INCOMPLETE_RETAIN` for same digest **after** APPLIED |
| J0-7e | terminal | no second `INCOMPLETE_PENDING` for same anchor after APPLIED |

**FAIL:** APPLIED missing · APPLIED before drain · duplicate pending after success

---

## 5. Forbidden patterns (auto-FAIL or INVALID)

| Pattern | Effect |
|---------|--------|
| `drainIncompleteAfterSupplementReadyForTest` | **FAIL** `forbidden_test_drain_path` |
| `P1C_REPUBLISH_AFTER_BIND` without product POST_BIND | **FAIL** `harness_only_continuation_prereq` |
| `PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE` | **INVALID** if injected=true (J0 must not run C-F7) |

---

## 6. Verdict output

### PASS

```text
VERDICT=PASS
J0-1=PASS … J0-7=PASS
identity_fence=PASS
continuation_path=product_auto
Meeting_J0=CLOSED / FIELD-PROVEN
Profile01_Meeting_chain=CLOSED / FIELD-PROVEN
Next=PHASE_A_ELIGIBLE_NEXT_GATE
note=CREATION_APPLIED != media_cutover
```

Exit code: `0`

### FAIL

```text
VERDICT=FAIL
J0-<n>=FAIL <evidence>
reason=<comma-separated>
Next=RCA_J0_CONTINUATION_HOLD_PHASE_A
P1-C_status=UNCHANGED_CLOSED
```

Exit code: `1`

### INVALID

```text
VERDICT=INVALID
reason=<precondition|capture|provisioning>
J0_CHAIN=NOT_TESTED
Next=FIX_HARNESS_OR_CAPTURE_RERUN_J0_ONLY
P1-C_status=UNCHANGED_CLOSED
```

Exit code: `2`

---

## 7. Dry-run fixtures

```text
talkback/scripts/fixtures/j0-meeting-operational/
  pass-full/           — synthesized from 20260909-101752 shadow lines
  invalid-missing-pre-g/
  fail-no-drain/
  fail-digest-mismatch/
  invalid-cf7-injected/
```

Run: `.\talkback\scripts\j0-meeting-operational-adjudicate.ps1 -LogDir <fixture>`

---

## 8. Corroborating evidence (P1-C PASS log)

`talkback/logs/p1c-package-field-20260909-101752/` already contains the J0-4/J0-6/J0-7 shadow sequence (informational only for P1-C; authoritative for J0 only after dedicated J0 run + adjudication).

```text
10:20:43  DEFERRED_NO_SESSION_RETAINED factType=1
10:20:53  INCOMPLETE_RETAIN digest=5f25f35f…
10:20:53  INCOMPLETE_PENDING MISSING_MEDIA_SUPPLEMENT
10:20:54  SUPPLEMENT READY → INGEST APPLIED → SESSION_INCOMPLETE_DRAIN count=1
10:20:54  WIRE_INGRESS factType=1 outcome=APPLIED
```
