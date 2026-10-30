# ADR-0057 Track C — Post-Accept PC / ICE Lineage Seam Audit

## Status

**READ-ONLY SEAM AUDIT COMPLETE** (2026-08-31)

```text
F7 — Post-Accept PC / ICE Lineage Desynchronization

Classification:  CONFIRMED IMPLEMENTATION GAP
Domain:          ICE / Media Execution

Not:
  signaling completion gap (A)
  readiness projection gap (C)
```

**Field evidence:** [Post-accept media readiness residual audit](./adr0057-post-accept-media-readiness-residual-audit.md)  
**Run:** `talkback/logs/adr0057-track-c-case-a-20260831-173143/`

**Frozen invariant violated:**

```text
An answer MUST be applied to the same logical
PeerConnection lineage that originated the offer it answers.
```

**Implementation:** NOT AUTHORIZED

**Next:** [GPLB narrow authorization](./adr0057-group-pc-lineage-binding-narrow-authorization.md) (contract frozen; wire correlation sign-off blocking)

---

## Audit objective

Locate the three bindings the GROUP mesh path lacks:

```text
Offer        → PC lineage
Answer       → originating offer lineage
ICE candidate → matching PC lineage
```

Scope limited to:

```text
GROUP_INVITE offer lineage
→ GIDR retry-created PC
→ GROUP_ACCEPT apply target
→ ICE candidate apply target
```

---

## Seam 1 — Outbound offer ownership

### What is recorded today

On successful GROUP_INVITE wire handoff, `recordInvitingAfterSuccessfulGroupInviteHandoff` writes session-scoped `OutboundGroupInviteAttempt`:

| Field | Recorded | Used on GROUP_ACCEPT |
|-------|----------|----------------------|
| `offerLineageId` (`GM{N}`) | Yes — wire + session | **No** |
| `deliveryAttemptId` | Yes (always `1` on stamp) | **No** |
| `sessionId` | Yes | Session lookup only |
| `remoteModuleId` | Yes | Peer key only |
| `semantic` | Yes | GIDR/admission only |
| `issuedAtMs` | Yes | Staleness only |
| `pcGeneration` | **No** | — |
| engine identity / PC hash | **No** | — |

Wire payload (`stampGroupMeshInvitePayload`) stamps `offerLineageId` and `deliveryAttemptId=1`.  
It does **not** stamp `realizationOfferLineageId`, `transportGeneration`, or `pcGeneration`.

```3078:3108:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        val offerLineageId = "GM${groupMeshOfferLineageSeq.incrementAndGet()}"
        return StampedGroupInvitePayload(
            payload = withSdp.copy(
                offerLineageId = offerLineageId,
                deliveryAttemptId = 1L
            ),
            semantic = semantic
        )
    ...
        OutboundGroupInviteAttemptSupport.recordSuccessfulHandoff(
            session = session,
            remoteModuleId = remoteModuleId,
            sessionId = session.id,
            semantic = stamped.semantic,
            offerLineageId = lineageId,
            deliveryAttemptId = stamped.payload.deliveryAttemptId,
            issuedAtMs = invitedAtMs
        )
```

### Verdict

`offerLineageId` exists for **GIDR delivery obligation** (#180 F2), not for **media PC binding**.  
`pcGeneration` is logged at offer time via `mediaRegistry.meshSessionState` elsewhere but is **not persisted** on the attempt record and is **not consulted** when GROUP_ACCEPT arrives.

**Binding gap:** Offer → PC lineage is **not captured** in durable state.

---

## Seam 2 — GIDR retry vs outstanding answer expectation

### Retry mechanics

GIDR (`deliveryRetryInvitees` → `dispatchGroupInviteAfterEngineProvision`) permits a new wire when:

```text
prior attempt TERMINAL (timeout) OR absent
AND peer admission incomplete
```

Each retry:

1. `session.meshCompletedModules.remove(moduleId)`
2. `mediaRegistry.requestGroupEngine(...)` — may close/recreate PC (`pcGeneration++`)
3. `engine.createOffer()` on **new** engine
4. `recordSuccessfulHandoff` — **overwrites** single `outboundGroupInviteAttemptsByRemoteModule[peer]` entry

```3390:3448:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        session.meshCompletedModules.remove(moduleId)
        ...
        mediaRegistry.requestGroupEngine(
            remoteModuleId = moduleId,
            ...
            onReady = { engine ->
                ...
                    val offer = engine.createOffer(iceRestart = iceRestartForOffer)
                    ...
                    recordInvitingAfterSuccessfulGroupInviteHandoff(
                        live,
                        moduleId,
                        stamped,
```

`MediaSessionManager.requestEngine` on reuse closes the prior non-CLOSED entry and provisions a new generation:

```102:129:talkback/android-board-talkback/src/main/java/com/talkback/core/media/MediaSessionManager.kt
        if (existing != null && !IceConnectivity.isClosed(existing.iceState)) {
            reuseViolations++
            ...
            entries.remove(moduleId)
            ...
            deliverProvisionedEngine(moduleId, scope, "mediaSessionReuse", onReady)
```

```560:562:talkback/android-board-talkback/src/main/java/com/talkback/core/media/MediaSessionManager.kt
    private fun provisionNew(moduleId: String, scope: MediaBearerScope): ProvisionedEngine {
        val generation = (generationByModule[module] ?: 0L) + 1L
```

### Outstanding answer expectation

When attempt #N goes stale (`ATTEMPT_STALE_TIMEOUT_MS = 10s`), GIDR may dispatch attempt #N+1.  
The local attempt record for #N is terminal, but:

- Remote may still hold INVITE #N SDP and answer it (late peer admission — exactly Case A)
- Local has **no** mechanism to route that answer to the PC that sent #N
- `deliveryAttemptId` is always `1` on restamp — **no per-retry attempt id** on wire

### Verdict

GIDR correctly models **delivery obligation** retry.  
It does **not** preserve or correlate **signaling obligation** across PC generation churn.

**Binding gap:** Stale attempt termination ≠ invalidation of remote answer to prior offer PC.

---

## Seam 3 — GROUP_ACCEPT ingress engine selection (primary defect seam)

### GROUP path (Case A)

```10789:10836:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        val engine = getOrCreateMeshEngine(session, moduleId)
        ...
        engine.applyRemoteAnswer(signal.payload, politeForMeshPair(moduleId))
        drainPendingIce(session.id, moduleId, engine)
```

`getOrCreateMeshEngine` resolves:

```1113:1141:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        val existing = meshEngineForSession(session, moduleId)
        if (existing != null) return existing
        ...
        val created = meshEngineFor(session, moduleId)  // mediaRegistry.groupEngine → new PC
```

`meshEngineForSession` → `mediaRegistry.getGroup(moduleId)` — **current** engine for peer, keyed only by `moduleId`.

### CONFERENCE path (contrast — has lineage)

Conference GROUP_ACCEPT uses `ConferenceRealizationLineage.correlate` against `lastConferenceOfferLineageByEdge` before `applyRemoteAnswer`:

```10802:10831:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
            val hostLocal = lastConferenceOfferLineageByEdge["${session.id}|$moduleId"]
            val correlation = ConferenceRealizationLineage.correlate(
                parsed.offerLineageId,
                hostLocal
            )
            ...
            dispatchConferenceAcceptMedia(...)
```

GROUP path has **no equivalent** `lastGroupOfferLineageByEdge` and **no correlation gate**.

### Verdict

Confirmed defect seam:

```text
GROUP_ACCEPT(peer)
→ getOrCreateMeshEngine(peer)   // current PC
→ applyRemoteAnswer(rawSdp)
```

**"Current PC" ≠ "PC that originated the offer the answer settles."**

This matches field evidence: offer on `pcGeneration=4`, answer applied on `pcGeneration=5`.

---

## Seam 4 — Answer correlation across attempts

### Outbound (offerer receiving GROUP_ACCEPT)

`handleGroupAccept` for GROUP:

- Calls `markDeliverySatisfiedFromGroupAccept(session, moduleId)` — **peer-only**, ignores `offerLineageId`
- Does not parse GROUP_ACCEPT payload for lineage (GROUP accept is **raw SDP**, not `ConferenceRealizationLineage.encodeAnswer`)

### Inbound (answerer sending GROUP_ACCEPT)

IGIE `completeGroupAcceptWithEngine` encodes accept via:

```2978:2983:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
                encodeConferenceAcceptAnswer(payload, answer)
```

`encodeConferenceAcceptAnswer` carries `payload.realizationOfferLineageId` — but GROUP mesh INVITE stamps only `offerLineageId` (`GM{N}`), **not** `realizationOfferLineageId`:

```2349:2351:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        val offerLineageId = payload.realizationOfferLineageId
            ?: ConferenceRealizationLineage.UNKNOWN
```

Answerer `applyRemoteOfferWithLineage` therefore logs `UNKNOWN` for group-mesh offers; accept wire cannot carry correlatable lineage back to offerer.

### Attempt map semantics

Single slot per peer: `outboundGroupInviteAttemptsByRemoteModule[remoteModuleId]`.  
Retry overwrites prior `offerLineageId`. No multi-attempt table, no stale-accept rejection.

### Verdict

**No answer → offer lineage binding** on GROUP path.

| Scenario | Handled? |
|----------|----------|
| ACCEPT for INVITE #1 after local retry to #4 | **No** — applies to current PC |
| ACCEPT for stale INVITE after timeout | **No** — may satisfy GIDR delivery only |
| ACCEPT with wrong `GM` id | **No** — id not checked |

---

## Seam 5 — ICE candidate binding

### Ingress

```11144:11180:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
    private fun handleIce(signal: SignalEnvelope) {
        ...
        val engine = when (session.type) {
            SessionType.UNICAST -> mediaRegistry.getUnicast(session.id)
            else -> meshEngineForSession(session, moduleId)
        }
        ...
            queuePendingIce(signal.sessionId, moduleId, signal.payload)
        ...
        engine.addIceCandidate(signal.payload)
```

### Queue / drain

```14590:14604:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
    private fun queuePendingIce(sessionId: String, moduleId: String, candidate: String)
    ...
    private fun drainPendingIce(sessionId: String, moduleId: String, engine: WebRtcAudioEngine) {
        ...
        pending.forEach { candidate ->
            engine.addIceCandidate(candidate)
```

Keys: `(sessionId, moduleId)` only. No `pcGeneration`, no `offerLineageId`, no attempt id.

### Verdict

ICE candidates route to **current engine for peer**, same as GROUP_ACCEPT.  
Late candidates from prior INVITE attempts drain onto whichever PC is current at SRD/accept time.

**Binding gap:** ICE candidate → matching PC lineage is **not enforced**.

---

## Three binding summary

| Binding | Conference path | GROUP mesh path (Case A) |
|---------|-----------------|--------------------------|
| Offer → PC lineage | `lastConferenceOfferLineageByEdge` + `pcGeneration` in bind | **Missing** — only ephemeral engine at offer time |
| Answer → offer lineage | `ConferenceRealizationLineage.correlate` before SRD | **Missing** — raw SDP to current PC |
| ICE → PC lineage | Queued per edge; conference SDP gate | **Missing** — `(session, peer)` queue only |

---

## Root cause statement

```text
GIDR + GIE correctly retry delivery obligation across pcGeneration churn,
but GROUP_ACCEPT and ICE ingress use peer-scoped "current engine" resolution
without correlating to the offer-bearing PC generation.

Late peer admission (INVITE #4 answered after retries #1–3)
is the field trigger; the implementation gap is structural, not GIDR-specific.
```

---

## Forbidden (unchanged)

```text
ICE restart
force reconnect
new retry timer
disable pcGeneration churn
pin one PC forever
ignore stale GROUP_ACCEPT (blind drop)
blindly reuse current engine
re-open GIDR / IGIE / GIE semantics
```

---

## Formal status

```text
F4 / GIE:   CLOSED
F5 / IGIE:  CLOSED
F6 / GIDR:  CLOSED

F7 / Post-Accept PC / ICE Lineage Desynchronization:
  CONFIRMED IMPLEMENTATION GAP
  READ-ONLY SEAM AUDIT COMPLETE
  IMPLEMENTATION NOT AUTHORIZED

Case A E2E:        PARTIAL / OPEN
ADR-0057 COMPLETE: NO
```

---

## Next step (not authorized)

[GPLB narrow authorization](./adr0057-group-pc-lineage-binding-narrow-authorization.md) — contract frozen.

Blocking before implementation:

```text
Wire correlation: GROUP_ACCEPT + ICE must carry or provably resolve offerLineageId / pcLineage
Option A (preferred): echo offerLineageId on GROUP_ACCEPT
Must not infer lineage from SDP content
```

No code changes until wire option is signed off and implementation cut authorized.
