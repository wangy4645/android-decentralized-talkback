# Phase A Shadow Runtime — RCA-B Handoff (Read-Only)

**Parent:** `meeting-product-integration-phase-a-shadow-runtime-rca-handoff.md`  
**Field evidence:** `talkback/logs/phase-a-shadow-runtime-20260909-111359/` (post PR-PA-SR-A1)  
**Status:** **RCA-B CLOSED (read-only)** — implementation **NOT authorized** in this document

```text
RCA-A          CLOSED · PA-SR1/SR2 FIELD VERIFIED (peer)
RCA-B          CLOSED (read-only) · PA-SR3 BLOCKED
PA-SR4…SR6     DOWNSTREAM / NOT CLOSED

ADR-0056       KEEP
cutover        NOT AUTHORIZED
```

---

## RCA-B goal (one sentence — frozen)

> 在 `SESSION_STARTED APPLIED` 已成立后，解释为什么产品侧 `MEMBER_MEDIA_READY(module=remote)` 仍无法从 Profile01 authoritative state 读取对应 member binding，并明确区分 SOURCE fact 是否已 apply、`mediaConnectedModules` 是否已满足，以及 replay 是否发生在正确时点。

---

## Executive verdict

| Root-cause class | Result |
|------------------|--------|
| **A — binding 未建立** | **CONFIRMED (primary)** |
| B — binding 已建立但读 gate 错 | **Rejected** |
| C — 条件后满足但缺 replay | **Rejected as primary** (latent wire-only concern noted below) |

**Narrow statement:** After RCA-A, peer `SESSION_STARTED` is live, but **no `SOURCE_DECLARATION` (factType=9) is ever published or applied** on either device in the Meeting product path. `ConferenceSessionMediaControlFactRegistry` therefore has **no member slot** for the remote module. `factPort.memberBinding(...)` correctly returns `null` → `DEFERRED_NO_BINDING`.

This is **not** a `mediaConnectedModules` ordering miss on the product ICE hook path, and **not** a second-chance replay gap given current field facts (SOURCE never arrives).

---

## Question chain (field + code)

```text
SOURCE_DECLARATION origin/send
→ peer wire ingress
→ SOURCE_DECLARATION APPLIED?
→ binding 写入哪个 registry/store
→ factPort.memberBinding(...) 读哪个 store
→ mediaConnectedModules 是否已包含 remote
→ MEMBER_MEDIA_READY replay/调用时序
→ DEFERRED_NO_BINDING
```

---

## 1) SOURCE 是否真的已 APPLIED?

### Field scan (both devices, full log window)

| factType | Constant | M01 `WIRE_INGRESS` | M02 `WIRE_INGRESS` |
|----------|----------|--------------------|--------------------|
| 1 | CREATION | **0** (host origin only) | **1 APPLIED** |
| 9 | SOURCE_DECLARATION | **0** | **0** |
| 11 | MEDIA_KEY_PACKAGE | **0** | **1 APPLIED** |

**Correction vs prior RCA draft:** `factType=11` is **`MEDIA_KEY_PACKAGE`**, not SOURCE. Prior handoff line “factType=11 APPLIED → SOURCE” was mislabeled.

### Origin emit (M01 host, authoritative sender)

```text
11:29:31.658  PROFILE01_ORIGIN_EMIT factType=1  outcome=EMITTED
11:29:31.925  PROFILE01_ORIGIN_EMIT factType=11 outcome=EMITTED target=M02
```

**No `PROFILE01_ORIGIN_EMIT factType=9` anywhere in field logs.**

### Product wiring (code)

`TalkbackRuntimeFactory` attaches:

- `MeetingProfile01CreationOriginBridge` — CREATION origin **present**
- `MeetingProfile01MediaKeyPackageOriginBridge` — package origin **present**
- **`MeetingProfile01SourceOriginBridge` — absent** (no class in product wiring)

Directed / soak harnesses apply SOURCE via `ingestSourceDeclarationSignedFact` + fixtures (`RealMeshE2ERunner`, `ProductChainFieldSoakRunner`). Meeting product path does **not**.

### Verdict (cut 1)

**SOURCE not applied — root is upstream of binding projection.** Not verify/decode failure on wire (no SOURCE datagram observed). Not registry read gate mis-keying.

---

## 2) binding 是否已存在但被 gate 掉?

### Where binding is written

```text
ingestSourceDeclarationSignedFact
  → Profile01ConferenceMediaFactIngress.ingestMember
  → ConferenceSessionMediaGbcPublisherBridge.publishMemberDeclaration
  → ConferenceSessionMediaControlFactRegistry.publishMember
```

CREATION (`publishSessionDeclaration`) and MEDIA_KEY_PACKAGE ingest **do not** populate `registry.members[moduleId]`.

### Where binding is read

```text
MeetingProfile01AwareConferenceSessionMediaFactPort.memberBinding
  → resolveConferenceId(sessionId)
  → registry.session(conferenceId)
  → registry.member(conferenceId, moduleId)   ← null without SOURCE apply
  → adapter.memberMatchesSession(session, member)
```

`memberBinding` does **not** use `rosterEpoch` / `membershipVersion` parameter (unlike `sessionFact`). Gate is: **member slot exists + session/member epoch alignment**.

### Identity mapping (M02 authoritative episode)

From manifest / logs:

```text
hostSessionId  = 2a4fc2fd-e8dd-478d-9964-ecf28a15b263
conferenceId   = 9c8e12a2f5b87745fa3f687446e6d113
```

CREATION APPLIED + `bindConferenceId` before replay (same ingress path as RCA-A). **Not** a missing `sessionId ↔ conferenceId` mapping at member hook time.

### Verdict (cut 2)

**Rejected.** Binding is **not present** in registry; not “present but fenced out.”

---

## 3) `mediaConnectedModules` 语义

### Code semantics

| Path | Behavior |
|------|----------|
| **Product ICE hook** (`TalkbackCoordinator` ~15330) | `markMeetingProfile01MediaConnected` then **unconditionally** `onConferenceMemberMediaReady` |
| **Wire ingress SOURCE replay** (`replayAfterPublish`) | `onConferenceMemberMediaReady` **only if** `moduleId ∈ sessionBinding.mediaConnectedModules` |

`mediaConnectedModules` is a **wire-replay admission set**, not a prerequisite for the product coordinator hook.

### Field timing (M02 — peer, SESSION_STARTED already APPLIED)

```text
11:29:43.563  MEDIA_LIFECYCLE module=M01 ice=CONNECTED
11:29:43.565  ICE M01 state=CONNECTED
11:29:43.567  SHADOW hook=MEMBER_MEDIA_READY module=M01 outcome=DEFERRED_NO_BINDING
```

ICE **CONNECTED precedes** hook by ~2–4 ms on the same coordinator thread. `markMediaConnected` runs in the same block **before** the hook.

### Field timing (M01 — host, SESSION_STARTED still DEFERRED on host)

```text
11:29:42.730  MEDIA_LIFECYCLE module=M02 ice=CONNECTED
11:29:42.746  SHADOW hook=MEMBER_MEDIA_READY module=M02 outcome=DEFERRED_NO_BINDING
```

Same pattern: ICE connected before hook. Host-origin `SESSION_STARTED` gap is **out of RCA-B scope** (per freeze); member failure here still consistent with **no M02 SOURCE in host registry**.

### Verdict (cut 3)

**Not primary.** Product path does not block on `mediaConnectedModules` for the observed hook. Wire replay gate is **latent** (would matter only after SOURCE publication exists).

---

## 4) 单次 replay 丢失？

### Observed hook cardinality

| Device | Module | `MEMBER_MEDIA_READY` count | Outcome |
|--------|--------|----------------------------|---------|
| M02 | M01 | **1** | DEFERRED_NO_BINDING |
| M01 | M02 | **1** | DEFERRED_NO_BINDING |

No second hook in 45s observation window or remainder of captured logcat.

### Hypothesis shape (user-provided)

```text
SOURCE APPLIED → MEMBER_MEDIA_READY → mediaConnectedModules not yet marked → DEFERRED
→ ICE CONNECTED / mark → no second replay
```

### Field fit

| Step | Field |
|------|-------|
| SOURCE APPLIED | **Never** |
| MEMBER_MEDIA_READY before ICE | **No** (ICE CONNECTED first on both sides) |
| mediaConnectedModules not marked | **No** (mark + hook synchronous) |
| Missing second replay after conditions | **Yes structurally** (single fire, no retry) — but **moot** because binding never established |

### Verdict (cut 4)

**Rejected as primary root cause.** The failure is explained without invoking replay timing. A **future** narrow obligation (“replay member hooks when SOURCE apply crosses ICE/media-connected boundary”) is **not evidenced as the current blocker**; SOURCE origin gap must close first.

---

## Authoritative M02 timeline (post RCA-A)

```text
11:29:43.397  PROFILE01_MEDIA_KEY_INGEST outcome=APPLIED
11:29:43.420  WIRE_INGRESS factType=1  outcome=APPLIED
11:29:43.499  SHADOW hook=SESSION_STARTED outcome=APPLIED
11:29:43.499  WIRE_INGRESS factType=11 outcome=APPLIED
11:29:43.563  ICE M01 CONNECTED
11:29:43.567  SHADOW hook=MEMBER_MEDIA_READY module=M01 outcome=DEFERRED_NO_BINDING
```

`SESSION_STARTED` live · `MEMBER_MEDIA_READY` blocked on **missing member registry slot**, not session fact handoff.

---

## Root-cause classification (frozen)

```text
TYPE A — binding 未建立

Mechanism: Meeting product origin publishes CREATION + MEDIA_KEY_PACKAGE only.
           SOURCE_DECLARATION (factType=9) has no origin bridge / no wire send.
           Registry member map empty for remote module.
           factPort.memberBinding → null → DEFERRED_NO_BINDING.
```

**Not TYPE B** (no binding to read).  
**Not TYPE C** (conditions for successful read were never satisfied).

---

## PA-SR3 implication

PA-SR3 requires `MEMBER_MEDIA_READY outcome=APPLIED` **and** catalog admission OBS. With TYPE A, both fail downstream — **blocked until SOURCE origin + apply path exists**, not until generic retry.

---

## PR chain (B0 → B1)

| PR | Doc | Status |
|----|-----|--------|
| **B0** | `meeting-product-integration-phase-a-shadow-runtime-rca-b0-fence-design.md` | **CLOSED / UNIT-PROVEN** |
| **B1** | `meeting-product-integration-phase-a-shadow-runtime-rca-b-fix-design.md` | **IMPL AUTHORIZED** · `…-pr-pa-sr-b1-implementation-authorization.md` |

**Pre-auth gate:** **A / PASS** (B0). B1 origin authorized; **not yet CLOSED**.

---

## Frozen routing

```text
PR-PA-SR-B0        CLOSED / UNIT-PROVEN
PR-PA-SR-B1        IMPL AUTHORIZED · NOT YET CLOSED
Pre-auth gate      A / PASS
PA-SR3             BLOCKED (B0 → B1 → field)
NOT                SourceOriginBridge before PRE-AUTH = A
NOT                retry / pre-bind retention / SR4–SR6 bundle / cutover
```

---

## References

- Field result (A1 re-run): `meeting-product-integration-phase-a-shadow-runtime-field-result-20260909-111359.md`
- RCA-A design: `meeting-product-integration-phase-a-shadow-runtime-rca-a-fix-design.md`
- Origin gap map: `meeting-profile01-media-key-package-origin-hook-gap-map.md`
- Code: `MeetingProfile01AwareConferenceSessionMediaFactPort.kt` · `Profile01ConferenceMediaFactIngress.kt` · `MeetingProfile01FactWireIngress.kt` · `TalkbackCoordinator.kt` · `TalkbackRuntimeFactory.kt`
