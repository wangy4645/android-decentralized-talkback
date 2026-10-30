# MEDIA_KEY_PACKAGE Origin — Hook Inventory Gap Map (Read-Only)

## Status

```text
INVENTORY COMPLETE (read-only gap map)
Parent RCA                     meeting-profile01-creation-apply-completeness-rca.md
Primary cause (apply)          C — REQUIRED_PREDECESSOR_MISSING
Field evidence                 …-132834

Implementation code            NOT AUTHORIZED
ADR-0061                       FREEZE
Retention/drain                FREEZE
CREATION origin/replay         FREEZE
MEMBERSHIP                     HOLD
Phase A                        BLOCKED

Next                           Path 1 design slice AUTHORIZED (see path1-design-slice.md)
Implementation code            NOT AUTHORIZED until slice sign-off
```

**Purpose:** Separate **origin authority** from **delivery seam** before Path 1 design.  
Do **not** treat `MEDIA_KEY_PACKAGE origin` as “send one more fact like CREATION.”

---

## Executive distinction

```text
CREATION
  = conference-scoped canonical fact
  = one signed envelope per session
  = same bytes replayable to all eligible peers
  = per-peer publication bookkeeping tracks *delivery*, not *payload variance*

MEDIA_KEY_PACKAGE
  = recipient-scoped encrypted artifact
  = per-recipient ciphertext (recipientModuleId, wrappedPek, packageIdentity, …)
  = publication bookkeeping is per peer AND per distinct package bytes
  = host must build N packages for N recipients
```

---

## Question 1 — Host production key material authority

### Who owns PEK / mediaKeyEpoch / commitment real material?

| Material | Authority today | Location |
|----------|-----------------|----------|
| `conferenceMediaSecret` (32 B root) | **Host — SOURCE_PRESENT** | `Profile01ConferenceMediaKeyMaterialAuthority.ensureMaterial` |
| `membershipKeyContextDigest` | **Host — SOURCE_PRESENT** | computed in same authority via `Profile01MembershipKeyContextDigest.computeForCreation` |
| `mediaKeyCommitment` | **Host — SOURCE_PRESENT** | `Profile01PackageCrypto.computeMediaKeyCommitment` |
| `mediaKeyEpoch` | **Host — hardcoded v0** | `MeetingProfile01CreationOriginBridge` uses `mediaKeyEpoch = 1L` at CREATION origin |
| **PEK** (256-bit per package) | **NOT FOUND (production)** | ADR Q7 step 7: random per package; no host generator/wrap seam |
| SRTP `masterKey` / `masterSalt` | **Derived on decrypt** | `Profile01PackageCrypto.deriveSrtpMaterial` after unwrap + plaintext parse |

Host material authority is **partially closed** for CREATION commitment chain:

```21:54:talkback/android-board-talkback/src/main/java/com/talkback/core/conference/session/profile01/wire/Profile01ConferenceMediaKeyMaterialAuthority.kt
    fun ensureMaterial(...): Material =
        bySessionId.getOrPut(sessionId) {
            val secret = ByteArray(Profile01WireConstants.CONFERENCE_MEDIA_SECRET_BYTES).also {
                SecureRandom().nextBytes(it)
            }
            val contextDigest = Profile01MembershipKeyContextDigest.computeForCreation(...)
            val commitment = Profile01PackageCrypto.computeMediaKeyCommitment(...)
            Material(...)
        }
```

Wired into CREATION origin at first topology publish (`MeetingProfile01CreationOriginBridge.createAndCacheCreationFact`).

### Does `Profile01SessionMediaSupplement` source exist on host?

| Component | Status |
|-----------|--------|
| `Profile01SessionMediaSupplement` type | **EXISTS** — documented as decrypt output |
| `Profile01SessionMediaSupplementRegistry` | **EXISTS** — `(conferenceId, mediaKeyEpoch)` index |
| Host populate via decrypt | **NOT WIRED** — host is origin, not recipient |
| Host populate via local derive | **NOT IMPLEMENTED** — no bridge from `Material` → per-recipient package plaintext |

`Profile01PackageCrypto` provides **verify/derive only** (commitment, AAD, plaintext parse, SRTP derive). **No encrypt/wrap/encode** functions.

### Gap verdict (Q1)

```text
CLOSED   conferenceMediaSecret + commitment authority (per session, via CREATION bridge)
GAP      PEK generation + AES-GCM package encryption + wire encode/sign
GAP      host-side “material ready” observer hook (onMediaKeyMaterialReady — not defined)
```

---

## Question 2 — Recipient encryption authority

### Where does peer public key / recipient identity come from?

**Contract (ADR-0058 Q7):**

```text
recipientModuleId + recipientKeyVersion
  → exact Task Profile RSA establishment fingerprint
```

Per-recipient packages bind to target module **key-establishment** public key (not ECDSA signing key).

**Decrypt side — SOURCE_PRESENT (harness):**

- `Profile01MediaKeyPackageDecryptSeam` — validates `recipientModuleId` + `recipientKeyVersion`, then `unwrapPek`
- `Profile01RecipientKeyEstablishmentSeam.unwrapPek` — RSA-OAEP unwrap interface
- Production comment: “binds Task Profile establishment keys”

**Production decrypt wiring — GAP:**

`TalkbackRuntimeFactory` builds ingress **without** `mediaKeyDecrypt`:

```331:341:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackRuntimeFactory.kt
        val profile01ConferenceMediaFactIngress =
            Profile01ConferenceMediaFactIngress(
                validator = ...,
                publisherBridge = conferenceSessionMediaGbcPublisherBridge,
                supplementRegistry = profile01SupplementRegistry,
            )
```

Even if package bytes arrived on wire today → `DECRYPT_SEAM_UNAVAILABLE`.

### RSA-OAEP package builder production seam?

| Side | Status | Evidence |
|------|--------|----------|
| Unwrap (decrypt) | Interface + Q5 test impl | `Profile01RecipientKeyEstablishmentSeam`, `Profile01Q5TestRecipientKeyEstablishmentSeam` |
| Wrap (encrypt) | **NOT FOUND** | No `wrapPek` / production lookup |
| Recipient pubkey lookup | **NOT FOUND** | No Task Profile establishment SPKI resolver for remote peers |
| Wire encode | **NOT FOUND** | `Profile01WireCborEncoder` — **CREATION only** (`encodeCreationFullFact`) |

Q5 test seam maps `"M02" to 4L` → test RSA private key only (`Profile01Q5DirectedFixtures.kt`).

### `localRecipientKeyVersion` semantic risk

Product wire ingress passes **ECDSA signer key version** as recipient key version:

```355:357:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackRuntimeFactory.kt
                localRecipientKeyVersion = {
                    localActiveSignerKeyVersion(profile01TrustStore, config.localModuleId.value)
                },
```

ADR contract binds `recipientKeyVersion` to **establishment** key version. Path 1 design must resolve this — not assume signer version ≡ establishment version.

### Gap verdict (Q2)

```text
CLOSED   decrypt seam + validator verify path (harness/Q5)
GAP      production establishment-key lookup (per peer moduleId + keyVersion)
GAP      RSA-OAEP wrap / PEK encryption production seam
GAP      product TalkbackRuntimeFactory mediaKeyDecrypt wiring
GAP      recipientKeyVersion semantics alignment (signer vs establishment)
```

---

## Question 3 — MEDIA_KEY_PACKAGE publication timing

### Can solo host CREATE generate package?

**CREATION origin:** requires `members.isNotEmpty()`; filters self from fanout. Solo with only host on roster → `EMPTY_ROSTER` / no wire send.

**MEDIA_KEY_PACKAGE:** contract requires **one package per recipient** (ADR Q7 step 7). No recipient → no package object. Solo-first CREATE can **cache locally** but cannot meaningfully deliver packages until remote peers exist on roster.

### Must it be per-peer after roster join?

**Yes — contract + wire shape.**

ADR non-circular origin order:

```text
1. fix MembershipKeyContextV1
2. compute membershipKeyContextDigest
3. generate conferenceMediaSecret
4. compute mediaKeyCommitment
5. insert commitment into Creation authority
6. compute final authority factDigest          ← CREATION must be signed first
7. construct one independently encrypted Package per recipient
8. originate Fact; transport may occur before or after Creation on wire
```

Wire authority is per-recipient (`Profile01WireMediaKeyPackage`: `recipientModuleId`, `packageIdentity`, `wrappedPek`, `recipientKeyVersion`).

Host CREATION bridge tracks **per-peer delivery of same envelope** (`CreationPublicationState.publishedToPeers`). **No equivalent** for MEDIA_KEY_PACKAGE.

### Does it require `membershipFactDigest`?

**Yes — validator hard gate:**

```217:226:talkback/android-board-talkback/src/main/java/com/talkback/core/conference/session/profile01/Profile01ConferenceMediaFactValidator.kt
        val generation = membershipConvergence.current(...) ?: return Rejected("MEMBERSHIP_NOT_CONVERGED")
        if (... !wire.membershipFactDigest.contentEquals(generation.generationFactDigest)) {
            return Rejected("MEMBERSHIP_CONTEXT_MISMATCH")
        }
```

Creation v0: `membershipFactDigest` = **CREATION `factDigest`** (ADR-0058).

Implications:

| Constraint | Meaning |
|------------|---------|
| Package builder needs signed CREATION digest | Host must complete CREATION sign **before** building packages |
| Wire order flexible | Package may arrive before or after CREATION on wire (ADR step 8) |
| Apply order strict | Peer CREATION publish needs supplement in registry **at apply instant** |
| Membership convergence on peer | CREATION `seedFromCreation` can establish head; package verify needs that head |

### Ordering vs CREATION replay

| Layer | CREATION | MEDIA_KEY_PACKAGE |
|-------|----------|-------------------|
| Host cache | One `signedFactBytes` per session | **None** — must build per recipient |
| Host fanout | `publishToEligiblePeers` — same bytes | **Missing** — needs per-peer distinct payload + bookkeeping |
| Host replay trigger | `replayProfile01CreationToEligiblePeers` on invite/topology | **None** |
| Peer pre-bind retention | CREATION only | **Not retained** (`PRE_BIND_NOT_RETAINED`) |
| Peer apply dependency | Needs supplement at publish | Populates supplement via decrypt |

**Harness reference order** (`RealMeshE2ERunner`, `MeetingProfile01FactWireIngressTest`):

```text
decrypt package → supplementRegistry.put → ingestCreation → APPLIED
```

This is **inject-first** harness order, not Meeting product origin wire order.

### Gap verdict (Q3)

```text
CONFIRMED  per-recipient artifact; cannot replay one envelope to all peers
CONFIRMED  membershipFactDigest = CREATION factDigest (creation v0)
CONFIRMED  host must sign CREATION before package build
OPEN       product publication ordering: package-to-peer before peer CREATION apply
OPEN       late-join: new peer needs new package + CREATION replay (both per-peer)
```

---

## Question 4 — Existing send path reusability

### Is `CONFERENCE_SIGNED_FACT` transport sufficient?

**Yes — SOURCE_PRESENT.**

```13152:13170:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
    private fun sendConferenceSignedFactToPeer(...): Boolean {
        ...
        sendSignalHandoff(peer, buildSignedEnvelope(SignalType.CONFERENCE_SIGNED_FACT, ..., payload))
        return true
    }
```

Receive: `TalkbackCoordinator` → `MeetingProfile01FactWireIngress.onConferenceSignedFact` → factType dispatch (1/9/10/11).

Negotiation gate allows `CONFERENCE_SIGNED_FACT`.

### What is missing?

| Layer | CREATION | MEDIA_KEY_PACKAGE |
|-------|----------|-------------------|
| Transport | **REUSE** | **REUSE** |
| Encode + sign | **CLOSED** (`Profile01WireCborEncoder` + creation publisher) | **GAP** — no encoder |
| Origin bridge | **CLOSED** (`MeetingProfile01CreationOriginBridge`) | **GAP** — no bridge |
| Per-peer publication state | **CLOSED** (`publishedToPeers`) | **GAP** |
| Peer decrypt wiring | N/A (host origin) | **GAP** (`mediaKeyDecrypt` not injected) |
| Trigger hook | `onTopologyPublished` | **GAP** (`onMediaKeyMaterialReady` undefined) |

**Verdict:** Transport is enough. Path 1 is **not** a transport PR — it is **authority + builder + per-recipient bookkeeping + peer decrypt wiring + ordering**.

---

## Extra — `REJECTED_INCOMPLETE` replay reachability

> After bind drain rejects CREATION, is there any reachable replay source when supplement arrives later?

### Peer-side state after field failure (…-132834)

```text
pre-bind: CREATION retained
bind drain: detach + re-ingress → REJECTED_INCOMPLETE (consumed)
retention mailbox: empty (detached, not re-queued)
supplementRegistry: empty at apply time
```

`MeetingProfile01PreBindFactRetention` — retain **pre-bind CREATION only**; drain **consumes** batch; rejected facts are **not** retained.

`Profile01ConferenceMediaFactIngress.ingestMediaKeyPackageSignedFact` — `registry.put` only; **no callback** to re-apply pending CREATION.

### Host-side re-delivery

`CreationPublicationState.publishedToPeers` — once peer is marked published, `publishToEligiblePeers` **skips** re-send:

```357:357:talkback/android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/MeetingProfile01CreationOriginBridge.kt
                if (!publishedToPeers.add(peerModuleId)) return@forEach
```

Field sequence: host already delivered CREATION (pre-bind retain proves wire receipt). Host will **not** auto-replay CREATION on package readiness.

`replayProfile01CreationToEligiblePeers` only fires on explicit coordinator events (invite sent, membership invite) — not on peer `REJECTED_INCOMPLETE`.

### Replay source matrix

| Source | Available after REJECTED_INCOMPLETE? |
|--------|-------------------------------------|
| Pre-bind retention mailbox | **NO** — consumed at drain |
| Peer local failed-apply queue | **NO** — does not exist |
| Supplement-ready re-apply hook | **NO** — `registry.put` only |
| Host CREATION wire re-send | **Only if** peer ∉ `publishedToPeers` or send failed (removed from set) |
| Host CREATION wire re-send (field case) | **NO** — peer already in `publishedToPeers` |
| New wire CREATION from peer | N/A |

### Verdict

```text
REJECTED_INCOMPLETE replay on peer     NOT REACHABLE (field path)
Package-later without CREATION re-apply  WILL NOT CLOSE E2E alone
```

Path 1 closure (design slice AUTHORIZED): **P1-E incomplete-apply continuation** — not sender ordering as correctness.

```text
1. P1-A..D  package origin + peer decrypt
2. P1-E     verified CREATION INCOMPLETE_PENDING → supplement ready → INCOMPLETE_DRAIN → APPLIED
```

Sender `PACKAGE → CREATION` order is **optimization only**. See `meeting-profile01-media-key-package-origin-path1-design-slice.md`.

---

## Gap map summary

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ CLOSED (reuse)                                                          │
├─────────────────────────────────────────────────────────────────────────┤
│ Profile01ConferenceMediaKeyMaterialAuthority (secret + commitment)      │
│ MeetingProfile01CreationOriginBridge (CREATION sign/cache/fanout)       │
│ CONFERENCE_SIGNED_FACT send/receive                                      │
│ Profile01MediaKeyPackageDecryptSeam + Profile01PackageCrypto (decrypt)  │
│ Profile01SessionMediaSupplementRegistry                                 │
│ Pre-bind CREATION retention + bind drain                                │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│ GAP (Path 1 scope candidates)                                           │
├─────────────────────────────────────────────────────────────────────────┤
│ G1  Production establishment-key lookup (per peer)                      │
│ G2  RSA-OAEP wrap + PEK + AES-GCM package builder                       │
│ G3  MEDIA_KEY_PACKAGE wire encode + sign                                │
│ G4  MeetingProfile01MediaKeyPackageOriginBridge (per-recipient state)   │
│ G5  Product mediaKeyDecrypt wiring (TalkbackRuntimeFactory)           │
│ G6  P1-E incomplete-apply continuation (not sender ordering)            │
│ G7  establishmentKeyVersion ≠ signerKeyVersion (P1-A)                  │
│ G8  P1-E: MISSING_MEDIA_SUPPLEMENT-only pending drain                 │
└─────────────────────────────────────────────────────────────────────────┘
```

---

Path 1 — Can it close alone?

**Yes, with P1-E incomplete-apply continuation** (design slice AUTHORIZED). Sender ordering is optimization only.

Design: `meeting-profile01-media-key-package-origin-path1-design-slice.md`

```text
authoritative media key material (existing authority)
  → recipient establishment key lookup (G1)
  → build recipient-specific MEDIA_KEY_PACKAGE (G2, G3)
  → sign + per-peer send CONFERENCE_SIGNED_FACT (G4, transport reuse)
  → peer decrypt + supplementRegistry.put (G5)
  → CREATION apply with resolved supplement
```

**P1-E (frozen):** narrow incomplete-apply continuation — **not** sender ordering as correctness. See `meeting-profile01-media-key-package-origin-path1-design-slice.md`.

---

## References

- Apply RCA: `meeting-profile01-creation-apply-completeness-rca.md`
- Parent hook inventory: `meeting-product-integration-phase-a-origin-publisher-hook-inventory.md`
- ADR Q7 origin order: `docs/analysis/0058-implementation-profile-01-control-crypto.md` (steps 1–8)
- Field: `talkback/logs/meeting-product-j0-creation-e2e-20260908-132834/`
