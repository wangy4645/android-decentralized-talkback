# Meeting Profile01 MEDIA_KEY_PACKAGE Origin + Incomplete-Apply Continuation — Path 1 Design Slice

**Parent RCA:** `meeting-profile01-creation-apply-completeness-rca.md`  
**Parent gap map:** `meeting-profile01-media-key-package-origin-hook-gap-map.md`  
**Status:** DESIGN **SIGNED OFF** — **P1-C IMPLEMENTED (PR-3)**

```text
Hook gap map                  COMPLETE
Path 1 design slice           SIGNED OFF (2026-09-08)

P1-A Establishment Authority  CLOSED (PR-1; A1–A4 PASS)
P1-B MEDIA_KEY_PACKAGE Builder CLOSED (PR-2; B1–B6 PASS)
P1-C Origin/Publication       CLOSED (PR-3; C1–C8 PASS)
P1-D                          CLOSED / UNIT-PROVEN
P1-E                          CLOSED / UNIT-PROVEN

Establishment Field Gate      CLOSED / FIELD PROVEN (EP Run #2)
Operator Provisioning         CLOSED

P1-C package field            CLOSED / FIELD-PROVEN
  closure: meeting-profile01-p1-c-field-closure.md
  evidence: talkback/logs/p1c-package-field-20260909-101752/
P1-C-FIELD-OBS                CLOSED / UNIT PASS
P1-C collect/adjudicate       CLOSED / DRY-RUN PASS
P1-C C-F7 injection seam      CLOSED / UNIT PASS
Meeting J0                    CLOSED / FIELD-PROVEN
  closure: meeting-profile01-j0-field-closure.md
  evidence: talkback/logs/j0-meeting-operational-20260909-103707/
Phase A Shadow Runtime Gate     AUTHORIZABLE / NEXT
  auth: meeting-product-integration-phase-a-shadow-runtime-gate-authorization.md
Phase A (full)                BLOCKED pending Shadow Runtime
ADR-0056                      KEEP
Meeting media cutover         NOT AUTHORIZED

ADR-0061                      FREEZE
Pre-bind retention            FREEZE
CREATION origin/replay        FREEZE (C8: P1-C does not touch publishedToPeers)
MEMBERSHIP                    HOLD
```

**Authoritative field failure:** `talkback/logs/meeting-product-j0-creation-e2e-20260908-132834/`  
`REJECTED_PUBLISH detail=REJECTED_INCOMPLETE` after retain+drain PROVEN.

---

## 1. Problem & closure target

### 1.1 Today (field-proven)

```text
Host: CREATION origin + per-peer replay        PASS
Peer: pre-bind CREATION retain + bind drain    PASS
Peer: CREATION verify/decode                   PASS
Peer: publish/apply                            REJECTED_INCOMPLETE (missing supplement)
Host: MEDIA_KEY_PACKAGE origin                 ORIGIN_SOURCE_GAP
Peer: mediaKeyDecrypt product wiring           GAP
Peer: incomplete CREATION replay after package   GAP
```

Primary cause (RCA): **C — REQUIRED_PREDECESSOR_MISSING**.  
Secondary amplifier: placeholder supplement + no continuation after incomplete apply.

### 1.2 v1 closure target

End-to-end peer path:

```text
Host
  authoritative media key material (existing)
  → per-recipient MEDIA_KEY_PACKAGE build + sign
  → CONFERENCE_SIGNED_FACT per peer

Peer
  MEDIA_KEY_PACKAGE decrypt → supplementRegistry.put
  CREATION apply (supplement resolved)
  → WIRE_INGRESS factType=1 outcome=APPLIED
```

**Correctness does not depend on wire arrival order.**  
Sender-side `PACKAGE before CREATION` is an **optimization only** — not a v1 contract.

---

## 2. P1-E decision (frozen)

### 2.1 Rejected strategy: sender ordering as correctness

```text
PACKAGE → CREATION   (host send order)
```

**Cannot** imply peer apply order:

| Disruptor | Effect |
|-----------|--------|
| Network reordering | CREATION may arrive first regardless of send order |
| Session bind timing | CREATION pre-bind retained; package → `PRE_BIND_NOT_RETAINED` |
| Current retention rules | factType=11 not retained pre-bind |

Making sender ordering a **hard correctness contract** would require ACK / session-ready publication protocol — **out of scope** for this slice.

### 2.2 Adopted strategy: narrow incomplete-apply continuation

```text
CREATION already verify-passed
  → publish blocked: missing media supplement
  → INCOMPLETE_PENDING (retain verified apply obligation)

MEDIA_KEY_PACKAGE decrypt
  → supplementRegistry.put
  → drain matching pending CREATION
  → existing publish/apply path
  → APPLIED or terminal reject
```

Aligns with promise-totality: `REJECTED_INCOMPLETE` for **missing prerequisite** must not permanently discard a verified obligation.

### 2.3 Two-mailbox layering (frozen — do not merge)

```text
PreBindRetention (FREEZE)
  = raw wire fact bytes
  = session does not exist yet
  = CREATION only (factType=1)
  = field proven; no semantic expansion without new authorization

IncompleteApplyContinuation (NEW — P1-E)
  = session binding exists
  = CREATION passed decode + trust verification
  = only missing media supplement
  = waits for MEDIA_KEY_PACKAGE → supplementRegistry
  = NOT raw wire retention
```

**Do not** extend pre-bind retention to factType=11.  
**Do not** store unverified bytes in incomplete continuation.

---

## 3. Layer map (P1-A → P1-E)

```text
P1-A  Recipient establishment authority
P1-B  MEDIA_KEY_PACKAGE builder
P1-C  Per-recipient origin publication
P1-D  Peer decrypt production wiring
P1-E  CREATION incomplete-apply continuation
```

Dependency order for implementation sign-off: **A → B → C → D → E**.  
Field E2E requires **all five**; partial stack does not claim J0 PASS.

---

## 4. P1-A — Recipient establishment authority

### 4.1 Hard boundary (frozen)

> **ECDSA signing key version ≠ establishment encryption key version.**

Current `TalkbackRuntimeFactory.localRecipientKeyVersion` uses **signer** key version from `AcceptedLocalTrustState`. This **must not** continue as the establishment identity.

| Identity | Purpose | Version field |
|----------|---------|---------------|
| Module signing | Fact ECDSA verify / origin sign | `signerKeyVersion` |
| Key establishment | RSA-OAEP wrap/unwrap for package PEK | `establishmentKeyVersion` (wire: `recipientKeyVersion`) |

APIs, lookup seams, and publication identity **must** model these separately even if a test harness temporarily maps both to one key material.

### 4.2 Host lookup (wrap side) — new seam

```kotlin
/** Host: resolve peer RSA establishment public key for package wrap. */
interface Profile01RecipientEstablishmentKeyLookup {
    fun activeEstablishmentKey(
        recipientModuleId: String,
    ): EstablishmentKeyRef?

    data class EstablishmentKeyRef(
        val recipientModuleId: String,
        val establishmentKeyVersion: Long,
        val establishmentPublicKeySpki: ByteArray,
    )
}
```

**Authority source (contract):** Task Profile binding per ADR-0058 Q1/Q7:

```text
moduleId + keyEstablishmentPublicKey fingerprint + establishmentKeyVersion
```

**Current code gap:** `ModuleSigningBinding` / `AcceptedLocalTrustState` carry **signing** keys only. P1-A implementation must introduce establishment binding read path (profile extension or parallel store) — **do not** overload `signerKeyVersion` as establishment version.

### 4.3 Peer lookup (unwrap side) — extend existing seam

`Profile01RecipientKeyEstablishmentSeam.unwrapPek` remains unwrap-only. Production implementation binds **local** establishment private key by `(localModuleId, establishmentKeyVersion)`.

Wire ingress must pass **`localEstablishmentKeyVersion`**, not signer version:

```kotlin
// MeetingProfile01FactWireIngress — replace semantic misuse
localEstablishmentKeyVersion: () -> Long
```

### 4.4 P1-A deliverables

| Item | Outcome |
|------|---------|
| `Profile01RecipientEstablishmentKeyLookup` | Host production impl + test double |
| `Profile01ProductionRecipientKeyEstablishmentSeam` | Peer unwrap from Keystore / trust binding |
| `localEstablishmentKeyVersion` | Separate from signer version in factory + ingress |
| Unit tests | Lookup miss → builder reject; version mismatch → decrypt reject |

---

## 5. P1-B — MEDIA_KEY_PACKAGE builder

### 5.1 Preconditions (ADR Q7 non-circular order)

Builder runs **after** host CREATION is signed and `factDigest` known:

```text
1. MembershipKeyContextV1 fixed (from CREATION authority snapshot)
2. conferenceMediaSecret + mediaKeyCommitment (Profile01ConferenceMediaKeyMaterialAuthority)
3. CREATION signed → factDigest
4. For each recipient:
     membershipFactDigest = CREATION factDigest   (creation v0)
     generate PEK (256 random bits)
     RSA-OAEP wrap PEK → wrappedPek
     AES-256-GCM encrypt PackagePlaintextV1
     canonical CBOR encode authority
     ECDSA sign (owner module signing key)
```

Reuse: `Profile01PackageCrypto` (commitment, AAD, plaintext layout, SRTP derive on decrypt side).

### 5.2 New production seam

```kotlin
interface Profile01MediaKeyPackageBuilder {
    fun buildPackage(request: BuildRequest): BuildResult

    data class BuildRequest(
        val sessionId: String,
        val conferenceId: ByteArray,
        val conferenceEpoch: Long,
        val ownerModuleId: String,
        val membershipVersion: Long,
        val mediaKeyEpoch: Long,
        val membershipFactDigest: ByteArray,      // CREATION factDigest (v0)
        val mediaKeyCommitment: ByteArray,
        val conferenceMediaSecret: ByteArray,
        val membershipKeyContextDigest: ByteArray,
        val recipient: Profile01RecipientEstablishmentKeyLookup.EstablishmentKeyRef,
    )

    sealed class BuildResult {
        data class Ready(val signedFactBytes: ByteArray, val packageIdentity: ByteArray) : BuildResult()
        data class Rejected(val reason: String) : BuildResult()
    }
}
```

**New code required:** PEK generation, RSA-OAEP wrap, AES-GCM encrypt, `Profile01WireCborEncoder.encodeMediaKeyPackageFullFact` (symmetric to CREATION encoder), sign via existing Profile01 signer pattern.

**Not in scope:** Changing commitment algorithm or package plaintext schema (frozen by ADR-0058).

### 5.3 P1-B deliverables

| Item | Outcome |
|------|---------|
| `Profile01WireCborEncoder.encodeMediaKeyPackageFullFact` | Round-trip with decoder + Q5 vectors |
| `Profile01MediaKeyPackageBuilder` | Production impl |
| Unit tests | Q5 vector parity; wrong commitment → reject; per-recipient distinct `packageIdentity` |

---

## 6. P1-C — Per-recipient origin publication

### 6.1 Do not copy CREATION `publishedToPeers` semantics

CREATION: one `signedFactBytes`, bookkeeping = "delivered to peer P".

MEDIA_KEY_PACKAGE: **distinct artifact per recipient**; bookkeeping must capture **publication identity**, not merely peer id.

### 6.2 Publication identity (frozen minimum)

```text
conferenceId
mediaKeyEpoch
recipientModuleId
recipientEstablishmentKeyVersion
membershipFactDigest
```

Optional disambiguator: `packageIdentity` (random per build).  
**Must not** treat "sent to peer P once" as sufficient across establishment version rotation or `membershipFactDigest` change.

### 6.3 New origin bridge

```kotlin
class MeetingProfile01MediaKeyPackageOriginBridge(
    private val sessionIndex: MeetingProfile01ConferenceSessionIndex,
    private val mediaKeyAuthority: Profile01ConferenceMediaKeyMaterialAuthority,
    private val creationState: () -> CreationDigestSnapshot?,  // read-only: CREATION factDigest + epochs
    private val establishmentLookup: Profile01RecipientEstablishmentKeyLookup,
    private val packageBuilder: Profile01MediaKeyPackageBuilder,
    private val onLog: (String) -> Unit = {},
) {
    fun onCreationPublished(
        sessionId: String,
        creationFactDigest: ByteArray,
        eligiblePeerModuleIds: Set<String>,
        publishToPeer: (moduleId: String, signedFactBytes: ByteArray) -> Boolean,
    ): PackageOriginOutcome
}
```

**Trigger:** After host CREATION origin caches signed fact (`MeetingProfile01CreationOriginBridge` emit) — **observe only**; do not modify CREATION bridge logic (FREEZE).

**Per-peer state:**

```kotlin
data class PackagePublicationKey(
    val conferenceIdHex: String,
    val mediaKeyEpoch: Long,
    val recipientModuleId: String,
    val recipientEstablishmentKeyVersion: Long,
    val membershipFactDigestHex: String,
)

// publishedPackages: ConcurrentHashMap<sessionId, MutableSet<PackagePublicationKey>>
```

**Send path:** Reuse `TalkbackCoordinator.sendConferenceSignedFactToPeer` — no new signal type.

### 6.4 P1-C deliverables

| Item | Outcome |
|------|---------|
| `MeetingProfile01MediaKeyPackageOriginBridge` | Build + fanout + publication set |
| Coordinator hook | Invoke after CREATION emit / eligible-peer replay (parallel to CREATION replay, independent bytes) |
| Observability | `PROFILE01_ORIGIN_EMIT factType=11 target=<peer> outcome=EMITTED|GAP` |
| Unit tests | Two peers → two distinct packages; re-emit skipped when publication key matches; new establishment version → new package allowed |

---

## 7. P1-D — Peer decrypt production wiring

**Authorization:** `meeting-profile01-p1-d-media-key-package-decrypt-production-wiring-authorization.md`  
**Prerequisite:** Establishment Field Gate **CLOSED** (EP Run #2 PASS).

### 7.1 Factory injection (frozen)

```kotlin
// TalkbackRuntimeFactory
val establishmentSeam = Profile01ProductionRecipientKeyEstablishmentSeam(...)
val mediaKeyDecrypt = Profile01MediaKeyPackageDecryptSeam(establishmentSeam)

Profile01ConferenceMediaFactIngress(
    ...
    mediaKeyDecrypt = mediaKeyDecrypt,
    supplementRegistry = profile01SupplementRegistry,
)

MeetingProfile01FactWireIngress(
    ...
    localEstablishmentKeyVersion = { localActiveEstablishmentKeyVersion(...) },
)
```

### 7.2 Decrypt → supplement path (existing — wire only)

```text
onConferenceSignedFact factType=11
  → decode → verifyMediaKeyPackage (membership converged + digest match)
  → decrypt (establishment version match)
  → supplementRegistry.put
  → trigger P1-E continuation drain (new)
```

### 7.3 P1-D deliverables

| Item | Outcome |
|------|---------|
| Production establishment unwrap | Keystore / trust-bound private key |
| `mediaKeyDecrypt` injected | No `DECRYPT_SEAM_UNAVAILABLE` on product path |
| `localEstablishmentKeyVersion` | Correct semantic |
| Unit / directed test | Q5 vector decrypt → put → registry lookup hit |

---

## 8. P1-E — CREATION incomplete-apply continuation

### 8.1 Eligibility (frozen — narrow)

Only this incomplete class enters continuation:

```text
factType = CREATION (1)
reason   = MISSING_MEDIA_SUPPLEMENT
```

**Mapping from today:** When `supplementRegistry.resolveSupplement` falls back to placeholder **and** publish returns `REJECTED_INCOMPLETE` **after** decode+verify+validate produced `ReadySession` — classify as `MISSING_MEDIA_SUPPLEMENT`.

**Explicit exclusions (terminal — no auto continuation):**

| Outcome | Action |
|---------|--------|
| `REJECTED_DECODE` / `REJECTED_VERIFY` | Terminal |
| `REJECTED_INCOMPLETE` for wrong key **sizes** (e.g. 32-byte placeholder policy violation) | Terminal unless explicitly reclassified — **do not** patch 32→16 |
| `REJECTED_STALE` / `REJECTED_SUPERSEDED` | Terminal |
| MEMBERSHIP / SOURCE / other fact types | Out of scope |

### 8.2 Pending record shape

```kotlin
/** Verified CREATION apply obligation — NOT raw wire mailbox. */
data class PendingIncompleteCreationApply(
    val sessionId: String,
    val conferenceIdHex: String,
    val mediaKeyEpoch: Long,
    val factDigestHex: String,
    val signedFactBytes: ByteArray,           // verified envelope
    val networkInterfaceName: String,
    val channelId: String,
    val retainedAtMs: Long,
)

class MeetingProfile01IncompleteApplyContinuation(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val observability: MeetingProfile01IncompleteApplyObservability,
    private val maxPendingPerConference: Int = 4,
    private val ttlMs: Long = 120_000,
) {
    fun retainPending(result: PendingIncompleteCreationApply): RetainPendingOutcome
    fun detachForSupplementReady(
        conferenceIdHex: String,
        mediaKeyEpoch: Long,
    ): List<PendingIncompleteCreationApply>
    fun discardForSession(sessionId: String, reason: DiscardReason)
    fun purgeExpired(nowMs: Long = clock())
}
```

**Key:** Store **verified** `signedFactBytes` + routing identity — not pre-trust raw retention duplicate. One pending per `(conferenceIdHex, mediaKeyEpoch, factDigestHex)` — dedupe.

### 8.3 Ingress integration

**On CREATION apply** (`MeetingProfile01FactWireIngress.ingestCreation` / ingress result):

```text
decode + verify + validate → ReadySession
supplementRegistry.resolve → still missing real material
publish → REJECTED_INCOMPLETE
  → classify MISSING_MEDIA_SUPPLEMENT
  → retainPending(...)
  → observability: WIRE_INGRESS factType=1 outcome=INCOMPLETE_PENDING
  → return INCOMPLETE_PENDING (new WireIngressOutcome)
```

**Do not** call `replayAfterPublish` on incomplete.

**On MEDIA_KEY_PACKAGE apply success:**

```text
supplementRegistry.put(material)          // existing
detachForSupplementReady(conferenceId, mediaKeyEpoch)
for each pending (consume before re-entry):
  revalidate conference/epoch/session binding fence
  re-enter ingestCreationSignedFact(signedFactBytes, ...)  // supplement now resolves
  existing publish/apply
  observability:
    PACKAGE_SUPPLEMENT_READY
    INCOMPLETE_DRAIN count=N
    WIRE_INGRESS factType=1 outcome=APPLIED | terminal reject
```

**Consume before callback:** Same discipline as pre-bind drain — `detachForSupplementReady` removes pending **before** re-ingress.

### 8.4 Session lifecycle

| Event | Action |
|-------|--------|
| `registerMeetingProfile01Session` | No change to pre-bind drain (FREEZE) |
| `onSessionUnregistered` | `discardForSession` incomplete pending |
| TTL expired | Purge + log `INCOMPLETE_EXPIRED` |

### 8.5 Pass paths (both valid — no fixed network order)

**Path A — package after CREATION attempt:**

```text
CREATION apply → INCOMPLETE_PENDING
PACKAGE decrypt → supplementRegistry.put → INCOMPLETE_DRAIN → APPLIED
```

**Path B — supplement ready before first CREATION apply:**

```text
PACKAGE decrypt → supplementRegistry.put
CREATION apply → supplement resolves → APPLIED (no pending)
```

### 8.6 P1-E deliverables

| Item | Outcome |
|------|---------|
| `MeetingProfile01IncompleteApplyContinuation` | Bounded mailbox + TTL + dedupe |
| `WireIngressOutcome.INCOMPLETE_PENDING` | Observable |
| Ingress hooks | retain on missing supplement; drain on package put |
| Unit tests | T-E1..T-E5 (see §10) |

---

## 9. Non-scope (frozen)

```text
PreBindRetention semantic expansion (factType=11)     forbidden
Sender ordering as correctness / ACK protocol        forbidden
ByteArray(32)→ByteArray(16) placeholder patch          forbidden (RCA)
MeetingProfile01CreationOriginBridge behavior change   FREEZE
ADR-0061 / media continuation                        FREEZE
MEMBERSHIP origin / Phase A membership bridge        HOLD
Coordinator timer / retry state machine              forbidden
Second registry write path (bypass verify)           forbidden
GBC validateSessionComplete relaxation               forbidden
```

---

## 10. Unit test matrix (minimum before field)

### P1-A

| ID | Scenario |
|----|----------|
| T-A1 | Host lookup returns establishment SPKI + version for peer |
| T-A2 | Signer version ≠ establishment version — decrypt uses establishment only |
| T-A3 | Unknown establishment key → builder reject |

### P1-B

| ID | Scenario |
|----|----------|
| T-B1 | Q5 package bytes round-trip encode/decode |
| T-B2 | Two recipients → distinct `wrappedPek` / `packageIdentity` |
| T-B3 | Wrong `membershipFactDigest` → verify reject |

### P1-C

| ID | Scenario |
|----|----------|
| T-C1 | Two peers → two sends, two publication keys |
| T-C2 | Same publication key → second emit skipped |
| T-C3 | establishment version bump → new publication allowed |

### P1-D

| ID | Scenario |
|----|----------|
| T-D1 | Product wiring: factType=11 → APPLIED, registry populated |
| T-D2 | Wrong establishment version → REJECTED_VERIFY/DECRYPT |

### P1-E

| ID | Scenario |
|----|----------|
| T-E1 | CREATION incomplete → INCOMPLETE_PENDING retained |
| T-E2 | Package put → INCOMPLETE_DRAIN → APPLIED |
| T-E3 | Package first → CREATION direct APPLIED (no pending) |
| T-E4 | Wrong factType incomplete → not retained |
| T-E5 | Session unregister → pending discarded |

---

## 11. Field exit criteria (upgraded)

Cannot claim PASS on `factType=11` arrival alone. Required causal chain:

### 11.1 Package leg

```text
PROFILE01_ORIGIN_EMIT factType=11 target=<peer> outcome=EMITTED
WIRE_INGRESS factType=11 outcome=APPLIED
(decrypt + supplementRegistry.put — implied by APPLIED)
```

### 11.2 CREATION leg (either path)

**Path A:**

```text
WIRE_INGRESS factType=1 outcome=INCOMPLETE_PENDING
WIRE_INGRESS PACKAGE_SUPPLEMENT_READY (or supplement put observability)
SESSION_INCOMPLETE_DRAIN count=1
WIRE_INGRESS factType=1 outcome=APPLIED
```

**Path B:**

```text
WIRE_INGRESS factType=11 outcome=APPLIED   (supplement ready first)
WIRE_INGRESS factType=1 outcome=APPLIED      (no INCOMPLETE_PENDING required)
```

### 11.3 J0 gate

```text
O0–O3                           PASS (unchanged)
same-session                    PASS
DEFERRED_NO_SESSION_RETAINED    PASS (if pre-bind CREATION)
SESSION_BIND_DRAIN              PASS (if applicable)
CREATION APPLIED                PASS (required)
J0 end-to-end                   PASS
```

---

## 12. Implementation sequencing (post sign-off)

```text
PR-1  P1-A  establishment authority seams + version split
PR-2  P1-B  package builder + encoder
PR-3  P1-C  origin bridge + coordinator hook
PR-4  P1-D  peer decrypt factory wiring
PR-5  P1-E  incomplete-apply continuation + ingress integration
PR-6  field J0 re-run + adjudication script v9
```

**Gate:** No field claim between PR-3 and PR-5 — incomplete path still required for reordering tolerance.

---

## 13. Sign-off (2026-09-08)

```text
[x] Establishment authority        ACCEPTED
[x] Per-recipient publication ID   ACCEPTED
[x] P1-E continuation model        ACCEPTED

Invariant (non-negotiable):
  SigningIdentity != EstablishmentIdentity
  Even if underlying keypair is temporarily shared in test harnesses,
  API / keyVersion / lookup authority MUST remain logically separate.

Publication identity minimum:
  conferenceId + mediaKeyEpoch + recipientModuleId
  + recipientEstablishmentKeyVersion + membershipFactDigest
  Publication success marks only this full identity — never peer-id alone.

P1-E continuation:
  CREATION + MISSING_MEDIA_SUPPLEMENT → INCOMPLETE_PENDING
  supplement ready → detach/consume → fence → existing apply → terminal outcome
  Does NOT extend PreBindRetention.

P1-E strategy: narrow incomplete-apply continuation (NOT sender ordering correctness)
PreBindRetention vs IncompleteApplyContinuation layering: ACCEPTED
MEMBERSHIP HOLD unchanged

Implementation authorization:
  PR-1 / P1-A ONLY — subsequent layers require separate authorization
```

### P1-A exit criteria (PR-1)

```text
A1  moduleId → establishment public key lookup PASS
A2  establishmentKeyVersion independent — NOT derived from signerKeyVersion
A3  unknown module / wrong version → explicit failure — NO signing-key fallback
A4  production wiring exposes authority — NO package build / wrap / wire emit
```

---

## 14. References

- Apply RCA: `meeting-profile01-creation-apply-completeness-rca.md`
- Gap map: `meeting-profile01-media-key-package-origin-hook-gap-map.md`
- Pre-bind slice (FREEZE): `meeting-profile01-pre-bind-fact-retention-drain-design-slice.md`
- ADR Q7 crypto: `docs/analysis/0058-implementation-profile-01-control-crypto.md`
- Field: `talkback/logs/meeting-product-j0-creation-e2e-20260908-132834/`
