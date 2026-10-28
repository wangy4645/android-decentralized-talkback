# Phase A Shadow Runtime — PR-PA-SR-B0 SOURCE Signer/Sender Authority Fence

**Parent:** `meeting-product-integration-phase-a-shadow-runtime-rca-b-fix-design.md`  
**RCA:** `meeting-product-integration-phase-a-shadow-runtime-rca-b-handoff.md`  
**Status:** **CLOSED / UNIT-PROVEN** (2026-09-09)  
**Authorization:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b0-implementation-authorization.md`

```text
PR-PA-SR-B0        CLOSED / UNIT-PROVEN
Pre-auth gate        A / PASS

PR-PA-SR-B1        DESIGN FROZEN · READY FOR SEPARATE AUTHORIZATION
PA-SR3             BLOCKED (B0 → B1 → field)
PA-SR4…SR6         HOLD

J0 / P1-C / P1-E   UNCHANGED_CLOSED
ADR-0056           KEEP
cutover            NOT AUTHORIZED
```

---

## Why Path 2 (not Pre-auth PASS)

Two facts hold simultaneously:

```text
authority[4] structurally co-locates signer key lookup and senderModuleId decode
BUT
validateMember has no explicit semantic binding assertion
```

That is **insufficient** to claim:

```text
authenticatedSignerModuleId == declaration.senderModuleId
```

**PRE-AUTH RESULT = B / NOT PASS** remains correct.  
**Do not** interpret structural co-location as receiver-enforced self-declaration.

**Legal sequence:**

```text
B0 fence PASS + T1–T5 (incl. impersonation reject)
  → PRE-AUTH RESULT = A
  → PR-PA-SR-B1 implementation authorization
```

**Forbidden:** `SourceOriginBridge` or any B1 origin code before **PRE-AUTH RESULT = A**.

---

## B0 goal (one sentence — frozen)

> 对已验签的 `SOURCE_DECLARATION`，接收端必须**显式**证明 `authenticatedSignerModuleId == declaration.senderModuleId`，否则拒绝该 SOURCE fact。

B0 **does not** publish SOURCE, **does not** modify `registry.publishMember` behavior, **does not** close PA-SR3.  
B0 **only** closes the pre-auth security contract required by B1.

---

## Pre-auth gate (binary — frozen)

Implementation authorization for **B1** must record:

```text
PRE-AUTH RESULT

A. PASS
   Receiver enforces: authenticatedSignerModuleId == declaration.senderModuleId
   (proven by B0 merge + T2/T3)
   → PR-PA-SR-B1 implementation MAY be authorized

B. NOT PASS
   → PR-PA-SR-B0 (this PR) first
   → after B0 PASS, re-sign PRE-AUTH RESULT = A
```

**Current posture:** **B** until B0 ships and T2/T3 pass.

---

## Design cut 1 — Minimal change strategy (avoid trust refactor)

**Preference:** SOURCE-local carry of authenticated signer identity — **not** a global `Verified` model upgrade unless proven minimal.

| Approach | Verdict |
|----------|---------|
| **A (preferred)** | Extend verification outcome **only on SOURCE path**: e.g. `verifySourceSignedFact` → `SourceVerified(signerModuleId, factDigest)` used exclusively by `validateMember` |
| **B (acceptable if smaller diff)** | Add optional `signerModuleId` to `Profile01WireVerificationResult.Verified` — **only populated for factType=9** in production trust boundary; other fact types pass `null` / omit |
| **C (reject)** | Full trust-architecture refactor; generic signer identity on all fact types; new ADR for cross-fact `Verified` semantics |

**Rule:** Choose A or B at implementation time by **line-count + blast radius**; document choice in PR. Default hypothesis: **A** — `Profile01ProductionSignedFactTrustBoundary` already resolves `signerModuleId` internally; expose it through a SOURCE-scoped helper without changing CREATION/MEMBERSHIP/PACKAGE verify callers.

### Where fence lives (frozen)

```text
ingestSourceDeclarationSignedFact
  → decode (senderModuleId from wire)
  → trust verify (authenticated signer module id)
  → validateMember:
       IF signerModuleId != wire.moduleId → Invalid("SIGNER_SENDER_MISMATCH")  [new]
       ELSE existing semantic gates unchanged
```

Fence is **semantic validation**, not wire-format change. No CBOR schema change in B0.

### Reject reason (frozen)

```text
SIGNER_SENDER_MISMATCH
```

Distinct from `UNKNOWN_SIGNER_KEY`, `STALE_SOURCE_GENERATION`, `MEMBER_NOT_IN_ROSTER`, etc.

---

## Design cut 2 — What “authenticated signer” means

| Term | Definition |
|------|------------|
| `authenticatedSignerModuleId` | Module id whose trusted signing key **verified** the signature on this `signedFactBytes` (production: from trust lookup after successful ECDSA verify) |
| `declaration.senderModuleId` | `wire.moduleId` from decoded SOURCE authority field 4 |

Equality must be **explicitly compared** after verify succeeds — not inferred from “same CBOR field was read twice.”

---

## PR-PA-SR-B0 — frozen scope

```text
PR-PA-SR-B0 — SOURCE Signer/Sender Authority Fence

IN
- verifier / validated-fact path exposes authenticated signer module identity (SOURCE-local minimal)
- validateMember (SOURCE) adds signer ↔ sender equality fence
- B0 focused unit tests T1–T5
- reject reason SIGNER_SENDER_MISMATCH

OUT
- MeetingProfile01SourceOriginBridge
- encodeSourceDeclarationFullFact / SOURCE publication / republication
- registry.publishMember behavior change
- mediaConnectedModules
- generic trust refactor / global Verified redesign (unless B minimal diff wins audit)
- MEMBER_MEDIA_READY hook change
- J0 / P1-C / P1-E
- PA-SR4…SR6
- field run / PA-SR3 closure
- ADR-0056 cutover
```

### Likely touch surfaces (implementation hint only)

| File | Change class |
|------|--------------|
| `Profile01ProductionSignedFactTrustBoundary.kt` | Return or helper exposes `signerModuleId` post-verify for SOURCE |
| `Profile01ConferenceMediaFactValidator.kt` | `validateMember`: explicit `signerModuleId == wire.moduleId` |
| `Profile01WireTrustBoundary.kt` or SOURCE-scoped verify helper | Only if Approach B chosen |
| New `Profile01SourceSignerSenderFenceTest.kt` (or extend ingress chain test) | T1–T5 |

**No** `TalkbackCoordinator` / origin bridge / field script changes.

---

## Test matrix (frozen — B0 acceptance)

These tests **are** the security gate for pre-auth. B1-2 **moves here** (B0 owns impersonation reject; B1 references B0 PASS).

| Id | Case | Expected |
|----|------|----------|
| **T1** | signer=A, sender=A (golden / honest self) | **ACCEPT** — publish path succeeds |
| **T2** | signer=A, sender=B (valid sig, wrong sender field) | **REJECT** — `SIGNER_SENDER_MISMATCH` (or verify fail if structurally impossible without fence) |
| **T3** | signer=B, sender=A | **REJECT** — same as T2 |
| **T4** | unknown / untrusted signer | **REJECT** — existing trust rejection preserved (no regression) |
| **T5** | honest SOURCE | All **other** semantic validations unchanged (roster, epoch, stale generation, digest) |

### T2/T3 fixture strategy

Golden vectors sign with module key matching sender in honest fixture. B0 tests need **crafted mismatch**:

- Build canonical SOURCE CBOR with `senderModuleId=B`, sign with **A**’s key (harness signer), or
- Test-only trust boundary that returns `signerModuleId=A` while wire decodes `moduleId=B`

**Requirement:** T2/T3 must fail **after** signature verifies under A’s key — proving fence is semantic, not crypto-only.

---

## B0 acceptance criteria

### Unit (pre-merge)

```text
T1–T5 PASS
No regression in Profile01ConferenceMediaFactIngressChainTest golden chain (T1 / T5)
CREATION / MEMBERSHIP / MEDIA_KEY_PACKAGE verify paths unchanged (no new mandatory Verified fields for non-SOURCE)
```

### Field

```text
NONE — B0 is unit-only. PA-SR3 remains blocked until B1 + field.
```

### Pre-auth sign-off (post-B0 merge)

```text
PRE-AUTH RESULT := A
Evidence: T2 + T3 PASS in CI
Sign-off recorded in PR-PA-SR-B1 implementation authorization (separate doc / comment)
```

---

## Relationship to B1

| PR | Role |
|----|------|
| **B0** | Security contract: receiver-enforced self-declaration |
| **B1** | Origin + transport + `registry.publishMember` (blocked until PRE-AUTH = A) |

After B0:

```text
B1 unit matrix retains B1-1, B1-3…B1-7
B1-2 satisfied by B0 T2/T3 (reference, do not duplicate unless ingress e2e needs one smoke)
```

---

## Frozen routing

```text
NOW     PR-PA-SR-B0 implementation authorized (see authorization doc)
NEXT    B0 impl + T1–T5 PASS → CLOSED / UNIT-PROVEN → PRE-AUTH RESULT = A
THEN    Separate PR-PA-SR-B1 implementation authorization
NEVER   SourceOriginBridge before PRE-AUTH = A
```

---

## References

- B1 design (blocked): `meeting-product-integration-phase-a-shadow-runtime-rca-b-fix-design.md`
- RCA-B handoff: `meeting-product-integration-phase-a-shadow-runtime-rca-b-handoff.md`
- Code: `Profile01ProductionSignedFactTrustBoundary.kt` · `Profile01ConferenceMediaFactValidator.validateMember` · `Profile01WireCborDecoder.decodeSourceDeclarationMember` · `Profile01WireTrustBoundary.kt`
