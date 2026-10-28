# Meeting Profile01 — Operator Establishment Provisioning Recovery Run Card

**Parent:** `meeting-profile01-establishment-provisioning-design-slice.md`  
**Field gate:** `meeting-profile01-establishment-field-gate-ep1-ep5-run-card.md`  
**Status:** **CLOSED** (2026-09-08)

```text
EP Field Run #1             VALID FAIL / PROVISIONING_DATA_GAP
EP Field Run #2             PASS (EP4 domain-independence semantics)
Operator Provisioning       CLOSED
Establishment Field Gate    CLOSED / FIELD PROVEN

P1-D / P1-E                   HOLD
P1-C package field            NOT AUTHORIZED
Meeting J0                    NOT AUTHORIZED
Phase A                       BLOCKED
```

**Purpose:** Recover authoritative establishment trust state on M01 + M02 using the
**existing PR-EP-1/2/3 toolchain** — then re-run the **same** EP1–EP5 field gate.

**Does NOT authorize:** production code changes, runtime enrollment in Factory, P1-D,
P1-C field, Meeting J0, or manifest backfill from `EP_PROBE`.

---

## 0. EP Field Run #1 — signed verdict (no implementation RCA)

Evidence: `talkback/logs/ep-establishment-field-20260908-154048/`

```text
Instrumentation path        PASS
Probe observability         PASS
Negative fence (EP5)        PASS

Authoritative establishment data
                            ABSENT

Classification:
  EP_FAIL_PROVISIONING_DATA_GAP

Corroboration:
  UnknownModule + Unavailable + establishmentKeyVersion=0
  accepted-local-establishment-trust-state.bin ABSENT (M01 + M02)
  signing FTPH present; establishment enrollment NOT performed
```

**Do not investigate:** P1-A/C, Factory wiring, Keystore verifier, or probe implementation.
Symptoms match missing operator provisioning exactly.

---

## 1. Recovery procedure (frozen order)

Use **PR-EP-3 enrollment helper + PR-EP-1 acceptor + ADR-0057 PAP delivery** only.
No new design. No device self-authorization.

```text
Step 1  M01 local establishment identity
        → operator-controlled Keystore provision/enrollment (PR-EP-2)

Step 2  M02 local establishment identity
        → operator-controlled Keystore provision/enrollment (PR-EP-2)

Step 3  Collect enrollment evidence (both modules)
        → EstablishmentEnrollmentEvidenceProducer output
        → MUST NOT read values from EP_PROBE or prior field run

Step 4  Provisioning authority (operator workstation)
        → assign monotonic establishmentKeyVersion per module
        → build draft with M01 + M02 public bindings (single revision)
        → publish via ADR-0057 PAP seam (no second signing protocol)

Step 5  PAP delivery + accept on each device
        → EstablishmentProfileRevisionDeliveryAcceptor
        → accepted-local-establishment-trust-state.bin PRESENT on both

Step 6  Static inventory (before EP re-run)
        → see §3

Step 7  New PROVISIONING_MANIFEST
        → sources: authority output + evidence + accepted store metadata
        → MUST NOT backfill from EP_PROBE

Step 8  Re-run EP1–EP5 field gate (identical run card, EP3=N/A)
```

**Tightening (Run #1 lesson):** **M01 and M02 each need a real local Keystore establishment
identity.** EP2 failed on both devices in Run #1. Provisioning M02 only is insufficient.

**Profile completeness:** Even with EP3=N/A for Run #2, publish **both** M01 and M02
establishment bindings in one revision so M01 store contains M02 and M02 contains M01.

---

## 2. Tools (existing — do not reimplement)

| Step | Component |
|------|-----------|
| Keystore provision | `AndroidKeystoreLocalEstablishmentIdentityStore` (operator action on device) |
| Evidence | `EstablishmentEnrollmentEvidenceProducer` |
| Version assignment | `EstablishmentEnrollmentAuthorityHelper` |
| PAP publish | ADR-0057 `AuthenticatedProfileRevisionEnvelope` + operational anchor |
| Device accept | `EstablishmentProfileRevisionDeliveryAcceptor` |
| Field probe | `EpEstablishmentFieldGateInstrumentedTest` |

Enrollment workflow reference: `EstablishmentEnrollmentWorkflow.publishAcceptAndVerify`
(unit/integration proven; field uses split operator → PAP → per-device accept).

---

## 3. Step 6 — static inventory checklist

Record on paper or `INVENTORY.txt` before EP Field Run #2.

### Per device (M01, M02)

```text
□ filesDir/gbc-trust/operational-trust-anchor.bin          PRESENT
□ filesDir/gbc-trust/accepted-local-trust-state.bin        PRESENT (signing — already from FTPH)
□ filesDir/gbc-trust/accepted-local-establishment-trust-state.bin  PRESENT
□ Android Keystore alias talkback-establishment-<moduleId>-v<version>  PRESENT
□ local establishment public SPKI fingerprint recorded (from evidence, not EP_PROBE)
```

### Cross-device (accepted establishment store)

```text
□ M01 store: binding row for M02 (moduleId, version, ACTIVE, SPKI fingerprint)
□ M02 store: binding row for M01 (moduleId, version, ACTIVE, SPKI fingerprint)
□ M01 store: binding row for M01 local module (matches M01 Keystore SPKI)
□ M02 store: binding row for M02 local module (matches M02 Keystore SPKI)
□ taskProfileRevision / revisionIdentity recorded for manifest
```

---

## 4. Pre-run hard checks (Run #2 — all required)

**Do not start instrumentation until all four pass.**

| ID | Check | Pass criterion |
|----|-------|----------------|
| **PRE-1** | PAP accepted establishment Profile | Both devices: `accepted-local-establishment-trust-state.bin` exists; non-empty snapshot |
| **PRE-2** | Local Keystore identity | Both M01 and M02: Keystore alias load returns Ready; SPKI matches evidence |
| **PRE-3** | Manifest complete | `PROVISIONING_MANIFEST.txt` records both modules' `establishmentKeyVersion` + `publicKeyFingerprint` + `signerKeyVersion` + `deploymentTrustDomainId` / `profileRevisionIdentity` — **authority sources only** |
| **PRE-4** | M01 peer binding resolvable | On M01: production `lookupEstablishmentKey(M02, V*)` → **Found** (manual or one-off read-only probe **before** formal EP run; do not use EP_PROBE output to write manifest) |

If PRE-4 fails, stop — fix provisioning, not code.

---

## 5. PROVISIONING_MANIFEST (Run #2 template)

```text
# AUTHORITY SOURCES ONLY — do not copy from EP_PROBE

operator_run_id=<enrollment batch id>
provisioned_at=<ISO8601>
deploymentTrustDomainId=talkback-operational
profileRevisionIdentity=<hex from accepted establishment store>

signerKeyVersion=<from signing store / FTPH>

M01.establishmentKeyVersion=<authority assigned>
M01.publicKeyFingerprint=<sha256 hex from M01 enrollment evidence>
M02.establishmentKeyVersion=<authority assigned>
M02.publicKeyFingerprint=<sha256 hex from M02 enrollment evidence>

# optional cross-check (not used as EP pass input)
M01.keystoreAlias=talkback-establishment-M01-v<version>
M02.keystoreAlias=talkback-establishment-M02-v<version>
```

---

## 6. EP Field Run #2

When PRE-1..PRE-4 pass:

```text
Same run card: meeting-profile01-establishment-field-gate-ep1-ep5-run-card.md
Same topology:  M01 EP1+EP2+EP4+EP5; M02 EP2+EP4+EP5; EP3=N/A
Same scripts:    ep-establishment-field-collect.ps1 -M02Serial DSJ-2407041
                 ep-establishment-field-adjudicate.ps1
```

Pass bar unchanged: **PASS / INVALID / FAIL** only — no `PARTIAL PASS`.

---

## 7. After recovery

| Outcome | Next |
|---------|------|
| EP Run #2 **PASS** | Establishment field gate **CLOSED / FIELD PROVEN**; P1-D authorized separately |
| EP Run #2 **FAIL** | Evidence-layer RCA (provisioning mismatch, wrong version, Keystore drift) — not implementation patch |
| EP Run #2 **INVALID** | Fix instrumentation / log capture / Keystore provider environment |

---

## 8. References

- **Command-level ops:** `meeting-profile01-establishment-operator-provisioning-ops-checklist.md`
- Run #1 evidence: `talkback/logs/ep-establishment-field-20260908-154048/`
- Provisioning design: `meeting-profile01-establishment-provisioning-design-slice.md`
- EP gate: `meeting-profile01-establishment-field-gate-ep1-ep5-run-card.md`
- Signing FTPH (Run #1): `FieldTestProvisioningSupport` / `FTPH_RESULT.txt` on device
