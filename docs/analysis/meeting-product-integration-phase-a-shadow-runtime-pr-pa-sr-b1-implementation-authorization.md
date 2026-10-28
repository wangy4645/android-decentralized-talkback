# Phase A Shadow Runtime — PR-PA-SR-B1 Implementation Authorization

**PR:** `PR-PA-SR-B1` — SOURCE_DECLARATION Origin & Member Binding Publication  
**Design:** `meeting-product-integration-phase-a-shadow-runtime-rca-b-fix-design.md`  
**Prerequisite:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b0-implementation-authorization.md`  
**RCA:** `meeting-product-integration-phase-a-shadow-runtime-rca-b-handoff.md`  
**Signed:** 2026-09-09  
**Status:** **IMPLEMENTATION AUTHORIZED** (B1 scope only)

```text
Prerequisite
  PR-PA-SR-B0     CLOSED / UNIT-PROVEN
  Pre-auth gate   A / PASS

Authorization status
  IMPLEMENTATION AUTHORIZED only for PR-PA-SR-B1 scope

PR-PA-SR-B1     CLOSED / UNIT-PROVEN · FIELD FAIL (origin gate)
B1-R1           CLOSED / UNIT-PROVEN
PA-SR3          AUTHORIZED FOR FIELD RE-RUN
PA-SR4…SR6      HOLD

J0 / P1-C / P1-E   UNCHANGED_CLOSED
ADR-0056           KEEP
cutover            NOT AUTHORIZED
```

Signer/sender trust is **closed by B0** — not re-litigated in B1.

---

## Goal (authorized)

For each **authoritative local conference source**:

```text
local committed source identity
  → SOURCE_DECLARATION
  → canonical signed factType=9
  → existing CONFERENCE_SIGNED_FACT transport
  → peer ingress apply
  → registry.publishMember
```

**No authority is derived from ICE connectivity.**

B1 **does not** own `MEMBER_MEDIA_READY APPLIED` as a PR contract endpoint — that is field evidence for PA-SR3.  
B1 stops at **`registry.publishMember`** on peers after successful ingress apply.

---

## Authorized implementation scope

### IN

```text
- Profile01WireCborEncoder.encodeSourceDeclarationFullFact
- MeetingProfile01SourceOriginBridge (+ publisher)
- onLocalConferenceSourceCommitted origin trigger
- local self-declaration only (wire.moduleId == local signing module)
- per-peer publication ledger
- post-bind republication of cached byte-identical signed SOURCE
- minimal factType=9 origin OBS (PROFILE01_ORIGIN_EMIT)
- unit tests B1-1 … B1-7
```

### OUT

```text
- SOURCE pre-bind retention
- generic retry / replay worker
- remote-source host proxy signing
- mediaConnectedModules semantic changes
- registry.publishMember behavior changes (apply path reuse only)
- RCA-A / host SESSION_STARTED origin replay
- PA-SR4 … PA-SR6 fixes
- J0 / P1-C / P1-E
- ADR-0056 / Meeting media cutover
- Re-opening B0 signer/sender fence design
```

---

## Hard constraints (non-negotiable)

### B1-HC1 — Self-declaration only

```text
SOURCE origin is self-declaration only.
wire.moduleId must equal the local authenticated signing module identity.
```

Host **must not** sign SOURCE for remote modules. B0 fence remains authoritative on receive path.

### B1-HC2 — Source truth vs ICE

```text
onLocalConferenceSourceCommitted creates source truth.
ICE / media readiness may affect publication eligibility,
but MUST NOT create or mutate SOURCE identity (SSRC, sourceInstanceId, sourceGeneration).
```

Anti-pattern: `ICE CONNECTED → synthesize SOURCE fields → sign`.

### B1-HC3 — Byte-identical republication

```text
For the same committed source identity,
post-bind republication reuses the same signed bytes.
No fresh sourceGeneration / sourceInstanceId / SSRC is synthesized on republication.
```

**Unlike MEDIA_KEY_PACKAGE:** republication is **byte-identical artifact replay**. Network timing must not accidentally advance source identity.

---

## Unit test matrix (merge gate — B1-1 … B1-7)

| Id | Property | Required outcome |
|----|----------|----------------|
| **B1-1** | Self-origin valid | Local module builds/signs SOURCE; peer validate accepts |
| **B1-2** | Signer/sender fence | **Inherited from B0** — remains PASS; no regression |
| **B1-3** | Same committed source | Stable identity; repeat publish does not bump `sourceGeneration` |
| **B1-4** | Replacement source | New `onLocalConferenceSourceCommitted` → new `sourceGeneration` + `sourceInstanceId` + new signed artifact |
| **B1-5** | Early publication | Pre-bind send → `PRE_BIND_NOT_RETAINED` — retention **not** authorized |
| **B1-6** | Post-bind republication | Second eligible-peer send = **byte-identical** `signedFactBytes` (not re-encode) |
| **B1-7** | Peer apply | `WIRE_INGRESS factType=9 APPLIED` → `registry.member` populated → `memberBinding` non-null |

**Regression:** existing Profile01 SOURCE / ingress chain tests — no behavior change outside B1 scope.

**B1 unit PASS does not close PA-SR3.**

---

## Field gate (separate — post B1 unit closure)

Authorized **only after** B1 unit tests B1-1…B1-7 + regression PASS.

```text
SOURCE originated (PROFILE01_ORIGIN_EMIT factType=9)
  → peer WIRE_INGRESS factType=9 outcome=APPLIED
  → registry.member exists
  → existing MEMBER_MEDIA_READY outcome=APPLIED

= PA-SR3 PASS
```

| Step | B1 field |
|------|----------|
| PA-SR1 / PA-SR2 | No regression required beyond prior A1 baseline |
| **PA-SR3** | **Primary field gate** |
| PA-SR4 … PA-SR6 | Observe only — **do not close** on B1 run |
| SR4 activity if opportunistic | Downstream evidence only |

---

## State transitions

### On B1 implementation authorization (this document)

```text
PR-PA-SR-B0     CLOSED / UNIT-PROVEN
Pre-auth gate   A / PASS
PR-PA-SR-B1     IMPLEMENTATION AUTHORIZED
PA-SR3          BLOCKED (unit + field pending)
```

### On B1 unit closure (target)

```text
PR-PA-SR-B1     CLOSED / UNIT-PROVEN
PA-SR3          AUTHORIZED FOR FIELD (not yet PASS)
```

### On B1 field closure (target)

```text
PA-SR3          PASS (field)
PA-SR4…SR6      HOLD unless separate RCA
```

---

## Sign-off record

| Item | Value |
|------|-------|
| **Authorized PR** | PR-PA-SR-B1 |
| **Authorization date** | 2026-09-09 |
| **Prerequisite** | PR-PA-SR-B0 CLOSED · PRE-AUTH = A |
| **Code status at sign-off** | Authorization document only — origin impl not started |
| **Field** | Not authorized until B1 unit closure recorded |

---

## References

- B1-R1 design: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r1-origin-eligibility-continuation-design.md`
- B1-R1 implementation authorization: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r1-implementation-authorization.md`
- Unit closure: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-unit-closure.md`
- PA-SR3 field run card: `meeting-product-integration-phase-a-sr3-field-run-card.md`
- PA-SR3 adjudication: `meeting-product-integration-phase-a-sr3-adjudication-criteria.md`
- B1 design: `meeting-product-integration-phase-a-shadow-runtime-rca-b-fix-design.md`
- B0 closure: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b0-implementation-authorization.md`
- B0 design: `meeting-product-integration-phase-a-shadow-runtime-rca-b0-fence-design.md`
- Field evidence (RCA-B): `talkback/logs/phase-a-shadow-runtime-20260909-111359/`
