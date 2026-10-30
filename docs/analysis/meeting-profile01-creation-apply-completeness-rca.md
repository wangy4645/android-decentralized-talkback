# RCA: Profile01 CREATION Apply-Completeness — `REJECTED_INCOMPLETE`

## Status

```text
RCA                          COMPLETE (read-only)
Classification               C — REQUIRED_PREDECESSOR_MISSING (primary)
                             B — PLACEHOLDER_CONTRACT_BUG (symptom amplifier; not authorized fix)
                             A — REJECTED (gate correctly scoped)
Field evidence             …-132834 (post retain/drain)

Session-bridge silent-drop   CLOSED
CREATION peer E2E            VERIFY_APPLY_GAP
J0 end-to-end                NOT PASS

Next                         Path 1 design slice sign-off → implementation (P1-A..E)
ADR-0061                     FREEZE
Retention/drain code         FREEZE
CREATION origin/replay       FREEZE
MEMBERSHIP                   HOLD
Phase A                      BLOCKED
```

**Parent field run:** `talkback/logs/meeting-product-j0-creation-e2e-20260908-132834/`  
**Prior bridge RCA:** `meeting-profile01-deferred-session-bridge-rca.md`

---

## Problem statement (post retain/drain)

Field chain after session-bridge fix:

```text
CREATION verify/decode     PASS (re-ingress reached publish)
publish/apply              REJECTED_PUBLISH detail=REJECTED_INCOMPLETE
```

This is **not** silent abandonment, retention miss, or drain miss. The failure is at **GBC registry publish completeness**.

---

## Field fingerprint (…-132834)

| Step | Evidence |
|------|----------|
| retain | `WIRE_INGRESS … DEFERRED_NO_SESSION_RETAINED factType=1` @ 13:30:45.577 |
| same-session | `incomingSessionId=4fdc5d75…` `creationSource=INVITE_ACCEPT` |
| drain | `SESSION_BIND_DRAIN … count=1` @ 13:30:59.531 |
| publish | `WIRE_INGRESS … outcome=REJECTED_PUBLISH detail=REJECTED_INCOMPLETE` @ 13:30:59.557 |
| MEDIA_KEY on peer | **absent** (no `factType=11` / no package ingress) |

---

## RCA question 1 — What does `validateSessionComplete()` require for CREATION?

`ConferenceSessionMediaGbcPublisherBridge.publishSessionDeclaration` calls `validateSessionComplete` **after** Profile01 validator has already produced an `AuthoritativeConferenceMediaSessionDeclaration` with `disposition=AUTHORITATIVE`.

### Required fields (all must pass)

```93:103:talkback/android-board-talkback/src/main/java/com/talkback/core/conference/session/gbc/ConferenceSessionMediaGbcPublisherBridge.kt
        fun validateSessionComplete(declaration: AuthoritativeConferenceMediaSessionDeclaration): Boolean =
            declaration.conferenceId.isNotBlank() &&
                declaration.channelId.isNotBlank() &&
                declaration.conferenceEpoch > 0L &&
                declaration.membershipVersion >= 0L &&
                declaration.mediaKeyEpoch > 0L &&
                declaration.factGeneration > 0L &&
                declaration.networkInterfaceName.isNotBlank() &&
                declaration.masterKey.size == MASTER_KEY_BYTES &&
                declaration.masterSalt.size == MASTER_SALT_BYTES &&
                declaration.keyContextHint64.size == KEY_CONTEXT_HINT_BYTES
```

| Constant | Bytes | Role |
|----------|-------|------|
| `MASTER_KEY_BYTES` | **16** | SRTP master key |
| `MASTER_SALT_BYTES` | **12** | SRTP master salt |
| `KEY_CONTEXT_HINT_BYTES` | **8** | RTP key-context hint |

### Why CREATION apply already carries SRTP `masterKey`

CREATION **wire bytes do not embed** SRTP keys. They carry `mediaKeyCommitment` (32-byte hash) plus topology/membership view. SRTP material is merged at decode from **`Profile01SessionMediaSupplement`**:

```48:63:talkback/android-board-talkback/src/main/java/com/talkback/core/conference/session/profile01/wire/Profile01WireCborDecoder.kt
        return DecodeResult.Ready(
            Profile01WireSessionFact(
                ...
                mediaKeyCommitment = mediaKeyCommitment,
                endpoint = endpoint,
                masterKey = supplement.masterKey.copyOf(),
                masterSalt = supplement.masterSalt.copyOf(),
                keyContextHint64 = supplement.keyContextHint64.copyOf(),
            ),
        )
```

Validator copies those into the authoritative declaration:

```76:90:talkback/android-board-talkback/src/main/java/com/talkback/core/conference/session/profile01/Profile01ConferenceMediaFactValidator.kt
        val declaration =
            AuthoritativeConferenceMediaSessionDeclaration(
                ...
                masterKey = wire.masterKey.copyOf(),
                masterSalt = wire.masterSalt.copyOf(),
                keyContextHint64 = wire.keyContextHint64.copyOf(),
                ...
                disposition = ConferenceMediaDeclarationDisposition.AUTHORITATIVE,
            )
```

**Contract meaning:** GBC registry session facts are **media-wiring-ready** control-plane records, not “CREATION-only shells.” `validateSessionComplete` enforces that the declaration already contains **valid-sized SRTP material**, not merely a wire commitment.

**Field failure mechanism:** peer re-ingress used supplement fallback with `masterKey = ByteArray(32)` → declaration has 32-byte key → `masterKey.size == 16` fails → `REJECTED_INCOMPLETE`.

---

## RCA question 2 — Placeholder 32-byte vs production 16-byte SRTP

### Profile01 SRTP contract

`Profile01PackageCrypto` derives SRTP material as **16-byte masterKey + 12-byte masterSalt**:

```99:102:talkback/android-board-talkback/src/main/java/com/talkback/core/conference/session/profile01/wire/Profile01PackageCrypto.kt
        return SrtpKeyMaterial(
            masterKey = material.copyOfRange(0, 16),
            masterSalt = material.copyOfRange(16, 28),
        )
```

Q5 unit vectors assert 16-byte master key hex. GBC bridge `MASTER_KEY_BYTES = 16` matches this.

### Wire ingress placeholder

```256:262:talkback/android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/MeetingProfile01FactWireIngress.kt
        val placeholder =
            Profile01SessionMediaSupplement(
                channelId = sessionBinding.channelId,
                masterKey = ByteArray(32),
                masterSalt = ByteArray(12),
                keyContextHint64 = ByteArray(8),
            )
```

`Profile01SessionMediaSupplement` documents intended source:

```3:7:talkback/android-board-talkback/src/main/java/com/talkback/core/conference/session/profile01/Profile01SessionMediaSupplement.kt
 * [masterKey]/[masterSalt]/[keyContextHint64] typically come from MEDIA_KEY_PACKAGE decrypt.
```

### Harness / product-chain vs field shadow

| Path | Supplement source | CREATION publish |
|------|-------------------|------------------|
| Unit / golden (`MeetingProfile01FactWireIngressTest`) | Q5 decrypt → `supplementRegistry.put` **before** apply | **APPLIED** |
| `RealMeshE2ERunner` | decrypt → registry → `q5Material.masterKey` (16 B) passed to ingest | **ACCEPTED** |
| Field shadow (`MeetingProfile01FactWireIngress`) | `ByteArray(32)` fallback; registry empty on peer | **REJECTED_INCOMPLETE** |

**Verdict on B:** The 32-byte array is a **shadow/harness transitional artifact**, not a production-allowed key size. It is **not** the root contract gap — changing 32→16 would only satisfy the size gate with zero bytes and would **not** satisfy media correctness. **Not authorized** as the fix path.

---

## RCA question 3 — CREATION vs MEDIA_KEY_PACKAGE ordering contract

### Two different “orders”

| Order type | Contract |
|------------|----------|
| **Wire delivery** | CREATION may arrive **before** peer session bind (field-proven); MEDIA_KEY_PACKAGE may also arrive later on wire |
| **Publish apply** | GBC session publish requires **resolved supplement** (typically from decrypted MEDIA_KEY_PACKAGE in `Profile01SessionMediaSupplementRegistry`) **at CREATION apply time** |

### Supplement resolution at apply

```50:60:talkback/android-board-talkback/src/main/java/com/talkback/core/conference/session/profile01/Profile01ConferenceMediaFactIngress.kt
        val resolvedSupplement =
            if (conferenceIdHex != null && mediaKeyEpoch != null && supplementRegistry != null) {
                supplementRegistry.resolveSupplement(
                    conferenceId = conferenceIdHex,
                    mediaKeyEpoch = mediaKeyEpoch,
                    channelId = supplement.channelId,
                    fallback = supplement,
                )
            } else {
                supplement
            }
```

If registry has no entry for `(conferenceId, mediaKeyEpoch)`, ingress uses **fallback placeholder** → field uses 32-byte zeros → publish incomplete.

### MEDIA_KEY_PACKAGE validator predecessor

`verifyMediaKeyPackage` requires **membership converged** (`membershipConvergence.current`) — CREATION `seedFromCreation` establishes that head. So wire order **can** be:

```text
CREATION (seeds membership convergence)
→ MEDIA_KEY_PACKAGE (verify against convergence)
→ supplementRegistry.put
```

But **CREATION publish to registry** still needs supplement material at apply instant. There is **no** deferred re-publish hook when package arrives later.

### Product origin state (field)

| Fact | Host origin on wire (field J0) |
|------|--------------------------------|
| CREATION | **YES** — `PROFILE01_ORIGIN_REPLAY target=M02` |
| MEDIA_KEY_PACKAGE | **NO** — host origin **ORIGIN_SOURCE_GAP** (hook inventory) |

Field peer received CREATION only. No `factType=11` ingress. Registry never populated.

### Harness reference order (not field)

Product-chain / RealMeshE2E **always** load decrypt material before CREATION publish (harness injection). That path is **not** Meeting UI origin — documented separately in product-chain soak run card.

Origin publisher run card explicitly allows perpetual shadow `DEFERRED_*` after CREATION when supplement is still placeholder until MEDIA_KEY_PACKAGE origin exists.

---

## Classification

| Candidate | Verdict | Rationale |
|-----------|---------|-----------|
| **A. APPLY_GATE_TOO_EARLY** | **REJECTED** | `validateSessionComplete` is correctly scoped: registry session facts must be media-wiring complete. CREATION wire intentionally defers keys to supplement side-channel; gate is not mis-layered — **missing predecessor material** is the gap. |
| **B. PLACEHOLDER_CONTRACT_BUG** | **Secondary / symptom** | 32-byte placeholder violates GBC 16-byte contract and supplement semantics. Explains **why** field hits `REJECTED_INCOMPLETE` given empty registry. **Not** an authorized fix (32→16 would bypass size check only). |
| **C. REQUIRED_PREDECESSOR_MISSING** | **PRIMARY** | Peer apply path lacks required MEDIA_KEY_PACKAGE-derived supplement at CREATION publish time. Host does not yet emit package on product origin wire. Validator/publish contract expects material present; field sequence is incomplete. |

**Working label:** `VERIFY_APPLY_GAP` / `REQUIRED_PREDECESSOR_MISSING` (media-key supplement at CREATION apply).

---

## What is closed vs open

### Closed (…-132834)

```text
pre-bind retain              FIELD PROVEN
bind drain                   FIELD PROVEN
re-ingress                   FIELD PROVEN
silent abandonment           CLOSED
```

### Open

```text
CREATION publish → registry   REJECTED_INCOMPLETE
CREATION peer E2E             VERIFY_APPLY_GAP
J0 end-to-end                 NOT PASS
```

---

## Non-actions (frozen discipline)

```text
Do NOT reopen retention/drain implementation
Do NOT patch ByteArray(32) → ByteArray(16) as closure strategy
Do NOT lift MEMBERSHIP HOLD
Do NOT expand Phase A scope
Do NOT modify ADR-0061 / CREATION origin replay code
```

---

## Decision fork for next scoped work (RCA only — not authorized)

Two **distinct** closure paths; require explicit product decision before implementation:

### Path 1 — Predecessor delivery (aligns with C)

Close host **MEDIA_KEY_PACKAGE origin** gap (or scoped peer delivery) so `supplementRegistry` is populated **before or at** CREATION apply on peer.

- Respects GBC completeness contract
- Aligns with hook inventory dependency order
- Does **not** require weakening `validateSessionComplete`

### Path 2 — Deferred session publish (only if product rejects C)

Introduce explicit **defer-not-reject** at ingress when supplement missing (shadow-scoped), with replay when package arrives — **new contract**; not evidenced in current GBC bridge.

**RCA recommendation:** Prefer **Path 1** investigation/scoping. Path 2 is architectural and overlaps coordinator `DEFERRED_NO_FACT` patterns — do not improvise without new scoped design.

---

## Status board

```text
CREATION transport/origin       PASS
CREATION pre-bind retention     PASS
CREATION session-bind drain     PASS
CREATION verify/re-ingress      PASS
CREATION publish/apply          REJECTED_INCOMPLETE

Session-bridge silent-drop gap  CLOSED
CREATION peer E2E                VERIFY_APPLY_GAP
J0 end-to-end                   NOT PASS

Next                            MEDIA_KEY_PACKAGE origin hook gap map → Path 1 design slice
ADR-0061                        FREEZE
Retention/drain code            FREEZE
CREATION origin/replay          FREEZE
MEMBERSHIP                      HOLD
Phase A                         BLOCKED
```
