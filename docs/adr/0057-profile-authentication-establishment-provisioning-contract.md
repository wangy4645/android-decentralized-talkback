# ADR-0057: Profile Authentication Establishment & Provisioning Contract

## Status

**ACCEPTED — CONTRACT ONLY** (2026-08-31)

**Grill:** COMPLETE · PAP-G1..G5 PASS  
**Mode:** READ / CONTRACT ONLY  
**Implementation:** **NOT AUTHORIZED** (Layer B requires separate narrow authorization)  
**Field Validation:** **NOT AUTHORIZED**

```text
ADR-0057 — PTI Layer B

PAP Contract:
  ACCEPTED / FROZEN

PAP-G1: PASS (with PAP-T1)
PAP-G2: PASS (with PAP-T2)
PAP-G3: PASS
PAP-G4: PASS (with PAP-T3)
PAP-G5: PASS (with PAP-T4)

CONTRACT_CONTRADICTION: NONE

Layer A Trust Integration Core:
  CLOSED / PASS

PTI Layer B Production Profile Authentication:
  HOLD condition removed at contract level
  READY FOR NARROW IMPLEMENTATION AUTHORIZATION
  IMPLEMENTATION STILL NOT AUTHORIZED

Production Verifier Wiring:
  NOT AUTHORIZED

ADR-0057 COMPLETE:
  NO
```

**Parents / boundaries:**

- [ADR-0057 Group Bootstrap Convergence Contract](./0057-group-bootstrap-convergence-contract.md) — GBC convergence domain. This ADR does **not** reopen GBC core, delivery, wire, PV-1, or PV-2.
- [Profile 01 Q7](../analysis/0058-implementation-profile-01-control-crypto.md#q7--module-key-lifecycle-rotation-loss-and-re-enrollment) — Module key lifecycle and re-enrollment **security requirements** for replacement/recovery paths. Q7 freezes behavior semantics; it does **not** assert that an operational trust root already exists in deployment.
- PTI Layer A (`ProfileRevisionAcceptor`, `ProfileBackedGenerationFactTrustLookup`) — **CLOSED / PASS**. PTI-T2: PTI does **not** require PREPARED before ACTIVE globally; PTI does **not** freeze the complete operational key lifecycle graph. This contract does **not** redesign Layer A acceptance semantics.

**Does NOT authorize:**

- `ProductionProfileRevisionAuthenticator` implementation (separate narrow authorization required)
- `talkback-app` `TaskProfile` production migration or replacement
- Coordinator / VerificationBoundary production verifier wiring
- Selection of MDM, USB, QR, LAN import, or other concrete provisioning transport
- In-band operational-root rotation (deferred)
- ADR-0057 COMPLETE claim

**Still deferred (not frozen by this contract):**

```text
private-key storage product
certificate format
signature algorithm
USB / LAN / QR / MDM transport
operator UI
Profile physical file format
in-band root rotation
```

---

## Why this contract exists

Layer A proved **authentication-gated trust acceptance semantics** using a fixture
authenticator. Layer B was blocked because the repository had **no production
operational trust root** and **no authenticated Profile provisioning path**.

```text
Layer B blocker
  ≠ TrustLookup
  ≠ ProfileRevisionAcceptor
  ≠ PV-1 verifier
  ≠ persistence

Layer B blocker
  = operational trust authority
  + authenticated Profile establishment
  + provisioning / enrollment semantics
```

Implementing `ProductionProfileRevisionAuthenticator` before this contract would
**invent authority at implementation time**. That path is not authorized.

---

## V1 authority model (frozen)

```text
For each deployment trust scope:

  one offline-capable operational signing authority
        ↓
  scope-bound authenticated Profile revision
        ↓
  independently established / pinned operational trust anchor
        ↓
  ProductionProfileRevisionAuthenticator
        ↓
  PTI Layer A ProfileRevisionAcceptor
```

This freezes the **logical authority model**, not a specific physical device
(one PC, one USB key). The authority private key MAY reside in an offline
operator workstation, managed signing tool, or equivalent secure facility.

Continuous cloud / online CA: **NOT REQUIRED**.

---

## Contract tightenings (PAP-T1..T4)

### PAP-T1 — Authenticated scope binding

Every authenticated Profile revision **MUST** be cryptographically bound to one
**deployment trust scope**.

The scope MUST be represented by either:

- **A.** an explicit `deploymentTrustDomainId` inside protected authenticated
  semantics, or
- **B.** an equivalent unambiguous namespace established by the pinned operational
  trust anchor.

Ordinary mutable fields such as `channelId`, task display name, static peers,
and `sharedSecret` **MUST NOT** serve as this trust-domain binding.

```text
valid signature
+ wrong deployment trust scope
→ authentication / authority rejection
```

Cross-deployment replay MUST NOT reach Layer A acceptance as ordinary
configuration handling.

### PAP-T2 — Root establishment provenance

An operational trust anchor **MUST NOT** become trusted merely because it is
first observed at runtime.

Initial establishment MUST be justified by an independently trusted provenance,
such as:

- explicit operator out-of-band verification
- managed installation authority
- authenticated manufacturing / enterprise provisioning
- equivalent pre-established trust channel

```text
Factory presence alone:           NOT sufficient
Authenticated factory / managed provenance: MAY be sufficient
```

V1 remains **NO TOFU** without blocking secure factory/MDM bootstrap architectures.

**Anchor replacement (V1):** requires an explicit new establishment event.

```text
In-band operational-root rotation:
  DEFERRED
  NOT REQUIRED for PAP V1 PASS
```

Authenticated superseding-anchor rotation revision is **not** frozen in V1. If
needed later, require a separate amendment.

### PAP-T3 — Enrollment lifecycle boundary

**INITIAL ENROLLMENT:** operational authority **MAY** issue an authenticated
**ACTIVE** binding directly, provided:

- `moduleId` / `signerKeyVersion` / public-key identity are explicitly approved
  by operational authority, and
- local device verifies its local key identity matches the accepted ACTIVE
  binding before signing use.

PREPARED-before-ACTIVE **MAY** be used but is **NOT** required by ADR-0057 PTI
semantics (PTI-T2).

**RE-ENROLLMENT / KEY REPLACEMENT:** MUST follow frozen Profile 01 Q7 two-phase
safety semantics: PREPARED (non-verifying) → operational approval → higher
authenticated revision → ACTIVE; prior ACTIVE version retired per Q7 rules.

### PAP-T4 — Container / acceptance separation

If ordinary product configuration and authenticated trust payload share one
physical container, their authority and acceptance results remain **logically
independent** unless a future integration contract explicitly defines atomic
composition.

```text
container version ≠ trust revision
```

Trust authentication failure **MUST NOT** mutate Accepted Local Trust State. It
**MAY or MAY NOT** prevent ordinary configuration update, depending on future
product integration policy. Implementation **MUST NOT** represent a partially
accepted container as if the trust revision itself were accepted.

---

## Vocabulary

| Term | Meaning |
|------|---------|
| **Deployment trust scope** | Cryptographically bound authority namespace for Profile revisions (PAP-T1). Distinct from `channelId` and other mutable product fields. |
| **Operational trust root** | The independent authority whose signature (or equivalent managed-install proof) makes a Profile revision authentic. Distinct from Module signing keys, Generation Fact origin keys, PV-2 issuance keys, and `sharedSecret`. |
| **Operational trust anchor** | Device-persisted material that lets a Module decide whether a candidate Profile revision was signed by the established operational trust root. Form deferred to Layer B implementation. |
| **Protected Profile revision semantics** | Complete cryptographic trust payload whose canonical encoding is defined by PTI Layer A (`GenerationFactProfileTrustPayload` / `ProfileRevisionCanonicalCodec`). |
| **Authenticated Profile revision** | Candidate delivery whose protected semantics are accompanied by proof that the operational trust root approved **this exact** protected semantics within the correct deployment trust scope. |
| **Accepted Local Trust State** | Layer A durable outcome after successful authentication-gated acceptance. Sole source for `ProfileBackedGenerationFactTrustLookup`. |
| **Provisioning transport** | Any mechanism that delivers a **candidate** Profile revision. Transport is **not** authority. |
| **Provisioning authority** | Operational trust root plus authentication rules in PAP-3 and scope rules in PAP-T1. |
| **Module enrollment evidence** | Device-exported public key material presented to operational authority. **Not** self-authorizing. |
| **Operational TaskProfile** | Product configuration: channel identity, display metadata, static peers, RF label, `sharedSecret`, etc. |
| **Authenticated Generation Fact Trust Payload** | Cryptographic trust authority in protected Profile revision semantics. |

**Frozen distinction:**

```text
Profile 01 Q7 requirement = security properties MUST hold
Profile 01 Q7 requirement ≠ operational root already exists in deployment

parse ≠ trust
pre-auth parsing MAY occur
pre-auth semantic authority MUST NOT occur
```

---

## Prohibited authority substitutes

```text
sharedSecret
Generation Fact origin signing key
PV-2 issuance key
device self-signed key
HELLO / peer gossip
election / Primary authority
```

A Module key **MUST NOT** self-authorize the Profile revision that trusts it.

```text
module key possession              ≠ authority
module signature over enrollment   ≠ authority
operational Profile approval       = authority
```

---

## PAP-1 — Authority Model

**PAP-G1: PASS (with PAP-T1)**

### Requirements

1. **Independent operational authority.** Per deployment trust scope, one
   offline-capable operational signing authority is the sole source of
   authenticated Profile revisions.

2. **Authenticated scope binding (PAP-T1).** Every revision MUST be
   cryptographically bound to its deployment trust scope. Wrong scope →
   authentication / authority rejection before Layer A.

3. **Monotonic issuance.** `taskProfileRevision` increases monotonically;
   rollback forbidden at acceptance (Layer A).

4. **Separation from coordination roles.** Primary / Anchor / election **MUST
   NOT** grant Profile issuance rights.

5. **Offline-capable.** Continuous online CA **NOT REQUIRED** for baseline trust.

### Anti-patterns (forbidden)

```text
Module signs "trust my own binding" → accept
sharedSecret HMAC over Profile bytes → accept
peer relay of unsigned Profile → accept
highest HELLO advertiser → Profile issuer
Site-A signed revision replayed into Site-B scope → accept
```

---

## PAP-2 — Root Establishment

**PAP-G2: PASS (with PAP-T2)**

### Requirements

1. **Explicit establishment (PAP-T2).** Anchor trust requires independently
   trusted provenance — not first runtime observation, not factory presence alone.
   Authenticated factory / managed provenance **MAY** suffice.

2. **Pinning.** Device persists operational trust anchor(s). Subsequent
   revisions verify against pinned anchor(s).

3. **Anchor replacement (V1).** Requires explicit new establishment event.
   In-band root rotation: **DEFERRED**.

4. **Fail closed.** Incomplete or ambiguous establishment → no Accepted Local
   Trust State from candidate deliveries.

5. **No TOFU.** No trust-on-first-use for operational root or Module keys.

### Establishment vs acceptance

```text
Root establishment     = why device trusts operational authority
Profile acceptance     = given that trust, revision authentic + semantically valid
```

Layer B authenticator **MUST** assume anchor material established under PAP-2.

---

## PAP-3 — Revision Authentication

**PAP-G3: PASS**

### Requirements

1. **Complete-semantics binding.** Authentication covers complete protected
   semantics. **PTI-IA-T4 normative:** `revisionIdentity` derived from protected
   semantics only.

2. **Tamper evidence.** Modified semantics without fresh proof → authentication
   failure; last-good retained.

3. **Authentication gate (implementation-boundary corrected).**

   ```text
   candidate bytes
         ↓
   minimal structural / envelope parse
     [NO authority advancement]
         ↓
   authenticate exact protected representation
     (+ deployment scope validation per PAP-T1)
         ↓ reject → last-good retained
   authenticated protected semantics
         ↓
   canonical semantic decode
         ↓
   derive / verify revisionIdentity
         ↓
   Layer A ordering / conflict / semantic validation
         ↓
   atomic Accepted Local Trust State
   ```

   ```text
   Pre-auth parsing MAY occur.
   Pre-auth semantic authority MUST NOT occur.
   ```

4. **Same-revision conflict.** Same `taskProfileRevision`, different
   authenticated semantics → revision identity conflict rejection.

5. **Idempotency.** Identical authenticated re-delivery **MAY** be idempotent.

6. **Envelope shape deferred.** Algorithm, chain depth, wire encoding are Layer B
   implementation details after narrow authorization.

### Mapping to Layer B evidence

| Gate | Contract clause |
|------|-----------------|
| B-EG2 protected payload tamper | PAP-3 §1–§2 |
| B-EG3 authenticated older revision | Layer A rollback + PAP-3 gate |
| B-EG4 same-revision authenticated conflict | PAP-3 §4 + Layer A |

---

## PAP-4 — Module Enrollment

**PAP-G4: PASS (with PAP-T3)**

### Initial enrollment

```text
device generates key
      ↓
exports enrollment evidence
      ↓
operational authority approves
      ↓
authenticated Profile revision (ACTIVE permitted directly)
      ↓
device verifies local key matches binding
      ↓
enable signing use
```

Operational authority **MAY** issue ACTIVE binding directly on initial enrollment.
PREPARED-before-ACTIVE is optional, not mandatory (PTI-T2).

### Re-enrollment / key replacement

```text
generate new key / signerKeyVersion
      ↓
enrollment evidence
      ↓
authenticated PREPARED revision
      ↓
PREPARED remains non-verifying
      ↓
operational approval / activation
      ↓
higher authenticated ACTIVE revision
      ↓
local key identity match
      ↓
activate new key; retire prior per Profile 01 Q7
```

Profile 01 Q7 two-phase safety applies to **replacement/recovery**, not as a
global requirement on every first enrollment.

### Anti-circular trust (mandatory)

```text
device generates key → signs "trust me" → Profile accepts   FORBIDDEN
```

### PREPARED is not trust

PREPARED bindings MUST NOT satisfy PV-1 trust lookup.

### Recovery key identity

Re-enrollment / replacement MUST use new `signerKeyVersion` and new key
identity / fingerprint. Fingerprint reuse forbidden in replacement/recovery
context.

### Enrollment vs Profile acceptance

```text
Enrollment evidence           = input to authority decision
Authenticated Profile revision  = output of authority decision
ProfileRevisionAcceptor         = consumer of authenticated output only
```

---

## PAP-5 — Product Integration Boundary

**PAP-G5: PASS (with PAP-T4)**

### Requirements

1. **Separate authority semantics.** Operational TaskProfile carries product
   config; Authenticated Generation Fact Trust Payload carries cryptographic
   trust authority.

2. **Optional shared container (PAP-T4).**
   ```text
   shared container ≠ shared authority semantics
   container version ≠ trust revision
   ```

3. **`sharedSecret` boundary.** MUST NOT authenticate trust payload.

4. **Mutable config isolation.** Mutable product fields MUST NOT silently mutate
   Accepted Local Trust State.

5. **No forced migration.** Replace / merge / split of `talkback-app`
   `TaskProfile` not decided here.

6. **Acceptance source of truth.** `ProfileBackedGenerationFactTrustLookup` reads
   only Accepted Local Trust State. TaskProfile loaders MUST NOT be read-through
   trust authorities.

7. **Container / acceptance separation (PAP-T4).** Trust auth failure MUST NOT
   mutate trust state. Partial container acceptance MUST NOT be represented as
   trust revision accepted.

### Integration posture (current repo)

```text
talkback-app TaskProfile:  SharedPreferences JSON, no revision auth
PTI Layer A:               fixture auth, test persistence
Bridge:                    NOT IMPLEMENTED — Layer B PASS + wiring auth
```

---

## Provisioning transport abstraction

```text
Provisioning transport  ≠  provisioning authority

Any transport MAY deliver a candidate Profile.

Only successful authentication under
the established operational trust root
(and correct deployment trust scope)
may create Accepted Local Trust State.
```

Concrete transports are **not** selected in this contract.

---

## Conformance record

| Gate | Result |
|------|--------|
| PAP-G1 Authority Model | **PASS** — V1 offline operational signing authority + pinned anchor; PAP-T1 scope binding |
| PAP-G2 Root Establishment | **PASS** — no TOFU; authenticated factory/managed provenance allowed; PAP-T2 |
| PAP-G3 Revision Authentication | **PASS** — complete semantics binding; pre-auth structural parse without authority |
| PAP-G4 Module Enrollment | **PASS** — initial ACTIVE permitted; re-enrollment two-phase; PAP-T3 |
| PAP-G5 Product Boundary | **PASS** — composition without migration mandate; PAP-T4 |

Implementation gates **B-EG1..B-EG5** remain Layer B scope. **MUST NOT** be
claimed until Layer B narrow implementation authorization and harness PASS.

---

## Dependency chain

```text
PV-1 Production Verifier Core              CLOSED / PASS
PV-2 Origin Issuance Commitment            CLOSED / PASS
PTI Layer A Trust Integration Core         CLOSED / PASS
PAP Contract (this ADR)                    FROZEN / PASS
PTI Layer B Production Profile Authentication
                                             CLOSED / PASS

Operational Establishment     CLOSED / PASS
  see [OE Narrow Authorization](./0057-operational-establishment-narrow-authorization.md)

Production Verifier Wiring    CLOSED / PASS
  see [PW Narrow Authorization](./0057-production-verifier-wiring-narrow-authorization.md)

Production Trust Integration
  IMPLEMENTATION CONFORMANCE    PASS

Track C Field Validation      NEXT / OPEN
  see [Track C Case A Run Card](../analysis/adr0057-track-c-production-convergence-field-validation-run-card.md)
ADR-0057 COMPLETE             NO
```

---

## Layer B — closed (reference)

Layer B implementation conformance is **CLOSED**. See
[PTI Layer B Narrow Implementation Authorization](./0057-pti-layer-b-production-profile-authentication-narrow-authorization.md).

Operational Establishment and Production Verifier Wiring are **CLOSED / PASS**.
Implementation chain is frozen for Track C field validation. No further trust/crypto
architecture knives unless field evidence shows contract contradiction.
