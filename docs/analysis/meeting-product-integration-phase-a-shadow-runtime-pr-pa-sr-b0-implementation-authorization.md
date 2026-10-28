# Phase A Shadow Runtime — PR-PA-SR-B0 Implementation Authorization

**PR:** `PR-PA-SR-B0` — SOURCE Signer/Sender Authority Fence  
**Design:** `meeting-product-integration-phase-a-shadow-runtime-rca-b0-fence-design.md`  
**Parent chain:** `meeting-product-integration-phase-a-shadow-runtime-rca-b-fix-design.md`  
**RCA:** `meeting-product-integration-phase-a-shadow-runtime-rca-b-handoff.md`  
**Signed:** 2026-09-09  
**Status:** **CLOSED / UNIT-PROVEN** (2026-09-09)

```text
Authorization status:
  PR-PA-SR-B0 CLOSED / UNIT-PROVEN

PRE-AUTH RESULT:  A / PASS

PR-PA-SR-B1     IMPL AUTHORIZED · `…-pr-pa-sr-b1-implementation-authorization.md`
PA-SR3          BLOCKED (pending B1 + field)
PA-SR4…SR6      HOLD

J0 / P1-C / P1-E   UNCHANGED_CLOSED
ADR-0056           KEEP
cutover            NOT AUTHORIZED
```

---

## Goal (authorized)

For **verified** `SOURCE_DECLARATION` (`factType=9`):

```text
authenticatedSignerModuleId == declaration.senderModuleId
  → continue existing SOURCE semantic validation

authenticatedSignerModuleId != declaration.senderModuleId
  → REJECT with SIGNER_SENDER_MISMATCH
```

B0 closes the **SOURCE self-declaration authority prerequisite** for pre-auth.  
B0 **is not** PA-SR3 progress and **does not** authorize origin publication or field runs.

---

## Authorized implementation scope

### IN

```text
- SOURCE verification path exposes authenticated signer module identity
      (SOURCE-local minimal; avoid global Verified refactor unless strictly smaller diff)
- SOURCE semantic validation enforces signer == sender
- explicit SIGNER_SENDER_MISMATCH rejection reason
- focused unit tests T1–T5
```

### OUT

```text
- MeetingProfile01SourceOriginBridge
- encodeSourceDeclarationFullFact
- SOURCE origin / publication / republication
- registry.publishMember behavior changes
- mediaConnectedModules changes
- generic trust framework refactor
- global Verified model refactor (unless implementation proves strictly smaller diff — must be noted in PR)
- MEMBER_MEDIA_READY hook changes
- J0 / P1-C / P1-E
- PA-SR3 field run
- PA-SR4…SR6
- ADR-0056 / Meeting media cutover
```

---

## Hard constraints (non-negotiable)

### HC-1 — Crypto verification remains authoritative

```text
Existing signature verification remains authoritative.
B0 adds semantic identity binding; it does not replace crypto verification.
```

B0 runs **after** successful signature verify (or within the same trust boundary that already proved the signature).  
Do not accept SOURCE on signer==sender match alone without valid signature.

### HC-2 — Trust failures stay distinct

```text
Unknown / untrusted signer continues through existing rejection path.
Do not collapse trust failure into SIGNER_SENDER_MISMATCH.
```

| Failure class | Expected reason family |
|---------------|------------------------|
| Bad signature / unknown key / unverifiable key | Existing trust rejections (`UNKNOWN_SIGNER_KEY`, `SIGNER_KEY_NOT_VERIFIABLE`, verify fail, etc.) |
| Valid signature, signer ≠ declared sender | **`SIGNER_SENDER_MISMATCH`** (new) |

### HC-3 — B0 does not auto-authorize B1

```text
B0 PASS does not authorize B1 automatically.

T1–T5 PASS
  → update PRE-AUTH RESULT = A
  → separately authorize PR-PA-SR-B1 implementation
```

Merging B0 **only** unlocks the pre-auth gate.  
`SourceOriginBridge` and all B1 origin work remain **NOT AUTHORIZED** until explicit B1 implementation authorization is signed.

---

## Acceptance criteria (unit — merge gate)

| Id | Case | Required outcome |
|----|------|------------------|
| **T1** | signer=A, sender=A | **ACCEPT** (honest self-declaration; publish path may proceed) |
| **T2** | signer=A, sender=B | **REJECT** — `SIGNER_SENDER_MISMATCH` |
| **T3** | signer=B, sender=A | **REJECT** — `SIGNER_SENDER_MISMATCH` |
| **T4** | unknown / untrusted signer | **REJECT** — existing trust rejection (not `SIGNER_SENDER_MISMATCH`) |
| **T5** | honest SOURCE | All **existing** SOURCE semantic validations preserved (roster, epoch fences, stale generation, digest, etc.) |

**Additional merge requirements:**

```text
- Profile01ConferenceMediaFactIngressChainTest (or equivalent golden chain) — no regression on honest path
- CREATION / MEMBERSHIP / MEDIA_KEY_PACKAGE verify callers — no behavior change
- No field scripts, no coordinator origin hooks, no OBS contract changes for PA-SR3
```

**Field:** **NONE** for B0.

---

## State transition on B0 closure (only)

When T1–T5 pass in CI and PR merges:

```text
PR-PA-SR-B0        CLOSED / UNIT-PROVEN
Pre-auth gate        A / PASS
PR-PA-SR-B1        DESIGN FROZEN / READY FOR SEPARATE AUTHORIZATION
PA-SR3             still BLOCKED pending B1 + field
PA-SR4…SR6         HOLD
```

**Do not document** B0 closure as “PA-SR3 progress.”  
Wording: **SOURCE self-declaration authority prerequisite CLOSED.**

---

## Implementation hints (authorized surfaces)

| Surface | Allowed change |
|---------|----------------|
| `Profile01ProductionSignedFactTrustBoundary` | Expose authenticated `signerModuleId` on SOURCE verify path |
| `Profile01ConferenceMediaFactValidator.validateMember` | `signerModuleId == wire.moduleId` fence |
| SOURCE-scoped verify helper (preferred) | Alternative to global `Verified` extension |
| New unit test class | T1–T5 |

---

## Closure record (unit adjudication)

| Item | Value |
|------|-------|
| **PR** | PR-PA-SR-B0 |
| **Closure date** | 2026-09-09 |
| **PRE-AUTH RESULT** | **A / PASS** |
| **Tests** | T1–T5 PASS · `Profile01SourceSignerSenderFenceTest` |
| **Regression** | `Profile01ConferenceMediaFactIngressChainTest` · `Profile01WireCborDecoderTest` PASS |
| **Field** | NONE (not in B0 scope) |
| **Closes** | SOURCE self-declaration authority prerequisite only — **not** PA-SR3 progress |
| **Next** | Sign PR-PA-SR-B1 implementation authorization separately |

---

## References

- B0 design: `meeting-product-integration-phase-a-shadow-runtime-rca-b0-fence-design.md`
- B1 design (blocked): `meeting-product-integration-phase-a-shadow-runtime-rca-b-fix-design.md`
- Field evidence (RCA-B): `talkback/logs/phase-a-shadow-runtime-20260909-111359/`
