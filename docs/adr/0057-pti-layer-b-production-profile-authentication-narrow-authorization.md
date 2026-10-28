# ADR-0057: PTI Layer B — Production Profile Authentication Narrow Implementation Authorization

## Status

**IMPLEMENTED — CLOSED** (2026-08-31)

**Grill:** COMPLETE · B-EG1..B-EG5 PASS · PTI-G1-A..G14 PASS · ADR-0057 regression 80/80 PASS

```text
ADR-0057 — PTI Layer B
Production Profile Authentication

Implementation Conformance:
  PASS

Status:
  IMPLEMENTED
  CLOSED

PTI-B-IA-T1 Established Anchor Boundary
  CLOSED / PASS

PTI-B-IA-T2 Scope-Bound Anchor Selection
  CLOSED / PASS

PTI-B-IA-T3 Protected Schema Version Admission
  CLOSED / PASS

Profile revision signature domain:
  TALKBACK-PROFILE-REVISION-AUTH-V1
  (isolated from Generation Fact signature domain)

B-EG1  ProductionProfileRevisionAuthenticator                    PASS
B-EG2  protected-semantics tamper → auth reject / last-good      PASS
B-EG3  authenticated older revision → rollback reject            PASS
B-EG4  same revision + different semantics → conflict reject     PASS
B-EG5  immutable domain-bound anchor seam / fixture≠field         PASS

Operational Provisioning / Establishment:
  NOT CLAIMED

Production Trust Integration:
  IMPLEMENTATION CONFORMANCE PASS

Production Verifier Wiring:
  CLOSED / PASS

Field Establishment Ceremony / Transport:
  NOT IMPLEMENTED / NOT CLAIMED

ADR-0057 COMPLETE:
  NO
```

**Parents:**

- [PAP Contract](./0057-profile-authentication-establishment-provisioning-contract.md) — **ACCEPTED / FROZEN**. Implementation MUST conform to PAP-1..5 and PAP-T1..T4.
- PTI Layer A — **CLOSED / PASS**. `ProfileRevisionAcceptor`, semantic validator, persistence, and `ProfileBackedGenerationFactTrustLookup` semantics **MUST NOT** be reopened except as required by explicit schema extension authorized below.

**Does NOT authorize:**

- Production Verifier Wiring (`Coordinator` / `VerificationBoundary` → `ProductionGenerationFactVerifier` + real adapter)
- Full operator root-establishment / provisioning ceremony product workflow
- In-band operational-root rotation
- Certificate chain hierarchy design freeze
- MDM / USB / QR / LAN transport selection or implementation
- `talkback-app` `TaskProfile` migration, merge, or production loading path
- ADR-0057 COMPLETE
- Production Trust Integration end-to-end claim
- Operational provisioning production-closed claim on fixture anchor evidence alone

---

## Why this authorization exists

PAP contract removed the **authority-design** blocker. Layer B implementation
remains unauthorized until this narrow cut explicitly permits production
authentication code.

```text
Layer B implementation target

established pinned operational anchor (input seam)
        ↓
ProductionProfileRevisionAuthenticator
        ↓
deployment trust scope validation (PAP-T1)
        ↓
complete protected-semantics authentication (PAP-3)
        ↓
existing Layer A ProfileRevisionAcceptor (unchanged semantics)
```

This knife authorizes **authentication production code + conformance evidence**,
not deployment operations or verifier wiring.

---

## Two fences (mandatory)

### Fence 1 — Establishment ceremony ≠ this knife

```text
Root establishment / provisioning ceremony
  ≠ 本刀必须实现完整 operator workflow
```

**Authorized:** read/verify against a **pre-established** pinned operational trust
anchor supplied through a narrow input seam (file, injectable store, or test
fixture representing PAP-T2–conformant anchor material).

**Not authorized:** operator UI, manufacturing line integration, MDM enrollment
flows, out-of-band verification UX, or product-path anchor installation.

If field devices lack a trustworthy anchor establishment/persistence path,
implementation conformance MAY still PASS on harness evidence, but **operational
provisioning production closure MUST NOT** be claimed.

### Fence 2 — Authentication ≠ wiring

```text
Production Profile Authentication
  ≠ Production Verifier Wiring
```

Layer B PASS proves a real authenticator can gate Layer A acceptance. It does
**not** authorize connecting `ProfileBackedGenerationFactTrustLookup` into
`TalkbackCoordinator` or live verification paths.

---

## Authorized scope (four blocks only)

### Block 1 — `ProductionProfileRevisionAuthenticator`

Implement a production `ProfileRevisionAuthenticator` that:

1. Is **not** `FixtureProfileRevisionAuthenticator` (B-EG1).
2. Verifies operational authority proof over the **exact protected semantic
   representation** authenticated by PAP-3 (complete-semantics binding).
3. Permits **minimal pre-auth structural/envelope parse** with **no authority
   advancement** before successful authentication (`parse ≠ trust`).
4. On authentication failure, returns `ProfileAuthenticationResult.Rejected`;
   Layer A retain last-good behavior remains unchanged.
5. Does **not** bypass `ProfileRevisionAcceptor` ordering, conflict, semantic,
   or persistence rules.

**V1 signature shape (implementation detail, not contract freeze):**

- Single operational signing key per pinned anchor (ECDSA P-256 / SHA-256
  aligned with PV-1 crypto posture) is sufficient for this narrow authorization.
- Multi-level certificate chains: **NOT authorized** to invent in this knife.
- Envelope MUST bind signature to the authenticated byte sequence whose semantic
  content equals Layer A protected semantics after canonical decode.

### Block 2 — Pinned operational trust anchor read / verify seam

Implement narrow anchor material access:

```text
OperationalTrustAnchor (or equivalent)
  - deploymentTrustDomainId (or equivalent namespace id)
  - operationalAuthorityPublicKey (verify-only material)
  - optional metadata for harness provenance only
```

```text
OperationalTrustAnchorSource / Store (read-only at authenticate time)
  - load pinned anchor for local device
  - fail closed if absent / malformed
```

**Authorized patterns:**

- Injectable / file-backed anchor store for tests and library integration seam
- `TestEstablishedOperationalTrustAnchor` harness fixture representing
  PAP-T2–conformant **already established** anchor state

**Not authorized:**

- Anchor establishment ceremony implementation
- In-band anchor rotation or superseding-anchor revision handling
- Writing anchor from unauthenticated candidate Profile delivery

### Block 3 — `deploymentTrustDomainId` scope binding (PAP-T1)

Extend protected Profile trust representation **minimally** to carry an explicit
`deploymentTrustDomainId` inside authenticated protected semantics.

Requirements:

1. Field is part of **protected semantics** covered by authentication proof
   and included in canonical encoding / `revisionIdentity` derivation.
2. Authenticator **MUST** reject candidate revisions whose
   `deploymentTrustDomainId` does not match the pinned anchor's trust domain.
3. `channelId`, display name, static peers, `sharedSecret`, and other mutable
   product fields **MUST NOT** substitute for scope binding.
4. Layer A acceptance rules otherwise unchanged; scope mismatch is an
   **authentication / authority rejection**, not a Layer A semantic quirk.

**Authorized schema action:**

- Bump `ProfileRevisionCanonicalCodec` schema (e.g. v2) with backward-isolated
  test coverage; existing Layer A fixtures updated in harness only.

**Not authorized:**

- Using anchor namespace alone (PAP-T1 option B) **instead of** explicit field
  in this knife — pick **option A** for implementation clarity unless Grill
  amendment says otherwise.

### Block 4 — B-EG1..B-EG5 conformance harness

Add Layer B conformance tests (new test class or extension; MUST NOT weaken
PTI Layer A gates).

| Gate | Requirement |
|------|-------------|
| **B-EG1** | Non-fixture `ProductionProfileRevisionAuthenticator` used in harness |
| **B-EG2** | Tamper protected semantics after signing → authentication reject; last-good retained |
| **B-EG3** | Authenticated older `taskProfileRevision` → Layer A rollback reject; last-good retained |
| **B-EG4** | Same `taskProfileRevision`, different authenticated protected semantics → conflict reject; last-good retained |
| **B-EG5** | Operational trust root provenance documented in code/test contract: harness uses **established** pinned anchor fixture; authenticator verifies against pinned material; document that fixture ≠ field provisioning closure |

B-EG3 and B-EG4 may exercise the **full** `ProfileRevisionAcceptor` pipeline
with production authenticator + established anchor fixture.

Regression: existing PTI-G1-A..G14 and ADR-0057 suite **MUST** remain PASS.

---

## Evidence layering (claim boundary)

Implementation conformance and operational deployment closure are **separate**
claims.

| Evidence class | May claim on Layer B PASS |
|----------------|---------------------------|
| Production authenticator obeys PAP-3 + PAP-T1 | **YES** |
| B-EG1..B-EG5 harness PASS | **YES** |
| Layer A + production authenticator integration | **YES** |
| Operational provisioning production-closed | **NO** (unless separate field evidence) |
| Root establishment ceremony implemented | **NO** |
| Production Trust Integration | **YES** (post-PW CLOSED / PASS) |
| Production Verifier Wiring | **YES** (post-PW CLOSED / PASS) |
| ADR-0057 COMPLETE | **NO** |

**Maximum claim after Layer B implementation conformance PASS:**

```text
ADR-0057 Production Profile Authentication
Implementation Conformance: PASS

Operational Provisioning / Establishment:
  NOT IMPLEMENTED / NOT CLAIMED

Production Trust Integration
Implementation Conformance:
  PASS

Production Verifier Wiring:
  CLOSED / PASS

Field Establishment Ceremony / Transport:
  NOT IMPLEMENTED / NOT CLAIMED
```

Fixture-established anchor in tests proves **authenticator behavior given a
valid pinned anchor input**, not that product devices can establish anchors in
the field.

---

## Not authorized (explicit)

```text
In-band operational-root rotation
X.509 / multi-level certificate chain product
MDM / USB / QR / LAN provisioning transport
Operator establishment UI or ceremony
talkback-app TaskProfile production migration
Coordinator / VerificationBoundary production wiring
PV-1 / PV-2 semantic changes
GBC core / delivery / wire changes
PTT / Track C
ADR-0057 COMPLETE
```

**Prohibited authority substitutes (unchanged from PAP):**

```text
sharedSecret
Generation Fact origin signing key
PV-2 issuance key
device self-signed key
HELLO / peer gossip
election / Primary authority
```

---

## Implementation boundaries

### May change

```text
android-board-talkback/.../trust/profile/
  ProductionProfileRevisionAuthenticator (+ envelope codec if needed)
  OperationalTrustAnchor* types / store seam
  GenerationFactProfileTrustPayload + ProfileRevisionCanonicalCodec (schema v2)
  Layer B conformance tests + fixture signing helper (test scope)
  Minimal test-only signing key helper for operational authority (NOT production authority tooling)
```

### Must not change

```text
ProfileRevisionAcceptor acceptance semantics (except consuming new schema field)
ProfileTrustSemanticValidator rules unrelated to scope field presence
ProfileBackedGenerationFactTrustLookup projection rules
ProductionGenerationFactVerifier
PV-2 issuance
TalkbackCoordinator verifier wiring
talkback-app TaskProfile*
```

### Dependency direction

```text
ProductionProfileRevisionAuthenticator
  → reads OperationalTrustAnchorSource (pinned)
  → verifies envelope + scope
  → returns Authenticated / Rejected only

ProfileRevisionAcceptor
  → unchanged gate order after auth success
```

---

## Grill focus for authorization decision

Reviewers SHOULD rule on:

1. **Authority source** — single pinned operational verify key per trust domain;
   no invented CA hierarchy in this knife.
2. **Scope binding** — explicit `deploymentTrustDomainId` in protected semantics;
   mismatch = auth reject.
3. **Protected-semantics authentication** — signature binds complete semantics;
   PTI-IA-T4 preserved.
4. **Anchor evidence** — established-anchor input seam only; no establishment
   ceremony scope creep.
5. **B-EG1..5** — sufficient to prevent fixture-for-production claim conflation.

---

## Suggested implementation sequence (if AUTHORIZED)

```text
1. Schema v2 + deploymentTrustDomainId in protected semantics
2. OperationalTrustAnchor + read-only store seam
3. Authenticated envelope codec + ProductionProfileRevisionAuthenticator
4. Test operational authority signing helper (test-only)
5. B-EG1..B-EG5 harness
6. PTI-G* regression + ADR-0057 suite
7. Conformance review → claim boundary check
```

---

## Dependency chain

```text
PV-1                          CLOSED / PASS
PV-2                          CLOSED / PASS
PTI Layer A                   CLOSED / PASS
PAP Contract                  FROZEN / PASS
PTI Layer B                   CLOSED / PASS

Device-Side Operational Est.  CLOSED / PASS
Production Verifier Wiring    CLOSED / PASS
  → 0057-production-verifier-wiring-narrow-authorization.md

Production Trust Integration
  IMPLEMENTATION CONFORMANCE    PASS

Track C Field Validation      NEXT / OPEN
  see [Track C Case A Run Card](../analysis/adr0057-track-c-production-convergence-field-validation-run-card.md)
ADR-0057 COMPLETE             NO
```

---

## Authorization decision (record)

```text
Decision:
  AUTHORIZE WITH TIGHTENING

Tightenings:
  PTI-B-IA-T1 Established Anchor Boundary
  PTI-B-IA-T2 Scope-Bound Anchor Selection
  PTI-B-IA-T3 Protected Schema Version Admission

Implementation start:
  GRANTED

Conformance:
  PASS (2026-08-31)
```

---

## Implementation artifacts

| Artifact | Role |
|----------|------|
| `OperationalTrustAnchor.kt` | PAP-T2-established anchor + read-only `PinnedOperationalTrustAnchorStore` |
| `AuthenticatedProfileRevisionEnvelope.kt` | Signed delivery + isolated signature domain |
| `ProductionProfileRevisionAuthenticator.kt` | Production authentication (scope + crypto verify) |
| `ProfileRevisionCanonicalCodec` schema v2 | `deploymentTrustDomainId` in protected semantics |
| `ProfileRevisionDeliveryCodec` | Envelope vs raw protected resolution for acceptor |
| `Adr0057PtiLayerBFixturesTest.kt` | B-EG1..B-EG5 + scope/schema negative tests |
| `OperationalProfileTestSigning.kt` | Test-only operational issuer (isolated from GF signing) |

---

## B-EG conformance record

| Gate | Result |
|------|--------|
| B-EG1 | PASS — `ProductionProfileRevisionAuthenticator`, not fixture |
| B-EG2 | PASS — tamper → auth reject; last-good retained |
| B-EG3 | PASS — authenticated older revision → rollback reject |
| B-EG4 | PASS — same revision, different semantics → conflict reject |
| B-EG5 | PASS — anchor pre-established, immutable, domain-bound; fixture ≠ field provisioning |
