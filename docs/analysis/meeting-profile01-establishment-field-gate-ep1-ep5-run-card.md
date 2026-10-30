# Meeting Profile01 — Establishment Field Gate EP1–EP5 Run Card

**Parent:** `meeting-profile01-establishment-provisioning-design-slice.md`  
**Status:** **EP Field Run #2 PASS** — **Establishment Field Gate CLOSED / FIELD PROVEN** (2026-09-08)

```text
EP Field Run #1             VALID FAIL / PROVISIONING_DATA_GAP
EP Field Run #2             PASS (EP4 semantics corrected — domain independence)
Operator Provisioning       CLOSED
Establishment Field Gate    CLOSED / FIELD PROVEN
Implementation RCA          NOT REQUIRED
Code changes                  NOT AUTHORIZED

EP Production Wiring        CLOSED / UNIT PASS
EP1–EP5 field gate          CLOSED / FIELD PROVEN

P1-A/B/C                   CLOSED
P1-D / P1-E                P1-D AUTHORIZED / NEXT · P1-E HOLD
P1-C package field         NOT AUTHORIZED
Meeting J0                 NOT AUTHORIZED

ADR-0061                   FREEZE
PreBindRetention           FREEZE
CREATION origin/replay     FREEZE
Phase A                    BLOCKED
```

**Goal:** Prove on real devices that production establishment trust state and Android
Keystore possession form a closed loop — **without** MEDIA_KEY_PACKAGE, P1-D decrypt, or
Meeting J0.

**Does NOT authorize:** production code changes, P1-D, P1-C field, runtime enrollment,
auto-provision, signer fallback, or using this gate as J0 substitute.

---

## 1. Questions this gate answers

```text
M01 是否真实信任 M02 establishment binding
M02 是否真实持有匹配 private identity
双方 version / SPKI 是否一致（按 provisioning 记录裁决）
signing 与 establishment 两个 identity domain 是否确实独立
错误输入是否显式失败且无 fallback
```

---

## 2. Topology / devices

| Role | Module | Serial (Run #1) |
|------|--------|-----------------|
| Host lookup (EP1) | M01 | `HTUBB21B09220661` |
| Local possession (EP2) | M02 | `DSJ-2407041` |

M03 **not required** for this gate. Same candidate build on M01 + M02.

**Run #1 result:** `talkback/logs/ep-establishment-field-20260908-154048/` — **FAIL /
PROVISIONING_DATA_GAP**. Recovery: `meeting-profile01-establishment-operator-provisioning-recovery-run-card.md`

---

## 3. Frozen cases

### EP1 — REMOTE LOOKUP (M01)

```text
M01 production lookup(M02, V*)
→ Found
→ expected moduleId = M02
→ RSA-3072 SPKI fingerprint matches provisioning record
→ establishmentKeyVersion = V*
PASS = lookupOutcome=Found AND source is accepted authenticated Profile store
```

### EP2 — LOCAL POSSESSION (M02) — includes W3 Android Keystore smoke

```text
M02 production verifiedLocalEstablishmentIdentity()
→ Verified
→ Keystore public SPKI fingerprint == Profile M02 local row
→ establishmentKeyVersion == V*
PASS = real Android Keystore identity verified (EP2 PASS closes field-ready W3 gap)
```

### EP3 — REVERSE DIRECTION (optional)

```text
Only when symmetric M02→M01 is required for upcoming package topology:
  M02 lookup(M01, V*) + M01 local possession (separate probe run on M01)
Default primary direction (M01→M02 only):
  EP3 = N/A on both devices — do NOT fabricate PASS
```

Set instrumentation `-e ep3Mode required` only when reverse is in scope.

### EP4 — IDENTITY DOMAIN SEPARATION (each device)

```text
Record signerKeyVersion and establishmentKeyVersion from EP_PROBE.

PASS = signing and establishment domains are independently sourced, stored,
       resolved and fenced on the production path.

Numeric equality is allowed:
  signerKeyVersion=1 and establishmentKeyVersion=1
  => independent generation #1 in each version space — NOT semantic collapse.

Structural corroboration required when probe emits domainSeparationOutcome=CollapsedDomain
(numeric equality only — probe label is legacy; adjudicator maps to PROVEN_INDEPENDENT):

  EP1  establishment lookup Found (not signing store)
  EP2  Keystore Verified against establishment binding
  EP5  wrong/unknown explicit reject + storeBindingCountUnchanged=true

Adjudicator records:
  numericRelation=EQUAL|DISTINCT
  domainSeparation=PROVEN_INDEPENDENT

FAIL only when:
  domainSeparationOutcome=InsufficientState
  establishment version derived from / aliased to signer version
  establishment lookup reads signing store
  missing establishment binding falls back to signer
  wire recipientKeyVersion interpreted through signing domain
  changing one domain implicitly mutates the other
```

**Frozen contract:** `SigningIdentity != EstablishmentIdentity` — NOT
`signerKeyVersion numeric value != establishmentKeyVersion numeric value`.

### EP5 — NEGATIVE FENCE (each device)

```text
Read-only negative lookups:
  lookup(M99, V*) → UnknownModule
  lookup(peer, V*+99) → UnknownKeyVersion (or KeyNotUsable)
  storeBindingCountUnchanged=true
PASS = explicit negative outcomes, zero signer fallback, zero runtime provision/rotation
```

---

## 4. Preconditions (hard)

```text
□ Same candidate APK on M01 + M02 (record git SHA / versionName)
□ ProductionGbcTrustWiring active (NOT TestGbcTrustWiring)
□ Establishment production wiring active (TalkbackRuntimeFactory composition)
□ Operational anchor + signing Profile provisioned (ADR-0057 field helper or equivalent)
□ Establishment Profile revision accepted into accepted-local-establishment-trust-state.bin
□ M02 Android Keystore establishment identity provisioned for V* (operator enrollment — NOT runtime)
□ NO Meeting / Conference / MEDIA_KEY_PACKAGE exercise
□ Record expected V* and SPKI fingerprints from provisioning manifest BEFORE run
```

Trust artifacts (per device):

```text
filesDir/gbc-trust/
  operational-trust-anchor.bin
  accepted-local-trust-state.bin
  accepted-local-establishment-trust-state.bin
```

**If lookup returns UnknownModule:** classify as **EP FAIL / provisioning data gap** — not
P1-A/P1-C implementation failure. Do not patch code to bypass.

---

## 5. Procedure

```text
T0  Record PRECONDITIONS.txt (build SHA, V*, expected fingerprints per module)
T1  force-stop Talkback on M01 + M02
T2  Start log collectors (see scripts/ep-establishment-field-collect.ps1)
T3  Launch Talkback on M01 + M02 (Talk page idle — no meeting actions)
T4  Run establishment probe instrumentation on M01 then M02:
      -e establishmentKeyVersion <V*>
      optional: -e ep3Mode required
T5  Stop collectors; run adjudication script
T6  Fill ADJUDICATION.txt (verdict template §7)
```

Suggested log directory:

```text
talkback/logs/ep-establishment-field-<yyyyMMdd-HHmmss>/
  M01-logcat.txt
  M02-logcat.txt
  PRECONDITIONS.txt
  ADJUDICATION.txt
  PROVISIONING_MANIFEST.txt   (expected V*, SPKI fingerprints — operator supplied)
```

---

## 6. Evidence fields (EP_PROBE)

Each probe emits one line:

```text
EP_PROBE case=EPn device=<moduleId> peerModuleId=... establishmentKeyVersion=...
  publicKeyFingerprint=<sha256-hex> localKeyAlias=... localIdentityAvailability=...
  profileRevisionIdentity=<hex> deploymentTrustDomainId=... signerKeyVersion=...
  lookupOutcome=... / domainSeparationOutcome=...
```

**Do NOT** log private keys or full DER/SPKI blobs. Fingerprint hex only.

Collector filter: `adb logcat -s Talkback:I | findstr EP_PROBE`

---

## 7. Field verdict (only three classes)

### PASS

```text
EP1 @ M01  → Found (expected V*, fingerprint match vs manifest)
EP2 @ M02  → Verified (Keystore fingerprint match vs manifest)
EP3        → PASS if required, else N/A recorded on both devices
EP4 @ both → domain independence proven (numeric equality allowed)
EP5 @ both → negative outcomes + storeBindingCountUnchanged=true
```

### INVALID

```text
Android Keystore / provider / instrumentation / log capture failure
No adjudicable EP_PROBE evidence
```

### FAIL

```text
Profile ↔ Keystore mismatch
Peer lookup missing/wrong SPKI or version
UnknownModule with no provisioning excuse documented
EP4 domain dependence / InsufficientState (see §3 EP4 FAIL criteria)
Signs of signer fallback, runtime auto-provision, or rotation
```

---

## 8. Scripts

| Script | Purpose |
|--------|---------|
| `talkback/scripts/ep-establishment-field-collect.ps1` | Start logcat + run instrumentation per device |
| `talkback/scripts/ep-establishment-field-adjudicate.ps1` | Parse EP_PROBE → draft verdict |

Instrumentation class:

```text
com.talkback.appprod.fieldtest.EpEstablishmentFieldGateInstrumentedTest
```

---

## 9. After this gate

```text
EP1–EP5 PASS  →  Establishment field gate CLOSED / FIELD PROVEN
                →  P1-D ELIGIBLE FOR SEPARATE AUTHORIZATION (not auto-authorized)
EP FAIL         →  provisioning / enrollment data gap — fix trust artifacts, re-run EP only
                →  do NOT authorize P1-C package field or P1-D on EP FAIL
```

---

## 10. References

- **P1-D authorization (next):** `meeting-profile01-p1-d-media-key-package-decrypt-production-wiring-authorization.md`
- `EstablishmentFieldProbe.kt` / `EstablishmentFieldProbeRuntime.kt`
- `EpEstablishmentFieldGateSupport.kt` (androidTest probe runner)
