# ADR-0057: Operational Establishment Narrow Implementation Authorization

## Status

**IMPLEMENTED — CLOSED** (2026-08-31)

**Grill:** COMPLETE · OE-EG1..OE-EG6 PASS · ADR-0057 regression 89/89 PASS

```text
ADR-0057 — Operational Establishment

Decision:
  AUTHORIZE WITH TIGHTENING

Implementation Conformance:
  PASS

Status:
  IMPLEMENTED
  CLOSED

Required tightenings:
  OE-IA-T1 First-Establishment Capability       CLOSED / PASS
  OE-IA-T2 Create-Once Linearization              CLOSED / PASS
  OE-IA-T3 Durable Anchor Integrity               CLOSED / PASS

Device-Side Operational Anchor
Establishment / Persistence:
  IMPLEMENTED / PASS

Field Establishment Ceremony / Transport:
  NOT IMPLEMENTED / NOT CLAIMED

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

- [PAP Contract](./0057-profile-authentication-establishment-provisioning-contract.md) — **ACCEPTED / FROZEN**. OE implements **PAP-T2** establishment properties only. Does **not** reopen PAP-1..5 or PAP-T1/T3/T4.
- [PTI Layer B](./0057-pti-layer-b-production-profile-authentication-narrow-authorization.md) — **CLOSED / PASS**. `ProductionProfileRevisionAuthenticator` consumes read-only `OperationalTrustAnchorSource`. OE supplies the **product/runtime** anchor load path that Layer B tests currently simulate via harness fixtures.

**Does NOT authorize:**

- Production Verifier Wiring
- Production Trust Integration end-to-end claim
- `talkback-app` `TaskProfile` / `TaskProfileStore` migration or reuse as root-of-trust storage
- Anchor replacement, in-band root rotation, certificate hierarchy, remote auto-enrollment
- USB / QR / MDM / LAN provisioning transport selection or operator UI
- ADR-0057 COMPLETE
- Track C field validation

---

## Why this knife exists

PTI Layer B closed **authentication** given a pre-established pinned anchor.
The remaining trust-source gap is **establishment and persistence**:

```text
Layer B today:
  harness pre-installs PAP-T2-compliant anchor fixture
  → PinnedOperationalTrustAnchorStore
  → ProductionProfileRevisionAuthenticator

Product gap:
  where does the device get that anchor?
  why is it trusted?
  how is it persisted across restart?
  what happens when anchor is absent?
```

Wiring `ProductionGenerationFactVerifier` before OE would invert evidence:

```text
live production consumer
        ↓
production verifier
        ↓
production Profile auth
        ↓
non-production anchor establishment   ← evidence gap
```

OE closes the **anchor establishment / persistence seam** only.

---

## Scope fence

```text
OE implementation MAY establish the first anchor.

OE MUST NOT implement:
  anchor replacement
  root rotation
  certificate hierarchy
  remote auto-enrollment
```

OE is **not** a PKI or full provisioning subsystem. It is the narrow product seam
that makes PAP-T2 **durable on device**.

```text
delivery mechanism  ≠  establishment authority   (unchanged from PAP)
```

---

## OE-1 — Establishment Authority

**PAP reference:** PAP-T2 Root Establishment Provenance

Only **PAP-T2-compliant, independently trusted provenance** MAY produce an
established operational anchor.

### Requirements

1. Establishment is an explicit, auditable decision — not first runtime
   observation (NO TOFU).
2. Acceptable provenance inputs (implementation MAY support one or more, but
   MUST NOT invent new authority classes without amendment):
   - operator out-of-band verification of anchor fingerprint / domain binding
   - managed-installation attestation channel (form deferred)
   - authenticated manufacturing / enterprise provisioning payload (form deferred)
   - equivalent pre-established trust channel documented in establishment metadata
3. **Forbidden writers.** The following **MUST NOT** create or modify established
   anchor state:
   ```text
   candidate Profile revision delivery
   sharedSecret
   HELLO / peer gossip
   Module signing key / enrollment evidence
   election / Primary authority
   ProfileRevisionAcceptor acceptance
   ProductionProfileRevisionAuthenticator
   ```
4. `ProductionProfileRevisionAuthenticator` remains **read-only** with respect
   to anchor establishment (PTI-B-IA-T1 preserved).

### OE-EG1 (future harness)

Establishment succeeds only from authorized provenance seam; forbidden inputs
cannot write anchor.

---

## OE-2 — First-Install Atomicity

### Requirements

1. **Success path:** complete validated anchor → single durable commit → becomes
   readable by `OperationalTrustAnchorSource`.
2. **Failure / interrupt path:** no usable anchor exposed to authentication or
   Profile acceptance paths.
3. **No partial established state.** Implementations **MUST NOT** expose:
   ```text
   domain id present but public key missing
   public key present but domain unbound
   establishment metadata committed without validated key identity
   ```
4. Concurrent or duplicate establishment attempts **MUST** fail closed or
   idempotently succeed with identical anchor material — never leave ambiguous
   composite state.

### OE-EG2 (future harness)

Simulated persist failure / incomplete write → `pinnedAnchor()` returns null;
authenticator fail closed.

---

## OE-3 — Durable Persistence

### Requirements

1. Established anchor **MUST** persist at minimum:
   ```text
   deploymentTrustDomainId
   operationalAuthorityPublicKeySpki (exact identity bytes)
   establishment metadata sufficient to audit provenance class
   ```
2. **Restart recovery:** after process restart, load returns the **same**
   authority identity (domain + SPKI bytes) unless establishment was never
   successfully committed.
3. Persistence format is an implementation detail; properties are normative.
4. Persistence store is **dedicated operational anchor storage** — not
   conflated with Accepted Local Trust State or Profile revision acceptance
   store unless explicitly layered with separate authority semantics (not
   recommended in this knife).

### OE-EG3 (future harness)

Establish → persist → new store instance / simulated restart → same domain +
SPKI recovered.

---

## OE-4 — Domain Binding

**PAP reference:** PAP-T1 + PAP-T2

### Requirements

1. Persisted anchor **MUST** bind `deploymentTrustDomainId` to
   `operationalAuthorityPublicKeySpki` as an atomic unit.
2. Load path **MUST** reject persisted records where:
   - domain id blank or malformed
   - SPKI empty or unparsable as operational verify key
   - stored domain/id tuple inconsistent with record header checksum or
     equivalent integrity guard (if used)
3. `ProductionProfileRevisionAuthenticator` scope validation (PTI-B-IA-T2) **MUST**
   remain consistent with loaded anchor domain — wrong domain lookup → fail closed.
4. Cross-domain anchor replay from persistence **MUST NOT** succeed.

### OE-EG4 (future harness)

Corrupt domain field or SPKI/domain mismatch in persisted blob → load returns
null / fail closed.

---

## OE-5 — Replacement Fence

**PAP reference:** PAP-T2 anchor replacement (V1)

### Requirements

1. Once an anchor is successfully established and committed, ordinary candidate
   Profile provisioning deliveries **MUST NOT** overwrite it.
2. `ProfileRevisionAcceptor` / Layer A accepted trust state changes **MUST NOT**
   mutate operational anchor store.
3. **NOT AUTHORIZED in OE:**
   ```text
   in-band anchor replacement
   root rotation protocol
   superseding-anchor authenticated revision
   ```
4. Changing operational root requires a **future** explicit establishment or
   rotation contract — not silent overwrite in this knife.

### OE-EG5 (future harness)

After establishment, attempted overwrite via provisioning path / acceptor /
authenticator → anchor unchanged.

---

## OE-6 — Product-Path Evidence

### Requirements

1. A **non-fixture** runtime/product seam **MUST** exist that:
   ```text
   load established anchor from durable store
         ↓
   OperationalTrustAnchorSource (production adapter)
         ↓
   PinnedOperationalTrustAnchorStore or equivalent read model
         ↓
   ProductionProfileRevisionAuthenticator
   ```
2. **No anchor → fail closed.** When no successfully established anchor is
   loaded, `pinnedAnchor(expectedDomain)` returns null and Profile authentication
   cannot succeed.
3. Harness fixtures **MAY** remain for unit tests, but OE-EG6 **MUST** also
   exercise the production load adapter without test-only anchor injection.
4. OE-EG6 proves **persistence/load path**, not field deployment UX.

### TaskProfile fence (mandatory)

```text
Operational anchor store
  != TaskProfileStore

TaskProfile SharedPreferences
  MUST NOT silently become root-of-trust storage
```

`talkback-app` `TaskProfile` migration, merge, or shared-container authority is
**NOT AUTHORIZED** in this knife unless separately proven and authorized.

### OE-EG6 (future harness)

Production establishment + persistence adapter (no fixture pre-install) → load →
authenticator can verify signed v2 Profile revision for bound domain.

---

## Implementation boundaries

### Authorized (if AUTHORIZED)

```text
OperationalTrustAnchorEstablishment*   (establishment gate / provenance seam)
OperationalTrustAnchorPersistence*     (durable store — dedicated, not TaskProfile)
OperationalTrustAnchorSource production adapter
  (loads persisted anchor → read-only source for Layer B authenticator)
OE-EG1..OE-EG6 conformance harness
Minimal integration hook in android-board-talkback runtime/bootstrap
  (load anchor; fail closed if absent — no Coordinator wiring)
```

### Must not change

```text
ProductionProfileRevisionAuthenticator semantics (PTI Layer B CLOSED)
ProfileRevisionAcceptor / Layer A acceptance semantics
ProfileBackedGenerationFactTrustLookup
ProductionGenerationFactVerifier / PV-2
TalkbackCoordinator verifier wiring
GBC core / delivery / wire
talkback-app TaskProfile* (unless future separate authorization)
```

### Dependency direction

```text
Establishment (OE)  →  writes anchor store (one-time first install)
Persistence (OE)    →  reads/writes dedicated anchor blob
Load adapter (OE)   →  read-only OperationalTrustAnchorSource
Authentication (Layer B)  →  consumes loaded anchor only
Acceptance (Layer A)      →  unchanged; does not write anchor
```

---

## Evidence layering (claim boundary)

| Evidence | May claim on OE PASS |
|----------|----------------------|
| OE-EG1..OE-EG6 harness PASS | **YES** |
| PAP-T2 establishment on device (library/runtime seam) | **YES** |
| Durable anchor persistence + restart recovery | **YES** |
| Field operator provisioning UX | **NO** |
| Provisioning transport implemented | **NO** |
| Production Trust Integration | **YES** (via PW CLOSED / PASS) |
| Production Verifier Wiring | **YES** (CLOSED / PASS) |
| ADR-0057 COMPLETE | **NO** |

**Maximum claim after OE implementation conformance PASS:**

```text
ADR-0057 Operational Establishment
Implementation Conformance:
  PASS

Operational Anchor Establishment / Persistence:
  IMPLEMENTED / PASS

Production Trust Integration
Implementation Conformance:
  PASS

Production Verifier Wiring:
  IMPLEMENTED / PASS

Field Provisioning UX / Transport:
  NOT CLAIMED

ADR-0057 COMPLETE:
  NO
```

Fixture-only establishment in tests proves **seam behavior**, not field
operational ceremony closure.

---

## Conformance gates (OE-EG1..OE-EG6)

| Gate | Maps to | Pass criterion |
|------|---------|----------------|
| OE-EG1 | OE-1 | Authorized provenance writes anchor; forbidden inputs cannot |
| OE-EG2 | OE-2 | Failed/interrupted establishment → no usable anchor |
| OE-EG3 | OE-3 | Established anchor survives restart with same identity |
| OE-EG4 | OE-4 | Domain/key mismatch in persistence → fail closed |
| OE-EG5 | OE-5 | Established anchor not overwritten by provisioning paths |
| OE-EG6 | OE-6 | Production load adapter (non-fixture) → Layer B auth can proceed |

Regression: PTI Layer B (B-EG1..5), PTI Layer A (G1-A..G14), ADR-0057 suite
**MUST** remain PASS.

---

## Suggested implementation sequence (if AUTHORIZED)

```text
1. OperationalTrustAnchorPersistence (dedicated store; atomic commit)
2. Establishment gate (PAP-T2 provenance seam — minimal v1: operator-verified input)
3. Production OperationalTrustAnchorSource adapter (load-only)
4. Bootstrap hook: load anchor / fail closed (no verifier wiring)
5. OE-EG1..OE-EG6 harness
6. Regression: Layer B + Layer A + ADR-0057
7. Conformance review → claim boundary check
```

---

## B-EG / OE-EG conformance record

| Gate | Result |
|------|--------|
| OE-EG1 | PASS — PAP-T2 capability required; invalid provenance cannot construct record |
| OE-EG2 | PASS — create-once; concurrent single commit; different anchor rejected |
| OE-EG3 | PASS — restart identity recovery; corrupt persistence fail closed |
| OE-EG4 | PASS — domain-bound load; wrong domain not exposed |
| OE-EG5 | PASS — established anchor not overwritten |
| OE-EG6 | PASS — product load seam → Layer B auth; EMPTY fail closed |

---

## Implementation artifacts

| Artifact | Role |
|----------|------|
| `establishment/OperationalAnchorEstablishment.kt` | OE-IA-T1 auth capability, establisher, load source |
| `establishment/FileOperationalTrustAnchorPersistence.kt` | OE-IA-T2/T3 create-once + integrity |
| `establishment/Adr0057OperationalEstablishmentFixturesTest.kt` | OE-EG1..6 harness |

---

## Dependency chain

```text
PV-1                          CLOSED / PASS
PV-2                          CLOSED / PASS
PTI Layer A                   CLOSED / PASS
PAP Contract                  FROZEN / PASS
PTI Layer B                   CLOSED / PASS
Operational Establishment     CLOSED / PASS

Field Ceremony / Transport    OPEN / NOT CLAIMED
Production Verifier Wiring    CLOSED / PASS
  see [PW Narrow Authorization](./0057-production-verifier-wiring-narrow-authorization.md)

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
  OE-IA-T1 First-Establishment Capability
  OE-IA-T2 Create-Once Linearization
  OE-IA-T3 Durable Anchor Integrity

Implementation start:
  GRANTED

Conformance:
  PASS (2026-08-31)
```
