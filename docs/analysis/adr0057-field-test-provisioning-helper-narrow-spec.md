# ADR-0057 — Field Test Provisioning Helper Narrow Specification

## Status

**AUTHORIZED WITH TIGHTENING** (2026-08-31)

```text
ADR-0057 — Field Test Provisioning Helper (FTPH)

DECISION:
  AUTHORIZE WITH TIGHTENING

REQUIRED:
  FTPH-IA-T1  Exact Runtime Generation-Fact Signer Binding

PURPOSE:
  Establish test trust preconditions for Track C field validation ONLY

Track C field adjudication:
  INCOMPLETE until FTPH PASS on all participating devices

Field Establishment Ceremony / Transport (product):
  NOT IMPLEMENTED / NOT CLAIMED

Implementation:
  AUTHORIZED
```

**Parents (CLOSED — do not reopen):**

- [Track C Case A Run Card](./adr0057-track-c-production-convergence-field-validation-run-card.md)
- [Operational Establishment](../adr/0057-operational-establishment-narrow-authorization.md) — OE seam only
- [Production Verifier Wiring](../adr/0057-production-verifier-wiring-narrow-authorization.md) — PW CLOSED
- [PAP Contract](../adr/0057-profile-authentication-establishment-provisioning-contract.md) — Bridge still NOT IMPLEMENTED

**Audit basis:** B-path read-only audit (2026-08-31) — accepted.

---

## 1. Problem statement

Track C is blocked because participating devices lack **first trust establishment**
on real hardware. Production verifier wiring is CLOSED; the gap is **device-side
provisioning entry**, not verifier semantics.

```text
Current device state (observed):
  filesDir/gbc-trust/ exists
  operational-trust-anchor.bin      ABSENT
  accepted-local-trust-state.bin    ABSENT

Production path without both files:
  anchor absent     → Profile auth fail-closed
  trust store empty → GenerationFactTrustLookup → UNVERIFIABLE (PW-EG3 T0)
```

**Anchor alone is insufficient.** Track C requires three steps:

```text
Step A  operational anchor established (OE seam)
Step B  field-established GF signer persisted (FTPH-IA-T1 seam)
Step C  signed Profile v2 accepted (PTI Layer A acceptor seam)
        bindings MUST match Step B public identity
```

### 1.1 Pre-implementation audit — Generation Fact origin signer (2026-08-31)

```text
FINDING:
  GenerationFactOriginEmissionSeam / GenerationFactIssuanceWriter
  exist in main/ library code but are NOT wired to TalkbackCoordinator
  or TalkbackRuntimeFactory today.

  There is NO Keystore / TaskProfile / sharedSecret GF signer in appprod.

IMPLICATION:
  Path A (read existing production signer) — NOT AVAILABLE on current build.

  Path B (field-established signer via formal persistence seam) — REQUIRED.
  FTPH MUST persist GF signing PKCS8 to filesDir/gbc-trust/ through
  FileGenerationFactSignerPersistence (create-once), then provision Profile
  bindings to the matching SPKI.

  PersistedGenerationFactIssuanceSigner (main/) loads the same key after
  androidTest terminates and app restarts — satisfying FTPH-IA-T1.

NOTE (out of FTPH scope, do not claim closed):
  PV-2 origin emission into Coordinator (fact origination on wire) remains
  unwired. FTPH clears trust preconditions; fact origination is separate.
```

---

## 1.2 FTPH-IA-T1 — Exact Runtime Generation-Fact Signer Binding (FROZEN)

```text
For every provisioned module (moduleId, signerKeyVersion, publicKey)
placed into signed Profile v2:

  MUST correspond exactly to the signing identity that the normal
  production Generation Fact origin path will use after androidTest
  terminates and the app restarts.

FTPH MUST NOT provision a test-only GF public key unless the production
runtime under test will possess and use the matching private key through
the authorized persisted-signer seam (Path B).

test key merely exists in androidTest
  != valid Track C module binding
```

---

## 2. Scope

### 2.1 Authorizes (on spec sign-off + implementation PASS)

```text
□ Narrow Field Test Provisioning Helper (FTPH)
□ androidTest-only device-side entry (talkback-app)
□ Field-test operational authority material (androidTest source set ONLY)
□ Desk conformance harness for FTPH (FTPH-EG1..N)
□ Track C PRECONDITIONS collector update (post-provision verify)
```

### 2.2 Does NOT authorize

```text
□ Field Establishment Ceremony / Transport product feature
□ Operator provisioning UI (USB / QR / MDM / LAN)
□ Production main / release dependency on test signing keys
□ adb direct copy of .bin blobs
□ Manual forgery of accepted-local-trust-state.bin
□ Production verifier fallback or injectable override
□ GBC / Coordinator / completion predicate changes
□ sharedSecret as trust substitute
□ ADR-0057 COMPLETE
□ Track C FIELD PASS (helper PASS is precondition only)
```

---

## 3. Design constraints (locked)

```text
FIELD-TEST-ONLY
NOT FOR PRODUCTION PROVISIONING

MUST:
  Execute inside app device context (Instrumentation / app Context)
  Call PapT2EstablishmentAuthorizationIssuer (production class)
  Call OperationalAnchorEstablisher (production class)
  Call ProfileRevisionAcceptor (production class)
  Use deploymentTrustDomainId = "talkback-operational"
  Profile v2 bindings include M01, M02, M03
  Write to real filesDir/gbc-trust/ paths used by TalkbackRuntimeFactory

MUST NOT:
  adb push / cp pre-generated bin into gbc-trust/
  Hand-craft accepted-local-trust-state.bin off-device
  Import OperationalProfileTestSigning / GenerationFactTestSigning into main/
  Expose helper entry in production runtime (Activity menu, BootReceiver, etc.)
```

### 3.1 Field-test authority isolation

Field-test signing material **MUST** live under:

```text
talkback-app/src/androidTest/.../fieldtest/
```

Named explicitly, e.g.:

```text
FieldTestOperationalAuthority     (Profile v2 operational signer)
FieldTestGenerationFactAuthority  (module GF signer SPKI for bindings)
```

Rules:

```text
□ production main / release MUST NOT reference these types
□ androidTest MAY duplicate deterministic key material from harness tests
  (document equivalence in FTPH-EG1; no runtime import from test source set)
□ SPKI / domain constants MUST be visible in PRECONDITIONS output
□ Log banner on every run:
    FIELD-TEST-ONLY NOT FOR PRODUCTION PROVISIONING
```

**Equivalence note:** Harness uses `OperationalProfileTestSigning` and
`GenerationFactTestSigning` (test source). FTPH field-test authority SHOULD
produce **bit-identical** operational SPKI and module binding SPKI to those
fixtures so desk PW/OE regression and field provisioning stay aligned. Equivalence
is proven in FTPH-EG1, not assumed.

---

## 4. Carrier

**Primary:** `talkback-app` **androidTest** instrumented test.

```text
Package:     com.talkback.appprod
Context:     InstrumentationRegistry.getInstrumentation().targetContext
Trust dir:   context.filesDir/gbc-trust/
             operational-trust-anchor.bin
             accepted-local-trust-state.bin
```

**Rationale:** Runs in the same UID and paths as production
`GbcProductionTrustComposition.createProductionWiring()` without adding a
production-runtime provisioning entry.

**Not authorized as carrier:** JVM adb-push tool, debug Activity in `main/`,
TaskProfile import, sharedSecret bootstrap.

### 4.1 Gradle surface (implementation hint)

```text
talkback-app/build.gradle.kts:
  android { defaultConfig { testInstrumentationRunner = "..." } }
  androidTestImplementation(project(":android-board-talkback"))
  androidTestImplementation("androidx.test.ext:junit:...")
  androidTestImplementation("androidx.test:runner:...")
```

No new dependencies in `implementation` for signing or establishment.

---

## 5. Provisioning procedure (per device)

Execute **before** Track C Case A force-stop / reopen. Order: M01 → M02 → M03.

```text
P0  force-stop com.talkback.appprod

P1  Run FTPH instrumented test on device
    (single test method; idempotent where possible)

P2  In-app context — Step A (anchor):
    record = EstablishmentRecord(
      deploymentTrustDomainId = "talkback-operational",
      operationalAuthorityPublicKeySpki = FieldTestOperationalAuthority.publicKeySpki,
      establishmentRecordIdentity = "adr0057-field-test-establishment-v1",
      provenanceClass = OPERATOR_OUT_OF_BAND,
    )
    auth = PapT2EstablishmentAuthorizationIssuer().authorize(record)
    persistence = FileOperationalTrustAnchorPersistence(anchorFile)
    result = OperationalAnchorEstablisher(persistence).establish(auth)
    ACCEPT: Established | AlreadyEstablished (same identity)
    REJECT: any other outcome → FTPH FAIL

P3  In-app context — Step B (GF signer, FTPH-IA-T1):
    pkcs8 = FieldTestGenerationFactAuthority.pkcs8PrivateKey
    signerPersistence = FileGenerationFactSignerPersistence(signerFile)
    result = GenerationFactSignerEstablisher(signerPersistence)
               .establish(pkcs8, "adr0057-field-test-gf-signer-v1")
    ACCEPT: Established | AlreadyEstablished (same identity)
    REJECT: any other outcome → FTPH FAIL

P4  In-app context — Step C (profile):
    Build GbcProductionTrustComposition with SAME anchorFile + trustStateFile
    as TalkbackRuntimeFactory (domain = talkback-operational)
    payload = GenerationFactProfileTrustPayload(
      taskProfileRevision = 10,
      localModuleId = <device moduleId from talkback_prod_config>,
      deploymentTrustDomainId = "talkback-operational",
      moduleBindings = [
        ACTIVE binding M01 signerKeyVersion=1 publicKeySpki=FieldTestGF...
        ACTIVE binding M02 signerKeyVersion=1 publicKeySpki=FieldTestGF...
        ACTIVE binding M03 signerKeyVersion=1 publicKeySpki=FieldTestGF...
      ],
    )
    signed = FieldTestOperationalAuthority.signPayloadV2(payload)
    accept = runtime.profileAcceptor.acceptProtectedRevision(signed)
    ACCEPT: ProfileRevisionAcceptResult.Accepted
    REJECT: any other outcome → FTPH FAIL

P5  Post-provision fingerprint gate (FTPH-IA-T1 / EG5):
    Load accepted Profile binding for localModuleId / signerKeyVersion=1
    Load PersistedGenerationFactIssuanceSigner via production loader
    Compare GenerationFactSignerFingerprint.spkiSha256Hex for both
    ALL local modules in binding table: MATCH required
    Emit FTPH_RESULT.txt with localModuleId, domain, per-module fingerprints

P6  Emit structured result (log + filesDir/gbc-trust/FTPH_RESULT.txt)
```

### 5.1 Per-device `localModuleId`

| Device | moduleId | localModuleId in payload |
|--------|----------|--------------------------|
| M01 | M01 | M01 |
| M02 | M02 | M02 |
| M03 | M03 | M03 |

**Bindings list is identical on all three devices** (M01+M02+M03). Only
`localModuleId` varies per device.

### 5.1.1 Binding constants (aligned with PV harness)

```text
signerKeyVersion      = 1
activatedAtRevision   = 10
keyState              = ACTIVE
publicKeySpki         = FieldTestGenerationFactAuthority.publicKeySpki
taskProfileRevision   = 10
```

### 5.2 Idempotency

```text
Anchor:     AlreadyEstablished with same record identity → PASS
            Rejected (different anchor) → FAIL; do not overwrite
Profile:    Accepted(idempotent=true) → PASS
            Accepted(idempotent=false) → PASS
```

**Re-provision after failed partial state:** If anchor exists but trust state
absent, Step B alone MAY be retried. If anchor corrupt or wrong identity, manual
`run-as` delete of `gbc-trust/*` is operator action **outside** FTPH (document in
run card; not automated by helper).

### 5.3 Suggested adb invocation (implementation detail)

```text
adb -s <serial> shell am instrument -w \
  -e class com.talkback.appprod.fieldtest.Adr0057FieldTestProvisioningInstrumentedTest#provisionTrustPreconditions \
  com.talkback.appprod.test/androidx.test.runner.AndroidJUnitRunner
```

Exact class name may change at implementation; runner MUST target `appprod` test APK.

---

## 6. Post-provision verification (hard gate)

After FTPH on each device, **before** Track C:

```text
□ operational-trust-anchor.bin      PRESENT (non-zero)
□ accepted-local-trust-state.bin      PRESENT (non-zero)
□ deploymentTrustDomainId             talkback-operational
□ establishmentRecordIdentity         adr0057-field-test-establishment-v1
□ Profile bindings (decode via desk script or log):
    M01  ACTIVE  signerKeyVersion=1
    M02  ACTIVE  signerKeyVersion=1
    M03  ACTIVE  signerKeyVersion=1
□ PRECONDITIONS.txt recorded per device
□ M04 force-stopped or off-LAN (Track C 3-device scope)
```

Desk verify commands (existing pattern):

```powershell
adb -s <serial> shell "run-as com.talkback.appprod ls -la files/gbc-trust/"
adb -s <serial> shell "run-as com.talkback.appprod stat -c '%s' files/gbc-trust/operational-trust-anchor.bin"
adb -s <serial> shell "run-as com.talkback.appprod stat -c '%s' files/gbc-trust/accepted-local-trust-state.bin"
```

**FTPH FAIL on any device → Track C remains INCOMPLETE.**

---

## 7. Integration with Track C Case A

```text
1. FTPH PASS on M01, M02, M03
2. Record PRECONDITIONS.txt (trust state = PRESENT)
3. force-stop all three
4. Start log collectors
5. Reopen M01 → M02 → M03 (~30s), Talk page
6. Idle observe ≥ 5 min
7. Stop collectors; ADJUDICATION per run card §4
```

Track C Core PASS/FAIL is **still independent** of FTPH — helper only clears the
provisioning precondition. Generation convergence evidence rules unchanged.

---

## 8. Conformance evidence (FTPH-EG)

Implementation MUST pass before field use:

| Gate | Claim |
|------|-------|
| **FTPH-EG1** | Field-test operational authority isolated; main has zero reference to `fieldtest` package; GF SPKI == harness `GenerationFactTestSigning.publicKeySpki` (desk) |
| **FTPH-EG2** | Step A via `OperationalAnchorEstablisher`; not blob push |
| **FTPH-EG3** | Step C via `ProfileRevisionAcceptor`; trust state persisted |
| **FTPH-EG4** | domain=`talkback-operational` + M01/M02/M03 bindings correct |
| **FTPH-EG5** | **Accepted module binding SPKI == `PersistedGenerationFactIssuanceSigner` SPKI per moduleId (FTPH-IA-T1)** |
| **FTPH-EG6** | Restart reload: anchor + trust state + signer file recover; production verifier UNVERIFIABLE→SUCCESS after profile (PW-EG3 pattern) |

---

## 9. Implementation boundaries

### Authorized files (indicative)

```text
talkback-app/src/androidTest/java/com/talkback/appprod/fieldtest/
  FieldTestOperationalAuthority.kt
  FieldTestGenerationFactAuthority.kt
  FieldTestProvisioningSupport.kt
  Adr0057FieldTestProvisioningInstrumentedTest.kt

talkback-app/src/androidTest/.../Adr0057FieldTestProvisioningFixturesTest.kt  (optional desk)

scripts/adr0057-field-test-provision-device.ps1   (thin adb wrapper — optional)
scripts/adr0057-collect-preconditions.ps1         (PRECONDITIONS — may follow FTPH)
```

### Must not change

```text
TalkbackCoordinator
GbcProductionTrustComposition semantics
ProductionGenerationFactVerifier
ProductionProfileRevisionAuthenticator
OperationalAnchorEstablisher / PapT2 issuer semantics
GBC core / delivery / wire
talkback-app main/ / release manifest
```

---

## 10. Claim boundary

| After FTPH implementation PASS | May claim |
|--------------------------------|-----------|
| Field test trust preconditions provisionable on device | **YES** |
| Production trust path exercisable in field (anchor + acceptor) | **YES** (precondition) |
| Field Establishment Ceremony product | **NO** |
| Operational provisioning production-closed | **NO** |
| Track C FIELD PASS | **NO** (requires Case A run) |
| ADR-0057 COMPLETE | **NO** |

---

## 11. Sign-off checklist

```text
☑ Spec reviewed — boundaries accepted (WITH FTPH-IA-T1)
☑ FTPH implementation authorized
□ FTPH-EG1..EG6 formal desk PASS (field EG2–EG5 exercised; EG1/EG6 desk harness pending)
☑ M01/M02/M03 FTPH field PASS + PRECONDITIONS recorded
    evidence: talkback/logs/adr0057-preconditions-20260831-145126/PRECONDITIONS.txt
    fingerprintMatch=true on all three; GF fp=A0:7A:09:30:…:CA:D1
☑ Track C Case A re-run authorized (2026-08-31)
```

**Field note (2026-08-31):** Android Conscrypt rejected a subset of ECDSA R||S→DER encodings
when `EcdsaP256Verifier.derEncode` stripped the DER INTEGER leading `0x00`. Fixed to use
`BigInteger.toByteArray()` (two's-complement). Profile delivery uses desk-signed assets
(androidTest) because device-local `SHA1PRNG`/`Signature` path was unreliable for FTPH
operational signing; GF signer Path B persist works on-device.

---

**Related:** [Track C Case A Run Card](./adr0057-track-c-production-convergence-field-validation-run-card.md) · [OE Narrow Authorization](../adr/0057-operational-establishment-narrow-authorization.md) · [PW Narrow Authorization](../adr/0057-production-verifier-wiring-narrow-authorization.md)
