# Meeting Profile01 Fact Origin Publisher — Authoritative Transition Hook Inventory

## Status

```text
INVENTORY COMPLETE
MEDIA_KEY_PACKAGE gap map       meeting-profile01-media-key-package-origin-hook-gap-map.md
Path 1 design slice             meeting-profile01-media-key-package-origin-path1-design-slice.md
NEXT                            slice sign-off → layered implementation (NOT AUTHORIZED)
Principle: Publisher reads authority only — does not create authority
```

Companion: `meeting-product-integration-phase-a-origin-publisher-run-card.md`

---

## Executive summary

| Fact | Authoritative read hook | Committed signal | Publisher hook verdict | Implementation verdict |
|------|-------------------------|------------------|----------------------|------------------------|
| **CREATION** | `ConferenceTopologyAuthority` publish after admission | `CONFERENCE_TOPOLOGY_PUBLISHED` | **SOURCE_PRESENT** (topology) | **ORIGIN_SOURCE_GAP** (id128 + payload + encoder) |
| **MEDIA_KEY_PACKAGE** | — (recipient decrypt only) | — | **ORIGIN_SOURCE_GAP** | **ORIGIN_SOURCE_GAP** |
| **MEMBERSHIP** | `GroupMembershipSupport.applyMembershipSnapshot` + authority gates | `MEMBERSHIP_EPOCH_TRANSITION` / `snapshotApplied` | **SOURCE_PRESENT** (roster) | **PARTIAL** (needs P01 field bridge + CREATION head) |
| **SOURCE_DECLARATION** | — (ICE ≠ binding) | `ICE_CONNECTED` (transport only) | **ORIGIN_SOURCE_GAP** | **ORIGIN_SOURCE_GAP** |

**Thin Publisher v1 cannot close all four facts without upstream source work.**  
Implement in dependency order; mark gaps explicitly — **no in-publisher state machine.**

```text
BLOCKED until source exists:
  MEDIA_KEY_PACKAGE     → host encrypt/wrap seam + key material authority
  SOURCE_DECLARATION    → host SSRC / sourceInstanceId / sourceGeneration authority

BLOCKED until bridge exists:
  CREATION              → id128 conferenceId allocation + session wire payload authority
  MEMBERSHIP            → roster authority → Profile01 membershipVersion / digest chain
```

---

## Shared infrastructure (reuse vs gap)

### Receive path — SOURCE_PRESENT

```text
TalkbackCoordinator (CONFERENCE_SIGNED_FACT receive)
  → MeetingProfile01FactWireIngress
  → Profile01ConferenceMediaFactIngress
  → ConferenceSessionMediaGbcPublisherBridge
  → MeetingProfile01AwareConferenceSessionMediaFactPort
  → shadow delegate
```

### Send path — SOURCE_PRESENT (CREATION origin)

`TalkbackCoordinator.sendConferenceSignedFactToPeer` + `replayProfile01CreationToEligiblePeers` — wired for CREATION fanout/replay. Origin Discovery (pre-CREATION-bridge) showed zero facts; J0 field (…-132834) confirms CREATION wire delivery.

### Encode / sign — ORIGIN_SOURCE_GAP (production)

| Component | Status | Location |
|-----------|--------|----------|
| CBOR canonical encode | reuse | `Profile01CborCodec.encode`, `Profile01FactDigest` |
| Signed envelope parse | reuse (inverse needed) | `Profile01SignedFactEnvelope.parse` |
| Production verify | reuse | `Profile01ProductionSignedFactTrustBoundary` |
| Production sign | **missing** | Pattern reference: `PersistedGenerationFactIssuanceSigner` (GBC domain — different `SIGNATURE_DOMAIN`) |
| Golden / directed bytes | fixture only | `Profile01DirectedWireFixtures` — **not production origin** |

### Session index — SOURCE_PRESENT (partial)

`MeetingProfile01ConferenceSessionIndex` maps `sessionId` (UUID) ↔ `conferenceId` (id128 hex).

- Registered at host create: `TalkbackCoordinator.registerMeetingProfile01Session` (L513–519)
- `conferenceId` bound today only on **ingress** (`MeetingProfile01FactWireIngress` after CREATION/MEMBERSHIP decode)
- **Gap:** host has no authoritative id128 allocator before first emit

---

## 1. CREATION — conference established

### Intended trigger (run card)

```text
authoritative conference established → CREATION
```

### Authoritative source — SOURCE_PRESENT (topology)

**Primary hook:** `TalkbackCoordinator.applyConferenceTopologyAdmission` → `ConferenceTopologyAuthority` publish

```4837:4951:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
    private fun applyConferenceTopologyAdmission(session: TalkbackSession, reason: String) {
        // ... AnchorAdmissionPolicy.decide → publishModeTransition / publishRosterEdgeChange / publishAnchorFailover
        logTopologyPublishResult(session, publishResult, reason, transition)
    }

    private fun logTopologyPublishResult(...) {
        when (result) {
            is ConferenceTopologyAuthority.PublishResult.Published -> {
                log("${sessionTag(session)} CONFERENCE_TOPOLOGY_PUBLISHED reason=$reason ...")
```

**Authority store:** `ConferenceTopologyAuthority` — single publish owner for `ConferenceTopologySnapshot` (`ConferenceTopologyAuthority.kt` L6–38).

**Call sites (non-exhaustive):** `invite_accept`, membership snapshot apply, `apply_group_topology`, roster epoch bump paths (`TalkbackCoordinator.kt` L9558, L13331, L14007).

### Committed vs tentative

| State | Authoritative? | Evidence |
|-------|----------------|----------|
| `ConferenceTopologyAuthority.PublishResult.Published` | **YES** | `CONFERENCE_TOPOLOGY_PUBLISHED` |
| `session.accepted == true` after anchor/mesh admission | **YES** (gate) | admission policy |
| `meshCallInternal` + `SESSION_CREATED` + `creationSource=USER_CREATE` | **NO** | L3869–3932 — tentative local CREATE |
| `onConferenceSessionStarted` shadow hook | **NO** | fires before facts; `DEFERRED_NO_FACT` expected |

```3869:3932:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        if (sessionType == SessionType.CONFERENCE) {
            auditAdmissionDecision(..., writer = "meshCallInternal", creationSource = "USER_CREATE", ...)
            ...
            ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted(...)
            auditConferenceSessionLifecycle(event = "SESSION_CREATED", writer = "meshCallInternal", ...)
```

**Publisher must observe topology publish, not `meshCallInternal` alone.**

### Profile01 ingress mirror (validation only)

`Profile01ConferenceMediaFactValidator.validateSession` → `membershipConvergence.seedFromCreation` → `AuthoritativeConferenceMediaSessionDeclaration` with `disposition=AUTHORITATIVE` (`Profile01ConferenceMediaFactValidator.kt` L37–104).

### ORIGIN_SOURCE_GAP items (do not invent in Publisher)

1. **id128 `conferenceId`** — no host allocator; index binds only after receive today
2. **CREATION wire payload** — `masterKey`, `masterSalt`, `endpoint`, `membershipView`, `mediaKeyCommitment` — no production assembler (fixtures in `Profile01DirectedWireFixtures`)
3. **Encoder + signer** — decode/verify only in tree

### Risk

Dual-HOST / yielding: emit only when local node is topology/membership authority for the admitted conference, not on every `USER_CREATE`.

---

## 2. MEDIA_KEY_PACKAGE — media key epoch / material ready

**Detailed gap map:** `meeting-profile01-media-key-package-origin-hook-gap-map.md`

```text
CREATION  = conference-scoped; same envelope replayable
PACKAGE   = recipient-scoped; per-peer ciphertext + publication bookkeeping
```

### Intended trigger

```text
media key epoch available / changed → MEDIA_KEY_PACKAGE (per recipient)
```

### Authoritative source — ORIGIN_SOURCE_GAP

**Recipient-side (verify/decrypt) — SOURCE_PRESENT:**

- `Profile01MediaKeyPackageDecryptSeam` + `Profile01PackageCrypto` (`Profile01PackageCrypto.kt`)
- `Profile01SessionMediaSupplementRegistry` stores derived material post-decrypt
- `Profile01MembershipGeneration.mediaKeyEpoch` / `mediaKeyCommitment` (`Profile01MembershipGeneration.kt`)

**Host-side (encrypt/wrap/sign package) — NOT FOUND in production:**

- `wrappedPek`, `gcmNonce`, `packageIdentity` — decoded in `Profile01WireCborDecoder`; no host encrypt seam
- `Profile01RecipientKeyEstablishmentSeam.unwrapPek` — unwrap only
- `Profile01Q5TestRecipientKeyEstablishmentSeam` / `Profile01Q5DirectedFixtures` — **test only**
- `AuthorityFactStore.acceptVerifiedKey(MediaKeyContextFact)` — Phase 1 SRTP wiring; **not** Profile01 wire package origin

### Committed signal

On recipient after wire arrives: `WIRE_INGRESS factType=11 outcome=APPLIED`.  
**No host-side “material ready” observability** exists today.

### Publisher hook verdict

**ORIGIN_SOURCE_GAP** — cannot attach Publisher to a read-only hook until host defines:

```text
authoritative conferenceMediaSecret + membershipKeyContextDigest
  → mediaKeyCommitment (Profile01PackageCrypto.verifyMediaKeyCommitment)
  → per-recipient wrappedPek (production wrap seam — missing)
```

### Risk

v1 frozen order is `MEDIA_KEY_PACKAGE` first; this fact is the **hard blocker** for end-to-end origin.

---

## 3. MEMBERSHIP — authoritative roster generation changed

### Intended trigger

```text
authoritative roster generation advanced → MEMBERSHIP
```

### Authoritative source — SOURCE_PRESENT (legacy roster)

**Primary apply path:**

```13817:13845:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
            GroupMembershipSupport.applyMembershipSnapshot(
                session, snapshot.rosterEpoch, snapshot.anchorEpoch, members,
                caller.moduleId.value, authorityId, monotonicEpoch = monotonicEpoch
            )
        ) {
            GroupMembershipSupport.MembershipSnapshotApplyResult.APPLIED -> {
                log("${sessionTag(session)} snapshotApplied rosterEpoch=...")
                logMembershipEpochTransition(..., applyResult = "APPLIED", path = "conference_snapshot_invite")
```

**Authority gate:** `isMembershipAuthority(session)` (`TalkbackCoordinator.kt` L13988+) — required before `broadcastMembershipSnapshot` / epoch bump.

**Topology-aligned roster:** `conferenceTopologyAuthority.publishRosterEdgeChange(session.id, members)` inside `applyConferenceTopologyAdmission` when anchor roster set changes (L4891–4895).

**Epoch bump:** `bumpRosterEpoch` → `applyConferenceTopologyAdmission(session, reason)` (L13324–13331).

### Committed signal

| Log / outcome | Meaning |
|---------------|---------|
| `MembershipSnapshotApplyResult.APPLIED` | roster snapshot committed |
| `MEMBERSHIP_EPOCH_TRANSITION ... applyResult=APPLIED` | conference epoch transition |
| `CONFERENCE_TOPOLOGY_PUBLISHED` + roster edge change | topology members committed |
| `MEMBERSHIP_SNAPSHOT_SENT` | legacy control-plane broadcast (not P01 wire) |

### Profile01 chain head (ingress mirror)

`Profile01MembershipConvergenceRegistry.applyMembership` requires CREATION-seeded head + `previousMembershipDigest` chain (`Profile01MembershipConvergenceRegistry.kt` L48–89).

### Bridge gap (not a new authority — mapping only)

Legacy `session.rosterEpoch` / snapshot members ≠ Profile01 `membershipVersion` / `Profile01WireMembershipMember` / digest chain **unless** explicit bridge reads:

- current `Profile01MembershipGeneration` head (after host CREATION emit), or
- authoritative snapshot at `APPLIED` time mapped into wire fields

### Publisher hook verdict

| Layer | Verdict |
|-------|---------|
| Observe roster generation change | **SOURCE_PRESENT** |
| Emit Profile01 MEMBERSHIP bytes | **PARTIAL** — needs bridge + CREATION head + encoder |

### Risk

Non-authority epoch bumps must not emit (`isMembershipAuthority` false). Branch conflicts (`MEMBERSHIP_BRANCH_CONFLICT`) if duplicate version.

---

## 4. SOURCE_DECLARATION — authoritative member media binding ready

### Intended trigger

```text
member source binding authoritative → SOURCE_DECLARATION
```

### Authoritative source — ORIGIN_SOURCE_GAP

**Contract (ingress):** SSRC and admission key are wire-authoritative — never derived from `moduleId`:

```25:29:talkback/android-board-talkback/src/main/java/com/talkback/core/conference/session/gbc/AuthoritativeConferenceMediaDeclarations.kt
 * Verified Source Declaration / Resolution at GBC convergence boundary.
 *
 * SSRC and admission key are authoritative wire facts — never derived from [moduleId].
```

**Validation path (after wire):** `Profile01ConferenceMediaFactValidator.validateMember` → `AuthoritativeConferenceMediaMemberDeclaration` (`Profile01ConferenceMediaFactValidator.kt` L144–203).

**Admission key derivation (verify-side):** `Profile01SourceAdmissionDeriver.deriveSourceAdmissionKey48(sourceInstanceId, ssrc, ...)`.

### What exists but is NOT binding authority

| Hook | Role | Authoritative for SOURCE_DECLARATION? |
|------|------|--------------------------------------|
| `ConferenceParticipantManager.onMediaConnected` | transport CONNECTED | **NO** |
| `onConferenceMemberMediaReady` / ICE `CONNECTED` | shadow replay trigger | **NO** |
| `MeetingProfile01ConferenceSessionIndex.markMediaConnected` | UI/wiring hint | **NO** |
| `SessionMediaWiringHarness` / soak runners | test SSRC allocation | **NO** (fixture) |
| `SourceBindingCatalog.install` | post-fact wiring | **NO** (consumer) |

### Committed signal

None on host for binding allocation. Recipient: `WIRE_INGRESS factType=9 outcome=APPLIED`.

### Publisher hook verdict

**ORIGIN_SOURCE_GAP** — requires upstream authority that assigns per-member:

```text
sourceInstanceId (id128)
sourceGeneration (monotonic per member)
ssrc
→ sourceAdmissionKey48 (derivable only after the above are authoritative)
```

### Risk

Emitting on ICE CONNECTED alone would violate Profile01 contract and duplicate transport truth as media binding truth.

---

## Recommended Publisher observation points (read-only)

When implementation starts, **observe** these transitions; **do not** emit from tentative CREATE or ICE alone.

| Fact | Observe (committed) | Do not observe |
|------|---------------------|----------------|
| CREATION | `PublishResult.Published` + local is conference authority | `meshCallInternal` USER_CREATE |
| MEDIA_KEY_PACKAGE | *(none today)* | placeholder emit |
| MEMBERSHIP | `applyMembershipSnapshot(APPLIED)` + `isMembershipAuthority` | non-authority bump |
| SOURCE_DECLARATION | *(none today)* | ICE CONNECTED only |

Suggested coordinator seam (future, not implemented here):

```text
MeetingProfile01FactOriginObserver
  onTopologyPublished(session, snapshot, reason)
  onMembershipGenerationAdvanced(session, generationSnapshot)
  onMediaKeyMaterialReady(session, epoch, recipients)   // ORIGIN_SOURCE_GAP until defined
  onMemberSourceBindingReady(session, moduleId, binding) // ORIGIN_SOURCE_GAP until defined
```

Publisher implementation returns `ORIGIN_SOURCE_GAP` for unimplemented observers — **no internal queue/retry state machine.**

---

## Implementation sequencing (post-inventory)

```text
Step 0  Encoder + signer seam (Profile01 SIGNATURE_DOMAIN)     — shared prerequisite
Step 1  CREATION bridge (id128 + payload authority)           — unblocks MEMBERSHIP head
Step 2  MEMBERSHIP bridge (roster APPLIED → wire)             — SOURCE_PRESENT hook
Step 3  MEDIA_KEY_PACKAGE host material seam                  — ORIGIN_SOURCE_GAP
Step 4  SOURCE_DECLARATION binding authority                  — ORIGIN_SOURCE_GAP
Step 5  CONFERENCE_SIGNED_FACT send + field re-run
```

**Do not claim Phase A PASS until field shows wire → WIRE_INGRESS APPLIED → shadow DEFERRED_* → APPLIED.**

---

## References

- Origin Discovery evidence: `talkback/logs/meeting-product-origin-discovery-20260907-153810/`
- Wire constants: `Profile01WireConstants.kt`
- ADR-0058: `docs/adr/0058-profile-01-q4-wire-registry.md`
- Anti-pattern proxy list: `GbcLegacyAuthorityProxies.kt` (do not use as generation authority)
