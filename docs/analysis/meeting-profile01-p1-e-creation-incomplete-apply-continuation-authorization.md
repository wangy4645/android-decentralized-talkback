# Meeting Profile01 — P1-E CREATION Incomplete-Apply Continuation Authorization

**Parent:** `meeting-profile01-media-key-package-origin-path1-design-slice.md` §8  
**Prerequisite:** `meeting-profile01-p1-d-media-key-package-decrypt-production-wiring-authorization.md` — **CLOSED / UNIT-PROVEN**  
**Status:** **CLOSED / UNIT-PROVEN** (2026-09-08)

```text
Operator Provisioning       CLOSED
Establishment Field Gate    CLOSED / FIELD PROVEN

P1-A                        CLOSED
P1-B                        CLOSED
P1-C                        CLOSED / UNIT PASS
P1-D                        CLOSED / UNIT-PROVEN
P1-E                        CLOSED / UNIT-PROVEN

PR-P1E-1 Continuation core   CLOSED (mailbox + wire hooks)
PR-P1E-2 E1–E7 unit tests    CLOSED (E1–E7 PASS)

P1-C package field          NOT AUTHORIZED
Meeting J0                  NOT AUTHORIZED
Phase A Shadow Runtime Gate     AUTHORIZABLE / NEXT

PreBindRetention            FREEZE
CREATION origin/replay      FREEZE
ADR-0061                    FREEZE
```

**Authorizes:** verified CREATION apply obligation continuation when media supplement is missing —
narrow `MISSING_MEDIA_SUPPLEMENT` class only.

**Does NOT authorize:** pre-bind retention expansion · factType=11 retention · host resend ·
sender-ordering dependency · generic `REJECTED_INCOMPLETE` retry · Meeting J0 · field run.

---

## 1. Authorized continuation chain (narrow)

```text
CREATION verified (decode + trust + validate → ReadySession)
→ supplementRegistry.resolveSupplement → placeholder (registry miss)
→ publish → REJECTED_INCOMPLETE
→ classify MISSING_MEDIA_SUPPLEMENT
→ retain PendingIncompleteCreationApply
→ WireIngressOutcome.INCOMPLETE_PENDING

MEDIA_KEY_PACKAGE APPLIED
→ supplementRegistry.put
→ detachForSupplementReady(conferenceId, mediaKeyEpoch)   // consume before re-entry
→ revalidate session / conference / epoch fence
→ re-enter ingestCreationSignedFact (supplement now resolves)
→ existing publish/apply path
→ APPLIED | terminal reject
```

**Path B (no pending):** supplement already in registry → CREATION first apply → APPLIED directly.

---

## 2. Eligibility (frozen)

| Enters P1-E | Terminal (no continuation) |
|-------------|---------------------------|
| `factType=CREATION` + `ReadySession` + `REJECTED_INCOMPLETE` + registry miss at apply time | `REJECTED_DECODE` / `REJECTED_VERIFY` |
| Classified `MISSING_MEDIA_SUPPLEMENT` | `REJECTED_STALE` / `REJECTED_SUPERSEDED` |
| | Other `REJECTED_INCOMPLETE` causes |
| | MEMBERSHIP / SOURCE / factType=11 |

**Do not** patch `ByteArray(32)` placeholder → `ByteArray(16)` to bypass `validateSessionComplete`.

---

## 3. Pending identity (minimum binding)

```text
conferenceIdHex
sessionId
mediaKeyEpoch
factDigestHex
signedFactBytes (verified envelope)
channelId
networkInterfaceName
```

Dedupe key: `(conferenceIdHex, mediaKeyEpoch, factDigestHex)` — at most one pending per key.

**Frozen:** `consume before re-entry` — `detachForSupplementReady` removes pending before re-ingress.

---

## 4. Exit criteria (E1–E7)

| ID | Proof |
|----|-------|
| **E1** | CREATION + missing supplement → `INCOMPLETE_PENDING`; no registry publish / no APPLIED |
| **E2** | Matching supplement put → exactly one `INCOMPLETE_DRAIN` → CREATION path → APPLIED |
| **E3** | Supplement exists first → CREATION direct APPLIED; no pending created |
| **E4** | Wrong conference / mediaKeyEpoch → no drain of unrelated pending |
| **E5** | Session stopped / generation fence invalid → pending consumed or terminally discarded |
| **E6** | Duplicate package / duplicate ready callback → at most one CREATION re-entry |
| **E7** | Other incomplete reason → does not enter P1-E continuation |

Unit PASS does not authorize field run or Meeting J0.

---

## 5. Explicit exclusions

```text
PreBindRetention semantic expansion (factType=11)     FORBIDDEN
factType=11 pre-bind retention                        FORBIDDEN
Host resend / sender-ordering contract                FORBIDDEN
Generic REJECTED_INCOMPLETE retry                     FORBIDDEN
Meeting J0                                            NOT AUTHORIZED
MeetingProfile01CreationOriginBridge behavior change  FREEZE
ADR-0061 / media continuation                         FREEZE
GBC validateSessionComplete relaxation                FORBIDDEN
```

---

## 6. Implementation queue

```text
PR-P1E-1  MeetingProfile01IncompleteApplyContinuation + unit mailbox tests
PR-P1E-2  MeetingProfile01FactWireIngress hooks + E1–E7 integration tests
```

---

## 7. Observability

```text
WIRE_INGRESS factType=1 outcome=INCOMPLETE_PENDING
PACKAGE_SUPPLEMENT_READY conferenceId=… mediaKeyEpoch=…
SESSION_INCOMPLETE_DRAIN count=N
WIRE_INGRESS factType=1 outcome=APPLIED   (post-drain)
```

---

## 8. References

- Path 1 stack: `meeting-profile01-media-key-package-origin-path1-design-slice.md` §8
- P1-D: `meeting-profile01-p1-d-media-key-package-decrypt-production-wiring-authorization.md`
- RCA: `meeting-profile01-creation-apply-completeness-rca.md`
- Gap map: `meeting-profile01-media-key-package-origin-hook-gap-map.md`
- Pre-bind (FREEZE): `meeting-profile01-pre-bind-fact-retention-drain-design-slice.md`
