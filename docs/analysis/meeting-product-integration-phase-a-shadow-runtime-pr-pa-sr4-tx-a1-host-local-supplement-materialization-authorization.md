# Phase A Shadow Runtime — PR-PA-SR4-TX-A1 Design + Implementation Authorization

**PR:** `PR-PA-SR4-TX-A1` — Host Local Media Supplement Materialization  
**Parent:** `PR-PA-SR4-TX` (TX-A dual-entry — **UNIT-PROVEN**)  
**RCA:** `meeting-product-integration-phase-a-sr4-tx-rca-host-projection-20260909-164833.md` — **CLOSED**  
**Field evidence:** `talkback/logs/phase-a-sr4-tx-field-20260909-164833/` — `INVALID_PREREQUISITE`  
**Date:** 2026-09-09  
**Status:** **CLOSED / UNIT-PROVEN** (TX-A1 Kotlin delivered)

```text
TX-A RCA         CLOSED
Root cause       HOST_LOCAL_SUPPLEMENT_MATERIALIZATION_MISSING

TX-A1            CLOSED / UNIT-PROVEN
PR-PA-SR4-TX     UNIT-PROVEN
TX field         AUTHORIZED FOR RE-RUN
TX-B/TX-3/TX-4   UNCHANGED
PA-SR4           VALID FAIL
RX               NOT AUTHORIZED
PLAY             HOLD
ADR-0056         KEEP
```

**Authorizes:** host authoritative media key material → local `Profile01SessionMediaSupplementRegistry` materialization → reuse existing supplement-ready continuation → host HLSP re-evaluates.

**Does NOT authorize:** HLSP bypass of supplement registry · host self-decrypt of own `MEDIA_KEY_PACKAGE` · TX-B/TX-3/TX-4 changes · RX/PLAY · new key distribution · field harness redesign · PA-SR4 full gate claims.

---

## 1. Problem statement (from RCA — frozen)

Field run `20260909-164833` proved TX-B stimulus path (`captureON=True`, `SHADOW_TX_FENCED reason=NO_SESSION_FACT`) while host shadow session never became live:

```text
HOST_SESSION_PROJECTED outcome=DEFERRED_NO_SUPPLEMENT   (M01, entry #1 only)
→ supplement READY on peer wire path only                (M02)
→ onMeetingProfile01MediaSupplementReady never on host   (M01)
→ SESSION_STARTED remains DEFERRED_NO_FACT
→ G1 NO_SESSION_FACT → INVALID_PREREQUISITE (not FAIL_PA_SR4_TX)
```

RCA closed: **no further TX-A lookup/session-path investigation**. Root cause is host missing local supplement materialization into the shared registry.

---

## 2. Selected fix direction (frozen — one path only)

### AUTHORIZED

```text
Host authoritative media material (Profile01ConferenceMediaKeyMaterialAuthority)
  → construct Profile01DerivedMediaKeyMaterial (same semantic as peer decrypt)
  → Profile01SessionMediaSupplementRegistry.put
  → existing onMediaSupplementReady continuation
  → TalkbackCoordinator.onMeetingProfile01MediaSupplementReady
  → Profile01HostLocalSessionFactProjection.maybeProjectHostShadowSession
  → APPLIED / ALREADY_LIVE
```

### REJECTED

```text
Profile01HostLocalSessionFactProjection reads Profile01ConferenceMediaKeyMaterialAuthority
directly when supplementRegistry.lookup misses (host-only bypass).
```

**Rationale:** Peer and host must converge on the same downstream fact:

```text
Profile01SessionMediaSupplementRegistry contains executable supplement
```

Sources differ; downstream contract does not:

```text
Peer:
  MEDIA_KEY_PACKAGE
    → verify/decrypt (Profile01MediaKeyPackageDecryptSeam)
    → supplementRegistry.put
    → onMediaSupplementReady
    → HLSP / peer CREATION continuation

Host:
  authoritative local media key material (Profile01ConferenceMediaKeyMaterialAuthority)
    → local materialization (TX-A1)
    → supplementRegistry.put
    → onMediaSupplementReady          # same callback chain
    → HLSP re-evaluates               # unchanged projection logic
```

`Profile01HostLocalSessionFactProjection` must **not** know whether supplement came from local materialization or remote decrypt.

---

## 3. Authorized chain (narrow)

```text
ensureMaterial(...)  [already at CREATION origin — MeetingProfile01CreationOriginBridge]
  → Profile01ConferenceMediaKeyMaterialAuthority.Material present for sessionId

TX-A1 materialization seam (new — name illustrative):
  → read session binding + CREATION bytes for identity fence (A1-HC3)
  → derive Profile01DerivedMediaKeyMaterial via shared crypto:
       Profile01PackageCrypto.deriveSrtpMaterial(secret, contextDigest)
       Profile01PackageCrypto.deriveKeyContextHint64(conferenceId, epoch, mediaKeyEpoch, contextDigest)
  → supplementRegistry.put(material)
  → log PROFILE01_MEDIA_SUPPLEMENT state=READY   # same observability as peer ingress
  → onMediaSupplementReady(conferenceId, mediaKeyEpoch)   # reuse Profile01ConferenceMediaFactIngress hook
```

**Existing code (reuse — do not fork crypto semantics):**

| Component | Role |
|-----------|------|
| `Profile01ConferenceMediaKeyMaterialAuthority` | **Only** authorized source of `conferenceMediaSecret` + `membershipKeyContextDigest` + `mediaKeyCommitment` on host |
| `Profile01PackageCrypto.deriveSrtpMaterial` | Same SRTP master key/salt as decrypt path |
| `Profile01PackageCrypto.deriveKeyContextHint64` | Same hint as decrypt path |
| `Profile01DerivedMediaKeyMaterial` | Registry value type — identical to peer decrypt output |
| `Profile01SessionMediaSupplementRegistry.put` | Shared downstream fact store |
| `Profile01ConferenceMediaFactIngress.onMediaSupplementReady` | Existing continuation — **do not duplicate coordinator wiring** |
| `Profile01HostLocalSessionFactProjection` | **Unchanged** lookup + ingest path |

**Explicit non-goal:**

```text
NOT: host ingests/decrypts its own MEDIA_KEY_PACKAGE wire fact
NOT: new signaling fact type or host-to-self key package emit
NOT: key distribution mechanism expansion
```

TX-A1 is **local authority material → local executable projection**, not wire loopback.

---

## 4. Hard constraints (non-negotiable)

### A1-HC1 — Authority-only source

```text
Materialization reads ONLY from Profile01ConferenceMediaKeyMaterialAuthority
for the active host session.

FORBIDDEN:
  - signaling / wire ingest of MEDIA_KEY_PACKAGE to self
  - decrypting a host-emitted package addressed to local module
  - placeholder or synthetic supplement not derived from authority Material
```

### A1-HC2 — Semantic equivalence

```text
Profile01DerivedMediaKeyMaterial written to registry MUST be byte-semantically
equivalent to what Profile01MediaKeyPackageDecryptSeam would produce for a
valid package built from the same authority Material + matching CREATION context.

Reuse Profile01PackageCrypto derivation helpers — no "host simplified supplement".
Field validation: masterKey(16B) / masterSalt(12B) / keyContextHint64(8B) /
membershipKeyContextDigest match peer decrypt corpus for same conference context.
```

### A1-HC3 — Identity fence

```text
Before put + READY callback, verify ALL of:
  - conferenceId (hex) matches sessionIndex binding for sessionId
  - mediaKeyEpoch matches CREATION authority field in cached signed CREATION bytes
  - membershipKeyContextDigest + mediaKeyCommitment match authority Material
    for that sessionId (commitment check against CREATION wire if applicable)

Stale or mismatched material → REJECT (no registry put, no READY callback).
```

### A1-HC4 — Idempotent continuation

```text
Repeated materialization for same (conferenceId, mediaKeyEpoch):
  - registry put is idempotent (same bytes)
  - supplement-ready callback may fire again
  - HLSP second call → ALREADY_LIVE (shadow scope not duplicated)
  - no duplicate WIRING_SESSION_STARTED / transport scope
```

---

## 5. Trigger timing (design)

Preferred: materialize **synchronously after** `ensureMaterial` succeeds at CREATION origin publish, **before or alongside** first `onMeetingProfile01HostCreationAuthoritativeCommit` → HLSP entry #1.

```text
ensureMaterial → TX-A1 materialize+put+READY → creation commit hook → HLSP entry #1
  → supplementRegistry.lookup HIT → APPLIED (or ALREADY_LIVE on retry)
```

If materialization is deferred (authority not yet allocated), entry #1 may still `DEFERRED_NO_SUPPLEMENT`; entry #2 via supplement-ready **must** fire on host once materialization completes — same as peer path contract.

**Do not** reorder CREATION origin publication semantics or B1-R2 media key package emit logic.

---

## 6. Authorized implementation scope

### AUTHORIZED

```text
TX-A1  Host local supplement materialization seam
       - authority Material → Profile01DerivedMediaKeyMaterial
       - supplementRegistry.put + onMediaSupplementReady (reuse ingress hook)
       - observability: PROFILE01_MEDIA_SUPPLEMENT state=READY on host

Wiring - TalkbackRuntimeFactory / CreationOriginBridge hook only as needed
       - no HLSP logic change unless required for A1-HC4 idempotency tests

Unit   A1-U1 … A1-U6 (below) + TX-U1 … TX-U8 regression
```

### NOT AUTHORIZED

```text
- Profile01HostLocalSessionFactProjection supplement source bypass
- Profile01ShadowMulticastTransmitSeam / six-gate egress changes
- Profile01ConferenceMediaFactIngress peer decrypt path changes (P1-D frozen)
- MeetingProfile01MediaKeyPackageOriginBridge peer emit semantics
- RX / PLAY / PA-SR4 full gate / ADR-0056
- TX field harness or adjudicator contract changes
- Treating INVALID_PREREQUISITE field run as TX FAIL
```

---

## 7. Unit exit criteria (minimum before field rerun)

| ID | Scenario | Required outcome |
|----|----------|------------------|
| **A1-U1** | Host `ensureMaterial` + TX-A1 materialize → `supplementRegistry.lookup` hit → HLSP `APPLIED` | PASS |
| **A1-U2** | CREATION commit before materialization → `DEFERRED_NO_SUPPLEMENT`; after materialize+READY → `APPLIED` | PASS |
| **A1-U3** | `mediaKeyEpoch` or `conferenceId` mismatch vs CREATION/binding → reject; registry unchanged | PASS |
| **A1-U4** | Duplicate materialize + duplicate READY callback → HLSP `ALREADY_LIVE`; single shadow session | PASS |
| **A1-U5** | Peer `ingestMediaKeyPackageSignedFact` path unchanged (existing P1-D / ingress tests green) | PASS |
| **A1-U6** | `Profile01ShadowSr4TxTest` TX-U1 … TX-U8 regression | PASS |

**Suggested proof vector (A1-HC2):** round-trip — host materialize from authority → registry; peer decrypt of package built from same authority → **identical** `Profile01DerivedMediaKeyMaterial` fields (reuse `Profile01MediaKeyPackageBuilderTest` / Q5 corpus pattern).

---

## 8. Observability (host — expected after A1)

```text
PROFILE01_MEDIA_SUPPLEMENT conferenceId=<id> mediaKeyEpoch=<e> state=READY   # M01 host
PROFILE01_SHADOW_RUNTIME phase=HOST_SESSION_PROJECTED outcome=APPLIED
PROFILE01_SHADOW_RUNTIME phase=WIRING_SESSION_STARTED transportScopeActive=true
SHADOW hook=SESSION_STARTED outcome=APPLIED
```

**Pre-A1 (field baseline — do not regress interpretation):**

```text
HOST_SESSION_PROJECTED outcome=DEFERRED_NO_SUPPLEMENT
SHADOW hook=SESSION_STARTED outcome=DEFERRED_NO_FACT
SHADOW_TX_FENCED reason=NO_SESSION_FACT
→ INVALID_PREREQUISITE
```

---

## 9. Field gate (after unit closure — no harness redesign)

Reuse existing harness unchanged:

```text
talkback/scripts/phase-a-sr4-tx-field-first-run.ps1
talkback/scripts/phase-a-sr4-tx-field-adjudicate.ps1
```

**Run card:** `meeting-product-integration-phase-a-sr4-tx-field-run-card.md` (frozen PASS chain).

**Expected progression after A1 unit closure:**

```text
1. Host supplement READY + HOST_SESSION_PROJECTED APPLIED + WIRING_SESSION_STARTED
2. Only then adjudicate SHADOW_TX_ACTIVITY packetsSent > 0
```

A1 field run **does not** close PA-SR4 or authorize RX/PLAY.

---

## 10. Implementation queue (single PR)

```text
PR-PA-SR4-TX-A1
  1. Materialization seam (authority → derived material)
  2. Wire into CREATION origin path (post ensureMaterial)
  3. Reuse ingress onMediaSupplementReady — no parallel coordinator hook
  4. Unit A1-U1..U6 + TX-U1..U8 regression
  5. Unit closure doc update
  6. Field rerun (separate step — authorized after unit closure only)
```

---

## 11. Status board (frozen at authorization)

```text
TX-A RCA         CLOSED
Root cause       HOST_LOCAL_SUPPLEMENT_MATERIALIZATION_MISSING

TX-A1            DESIGN FROZEN / IMPLEMENTATION AUTHORIZED
PR-PA-SR4-TX     UNIT-PROVEN / FIELD NOT YET VALIDLY TESTED
TX-B/TX-3/TX-4   UNCHANGED
PA-SR4           VALID FAIL
RX               NOT AUTHORIZED
PLAY             HOLD
```

**Next after implementation:** A1 unit closure → re-run TX field gate (same harness).

---

## 12. References

- RCA: `meeting-product-integration-phase-a-sr4-tx-rca-host-projection-20260909-164833.md`
- TX design: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-tx-design.md`
- TX authorization: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-tx-implementation-authorization.md`
- TX unit closure: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-tx-unit-closure.md`
- TX field run card: `meeting-product-integration-phase-a-sr4-tx-field-run-card.md`
- Peer decrypt (frozen): `meeting-profile01-p1-d-media-key-package-decrypt-production-wiring-authorization.md`
- Code: `Profile01ConferenceMediaKeyMaterialAuthority.kt`, `MeetingProfile01CreationOriginBridge.kt`, `Profile01ConferenceMediaFactIngress.kt`, `Profile01HostLocalSessionFactProjection.kt`, `TalkbackCoordinator.kt`
