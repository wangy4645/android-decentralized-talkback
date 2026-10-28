# Meeting Profile01 — J0 Meeting Operational Run Card

**Parent authorization:** `meeting-profile01-j0-meeting-operational-authorization.md`  
**Adjudication spec:** `meeting-profile01-j0-meeting-operational-adjudication-criteria.md`  
**Status:** **CLOSED / FIELD-PROVEN** (2026-09-09) — authoritative PASS `talkback/logs/j0-meeting-operational-20260909-103707/`

```text
Establishment Gate       CLOSED / FIELD-PROVEN
P1-C package field       CLOSED / FIELD-PROVEN
P1-C-R1                  CLOSED / FIELD-PROVEN
P1-D / P1-E              CLOSED / UNIT-PROVEN

J0 run card/adjudicator    CLOSED / DRY-RUN PASS
J0 field driver            CLOSED / READY
J0 first run               CLOSED / FIELD-PROVEN
Meeting J0                 CLOSED / FIELD-PROVEN
  closure: meeting-profile01-j0-field-closure.md
Phase A                    ELIGIBLE FOR NEXT GATE
ADR-0056                   KEEP
Meeting media cutover      NOT AUTHORIZED
```

```text
Establishment Gate       CLOSED / FIELD-PROVEN
P1-C package field       CLOSED / FIELD-PROVEN
P1-C-R1                  CLOSED / FIELD-PROVEN
P1-D / P1-E              CLOSED / UNIT-PROVEN

Meeting J0               AUTHORIZED / NEXT
Phase A                  BLOCKED pending J0
ADR-0056                 KEEP
Meeting media cutover    NOT AUTHORIZED
```

---

## 1. Question this gate answers

> 在真实 Meeting 产品生命周期中，早到的 CREATION 能否在 peer bind、真实 package 到达后，通过 P1-E 自动恢复并最终 `CREATION APPLIED`？

**J0 不是 package leg 再审。** P1-C 已关闭 package/R1；J0 的核心新增证据是 **J0-4（bind 后 INCOMPLETE_PENDING）** 与 **J0-6（supplement READY → P1-E 自动 drain → re-entry）**。

**Explicit non-claims:**

```text
CREATION APPLIED        ≠  Meeting media cutover PASS
J0 PASS                 ≠  Phase A complete
J0 PASS                 ≠  ADR-0056 replacement
```

---

## 2. Frozen positive chain (J0-1…J0-7)

```text
J0-1  Host authoritative CREATION originated
      → canonical signed CREATION exists
      → PROFILE01_ORIGIN_EMIT factType=1 outcome=EMITTED

J0-2  Peer receives CREATION before session bind
      → WIRE_INGRESS outcome=DEFERRED_NO_SESSION_RETAINED factType=1
      → same sessionId=H as eventual bind

J0-3  Peer joins host conference
      → PRE-G1…G4 PASS
      → PRE-I1…I6 PASS
      → PRE-J1…J6 PASS
      → peerSessionId == hostSessionId == H

J0-4  Retained CREATION drains after bind
      → verified CREATION re-ingress
      → supplementRegistry miss
      → INCOMPLETE_RETAIN digest=D
      → WIRE_INGRESS factType=1 outcome=INCOMPLETE_PENDING detail=MISSING_MEDIA_SUPPLEMENT

J0-5  Product automatic post-bind package publication
      → POST_BIND_EMITTED (product auto; not harness-only)
      → peer MEDIA_KEY_PACKAGE APPLIED
      → PROFILE01_MEDIA_SUPPLEMENT state=READY
      → package.membershipFactDigest == D

J0-6  Supplement ready triggers P1-E (production path)
      → supplementRegistry.put → automatic drain
      → SESSION_INCOMPLETE_DRAIN conference=C mediaKeyEpoch=E count>=1
      → digest on drain matches D
      → FORBIDDEN: drainIncompleteAfterSupplementReadyForTest in field path

J0-7  Re-entry → CREATION APPLIED
      → WIRE_INGRESS factType=1 outcome=APPLIED (post-drain)
      → same conferenceId / sessionId / mediaKeyEpoch / factDigest=D
      → no second INCOMPLETE_PENDING for same identity after APPLIED
```

---

## 3. Identity keys (strict correlation)

Adjudicator **must not** stitch logs by time proximity alone. Minimum join keys:

```text
conferenceId
sessionId
mediaKeyEpoch
creationFactDigest        (host ORIGIN_EMIT factDigest)
membershipFactDigest      (package binding; must equal creationFactDigest)
```

**Hard identity fence:**

```text
MEDIA_KEY_PACKAGE.membershipFactDigest == pending CREATION.factDigest
```

Anchor order:

```text
1. Host J0-1 ORIGIN_EMIT → (conferenceId, sessionId, creationFactDigest, mediaKeyEpoch)
2. Peer J0-2 retain → sessionId=H, factType=1
3. Peer J0-4 INCOMPLETE_RETAIN → digest must equal creationFactDigest
4. Package ORIGIN/POST_BIND/INGEST → membershipFactDigest must equal creationFactDigest
5. J0-6 drain + J0-7 APPLIED → same conferenceId + mediaKeyEpoch + digest
```

---

## 4. Topology / devices

| Role | Module | Serial (baseline) |
|------|--------|-------------------|
| Host | M01 | `HTUBB21B09220661` |
| Peer | M02 | `DSJ-2407041` (record in PRECONDITIONS) |

Same candidate APK on M01 + M02. Reuse Establishment Field Gate provisioning. EP lookup failure → **INVALID / PROVISIONING**, not J0 product FAIL.

---

## 5. Harness (frozen)

### Reuse from P1-C (unchanged)

| Artifact | Purpose |
|----------|---------|
| `talkback/scripts/p1c-package-field-first-run.ps1` | **Base driver** — fork or `-J0Mode` wrapper (TBD at field execution) |
| PRE-G1…G4 | GROUP precondition |
| PRE-I1…I6 | Invite overlay join |
| PRE-J1…J6 | Same-session bind |

Gate docs: `meeting-profile01-p1-c-field-group-precondition-gate.md`,  
`meeting-profile01-p1-c-field-invite-join-precondition-gate.md`,  
`meeting-profile01-p1-c-field-join-precondition-gate.md`

### J0-specific harness deltas

```text
ENABLE   PRE-G / PRE-I / PRE-J (required)
ENABLE   logcat tags Talkback:I MEETING_MEDIA_SHADOW:I
DISABLE  C-F7 negative fixture injection (not a J0 gate)
DISABLE  harness-only POST_BIND republish as success path
DISABLE  drainIncompleteAfterSupplementReadyForTest / any debug continuation trigger
DISABLE  manual mailbox drain / retry sleeps / artificial supplement inject
```

**Field driver:** `talkback/scripts/j0-meeting-operational-first-run.ps1`  
**Orchestration:** `p1c-package-field-first-run.ps1 -DriverMode J0` (shared PRE-G/I/J body)

---

## 6. Observability

### v1 adjudication sources (sufficient — proven in P1-C PASS log `20260909-101752`)

| Step | Line pattern | Tag |
|------|--------------|-----|
| J0-1 | `PROFILE01_ORIGIN_EMIT … factType=1 outcome=EMITTED conferenceId=… factDigest=…` | Talkback (M01) |
| J0-2 | `WIRE_INGRESS … outcome=DEFERRED_NO_SESSION_RETAINED … factType=1` | MEETING_MEDIA_SHADOW (M02) |
| J0-4 | `INCOMPLETE_RETAIN session=… conference=… mediaKeyEpoch=… digest=…` | MEETING_MEDIA_SHADOW (M02) |
| J0-4 | `WIRE_INGRESS … factType=1 outcome=INCOMPLETE_PENDING detail=MISSING_MEDIA_SUPPLEMENT` | MEETING_MEDIA_SHADOW (M02) |
| J0-5 | `PROFILE01_MEDIA_KEY_PACKAGE_POST_BIND_REPUBLISH … publicationOutcome=EMITTED` | Talkback (M01) |
| J0-5 | `PROFILE01_MEDIA_SUPPLEMENT … state=READY` | Talkback (M02) |
| J0-6 | `SESSION_INCOMPLETE_DRAIN conference=… mediaKeyEpoch=… count=…` | MEETING_MEDIA_SHADOW (M02) |
| J0-7 | `WIRE_INGRESS … factType=1 outcome=APPLIED` (timestamp after drain) | MEETING_MEDIA_SHADOW (M02) |

### Optional thin OBS (only if v1 dry-run cannot correlate)

Behavior-neutral continuation line — **not** authorized in this run card until separate OBS PR:

```text
PROFILE01_CREATION_CONTINUATION
  conferenceId=
  sessionId=
  mediaKeyEpoch=
  factDigest=
  state=INCOMPLETE_PENDING
       | DETACHED_FOR_SUPPLEMENT
       | REVALIDATED
       | REENTERED
       | APPLIED
       | DISCARDED_<reason>
```

**v1 decision:** existing shadow lines are authoritative for first J0 field run. Thin OBS is a **fallback PR**, not a blocker for run card / adjudicator authoring.

---

## 7. Verdict model (three-state)

| Verdict | When |
|---------|------|
| **PASS** | PRE-G/I/J PASS · J0-1…J0-7 PASS · continuation product-auto · identity fence PASS |
| **FAIL** | PRE complete · evidence complete · J0-1…J0-7 product-chain violation |
| **INVALID** | GROUP / invite / same-session / capture / required OBS / provisioning insufficient |

**INVALID routing:** PRE-G/I/J failure → fix harness only — **do not re-open P1-C/R1**.

**Forbidden in PASS path:**

```text
drainIncompleteAfterSupplementReadyForTest
P1C_REPUBLISH_AFTER_BIND as sole post-bind success
harnessOnly=True without product POST_BIND_EMITTED
```

---

## 8. Run sequence (operator)

```text
1. Verify EP provisioning manifest (same as P1-C)
2. PRE-G leave residual GROUP if needed
3. Peer foreground + Meeting tab BEFORE host CREATE
4. Start dual logcat capture
5. Host Meeting CREATE → CREATION emit (J0-1)
6. Peer pre-bind retain CREATION (J0-2) — automatic
7. PRE-I: btnJoinMeeting within invite window
8. PRE-J: peer binds session H
9. Wait for bind-drain INCOMPLETE_PENDING (J0-4)
10. Wait for product POST_BIND + supplement READY (J0-5)
11. Wait for SESSION_INCOMPLETE_DRAIN + CREATION APPLIED (J0-6/7)
12. Stop capture → write RUN_META / gate files → adjudicate
```

No C-F7 step. No negative fixture broadcast.

---

## 9. Artifacts per run

```text
talkback/logs/j0-meeting-operational-<stamp>/
  M01-logcat.txt
  M02-logcat.txt
  PRECONDITIONS.txt
  PROVISIONING_MANIFEST.txt
  GROUP_GATE.txt
  INVITE_JOIN_GATE.txt
  JOIN_GATE.txt
  RUN_META.txt
  ADJUDICATION.txt
```

---

## 10. After J0 PASS

```text
Meeting J0               CLOSED / FIELD-PROVEN
Profile01 Meeting chain  CLOSED / FIELD-PROVEN

Phase A                  ELIGIBLE FOR NEXT GATE
ADR-0056                 KEEP
Meeting media cutover    NOT AUTHORIZED
```

**FAIL** → RCA inside continuation chain only; P1-C/R1 remain CLOSED.  
**INVALID** → fix harness / capture / OBS; re-run J0 only.

---

## 11. References

- J0 authorization: `meeting-profile01-j0-meeting-operational-authorization.md`
- Adjudication criteria: `meeting-profile01-j0-meeting-operational-adjudication-criteria.md`
- P1-C closure: `meeting-profile01-p1-c-field-closure.md`
- P1-E contract: `meeting-profile01-p1-e-creation-incomplete-apply-continuation-authorization.md`
- Pre-bind retain: `meeting-profile01-pre-bind-fact-retention-drain-design-slice.md`
- P1-C PASS log (corroborating shadow lines): `talkback/logs/p1c-package-field-20260909-101752/`
