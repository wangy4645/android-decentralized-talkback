# Meeting Profile01 — P1-C MEDIA_KEY_PACKAGE Field Authorization

**Parent:** `meeting-profile01-media-key-package-origin-path1-design-slice.md` §6 / §11.1  
**Prerequisite:**  
- Establishment Field Gate — **CLOSED / FIELD PROVEN**  
- P1-C unit (origin/publication) — **CLOSED / UNIT PASS**  
- P1-D — **CLOSED / UNIT-PROVEN**  
- P1-E — **CLOSED / UNIT-PROVEN** (not exercised by this gate)  
**Companion run card:** `meeting-profile01-p1-c-media-key-package-field-run-card.md`  
**Status:** **CLOSED / FIELD-PROVEN** (2026-09-09) — authoritative evidence `talkback/logs/p1c-package-field-20260909-101752/` · closure: `meeting-profile01-p1-c-field-closure.md`

```text
Operator Provisioning       CLOSED
Establishment Field Gate    CLOSED / FIELD PROVEN

P1-A                        CLOSED
P1-B                        CLOSED
P1-C (unit origin)          CLOSED / UNIT PASS
P1-D                        CLOSED / UNIT-PROVEN
P1-E                        CLOSED / UNIT-PROVEN

P1-C-FIELD-OBS              CLOSED / UNIT PASS
P1-C collect/adjudicate     CLOSED / DRY-RUN PASS
P1-C C-F7 injection seam    CLOSED / UNIT PASS
P1-C-R1 post-bind republish CLOSED / FIELD-PROVEN
  auth: meeting-profile01-p1-c-r1-post-bind-republish-obligation.md
  evidence: product_auto_POST_BIND_EMITTED (20260909-101752)
Group precondition          CLOSED / FIELD PASS
Invite join precondition    CLOSED / FIELD PASS
P1-C package field          CLOSED / FIELD-PROVEN
  closure: meeting-profile01-p1-c-field-closure.md
Meeting J0                  CLOSED / FIELD-PROVEN
  closure: meeting-profile01-j0-field-closure.md
Phase A                     ELIGIBLE FOR NEXT GATE
ADR-0056                    KEEP
Meeting media cutover       NOT AUTHORIZED

PreBindRetention            FREEZE
CREATION origin/replay      FREEZE
ADR-0061                    FREEZE
```

**Authorizes:** one narrow device proof that the **real host→peer `factType=11` path**
produces, transports, verifies, decrypts (production establishment / Keystore), and
puts a non-placeholder supplement — including **product auto post-bind republish** after
legitimate pre-bind drop. Requires PRE-J1…J6 same-session join before R1 adjudication.

**Does NOT authorize:** Meeting J0 · CREATION APPLIED requirement · P1-E drain adjudication ·
Meeting OPERATIONAL · pre-bind retention expansion · factType=11 retention · host resend
contract · signer fallback · P1-D/P1-E code changes · Phase A.

---

## 1. Why this gate before J0

```text
P1-D / P1-E are UNIT-PROVEN only.
Device has not yet proven real factType=11 produce + consume.

Jumping to J0 mixes:
  host origin · wire transport · Keystore unwrap · supplement put · continuation drain

→ RCA cost rises sharply on any FAIL.
```

P1-C field isolates the **package leg** (§11.1 of Path 1 design). J0 later proves:

```text
CREATION retained/pending
+ real factType=11
→ supplement ready
→ P1-E drain
→ CREATION APPLIED
```

E2 “real package wire ingest → drain” synthetic unit remains **non-blocking** for this gate.

---

## 2. Authorized causal chain (frozen stop)

```text
Host
  CREATION canonical fact exists
  → build recipient-specific MEDIA_KEY_PACKAGE
  → publish CONFERENCE_SIGNED_FACT factType=11

Peer
  → receive
  → verify
  → production establishment decrypt
  → supplementRegistry.put
```

**Success stops at:**

```text
PACKAGE_ORIGINATED
PACKAGE_RECEIVED
PACKAGE_VERIFIED
PACKAGE_DECRYPTED
SUPPLEMENT_READY
```

**Explicitly out of this gate:**

```text
INCOMPLETE_DRAIN
CREATION_APPLIED
Meeting OPERATIONAL
```

Do not fail or PASS this gate on CREATION apply / Meeting lifecycle outcomes.

---

## 3. Field exit criteria (C-F1…C-F7)

| ID | Proof |
|----|-------|
| **C-F1** | Host builds `factType=11` for an authorized recipient — `recipientModuleId` / `establishmentKeyVersion` correct |
| **C-F2** | Package binds current CREATION `membershipFactDigest` + current `mediaKeyEpoch` |
| **C-F3** | Transport delivers to peer — peer observes `factType=11` |
| **C-F4** | Peer production verify PASS — signer / authenticated fact boundary normal |
| **C-F5** | Peer uses real local establishment identity — Keystore unwrap succeeds — **no** signer fallback |
| **C-F6** | `supplementRegistry` hit — key16 / salt12 / hint8 **non-placeholder** (lengths / readiness only; never log secret bytes) |
| **C-F7** | Negative fence — wrong recipient/version or missing establishment identity → explicit reject → **registry zero mutation** |

All seven required for **PASS**. Partial package arrival alone is not PASS.

---

## 4. Required field evidence (minimum print)

Landed as **P1-C-FIELD-OBS** (behavior-neutral). Prefer these semantic tags:

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

**OBS placement (frozen):**

```text
ORIGIN     host publish attempt (EMITTED | SEND_FAILED) — OriginBridge
SUPPLEMENT after supplementRegistry.put success only — MediaFactIngress
INGEST     after put+READY on success, or on any reject path — MediaFactIngress
```

`INGEST outcome=APPLIED` and `SUPPLEMENT state=READY` are **both required** for positive
C-F4…C-F6; they do not substitute each other. Reject paths must emit `REJECTED_<reason>`
and must **not** emit matching `SUPPLEMENT_READY`.

**Forbidden to print:** key · salt · PEK · ciphertext plaintext · private key material ·
full SPKI DER · any secret derived material.

Fingerprint / digest hex and version integers only.

---

## 5. Explicit exclusions

```text
Require CREATION APPLIED on peer                     FORBIDDEN for this gate
Require INCOMPLETE_DRAIN / P1-E adjudication         FORBIDDEN for this gate
Require Meeting OPERATIONAL / J0 O0–O3               FORBIDDEN for this gate
PreBindRetention expansion / factType=11 retention   FORBIDDEN
Host resend / sender-ordering contract               FORBIDDEN
Signer fallback for unwrap                           FORBIDDEN
Change P1-D / P1-E product behavior                  FORBIDDEN
GBC validateSessionComplete relaxation               FORBIDDEN
ADR-0061 / media continuation predicate change       FREEZE
```

Thin **observability-only** lines that emit the §4 fields (no behavior change) may be
authorized as run-card preflight — not as a reopen of P1-D/P1-E scope.

---

## 6. Verdict classes

### PASS

```text
C-F1…C-F6 positive chain closed on real devices
C-F7 negative fence explicit reject + registry unchanged
Same candidate APK; production establishment path; no signer fallback
```

### INVALID

```text
Log capture / instrumentation / Keystore provider failure
No adjudicable ORIGIN / INGEST / SUPPLEMENT evidence
Topology never reached CREATION canonical fact on host
```

### FAIL

```text
Host never emits factType=11 for authorized recipient
Peer never receives / verify fails on production trust boundary
Decrypt uses signer path or DECRYPT_SEAM_UNAVAILABLE on provisioned peer
Supplement placeholder / registry miss after claimed APPLIED
C-F7 missing reject or registry mutated on negative input
```

---

## 7. After this gate

```text
P1-C package field PASS
  → P1-C package field CLOSED / FIELD PROVEN
  → Meeting J0 becomes AUTHORIZABLE (separate authorization — not auto-run)

P1-C package field FAIL
  → RCA stays inside package origin / transport / verify / unwrap / put
  → do NOT open J0
  → do NOT reopen P1-D/P1-E unit unless boundary violation proven

P1-C package field INVALID
  → fix capture / provisioning / trigger preconditions; re-run this gate only
```

---

## 8. References

- Path 1 design: `meeting-profile01-media-key-package-origin-path1-design-slice.md`
- Run card: `meeting-profile01-p1-c-media-key-package-field-run-card.md`
- P1-D: `meeting-profile01-p1-d-media-key-package-decrypt-production-wiring-authorization.md`
- P1-E: `meeting-profile01-p1-e-creation-incomplete-apply-continuation-authorization.md`
- Establishment gate: `meeting-profile01-establishment-field-gate-ep1-ep5-run-card.md`
- Origin bridge: `MeetingProfile01MediaKeyPackageOriginBridge.kt`
- Wire ingress: `MeetingProfile01FactWireIngress.kt`
- Decrypt composition: `Profile01ProductionMediaKeyDecryptComposition.kt`
