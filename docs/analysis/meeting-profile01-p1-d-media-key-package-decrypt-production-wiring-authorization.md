# Meeting Profile01 — P1-D MEDIA_KEY_PACKAGE Decrypt Production Wiring Authorization

**Parent:** `meeting-profile01-media-key-package-origin-path1-design-slice.md` §7  
**Prerequisite:** `meeting-profile01-establishment-field-gate-ep1-ep5-run-card.md` — **CLOSED / FIELD PROVEN**  
**Status:** **CLOSED / UNIT-PROVEN** (2026-09-08) · PR-P1D-1..3 CLOSED (D1-1..D3-7)

```text
Operator Provisioning       CLOSED
Establishment Field Gate    CLOSED / FIELD PROVEN

P1-A                        CLOSED
P1-B                        CLOSED
P1-C                        CLOSED / UNIT PASS
P1-D                        CLOSED / UNIT-PROVEN
P1-E                        CLOSED / UNIT-PROVEN

PR-P1D-1 Production unwrap   CLOSED (unit D1-1..D1-6)
PR-P1D-2 Factory wiring      CLOSED (unit D2-1..D2-6)
PR-P1D-3 Exit tests          CLOSED (unit D3-1..D3-7)

P1-C package field          NOT AUTHORIZED
Meeting J0                  NOT AUTHORIZED
Phase A Shadow Runtime Gate     AUTHORIZABLE / NEXT
```

**Authorizes:** production `factType=11` decrypt wiring only — peer unwrap through real
Android Keystore establishment identity → supplement registry.

**Does NOT authorize:** P1-E continuation, P1-C package field run, Meeting J0, establishment
rotation, signer fallback, pre-bind retention for factType=11, or host origin changes.

---

## 1. Frozen identity contract (from Establishment Gate)

```text
SigningIdentity != EstablishmentIdentity
```

Proof is **domain independence** (separate authority / store / lookup / verifier / fallback
fence) — **not** numeric inequality between `signerKeyVersion` and
`establishmentKeyVersion`. EP Field Run #2 demonstrated `numericRelation=EQUAL` with
`domainSeparation=PROVEN_INDEPENDENT`.

P1-D must consume **establishment** version on the wire and in unwrap — never signer version.

---

## 2. Authorized production chain (narrow)

```text
received factType=11 (MEDIA_KEY_PACKAGE)
  → decode signed fact + verifyMediaKeyPackage (membership converged + digest match)
  → production local establishment identity (Keystore private key)
  → Profile01MediaKeyPackageDecryptSeam.decrypt(
        wire,
        localRecipientModuleId,
        localEstablishmentKeyVersion,    // NOT signerKeyVersion
    )
  → validate recipient / version / commitment (inside decrypt seam)
  → Profile01DerivedMediaKeyMaterial
  → Profile01SessionMediaSupplement
  → supplementRegistry.put
```

**Existing code (reuse — do not reimplement crypto):**

| Component | Role |
|-----------|------|
| `MeetingProfile01FactWireIngress` | Routes factType=11; passes `localEstablishmentKeyVersion` |
| `Profile01ConferenceMediaFactIngress.ingestMediaKeyPackageSignedFact` | verify → decrypt → `registry.put` |
| `Profile01MediaKeyPackageDecryptSeam` | PEK unwrap + GCM + commitment (unit-proven Q5) |
| `Profile01VerifiedLocalEstablishmentIdentity` | Profile ↔ Keystore binding resolution |
| `Profile01EstablishmentAuthoritySurface.localEstablishmentKeyVersion` | Factory version source |

**Gap (P1-D deliverable):**

| Item | Current | P1-D target |
|------|---------|-------------|
| `Profile01RecipientKeyEstablishmentSeam` production impl | **CLOSED** — `Profile01ProductionRecipientKeyEstablishmentSeam` | RSA-OAEP unwrap via verified Keystore alias |
| `Profile01ConferenceMediaFactIngress.mediaKeyDecrypt` | **CLOSED** — wired via `Profile01ProductionMediaKeyDecryptComposition` | Verified → production seam; unavailable → null |
| `localEstablishmentKeyVersion` in wire ingress | **Wired** via `profile01EstablishmentAuthority` | Verify unchanged; no signer fallback |

---

## 3. Exit criteria (must prove)

### 3.1 Positive (production path)

| ID | Proof |
|----|-------|
| **D1** | Real Android Keystore private key used for PEK unwrap (non-exportable; alias matches accepted local establishment binding) |
| **D2** | `localEstablishmentKeyVersion` passed to decrypt — wire `recipientKeyVersion` interpreted as **establishment** version; signer version never substituted |
| **D3** | Successful decrypt → `Profile01SessionMediaSupplement` → `supplementRegistry.put` → `resolveSupplement` returns non-placeholder for matching `(conferenceId, mediaKeyEpoch)` |

### 3.2 Negative (fail-closed — no fallback)

| ID | Input | Required outcome |
|----|-------|------------------|
| **D4** | Wrong `recipientModuleId` | `RECIPIENT_MODULE_MISMATCH` — no signer path |
| **D5** | Wrong `recipientKeyVersion` | `RECIPIENT_KEY_VERSION_MISMATCH` — no signer version substitution |
| **D6** | Corrupt wrapped PEK / GCM / commitment | Explicit reject — no supplement put |
| **D7** | Missing Keystore / profile binding | `DECRYPT_SEAM_UNAVAILABLE` or unwrap reject — **not** signing key fallback |

### 3.3 Unit scope (minimum before field)

```text
D-UNIT-1  Q5 vector round-trip with production seam test double (Keystore mock or Robolectric)
D-UNIT-2  Factory composition: mediaKeyDecrypt non-null when establishment authority present
D-UNIT-3  ingestMediaKeyPackageSignedFact → registry hit (existing ingress tests extended)
```

**Field run for P1-D is NOT authorized by this document.** Unit + directed wiring only.

---

## 4. Explicit exclusions

```text
P1-E CREATION incomplete-apply continuation     HOLD — separate authorization
P1-C package field (host emit on device)          NOT AUTHORIZED
Meeting J0 E2E                                    NOT AUTHORIZED
Runtime establishment enroll / rotate             FORBIDDEN
Signer key fallback on decrypt miss               FORBIDDEN
Pre-bind retention for factType=11                FORBIDDEN (frozen)
Changes to P1-C origin bridge / builder crypto    FORBIDDEN
```

---

## 5. Implementation queue (suggested PR order)

```text
PR-P1D-1  Profile01ProductionRecipientKeyEstablishmentSeam     CLOSED (unit D1-1..D1-6)
            Keystore alias from verified identity only — wire version is not alias authority

PR-P1D-2  TalkbackRuntimeFactory wiring                        CLOSED (unit D2-1..D2-6)
            Verified identity → production decrypt seam; unavailable → null (fail-closed)

PR-P1D-3  Unit exit tests D3-1..D3-7 + ingress registry hit   CLOSED
            factType=11 → verify → production unwrap → decrypt → registry.put → resolveSupplement hit
```

Each PR: behavior-neutral outside production decrypt path; no P1-E hook.

### PR-P1D-1 frozen principle

```text
Keystore alias is NOT wire authority.

accepted local establishment binding + verified local Keystore identity
  → authorized alias / key handle
  → unwrap

wire recipientKeyVersion is compared to verified identity only.
```

### PR-P1D-3 exit tests (D3-1..D3-7)

```text
D3-1  valid MEDIA_KEY_PACKAGE → ingest APPLIED (+ wire ingress APPLIED)
D3-2  registry.resolveSupplement → real 16B/12B/8B key material (non-placeholder)
D3-3  recipientModuleId mismatch → terminal reject, registry unchanged
D3-4  recipientKeyVersion mismatch → terminal reject, registry unchanged
D3-5  corrupt wrappedPek / ciphertext / commitment → terminal reject, registry unchanged
D3-6  establishment unavailable → DECRYPT_SEAM_UNAVAILABLE, registry unchanged
D3-7  signer version == establishment version allowed; unwrap uses establishment alias only
```

**Does NOT prove:** CREATION APPLIED · Meeting J0 PASS · P1-E continuation · factType=11 pre-bind retention.

### PR-P1D-2 frozen Factory behavior

```text
local establishment identity = Verified
  → mediaKeyDecrypt = production decrypt seam (non-null)

local establishment identity = Unavailable
  → mediaKeyDecrypt = null
  → factType=11 = DECRYPT_SEAM_UNAVAILABLE

Do NOT create a always-failing decrypt seam to keep Factory non-null.
```

---

## 6. Observability (unit / future field)

```text
PROFILE01_MEDIA_KEY_INGEST outcome=APPLIED|REJECTED_<reason>
  establishmentKeyVersion=<v>
  signerKeyVersion=<s>          # recorded for domain audit only — not used for unwrap
```

Do not treat `signerKeyVersion == establishmentKeyVersion` as failure.

---

## 7. References

- Path 1 stack: `meeting-profile01-media-key-package-origin-path1-design-slice.md`
- Establishment gate: `meeting-profile01-establishment-field-gate-ep1-ep5-run-card.md`
- EP Run #2 evidence: `talkback/logs/ep-establishment-field-20260908-160928/`
- Operator manifest: `talkback/logs/ep-establishment-operator-20260908-155431/PROVISIONING_MANIFEST.txt`
- Decrypt seam: `Profile01MediaKeyPackageDecryptSeam.kt`
- Ingress: `Profile01ConferenceMediaFactIngress.kt`, `MeetingProfile01FactWireIngress.kt`
- Factory: `TalkbackRuntimeFactory.kt`
