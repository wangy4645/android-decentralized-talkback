# Meeting Profile01 — P1-C MEDIA_KEY_PACKAGE First-Run Authorization

**Parent run card:** `meeting-profile01-p1-c-media-key-package-field-run-card.md`  
**Parent authorization:** `meeting-profile01-p1-c-media-key-package-field-authorization.md`  
**Prerequisite:**  
- P1-C-FIELD-OBS — **CLOSED / UNIT PASS**  
- P1-C collect/adjudicate — **CLOSED / DRY-RUN PASS**  
- C-F7 recipientKeyVersion mismatch injection seam — **UNIT PASS**  
**Status:** **CLOSED / FIELD-PROVEN** (2026-09-09) — authoritative PASS `talkback/logs/p1c-package-field-20260909-101752/` · closure: `meeting-profile01-p1-c-field-closure.md`

```text
P1-C-FIELD-OBS              CLOSED / UNIT PASS
P1-C collect/adjudicate     CLOSED / DRY-RUN PASS
P1-C C-F7 injection seam    CLOSED / UNIT PASS
P1-C first-run              CLOSED / FIELD-PROVEN (20260909-101752)
P1-C-R1 post-bind republish CLOSED / FIELD-PROVEN
  auth: meeting-profile01-p1-c-r1-post-bind-republish-obligation.md
P1-C package field          CLOSED / FIELD-PROVEN
  closure: meeting-profile01-p1-c-field-closure.md
Meeting J0                  AUTHORIZABLE / NEXT
  auth: meeting-profile01-j0-meeting-operational-authorization.md
Phase A                     BLOCKED pending J0
```

**Authorizes:** one shared-window M01→M02 field run covering positive C-F1…C-F6 and
negative C-F7 via the frozen recipientKeyVersion mismatch fixture.

**Does NOT authorize:** Meeting J0 · J0 run card · Phase A · PreBindRetention expansion ·
establishment rotation · Keystore clear · peer trust-store mutation · forged logs ·
P1-D/P1-E product changes.

---

## 1. Frozen C-F7 injection (narrow)

```text
正常 package builder（正确 SPKI wrap）
→ field-test injection seam 仅覆写 wire recipientKeyVersion
→ 选用明确 ≠ peer 当前 establishmentKeyVersion 的测试值
→ 现有 encoder + signer 重签
→ 现有 transport 发送
```

**Hard constraints:**

```text
ONLY overwrite wire recipientKeyVersion
Package still produced by existing builder/encoder/signer
NO forged logs as success evidence
NO peer trust-store mutation
NO establishment rotation
NO Keystore clear
NO signer-version borrow for unwrap
NO positive publication ledger mark for the negative emit
```

`wireRecipientKeyVersion` is a **negative wire input**, not a new establishment authority
version. Harness selects a sentinel distinct from the accepted binding (default
`Profile01MediaKeyPackageNegativeFixtureVersions` sentinel band) — do not hard-depend on
`actual+1` as a long-term algorithm.

Negative fixture identity is separate from the positive package. Required OBS:

```text
PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE
  fixtureType=RECIPIENT_KEY_VERSION_MISMATCH
  negativeFixtureId=<id>
  injected=true
  conferenceId=
  mediaKeyEpoch=
  recipientModuleId=
  expectedRecipientEstablishmentKeyVersion=<actual>
  wireRecipientKeyVersion=<mismatch>
  membershipFactDigest=
  publicationOutcome=EMITTED
```

---

## 2. First-run scope

```text
Positive leg
  Host M01 → Peer M02
  C-F1 … C-F6

Negative leg
  Host M01 → Peer M02
  recipientKeyVersionMismatch fixture
  → C-F7
```

Same collector window. Do not require CREATION APPLIED / INCOMPLETE_DRAIN / Meeting
OPERATIONAL.

---

## 3. Hard preconditions

```text
PRE-1  M01/M02 accepted establishment trust state present
PRE-2  M02 local establishment identity Verified (EP2-class)
PRE-3  M01 lookup(M02) Found (EP1-class)
PRE-4  production package OBS available (ORIGIN / INGEST / SUPPLEMENT)
PRE-5  negative fixture seam reports injected=true (OBS — not file-only)
PRE-6  collector captures both devices for one shared run window
```

---

## 4. Adjudication rule (critical)

```text
Positive FAIL
≠
Negative fixture failure
```

Examples:

| Observation | Verdict |
|-------------|---------|
| C-F1…C-F6 PASS, C-F7 PASS | **PASS** |
| Field evidence complete; package leg violates frozen contract | **FAIL** |
| Positive PASS but negative never actually emitted (`injected≠true` OBS) | **INVALID** |
| Missing OBS / capture / identity correlation / PRE fail | **INVALID** |

Do not classify a missing negative fixture as package-implementation FAIL.

---

## 5. Verdict classes (only three)

```text
PASS
  C-F1…C-F7 all PASS

FAIL
  field evidence complete AND package leg violates a frozen contract

INVALID
  fixture / capture / identity / precondition insufficient
```

---

## 6. Operator procedure (summary)

```text
T0  collect.ps1 (shared window) + EP manifest copy
T1  Positive: conference create → M01 ORIGIN EMITTED → M02 WIRE/INGEST/READY
T2  Negative: on M01 (DEBUG build), after positive:
      adb shell am broadcast -a com.talkback.appprod.debug.P1C_CF7_RECIPIENT_KEY_VERSION_MISMATCH \
        --es remote M02
    Expect PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE injected=true
    Expect peer INGEST outcome=REJECTED_RECIPIENT_KEY_VERSION_MISMATCH
    Expect no matching SUPPLEMENT READY after that reject
T3  Stop collectors; set NEGATIVE_FIXTURE.txt from OBS (injected=true + ids)
T4  adjudicate.ps1 -LogDir <dir>
```

API surface (DEBUG only):

```text
TalkbackRuntime.debugP1cEmitRecipientKeyVersionMismatchFixture(remoteModuleId, wireRecipientKeyVersion?)
MeetingProfile01MediaKeyPackageOriginBridge.emitRecipientKeyVersionMismatchFixture(...)
Profile01MediaKeyPackageBuilder.overrideRecipientKeyVersionAndResign(...)
```

---

## 7. After first-run

```text
PASS
  → P1-C package field CLOSED / FIELD-PROVEN
  → Meeting J0 AUTHORIZABLE / NEXT (separate authorization — not auto-started)

FAIL
  → RCA inside package leg only; J0 remains HOLD

INVALID
  → fix PRE / fixture / capture; re-run first-run only
```

---

## 8. References

- Run card: `meeting-profile01-p1-c-media-key-package-field-run-card.md`
- Field auth: `meeting-profile01-p1-c-media-key-package-field-authorization.md`
- Collect: `talkback/scripts/p1c-package-field-collect.ps1`
- Adjudicate: `talkback/scripts/p1c-package-field-adjudicate.ps1`
- Seam: `Profile01MediaKeyPackageBuilder.overrideRecipientKeyVersionAndResign`
- Emit: `MeetingProfile01MediaKeyPackageOriginBridge.emitRecipientKeyVersionMismatchFixture`
