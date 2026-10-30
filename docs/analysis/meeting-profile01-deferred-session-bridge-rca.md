# RCA: Profile01 Deferred Fact — Meeting Session Bridge Gap

## Status

```text
RCA                          COMPLETE (read-only)
Classification               C — DEFERRED_IS_NOT_RETAINED (primary)
                             A — latent once retention exists (no drain hook today)
Verdict                      VERIFY_DECODE_OR_SESSION_BRIDGE_GAP
Implementation design slice  SIGNED OFF — v1 IMPLEMENTED (unit T1–T5)
Implementation code          AUTHORIZED — field rerun NEXT
ADR-0061 code                FREEZE
CREATION origin replay code  FREEZE
MEMBERSHIP                   HOLD
Phase A                      BLOCKED
```

**Design slice:** `meeting-profile01-pre-bind-fact-retention-drain-design-slice.md`

**Parent verdict taxonomy:** `meeting-product-integration-phase-a-origin-publisher-run-card.md` — `VERIFY_DECODE_OR_SESSION_BRIDGE_GAP`

**Field evidence (authoritative):** `talkback/logs/meeting-product-j0-creation-e2e-20260908-113711/`  
**session:** `0897503f-2afb-4601-b6bb-08a7c831668a`

---

## Problem statement

Peer receives `CONFERENCE_SIGNED_FACT` on the wire **before** the Meeting session bridge exists locally. Wire ingress records `DEFERRED_NO_SESSION` and returns. After `INVITE_ACCEPT → SESSION_CREATED → registerMeetingProfile01Session`, nothing re-drives the fact to `APPLIED`.

This is **not**:

- wire delivery gap (peer decoded the signal envelope)
- CREATION origin replay gap (host logged `PROFILE01_ORIGIN_REPLAY target=M02`)
- ADR-0061 media-engine continuation regression

This **is**:

> **Deferred Profile01 fact at peer ingress: who owns continuation after Meeting session bridge bind?**

Ownership is **fact ingress / session-binding continuation** — analogous in shape to ADR-0061 obligation drain, but a different layer and owner.

---

## Field timeline (M02 peer)

| Time | Event |
|------|-------|
| `11:39:03.505` | `CONFERENCE_SIGNED_FACT` envelope decoded (same `sessionId`) |
| `11:39:03.543` | `WIRE_INGRESS outcome=DEFERRED_NO_SESSION factType=null` |
| `11:39:16.848` | `T0_INVITE_ACCEPTED` / `ADMISSION_DECISION … INVITE_ACCEPTED` |
| `11:39:16.850` | `SESSION_CREATED writer=acceptGroupInvite` |
| post-Accept | No second `WIRE_INGRESS`; no `outcome=APPLIED` |

Host (M01) same run: `PROFILE01_ORIGIN_REPLAY reason=conference_invite_sent target=M02` at `11:39:03.612`.

---

## RCA question 1 — Is `DEFERRED_NO_SESSION` legal staging or silent abandonment?

**Answer: silent abandonment today.**

`MeetingProfile01FactWireIngress.onConferenceSignedFact` checks `sessionIndex.bindingForSession(signal.sessionId)` **before** decode. On miss it logs observability and returns immediately — no payload retention, no queue, no registry write:

```32:37:talkback/android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/MeetingProfile01FactWireIngress.kt
    fun onConferenceSignedFact(signal: SignalEnvelope): WireIngressOutcome {
        if (!MeetingProductMediaShadow.enabled) return WireIngressOutcome.SHADOW_DISABLED
        val sessionBinding = sessionIndex.bindingForSession(signal.sessionId)
        if (sessionBinding == null) {
            observability.record(WireIngressOutcome.DEFERRED_NO_SESSION, signal.sessionId, null)
            return WireIngressOutcome.DEFERRED_NO_SESSION
```

`factType=null` in field logs is expected: decode is skipped on this path.

**Naming mismatch:** Shadow run-card groups `DEFERRED_NO_SESSION` with coordinator `DEFERRED_NO_*` outcomes that **do** resume via hook replay once prerequisites arrive (`meeting-product-integration-phase-a-shadow-run-card.md`). Coordinator `DEFERRED_NO_FACT` is the inverse ordering (session hook before fact) and **does** recover when wire ingress later calls `replayAfterPublish`. Wire `DEFERRED_NO_SESSION` has no symmetric retention — the label reads like staging but behaves as drop.

Unit test `unknownSession_returnsDeferredNoSession` only asserts the early return; there is no test for post-register drain.

**I5 analogy:** ADR-0061 forbids silent abandonment of engine obligations without a live drain source. Shadow ingress currently violates the same *spirit* for pre-bind wire facts: terminal observability without continuation obligation.

---

## RCA question 2 — Pending storage and why bind does not drain

### Session index

`MeetingProfile01ConferenceSessionIndex` holds `Binding` per `sessionId` (channelId, rosterEpoch, conferenceId, mediaConnectedModules). It has **no** pending-fact storage.

`registerSession` is a pure map insert; it does not invoke wire ingress or coordinator hooks.

### Fact registry

`ConferenceSessionMediaControlFactRegistry` is populated only **after** wire ingress decode → verify → publish. Pre-bind facts never reach the registry.

### Peer bind site

On `acceptGroupInvite`, peer calls `registerMeetingProfile01Session(session)` at session create — and **nothing else** in the Profile01 shadow chain:

```9564:9572:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        if (sessionType == SessionType.CONFERENCE) {
            registerMeetingProfile01Session(session)
            auditConferenceSessionLifecycle(
                event = "SESSION_CREATED",
                session = session,
                writer = "acceptGroupInvite",
                cause = "participant_invite_accepted",
                creationSource = "INVITE_ACCEPT"
            )
```

Compare host create path (`meshCallInternal`), which **also** calls `ConferenceSessionMediaCoordinatorDelegate.onConferenceSessionStarted` immediately after register — peer accept path never does (only host at line 3947).

### Existing replay machinery (downstream only)

Once a fact passes the session-index gate, ingress already replays coordinator hooks:

- `replayAfterPublish` → `replaySessionStarted` for CREATION
- `replayAfterMembership` → session started + member media ready

These paths are **never reached** for pre-bind facts because the early return discards the signal.

### Host `CreationOriginBridge`

Host-side `MeetingProfile01CreationOriginBridge` caches signed bytes and replays **outbound** publication to peers. That is send-side origin replay — not peer ingress continuation. Under current discipline it remains **FROZEN** and is not the peer bind drain owner.

---

## RCA question 3 — Existing events for safe deferred replay?

| Event / seam | Present? | Drain today? | Notes |
|--------------|----------|--------------|-------|
| `CONFERENCE_SIGNED_FACT` RX | Yes | N/A (dropped pre-bind) | Would need retention at ingress |
| `registerMeetingProfile01Session` | Yes (INVITE_ACCEPT) | **No** | Natural bind seam; no callback into wire ingress |
| `SESSION_CREATED` audit | Yes | No | Observability only |
| `onConferenceSessionStarted` | Host only on create | Peer never called on accept | Even if called post-bind, registry empty without prior ingress apply → would log `DEFERRED_NO_FACT` |
| `replayAfterPublish` / `replaySessionStarted` | Yes (in ingress) | Only after successful apply | Reusable **after** retained fact is re-driven through ingress |
| Host `PROFILE01_ORIGIN_REPLAY` | Yes | Outbound resend | CREATION replay **FREEZE**; different ownership |

**Conclusion:** No existing peer-side event completes the continuation. The closest safe hook is **`registerMeetingProfile01Session` immediately after INVITE_ACCEPT session materialization**, but it requires **new** retention at wire ingress (or equivalent) first.

### Fencing / exactly-once constraints (for future narrow fix — not authorized here)

Any continuation design should preserve:

1. **Same-session:** drain only for matching `sessionId` at bind time.
2. **Generation fenced:** discard if session superseded / unregistered before drain (`unregisterMeetingProfile01Session` already clears index).
3. **Exactly-once:** consume retained fact on drain; idempotent publish path (`ACCEPTED` / `IDEMPOTENT`) already exists in `recordPublishAndReplay`.
4. **No coordinator timer/retry loop** — mirror ADR-0061: one live drain source at bind, not polling.

---

## Classification

| Candidate | Verdict | Rationale |
|-----------|---------|-----------|
| **A. SESSION_BRIDGE_DRAIN_GAP** | Latent / secondary | No pending storage → nothing to drain. Once retention exists, missing bind hook is the drain gap. |
| **B. SESSION_INDEX_ORDERING_GAP** | Rejected | Not a ordering race on an existing replay trigger — **no replay trigger is wired** at register. |
| **C. DEFERRED_IS_NOT_RETAINED** | **Primary** | `DEFERRED_NO_SESSION` is observability for immediate drop; fact bytes are not kept. |

**Working label:** `SESSION_BRIDGE_DRAIN_GAP` at product level; **root mechanism:** `DEFERRED_IS_NOT_RETAINED` + missing bind continuation.

---

## Asymmetry (host vs peer)

```text
Host CREATE:
  registerMeetingProfile01Session
  → onConferenceSessionStarted        (DEFERRED_NO_FACT until wire fact)
  → wire fact arrives → APPLIED → replaySessionStarted

Peer INVITE_ACCEPT:
  registerMeetingProfile01Session
  → (no onConferenceSessionStarted)
  → wire fact may have already arrived → DEFERRED_NO_SESSION → lost
  → Accept does not re-enter ingress
```

Peer E2E CREATION requires **both** retention at ingress and bind-time re-drive (or equivalent host resend — frozen).

---

## Discipline (unchanged)

```text
ADR-0061 code          FREEZE
CREATION replay code   FREEZE
MEMBERSHIP             HOLD
Phase A                BLOCKED until peer WIRE_INGRESS CREATION APPLIED
```

## Status board (post J0 + RCA)

```text
ADR-0061                    FIELD PROVEN / CLOSED FOR THIS GAP
Meeting release/quiescence  CLOSED
CREATION peer E2E           BLOCKED BY SESSION-BRIDGE GAP
J0 operational chain        PASS
J0 end-to-end               NOT PASS
MEMBERSHIP                  HOLD
Phase A                     BLOCKED
```

## Next step

**Design slice AUTHORIZED:** `meeting-profile01-pre-bind-fact-retention-drain-design-slice.md` — retain+drain **FIELD PROVEN**; silent-drop **CLOSED**.  
**Apply-completeness RCA:** `meeting-profile01-creation-apply-completeness-rca.md` — **C REQUIRED_PREDECESSOR_MISSING**; no 32→16 patch authorized.
