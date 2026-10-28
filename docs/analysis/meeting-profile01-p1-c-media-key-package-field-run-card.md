# Meeting Profile01 — P1-C MEDIA_KEY_PACKAGE Field Run Card

**Parent authorization:** `meeting-profile01-p1-c-media-key-package-field-authorization.md`  
**Status:** **CLOSED / FIELD-PROVEN** (2026-09-09) — authoritative PASS `talkback/logs/p1c-package-field-20260909-101752/`

```text
P1-C-FIELD-OBS              CLOSED / UNIT PASS
P1-C collect/adjudicate     CLOSED / DRY-RUN PASS
P1-C C-F7 injection seam    CLOSED / UNIT PASS
P1-C first-run              CLOSED / FIELD-PROVEN (20260909-101752)
P1-C package field          CLOSED / FIELD-PROVEN
  closure: meeting-profile01-p1-c-field-closure.md
Meeting J0                  AUTHORIZABLE / NEXT
Phase A Shadow Runtime Gate     AUTHORIZABLE / NEXT
Phase A (full)                  BLOCKED pending Shadow Runtime

P1-D                        CLOSED / UNIT-PROVEN   (do not reopen)
P1-E                        CLOSED / UNIT-PROVEN   (do not reopen / do not adjudicate drain)

PreBindRetention            FREEZE
CREATION origin/replay      FREEZE
ADR-0061                    FREEZE
```

**Goal:** On real devices, prove host produces recipient-specific `factType=11` and peer
consumes it through production verify + Keystore unwrap into `supplementRegistry` —
**without** requiring CREATION APPLIED or Meeting lifecycle completion.

**Does NOT authorize:** Meeting J0, P1-E drain PASS, Meeting OPERATIONAL, code changes to
P1-D/P1-E behavior, or treating this run as J0 substitute.

---

## 1. Questions this gate answers

```text
Host 是否对已授权 recipient 真实发出 factType=11
Package 是否绑定当前 CREATION membershipFactDigest + mediaKeyEpoch
Transport 是否把 package 送到 peer
Peer production verify 是否 PASS
Peer 是否用真实 local establishment identity / Keystore unwrap（无 signer fallback）
supplementRegistry 是否 READY 且非 placeholder
错误 recipient/version / 缺 establishment 是否显式拒绝且 registry 零突变
```

**Not answered here:**

```text
INCOMPLETE_DRAIN 是否发生
CREATION 是否 APPLIED
Meeting 是否 OPERATIONAL
```

---

## 2. Topology / devices

| Role | Module | Serial (EP-proven baseline) |
|------|--------|-----------------------------|
| Host (origin) | M01 | `HTUBB21B09220661` |
| Peer (ingest) | M02 | EP Run #2 peer (record serial in PRECONDITIONS) |

M03 **not required**. Same candidate APK on M01 + M02.

Reuse Establishment Field Gate provisioning artifacts (accepted establishment trust state +
M02 Keystore V\*). If EP lookup would fail, classify **INVALID / PROVISIONING** — not P1-C
implementation FAIL.

---

## 3. Frozen cases (C-F1…C-F7)

### C-F1 — HOST ORIGIN IDENTITY

```text
M01 PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN (or mapped ORIGIN_EMIT factType=11)
  recipientModuleId = M02
  recipientEstablishmentKeyVersion = V* (manifest)
  publicationOutcome = EMITTED | equivalent success
PASS = authorized recipient + correct establishment version on emit
```

### C-F2 — CREATION BINDING

```text
Same ORIGIN line includes:
  membershipFactDigest = current CREATION digest (hex)
  mediaKeyEpoch = current media key epoch
PASS = digest + epoch present and consistent with host CREATION canonical fact
       for this conference (cross-check CREATION emit / session index if needed)
```

### C-F3 — TRANSPORT DELIVERY

```text
M02 observes factType=11 ingress attempt
  (PROFILE01_MEDIA_KEY_INGEST or WIRE_INGRESS factType=11)
PASS = peer received the package on wire / ingress path
```

### C-F4 — PRODUCTION VERIFY

```text
Peer ingest outcome not REJECTED_DECODE / REJECTED_VERIFY for trust/signature
PASS = authenticated fact boundary accepts package under production signer trust
```

### C-F5 — KEYSTORE UNWRAP (NO SIGNER FALLBACK)

```text
Peer decrypt uses local establishment identity V*
  outcome = APPLIED | DECRYPTED equivalent
  establishmentKeyVersion = V* on ingest line
PASS = real Keystore unwrap; no signer-key unwrap; no DECRYPT_SEAM_UNAVAILABLE
       on provisioned M02
```

### C-F6 — SUPPLEMENT READY (NON-PLACEHOLDER)

```text
PROFILE01_MEDIA_SUPPLEMENT
  conferenceId=… mediaKeyEpoch=… state=READY
Or equivalent registry-ready observability proving:
  key length 16 · salt length 12 · hint length 8
  (state/lengths only — never print bytes)
PASS = registry hit for matching (conferenceId, mediaKeyEpoch); non-placeholder
```

### C-F7 — NEGATIVE FENCE

```text
At least one of:
  A) wrong recipientModuleId / wrong establishmentKeyVersion package path
  B) missing / unavailable local establishment identity on peer
→ explicit reject (RECIPIENT_* / UNKNOWN_* / DECRYPT_SEAM_UNAVAILABLE / equivalent)
→ supplementRegistry binding count unchanged for target conference/epoch
PASS = explicit reject + zero mutation
Do NOT use signer fallback to manufacture success
```

C-F7 may use a directed negative probe / secondary short run on the **same APK**.
Record procedure in ADJUDICATION.txt. Unit D4–D7 corroborate crypto fence but **do not**
replace C-F7 field evidence unless INVALID documents why field negative is blocked
(then gate stays incomplete — not PASS).

---

## 4. Preconditions (hard)

```text
□ Same candidate APK on M01 + M02 (git SHA / versionName recorded)
□ ProductionGbcTrustWiring active
□ Production establishment wiring active (P1-D factory composition present)
□ P1-C origin bridge hooked (MeetingProfile01MediaKeyPackageOriginBridge)
□ EP provisioning still valid (manifest V*, SPKI fingerprints, Keystore on M02)
□ Observability sufficient for §6 evidence map (thin OBS PR OK if behavior-neutral)
□ Trigger path reaches host CREATION canonical fact + eligible peer fanout
□ Do NOT require / wait for peer CREATION APPLIED
□ Do NOT adjudicate Meeting OPERATIONAL / J0 O0–O3 as this gate’s PASS input
□ No PreBindRetention / ADR-0061 / CREATION origin changes
```

Suggested trigger (minimal):

```text
Start conference create enough for M01 CREATION origin + package fanout to M02.
Stop adjudication once package leg evidence is complete.
Ignore / park CREATION incomplete / apply outcomes for J0.
```

---

## 5. Procedure

```text
T0  Write PRECONDITIONS.txt
      build SHA, versionName, V*, expected fingerprints, device serials
T1  force-stop Talkback on M01 + M02
T2  Start log collectors (host + peer)
T3  Launch Talkback; bring topology to CREATION-canonical + eligible peer
T4  Positive path: wait for ORIGIN emit + peer INGEST + SUPPLEMENT READY
T5  Negative path (C-F7): run directed fence; confirm reject + zero registry mutation
T6  Stop collectors; fill ADJUDICATION.txt (§8)
```

Suggested log directory:

```text
talkback/logs/p1c-package-field-<yyyyMMdd-HHmmss>/
  M01-logcat.txt
  M02-logcat.txt
  PRECONDITIONS.txt
  ADJUDICATION.txt
  PROVISIONING_MANIFEST.txt   (copy / link EP manifest)
```

Collector filter (minimum):

```text
adb logcat -s Talkback:I | findstr /I "PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN PROFILE01_MEDIA_KEY_INGEST PROFILE01_MEDIA_SUPPLEMENT"
```

---

## 6. Evidence map (P1-C-FIELD-OBS landed)

Field tags are first-class structured lines — adjudicator must join on fields, not guess:

```text
same conferenceId
same mediaKeyEpoch
same recipientModuleId
same membershipFactDigest   (ORIGIN ↔ CREATION binding; peer INGEST uses package recipient)

ORIGIN publicationOutcome=EMITTED
→ peer INGEST outcome=APPLIED
→ SUPPLEMENT state=READY

Negative:
INGEST outcome=REJECTED_<reason>
AND no matching SUPPLEMENT state=READY
```

| Semantic tag | Emitter | Covers |
|--------------|---------|--------|
| `PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN` | `MeetingProfile01MediaKeyPackageOriginBridge` | C-F1, C-F2 |
| `PROFILE01_MEDIA_KEY_INGEST` | `Profile01ConferenceMediaFactIngress` | C-F3, C-F4, C-F5 |
| `PROFILE01_MEDIA_SUPPLEMENT state=READY` | after `supplementRegistry.put` only | C-F6 |

Legacy lines (`PROFILE01_ORIGIN_EMIT factType=11`, `WIRE_INGRESS factType=11`, `PROFILE01_PACKAGE_ORIGIN`) may still appear; **C-F adjudication prefers the three OBS tags above**.

### Required field prints

```text
PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN
  conferenceId=
  mediaKeyEpoch=
  recipientModuleId=
  recipientEstablishmentKeyVersion=
  membershipFactDigest=
  publicationOutcome=

PROFILE01_MEDIA_KEY_INGEST
  conferenceId=
  mediaKeyEpoch=
  recipientModuleId=
  establishmentKeyVersion=
  outcome=APPLIED|REJECTED_<reason>

PROFILE01_MEDIA_SUPPLEMENT
  conferenceId=
  mediaKeyEpoch=
  state=READY
```

**Never print:** key, salt, PEK, plaintext, private keys, ciphertext bodies.

---

## 7. Adjudication rules

Scripts:

| Script | Role |
|--------|------|
| `talkback/scripts/p1c-package-field-collect.ps1` | Collect only — logs + PRECONDITIONS / RUN_META / NEGATIVE_FIXTURE |
| `talkback/scripts/p1c-package-field-adjudicate.ps1` | Strict identity join; C-F1…C-F7; INVALID≠FAIL |
| `talkback/scripts/p1c-package-field-adjudicate-dry-run.ps1` | Fixture dry-run (PASS/FAIL/INVALID) |

Join keys (no inference):

```text
conferenceId
mediaKeyEpoch
recipientModuleId
membershipFactDigest
```

Positive chain (required):

```text
ORIGIN publicationOutcome=EMITTED
→ peer WIRE_INGRESS factType=11
→ PROFILE01_MEDIA_KEY_INGEST outcome=APPLIED
→ PROFILE01_MEDIA_SUPPLEMENT state=READY
READY timestamp <= APPLIED timestamp
```

### PASS

```text
C-F1…C-F7 all PASS (per-case lines in ADJUDICATION.txt)
POSITIVE LEG PASS
C-F5 evidence uses decryptOutcome=SUCCESS establishmentPath=PRODUCTION_WIRED
  (does not claim live Keystore unwrap from APPLIED alone)
```

### INVALID

```text
instrumentation / required OBS field absent
device/log capture incomplete
conference identity cannot correlate
negative fixture not actually injected
READY/APPLIED timestamps absent when order check required
```

### FAIL

```text
origin emitted but peer never received factType=11
received but verify/decrypt rejected unexpectedly
APPLIED without matching READY
READY identity mismatch / READY after APPLIED (evidence anomaly)
negative input produced READY after reject
```

**Do not** upgrade to Meeting J0 PASS from this run.

---

## 8. ADJUDICATION.txt template

```text
P1-C package field run
buildSha=
versionName=
devices=M01=… M02=…
establishmentKeyVersion=V*

C-F1 HOST_ORIGIN          PASS|FAIL|INVALID  evidence=
C-F2 CREATION_BINDING     PASS|FAIL|INVALID  digest= epoch= evidence=
C-F3 TRANSPORT            PASS|FAIL|INVALID  evidence=
C-F4 VERIFY               PASS|FAIL|INVALID  evidence=
C-F5 KEYSTORE_UNWRAP      PASS|FAIL|INVALID  evidence=
C-F6 SUPPLEMENT_READY     PASS|FAIL|INVALID  evidence=
C-F7 NEGATIVE_FENCE       PASS|FAIL|INVALID  evidence=

CREATION_APPLIED observed=YES|NO|UNKNOWN   (informational only — not gate input)
INCOMPLETE_DRAIN observed=YES|NO|UNKNOWN   (informational only — not gate input)

VERDICT=PASS|FAIL|INVALID
Next= HOLD_J0 | AUTHORIZE_J0_ELIGIBLE | FIX_AND_RERUN_P1C_ONLY
```

---

## 9. Scripts

| Script | Purpose |
|--------|---------|
| `talkback/scripts/p1c-package-field-collect.ps1` | Dual-device logcat + PRECONDITIONS / RUN_META / NEGATIVE_FIXTURE (no verdict) |
| `talkback/scripts/p1c-package-field-adjudicate.ps1` | Parse ORIGIN/WIRE/INGEST/SUPPLEMENT → C-F1…C-F7 + VERDICT |
| `talkback/scripts/p1c-package-field-adjudicate-dry-run.ps1` | Fixture dry-run — **CLOSED / DRY-RUN PASS** |
| Fixtures | `talkback/scripts/fixtures/p1c-package-field/` |

Field run remains **HOLD** until separately authorized after scripts PASS.

---

## 10. After this run

```text
PASS (20260909-101752) → P1-C package field CLOSED / FIELD-PROVEN
                       → P1-C-R1 CLOSED / FIELD-PROVEN
                       → Meeting J0 AUTHORIZABLE / NEXT
                         auth: meeting-profile01-j0-meeting-operational-authorization.md

FAIL     → RCA inside package leg only; J0 remains BLOCKED

INVALID  → fix preconditions / OBS / provisioning; re-run this card only
```

J0 (later, separate auth) must still prove:

```text
CREATION retained/pending
+ real factType=11
→ supplement ready
→ P1-E drain
→ CREATION APPLIED
```

---

## 11. References

- Authorization: `meeting-profile01-p1-c-media-key-package-field-authorization.md`
- Path 1 §11.1 package leg: `meeting-profile01-media-key-package-origin-path1-design-slice.md`
- EP gate (devices / trust baseline): `meeting-profile01-establishment-field-gate-ep1-ep5-run-card.md`
- P1-D unit: `meeting-profile01-p1-d-media-key-package-decrypt-production-wiring-authorization.md`
- P1-E unit: `meeting-profile01-p1-e-creation-incomplete-apply-continuation-authorization.md`
- Origin: `MeetingProfile01MediaKeyPackageOriginBridge.kt`
- Coordinator hook: `TalkbackCoordinator.publishMediaKeyPackageToEligiblePeers`
- Ingress: `MeetingProfile01FactWireIngress.ingestMediaKeyPackage`
