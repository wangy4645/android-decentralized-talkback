# Meeting Profile01 Establishment Provisioning — Design Slice

**Parent:** `meeting-profile01-media-key-package-origin-path1-design-slice.md`  
**Status:** **SIGNED OFF** (2026-09-08) — **EP Field Run #1 VALID FAIL**

```text
P1-A/B/C                  CLOSED / UNIT PASS
P1-D                      AUTHORIZABLE (see p1-d authorization doc)
P1-E                      HOLD

Establishment provisioning  SIGNED OFF
PR-EP-1 codec + acceptor    CLOSED (EP1-1..EP1-6 PASS)
PR-EP-2 Keystore identity   CLOSED (EP2-1..EP2-7 PASS)
PR-EP-3 Enrollment helper   CLOSED (E3-1..E3-7 PASS)

EP Production Wiring        CLOSED (W1–W7 unit PASS)
EP provisioning chain       PRODUCT-WIRED

EP Field Run #1             VALID FAIL / PROVISIONING_DATA_GAP
Operator Provisioning       AUTHORIZED / NEXT
EP Field Run #2             BLOCKED until PRE-1..PRE-4
Implementation RCA          NOT REQUIRED

P1-C package field          NOT AUTHORIZED

ADR-0061                  FREEZE
PreBindRetention          FREEZE
CREATION origin/replay      FREEZE
MEMBERSHIP                  HOLD
Phase A                     BLOCKED
```

**Purpose:** Freeze **production trust-state provenance** for Module RSA key-establishment
identity — not “put a test RSA key in a map.” Without this, P1-C origin and future P1-D
decrypt may be internally consistent on **test keys** but not on **product trust chain**.

**Does NOT authorize:** P1-D implementation, P1-E, P1-C field, device field runs,
signaling/HELLO auto-import, TOFU, or Task Profile migration implementation.

---

## 1. Problem statement

P1-A/C closed the **runtime seams** assuming establishment bindings already exist:

```text
Profile01RecipientEstablishmentKeyLookup     → peer public SPKI + establishmentKeyVersion
Profile01LocalEstablishmentIdentity          → local active establishmentKeyVersion
AcceptedLocalEstablishmentTrustStateStore    → in-memory accepted bindings (may be empty)
```

Production today:

```text
AcceptedLocalEstablishmentTrustStateStore   often EMPTY
local establishment private key (Keystore)  NOT WIRED for package unwrap
P1-C on device                              → ORIGIN_SOURCE_GAP (expected until provisioned)
```

The gap is **not** builder/origin logic. It is:

```text
Where does each device get its RSA establishment private key?
Where does each device get each peer's authorized RSA establishment public binding?
How do both sides know they refer to the same versioned authority state?
```

Answering these incorrectly collapses establishment trust into TOFU, signaling gossip,
or signer-key fallback — contradicting Profile 01 Q1/Q7 and P1-A frozen boundary
(`SigningIdentity != EstablishmentIdentity`).

---

## 2. Four design questions (scope of this slice)

This slice answers **only** these four questions. Transport, operator UI, and Keystore
alias naming are deferred unless required for semantic clarity.

### Q1 — Who generates and who holds the establishment key pair?

**Frozen answer:**

```text
LOCAL DEVICE (each Module)
  generates RSA-3072 establishment keypair in Android Keystore (non-exportable)
  holds establishment PRIVATE key — never in AcceptedLocalEstablishmentTrustStateStore
  exports enrollment evidence only:
    moduleId
    establishmentPublicKeySpki (or SHA-256 fingerprint)
    proposed establishmentKeyVersion (operator-assigned at enrollment time)

PROVISIONING AUTHORITY (operational / Task Profile issuer)
  approves enrollment evidence
  distributes authorized PUBLIC bindings to all Modules in deployment scope
  does NOT generate peer private keys
  does NOT accept device self-signed “trust me” profile revisions (PAP-4)
```

```text
Signing private key  → ECDSA Keystore alias (existing GF signer path)
Establishment private key → RSA Keystore alias (separate alias namespace)
```

Private establishment material **never** transits profile bytes, signaling, CREATION,
or MEDIA_KEY_PACKAGE facts.

### Q2 — Where do peer public establishment bindings enter local trust state?

**Frozen answer:**

```text
Candidate delivery (USB / MDM / LAN / operator tool — transport deferred)
        ↓
Authenticated Profile revision (ADR-0057 PAP-3 gate)
  protected semantics include establishment bindings
        ↓
Establishment trust acceptance (new acceptor seam — parallel to signing acceptor)
        ↓
AcceptedLocalEstablishmentTrustStateStore.atomicReplace(...)
        ↓
Profile01ProfileBackedRecipientEstablishmentKeyLookup (P1-A, already implemented)
```

**Authority source:** Task Profile / authenticated trust payload **only**.

**Explicitly forbidden ingress paths:**

```text
HELLO / peer gossip
CONFERENCE_SIGNED_FACT (CREATION / MEDIA_KEY_PACKAGE / any fact)
runtime discovery
signaling envelope fields
first-seen peer advertisement
AcceptedLocalTrustState (signing store) read-through fallback
```

`AcceptedLocalEstablishmentTrustStateStore` stores **already-authorized** bindings.
It does **not** invent, infer, TOFU, or fallback.

### Q3 — How is `establishmentKeyVersion` generated, rotated, and compared?

**Frozen answer — separate version domain:**

```text
signerKeyVersion           → ECDSA signing domain (ModuleSigningBinding)
establishmentKeyVersion    → RSA key-establishment domain (ModuleEstablishmentBinding)
wire recipientKeyVersion   → equals establishmentKeyVersion on MEDIA_KEY_PACKAGE (NOT signerKeyVersion)
```

Per `moduleId`, `establishmentKeyVersion` is a monotonic unsigned integer:

```text
MUST NOT be reused or rolled back
MUST NOT be derived from signerKeyVersion
MUST NOT be coerced from signer lookup on miss
```

**Generation:** assigned by provisioning authority at enrollment / rotation event
(operator or manufacturing tooling), not by runtime counters.

**Rotation:** follows Profile 01 Q7 two-phase safety (PREPARED → ACTIVE) applied to
establishment bindings. A single authenticated profile revision **may** advance both
signing and establishment versions in one operator event, but acceptance stores and
APIs **must** persist separate version fields — P1-A/P1-C already depend on this.

**Comparison rules:**

| Context | Rule |
|---------|------|
| Host package wrap (P1-C) | `activeEstablishmentKey(peer)` → exact `(moduleId, establishmentKeyVersion, spki)` |
| Peer package unwrap (P1-D) | wire `recipientKeyVersion` MUST equal local `activeEstablishmentKeyVersion()` |
| Lookup miss | `UnknownModule` / `UnknownKeyVersion` / `KeyNotUsable` — no retry with signer key |
| Publication identity (P1-C) | includes `recipientEstablishmentKeyVersion` as distinct dimension |

**Active version selection (already implemented):**

```text
active establishment version for module M
  = max establishmentKeyVersion among bindings where
      moduleId == M AND keyState == ACTIVE
```

`RETIRED_VERIFY` establishment keys MUST NOT wrap new packages or activate new unwrap;
historical packages for retired versions follow Q7 `RECIPIENT_KEY_VERSION_RETIRED` semantics
(P1-D scope).

### Q4 — How do M01 and M02 prove matching authority state?

**Frozen answer — bilateral provisioning evidence, not wire handshake:**

Provisioning is **out-of-band relative to conference runtime**. M01↔M02 agreement is
proven by **both devices holding the same authenticated profile revision** (or
revision-consistent establishment subset), not by an in-session key exchange.

```text
Provisioning authority issues revision R containing:

  M01 establishment binding: (M01, establishmentKeyVersion=V1, spki=SPKI_M01, ACTIVE)
  M02 establishment binding: (M02, establishmentKeyVersion=V4, spki=SPKI_M02, ACTIVE)
  ... plus signing bindings in parallel signing payload ...

M01 device:
  local Keystore RSA public fingerprint == profile M01 establishment binding
  store contains M02 row exactly as authorized

M02 device:
  local Keystore RSA public fingerprint == profile M02 establishment binding
  store contains M01 row exactly as authorized
```

**Match predicate (per device, per peer):**

```text
authorized.spki fingerprint == expected fingerprint from profile revision R
authorized.establishmentKeyVersion == expected version from profile revision R
local active establishmentKeyVersion == profile local row version
local Keystore public fingerprint == profile local row spki fingerprint
```

**No runtime proof channel required for v1:** CREATION/MEDIA_KEY_PACKAGE send order,
HELLO, or ICE must not be used to “confirm” keys. Field gate EP1–EP5 (§8) is the
pre-P1-D evidence contract.

---

## 3. Ownership model (frozen)

```text
┌─────────────────────────────────────────────────────────────────┐
│ Local device                                                    │
│   owns: RSA establishment PRIVATE key (Android Keystore)        │
│   exposes: enrollment evidence (public SPKI + version proposal)   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼ enrollment evidence (out of band)
┌─────────────────────────────────────────────────────────────────┐
│ Task Profile / provisioning authority                           │
│   approves bindings                                           │
│   issues authenticated profile revision                       │
│   distributes peer PUBLIC establishment bindings              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼ authenticated profile delivery
┌─────────────────────────────────────────────────────────────────┐
│ AcceptedLocalEstablishmentTrustStateStore                       │
│   stores: authorized peer + local PUBLIC bindings               │
│   does NOT: invent / TOFU / fallback / read signing store       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ Profile01RecipientEstablishmentKeyLookup (host wrap)            │
│ Profile01RecipientKeyEstablishmentSeam (peer unwrap — P1-D)       │
└─────────────────────────────────────────────────────────────────┘
```

**Invariant (re-stated):**

```text
SigningIdentity != EstablishmentIdentity
```

Even when a test harness temporarily shares key material, production provisioning
MUST NOT collapse the stores or version domains.

---

## 4. V1 boundary — prohibited shortcuts

```text
NO TOFU
NO auto-import from signaling
NO signer-key fallback
NO runtime-generated peer trust
NO implicit key-version coercion (signer → establishment)
NO CREATION / MEDIA_KEY_PACKAGE as establishment authority
NO HELLO-as-trust-root
NO “first successful decrypt writes binding”
```

Violation symptom on device today: `ORIGIN_SOURCE_GAP` (P1-C) or decrypt reject (P1-D).
That is **correct fail-closed behavior** until provisioning closes.

---

## 5. Provisioning split — LOCAL vs REMOTE

Do **not** merge these into one store or one acceptance code path.

### 5.1 LOCAL — private key + local establishment identity

```text
Android Keystore
  generate RSA-3072 (non-exportable)
  alias namespace distinct from ECDSA signer alias
        ↓
Enrollment evidence export (operator tooling)
        ↓
Authenticated profile revision includes LOCAL establishment binding row
        ↓
On acceptance:
  verify Keystore public SPKI fingerprint == profile local row
  atomically enable unwrap for (localModuleId, establishmentKeyVersion)
        ↓
Profile01LocalEstablishmentIdentity.activeEstablishmentKeyVersion()
Profile01ProductionRecipientKeyEstablishmentSeam (P1-D — not yet authorized)
```

**Store content for local module:** public binding row only (for fingerprint match and
version query). Private key never enters `AcceptedLocalEstablishmentTrustStateStore`.

### 5.2 REMOTE — authorized peer public bindings

```text
Authenticated profile revision carries peer ModuleEstablishmentBinding rows
        ↓
Establishment profile acceptor (new seam; mirrors ProfileRevisionAcceptor discipline)
  monotonic taskProfileRevision
  revisionIdentity over protected semantics
  fail-closed / last-good on conflict
        ↓
AcceptedLocalEstablishmentTrustStateStore.atomicReplace
        ↓
Profile01ProfileBackedRecipientEstablishmentKeyLookup
        ↓
P1-C origin bridge (host wrap) / P1-D decrypt (peer unwrap)
```

---

## 6. Task Profile / trust payload fields (minimum semantics)

If `GenerationFactProfileTrustPayload` is extended (preferred: parallel binding list in
same authenticated revision as signing bindings), each establishment row MUST carry at
least:

```text
moduleId
establishmentAlgorithm          // v1 fixed: RSA-3072-OAEP-SHA256-MGF1-SHA1 (Profile 01 Q2)
establishmentPublicKeySpki      // full SPKI bytes; fingerprint = SHA-256(SPKI)
establishmentKeyVersion         // establishment domain — NOT signerKeyVersion
keyState                        // PREPARED | ACTIVE | RETIRED_VERIFY | RETIRED_OFF | REVOKED
activatedAtRevision             // taskProfileRevision that activated this row
```

**Optional but recommended for audit:**

```text
establishmentPublicKeyFingerprint // SHA-256 hex; must match SPKI if present
```

**Forbidden:**

```text
establishmentPrivateKey
reusing signerKeyVersion as establishmentKeyVersion
inferring establishment SPKI from ModuleSigningBinding.publicKeySpki
```

### 6.1 Relationship to signing payload

```text
GenerationFactProfileTrustPayload (signing)     → AcceptedLocalTrustStateStore
Establishment bindings (same or paired revision) → AcceptedLocalEstablishmentTrustStateStore
```

Acceptance MAY occur in one operator transaction (single authenticated revision), but
**durable outcomes remain two stores** with separate lookup APIs — already reflected in
P1-A wiring (`Profile01EstablishmentAuthorityWiring`).

Codec / acceptor implementation is **deferred** to a narrow implementation authorization
after this design slice sign-off.

---

## 7. End-to-end provisioning flow (M01 ↔ M02 reference topology)

```text
Phase 0 — operational anchor + signing trust (existing ADR-0057 / FTPH chain)
  both devices: operational anchor established
  both devices: signing bindings accepted (GF signer / AcceptedLocalTrustState)

Phase 1 — local establishment key generation (per device, once per version)
  M01: Keystore RSA keypair → export SPKI_M01, propose version V1
  M02: Keystore RSA keypair → export SPKI_M02, propose version V4

Phase 2 — authority approval (out of band)
  operator builds authenticated profile revision R:
    establishment rows for M01(V1, SPKI_M01, ACTIVE)
    establishment rows for M02(V4, SPKI_M02, ACTIVE)
    signing rows unchanged or co-revisioned per Q7 policy

Phase 3 — profile delivery (transport deferred)
  M01 accepts R → establishment store contains M02(V4, SPKI_M02) + local M01 row
  M02 accepts R → establishment store contains M01(V1, SPKI_M01) + local M02 row
  each device verifies local Keystore fingerprint matches local row

Phase 4 — runtime (conference)
  P1-C: M01 lookup(M02) → Found(V4, SPKI_M02) → wrap package
  P1-D: M02 unwrap with Keystore private(V4) → supplement (future)
```

CREATION and MEDIA_KEY_PACKAGE wire order remains **non-authoritative** for establishment
trust (Path 1 frozen).

---

## 8. Field gate — EP1–EP5 (before P1-D production decrypt field)

**Run card:** `meeting-profile01-establishment-field-gate-ep1-ep5-run-card.md`  
**Probe:** `EstablishmentFieldProbe` → `EP_PROBE` log lines  
**Collect:** `talkback/scripts/ep-establishment-field-collect.ps1`  
**Adjudicate:** `talkback/scripts/ep-establishment-field-adjudicate.ps1`

Independent gate **Establishment Provisioning EP**. Must PASS on real devices before
**P1-D field** or **P1-C field** is authorized.

| Gate | Check | Pass criterion |
|------|-------|----------------|
| **EP1** | M01 host lookup | `lookupEstablishmentKey(M02, V4)` → `Found` with expected SPKI fingerprint and version |
| **EP2** | M02 local private identity | Keystore RSA public fingerprint matches profile M02 establishment binding; `activeEstablishmentKeyVersion() == V4` |
| **EP3** | Reverse direction (if required for topology) | M02 lookup(M01) → expected SPKI/version; M01 local identity matches profile |
| **EP4** | Version domain separation | Independent signing/establishment domains sourced, stored, resolved, fenced; numeric equality allowed; corroborated by EP1+EP2+EP5 |
| **EP5** | Fail-closed | wrong version / unknown module / missing binding → explicit reject (`UnknownKeyVersion` / `ORIGIN_SOURCE_GAP` / decrypt reject) — **no fallback** |

**EP gate does not replace J0 E2E.** It is a **hard prerequisite** so field logs are
diagnostic rather than noise.

Suggested evidence format (Appendix B style):

```text
EP_PROBE session=<none> device=M01 lookup_target=M02 outcome=Found version=4 spki_fp=<hex>
EP_PROBE session=<none> device=M02 local_establishment_version=4 keystore_fp_match=true
```

---

## 9. Mapping to current code (read-only baseline)

| Component | Status | Provisioning role |
|-----------|--------|-------------------|
| `ModuleEstablishmentBinding` | Implemented | Canonical row shape |
| `AcceptedLocalEstablishmentTrustStateStore` | Implemented (memory) | REMOTE + local public rows after acceptance |
| `Profile01ProfileBackedRecipientEstablishmentKeyLookup` | Implemented | Host lookup — no provisioning |
| `Profile01LocalEstablishmentIdentity` | Implemented | Reads active version from store — no Keystore |
| `Profile01Q5TestRecipientKeyEstablishmentSeam` | Test only | **Not** production trust |
| `EstablishmentProfileRevisionCanonicalCodec` | **Implemented (PR-EP-1)** | Protected semantics codec |
| `EstablishmentProfileRevisionAcceptor` | **Implemented (PR-EP-1)** | PAP-gated acceptance only |
| `AuthenticatedEstablishmentProfileRevision` | **Implemented (PR-EP-1)** | Upstream PAP prerequisite type |
| `GenerationFactProfileTrustPayload` | Signing only | Establishment uses parallel payload |
| `ProfileRevisionAcceptor` | Signing only | Does not populate establishment store |
| Android Keystore RSA establishment | **Not wired** | LOCAL provisioning target (P1-D dependency) |

Production `TalkbackRuntimeFactory` may keep establishment store **empty** until
provisioning implementation lands; P1-A/B/C unit closure remains valid.

---

## 10. What this slice authorizes / does not authorize

### Authorized

```text
PR-EP-1 IMPLEMENTED:
  EstablishmentProfileRevisionCanonicalCodec
  EstablishmentProfileRevisionAcceptor
  AuthenticatedEstablishmentProfileRevision (PAP prerequisite type)
  EP1-1..EP1-6 unit PASS

PR-EP-2 CLOSED:
  AndroidKeystoreLocalEstablishmentIdentityStore
  Profile01LocalEstablishmentIdentitySnapshot
  LocalEstablishmentBindingVerifier
  EP2-1..EP2-7 unit PASS

PR-EP-3 CLOSED:
  EstablishmentEnrollmentEvidenceProducer
  EstablishmentEnrollmentAuthorityHelper
  EstablishmentEnrollmentWorkflow (PAP seam → accept → verify)
  E3-1..E3-7 unit PASS

EP Production Wiring CLOSED:
  EstablishmentProductionComposition
  EstablishmentProfileRevisionDeliveryAcceptor
  Profile01VerifiedLocalEstablishmentIdentity
  TalkbackRuntimeFactory composition (no runtime enroll/provision)
  W1–W7 unit PASS
```

### EP Production Wiring — authorization / sign-off (narrow)

**Authorized scope (two independent chains):**

```text
REMOTE TRUST
  ADR-0057 PAP-authenticated establishment Profile revision
  → EstablishmentProfileRevisionDeliveryAcceptor
  → AcceptedLocalEstablishmentTrustStateStore (+ file persistence in production)
  → Profile01ProfileBackedRecipientEstablishmentKeyLookup

LOCAL POSSESSION
  Android Keystore identity + accepted Profile local binding
  → Profile01VerifiedLocalEstablishmentIdentity
  → Profile01LocalEstablishmentIdentityAvailability (Verified | Unavailable)
```

**Factory rule:** `TalkbackRuntimeFactory` composes only — **no** runtime enrollment, key generation,
trust acceptance, auto-rotation, or signer fallback. Missing profile / Keystore / mismatch →
`Unavailable` diagnostic.

**Exit tests:** W1–W7 (`EstablishmentProductionWiringTest`). W3 Android Keystore smoke remains
required before EP field-ready adjudication (unit wiring PASS alone is insufficient).

**Still forbidden in this slice:** P1-D decrypt wiring, MEDIA_KEY_PACKAGE field send, P1-E,
Meeting J0 run, runtime enrollment.

### Not authorized (yet)

```text
P1-E incomplete-apply continuation
P1-C package field run
P1-D field run
Meeting J0
```

### Previously deferred (now closed)

```text
PR-EP-3 enrollment helper / operator tooling — CLOSED
EP Production Wiring — CLOSED
Operator Provisioning — CLOSED
Establishment Field Gate — CLOSED / FIELD PROVEN (EP Run #2)
  evidence: talkback/logs/ep-establishment-field-20260908-160928/
  manifest: talkback/logs/ep-establishment-operator-20260908-155431/
```

### Next authorized work

```text
meeting-profile01-p1-d-media-key-package-decrypt-production-wiring-authorization.md
```

### EP Field Run #1 — closed

```text
Verdict: VALID FAIL / PROVISIONING_DATA_GAP
Evidence: talkback/logs/ep-establishment-field-20260908-154048/
Implementation RCA: NOT REQUIRED
```

---

## 11. Parent document status update

Path 1 stack status after this slice:

```text
P1-A  CLOSED
P1-B  CLOSED
P1-C  CLOSED (unit)
P1-D  AUTHORIZED / NEXT
P1-E  HOLD

Establishment provisioning  CLOSED (operator + authority)
Establishment Field Gate    CLOSED / FIELD PROVEN
```

---

## 12. References

- Path 1 design: `meeting-profile01-media-key-package-origin-path1-design-slice.md`
- Profile 01 Q1/Q2/Q7: `0058-implementation-profile-01-control-crypto.md`
- PAP contract: `docs/adr/0057-profile-authentication-establishment-provisioning-contract.md`
- Operational anchor (signing trust): `docs/adr/0057-operational-establishment-narrow-authorization.md`
- Field signing helper (not establishment): `adr0057-field-test-provisioning-helper-narrow-spec.md`
- P1-A code: `ModuleEstablishmentBinding.kt`, `Profile01EstablishmentAuthorityWiring.kt`
- P1-C code: `MeetingProfile01MediaKeyPackageOriginBridge.kt`

---

## Sign-off checklist

```text
[ ] Q1 key generation / holding ownership frozen
[ ] Q2 trust-state ingress frozen (profile-only)
[ ] Q3 establishmentKeyVersion domain frozen (≠ signerKeyVersion)
[ ] Q4 bilateral authority match model frozen
[ ] LOCAL / REMOTE split frozen
[ ] v1 NO-TOFU boundary frozen
[ ] EP1–EP5 gate frozen
[ ] P1-D / field explicitly not authorized by this slice
```
