# RCA — PA-SR4-TX Host Projection Continuation (`RCA_PA_SR4_TX_HOST_PROJECTION`)

**Field run:** `talkback/logs/phase-a-sr4-tx-field-20260909-164833`  
**Harness verdict:** `INVALID_PREREQUISITE` (`host_shadow_session_not_established`) — **not** `FAIL_PA_SR4_TX`  
**Date:** 2026-09-09  
**Scope:** TX-A host local projection continuation only. **No code change in this document.** **No field rerun.**

**Explicitly out of scope:** TX-B capture tap · TX-3 egress gates · RX/PLAY · PA-SR4 full gate · ADR-0056 cutover.

---

## Failure locus (frozen)

```text
HOST_SESSION_PROJECTED outcome=DEFERRED_NO_SUPPLEMENT   (M01 host, t+0)
→ supplement later READY on peer wire path only         (M02, t+12.6s)
→ host second projection entry never fires              (M01)
→ SHADOW hook=SESSION_STARTED outcome=DEFERRED_NO_FACT  (M01, unchanged)
→ PTT captureON=True                                    (M01, stimulus OK)
→ SHADOW_TX_FENCED reason=NO_SESSION_FACT               (G1 — session never live)
→ 0 × SHADOW_TX_ACTIVITY
```

**Not** TX-B / egress / capture tap (stimulus reached seam; fences are G1 preconditions).  
**Not** identity mismatch on re-entry (re-entry never attempted on host).  
**Not** supplement registry key / epoch mismatch at second lookup (second lookup never happens on host).

---

## Correlated identity

| Key | Value |
|-----|-------|
| sessionId | `9c2f6c81-673a-4149-81b4-a834bd92f8d8` |
| conferenceId | `76130a6c5026816132b54c42c75d73c5` |
| hostModuleId | `M01` |
| peerModuleId | `M02` |
| mediaKeyEpoch | `1` |
| creationFactDigest | `dc2ab1f9f158c81db865cccd6677145336627c8faea2c02acb35af0b3d8e398e` |

---

## Three-question RCA (narrow)

### Q1 — Was `onMeetingProfile01MediaSupplementReady` triggered on host?

**Answer: NO.**

| Device | `PROFILE01_MEDIA_SUPPLEMENT … state=READY` | Projection follow-up |
|--------|---------------------------------------------|----------------------|
| **M01 (host)** | **0 lines** | Only `HOST_SESSION_PROJECTED outcome=DEFERRED_NO_SUPPLEMENT` (once) |
| M02 (peer) | 1 line @ 16:53:25.503 | `HOST_SESSION_PROJECTED outcome=DEFERRED_NO_CREATION` (expected — peer has no host creation bytes) |

**Wiring (product):**

```text
Profile01ConferenceMediaFactIngress.ingestMediaKeyPackage
  → decrypt Ready
  → supplementRegistry.put(material)
  → logMediaSupplementReady → PROFILE01_MEDIA_SUPPLEMENT state=READY
  → onMediaSupplementReady?.invoke(conferenceId, mediaKeyEpoch)
  → TalkbackCoordinator.onMeetingProfile01MediaSupplementReady
  → profile01HostLocalSessionFactProjection.maybeProjectHostShadowSession
```

This path is **receive/decrypt only**. On host, field evidence shows **emit-only** media key packages:

```text
M01 16:53:13.163 PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN recipientModuleId=M02 outcome=EMITTED
M01 16:53:24.525 PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN recipientModuleId=M02 outcome=POST_BIND_EMITTED
```

Host never ingests a MEDIA_KEY_PACKAGE for itself, so `supplementRegistry` is never populated on M01 and `onMediaSupplementReady` never fires.

**Harness note:** J0 prerequisite `SUPPLEMENT READY` in the driver is detected from **M02** log (`p1c-package-field-first-run.ps1` polls `m02Log` for `PROFILE01_MEDIA_SUPPLEMENT`). That proves peer supplement path — **not** host supplement-ready continuation.

---

### Q2 — If triggered, why no re-entry into `Profile01HostLocalSessionFactProjection`?

**Answer: N/A on host — callback never invoked.**

Secondary control-plane check (for completeness if host had fired):

| Gate | Host state @ supplement-ready time | Would block? |
|------|-----------------------------------|----------------|
| `sessionIdForConference(conferenceId)` | `bindConferenceId` @ creation emit (16:53:12.869) | No |
| `sessions[sessionId]` CONFERENCE | Active host session | No |
| `session.type != CONFERENCE` | CONFERENCE | No |
| Host role gate in coordinator | **None** — no `isHost` check on supplement-ready path | No |

Re-entry failure is **not** session/conference lookup or host role gate. It is **missing supplement-ready event on host**.

**Analogy (narrow, not B1-R1 framework):** Similar *shape* to “prerequisite later satisfied but continuation not re-driven” — but the specific gap is **host supplement registry population is wired only through peer wire decrypt**, not through host origin materialization.

---

### Q3 — If re-entered, why still no `APPLIED`?

**Answer: N/A on host — single entry at creation commit already failed; no second entry.**

First (and only) host projection @ 16:53:12.905:

```text
PROFILE01_SHADOW_RUNTIME phase=HOST_SESSION_PROJECTED outcome=DEFERRED_NO_SUPPLEMENT
```

Code path (`Profile01HostLocalSessionFactProjection.maybeProjectHostShadowSession`):

```text
supplementRegistry.lookup(conferenceId, mediaKeyEpoch) ?: defer("NO_SUPPLEMENT")
```

At creation commit time:

- `readSignedCreationFact(sessionId)` — **present** (`creationStateBySession` populated by origin emit)
- `sessionIndex.bindingForSession` / `conferenceId` — **present** (`76130a6c…` bound @ emit)
- `readCreationMediaKeyEpoch` — **present** (epoch `1` in CREATION bytes)
- `supplementRegistry.lookup` — **absent** on host

Host **does** hold authoritative key material in `Profile01ConferenceMediaKeyMaterialAuthority.materialForSession(sessionId)` (used to build peer packages). That material is **not** translated into `Profile01DerivedMediaKeyMaterial` / `supplementRegistry.put` on the host path.

Therefore the second dual-entry hook (`onMeetingProfile01MediaSupplementReady`) — designed to retry when supplement becomes ready — **never runs on host**, and `APPLIED` is unreachable without a new host-local supplement materialization seam.

---

## Timeline (M01 host, authoritative)

| Time | Event |
|------|-------|
| 16:53:12.819 | `SESSION_BIND_DRAIN` conferenceId bound |
| 16:53:12.823 | `SHADOW hook=SESSION_STARTED outcome=DEFERRED_NO_FACT` (coordinator before creation ingest) |
| 16:53:12.869 | `PROFILE01_ORIGIN_EMIT factType=1 outcome=EMITTED` |
| 16:53:12.899 | `PROFILE01_SOURCE_ORIGIN reason=r1a_host_creation_projected outcome=BUILT` |
| 16:53:12.905 | **`HOST_SESSION_PROJECTED outcome=DEFERRED_NO_SUPPLEMENT`** ← TX-A entry #1 |
| 16:53:13.163 | `MEDIA_KEY_PACKAGE_ORIGIN recipientModuleId=M02 EMITTED` (peer only) |
| 16:53:24.525 | `MEDIA_KEY_PACKAGE POST_BIND_EMITTED` to M02 |
| — | **No** second `HOST_SESSION_PROJECTED` on M01 |
| — | **No** `PROFILE01_MEDIA_SUPPLEMENT state=READY` on M01 |
| 16:53:24.953+ | `SHADOW_TX_FENCED reason=NO_SESSION_FACT` (PTT stimulus; G1) |

Peer (M02) for contrast @ 16:53:25.503: supplement READY → peer wire CREATION APPLIED → peer shadow session live. Host shadow session remains absent.

---

## Root cause statement (frozen)

```text
GAP-TX-A-HOST-SUPPLEMENT-CONTINUATION

Host TX-A dual-entry assumes supplementRegistry is populated before or after
creation commit via the same path that fires onMediaSupplementReady.

On host, supplementRegistry is populated only by wire MEDIA_KEY_PACKAGE decrypt
(peer-receive path). Host origin emits packages to peers but never materializes
host-local Profile01DerivedMediaKeyMaterial into supplementRegistry and never
invokes onMeetingProfile01MediaSupplementReady.

Result:
  Entry #1 (creation commit) → DEFERRED_NO_SUPPLEMENT
  Entry #2 (supplement ready) → never scheduled on host
  → SESSION_STARTED stays DEFERRED_NO_FACT
  → valid TX field = INVALID_PREREQUISITE (G1), not FAIL_PA_SR4_TX
```

---

## What this RCA does **not** claim

| Claim | Status |
|-------|--------|
| TX-B broken | **Rejected** — `captureON=True`, `SHADOW_TX_FENCED` proves seam armed + gated |
| Egress six-gate logic wrong | **Out of scope** — fences are `NO_SESSION_FACT` (G1), not epoch/generation |
| RX/PLAY regression | **Out of scope** |
| PA-SR4 should PASS | **Rejected** — full SR4 chain not exercised; host never live |
| Reuse B1-R1 obligation framework wholesale | **Avoided** — host-specific supplement materialization gap |

---

## Fix direction (selected — see TX-A1 authorization)

**Selected (frozen):** Host authoritative key material → local `Profile01SessionMediaSupplementRegistry` materialization → reuse existing supplement-ready continuation.

**Rejected:** HLSP bypass of supplement registry to read `Profile01ConferenceMediaKeyMaterialAuthority` directly.

**Authorization:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-tx-a1-host-local-supplement-materialization-authorization.md`

**Do not** fix by weakening TX field gate to accept `HOST_SESSION_PROJECTED` without session live or without `SHADOW_TX_ACTIVITY`.

---

## Status board (frozen)

```text
TX-A RCA         CLOSED
Root cause       HOST_LOCAL_SUPPLEMENT_MATERIALIZATION_MISSING

TX-A1            DESIGN FROZEN / IMPLEMENTATION AUTHORIZED
PR-PA-SR4-TX     UNIT-PROVEN / FIELD NOT YET VALIDLY TESTED
TX field         INVALID_PREREQUISITE (20260909-164833)
TX-B/TX-3/TX-4   UNCHANGED
PA-SR4           VALID FAIL
RX               NOT AUTHORIZED
PLAY             HOLD
ADR-0056         KEEP
```

**Next:** PR-PA-SR4-TX-A1 implement + unit closure; then re-run TX field gate (same harness).
