# ADR-0057 Track C — Inbound Group Invite Execution Residual Audit (F5 candidate)

## Status

**CONFIRMED — IMPLEMENTATION CUT AUTHORIZED** (2026-08-31)

```text
F5 / Inbound Group Invite Execution:
  CONFIRMED IMPLEMENTATION GAP
  IMPLEMENTATION CUT AUTHORIZED

See: adr0057-inbound-group-invite-execution-narrow-authorization.md
```

---

## Context

Outbound GIE (F4) is **CLOSED**: late-peer admission → deferred provision → drain → valid `GROUP_INVITE` on wire → peer received.

Post-invite residual on M03 (follower / late peer):

```text
GROUP_INVITE received (valid SDP, ~2KB)
→ acceptGroupInvite entered
→ sync meshEngineFor / acquireMeshEngine throws
→ Coordinator error swallowed
→ engine provisions async ~150–400ms later
→ NO GROUP_ACCEPT / NO SRD / NO resume
→ waitingForPrimary=true, transmitReady=false (entire idle window)
```

This is **not** an ICE-first diagnosis. ICE candidates arrive and queue, but answerer SDP path never starts.

---

## Three audit questions

### Q1 — Is `Synchronous mesh engine create pending…` transient, or does it lose inbound intent?

**Answer: intent is lost (functional failure), not merely a diagnostic.**

| Observation | Evidence |
|-------------|----------|
| Exception is swallowed | `runOnCoordinator` catches and logs `Coordinator error: …` — no retry |
| Session partially committed | `acceptGroupInvite` creates session + `enterChannelMode` **before** `acquireMeshEngine` |
| Accept aborts before SDP | `applyRemoteOfferWithLineage` never runs |
| Engine later ready | `MEDIA_PROVISIONED` ~412ms after error — **no** follow-on accept |
| Wire outcome | **Zero** `GROUP_ACCEPT`, `GROUP_ACCEPT_HANDOFF`, `Group invite accepted` on M03 |
| End state | `waitingForPrimary=true`, `transmitReady=false` for full ~5min window |

The sync throw is **short-lived** (~7ms until release completes), but the **inbound invite accept transaction does not survive it**. This is the same obligation-loss pattern as pre-GIE outbound F4, on the answerer side.

### Q2 — Does inbound still use sync `acquireMeshEngine` (F4-symmetric coupling)?

**Answer: YES — GROUP inbound has no async engine-ready seam.**

| Path | Engine acquisition |
|------|-------------------|
| **Outbound GIE (fixed)** | `requestGroupEngine` + `onReady` → wire |
| **Inbound GROUP fresh accept** | `acceptGroupInvite` → `acquireMeshEngine` → `meshEngineFor` → `mediaRegistry.groupEngine` → **sync** `create()` |
| **Inbound GROUP reconnect** | `acceptGroupInviteReconnect` → `getOrCreateMeshEngine` → same **sync** `meshEngineFor` |
| **Inbound CONFERENCE (contrast)** | `beginConferenceAcceptWhenEngineReady` → `requestConferenceEngine` + `completeConferenceAcceptWithEngine` |

Code seams:

- `TalkbackCoordinator.acceptGroupInvite` (~9121): `acquireMeshEngine` then immediate `applyRemoteOfferWithLineage`
- `TalkbackCoordinator.acceptGroupInviteReconnect` (~9224): `getOrCreateMeshEngine` → sync `meshEngineFor`
- `SessionMediaRegistry.groupEngine` → `MediaSessionManager.create` → sync `requestEngine` or throw

Conference already has the correct pattern (`beginConferenceAcceptWhenEngineReady`). GROUP inbound does not.

### Q3 — After engine becomes ready, is there execution opportunity to resume the original invite?

**Answer: NO — resume requires a **new** `GROUP_INVITE` (and reconnect path hits the same sync trap).**

Field timeline (M03 ← M01, first invite):

```text
16:37:35.164  GROUP_INVITE received (M01, GM4, 2040B)
16:37:35.205  acceptGroupInvite → CHANNEL_MODE GROUP_PTT
16:37:35.208  MEDIA_RELEASE_DEFERRED_CREATE module=M01
16:37:35.212  Coordinator error: Synchronous mesh engine create pending… M01
16:37:35.219  RELEASE_COMPLETE → PENDING_DRAIN
16:37:35.624  MEDIA_PROVISIONED module=M01 (engine ready)
              (no GROUP_ACCEPT, no SRD, no applyRemoteOffer)
16:37:35.640  Waiting for primary M01… waitingForPrimary=true
```

Second invite (M03 ← M02):

```text
16:37:41.737  GROUP_INVITE received (M02, GM1)
16:37:41.763  MEDIA_RELEASE_DEFERRED_CREATE module=M02  (reconnect/getOrCreate path)
16:37:41.766  Coordinator error: sync pending… M02
16:37:41.912  MEDIA_PROVISIONED module=M02
              (still no GROUP_ACCEPT / SRD)
```

No lifecycle-completion hook re-dispatches the retained invite payload. Duplicate invites do not complete accept after engine ready.

---

## Classification proposal

```text
F5 — Inbound Group Invite Execution Lifecycle Coupling

Classification:
  CONFIRMED IMPLEMENTATION GAP (symmetric to F4/GIE)

Admission:
  PASS (invite received, parsed, accept entered)

Engine execution:
  BLOCKED (sync acquire during async release)

Wire outcome:
  FAIL (zero GROUP_ACCEPT from answerer)

Blocking dependency:
  acceptGroupInvite / acceptGroupInviteReconnect
  require synchronous meshEngineFor
  while MediaSessionManager async release is pending

Downstream:
  transmitReady=false is EXPECTED consequence — do not score first
```

---

## Invariant sketch (pre-authorization)

Mirror GIE pattern on answerer side:

| ID | Intent |
|----|--------|
| **IGIE-T1** | Once inbound GROUP invite accept/admission starts, sync engine unavailability must **not** discard the invite accept obligation |
| **IGIE-T2** | Must not fabricate / partial-apply SDP before engine ready |
| **IGIE-T3** | Release ordering preserved — no force-overlap |
| **IGIE-T4** | Duplicate invites must not burst GROUP_ACCEPT; must not permanently suppress deferred accept |

Reuse seam: `beginConferenceAcceptWhenEngineReady` pattern → `beginGroupAcceptWhenEngineReady` (name TBD).

---

## Not in scope for F5 audit

- ICE retry policy
- `transmitReady` semantics
- Membership / `waitingForPrimary` policy (M03 follower posture is legal; separate from accept failure)
- Outbound GIE (closed)
- CSO / ACQ / generation

---

## Recommended next step

Draft **narrow authorization** for inbound accept lifecycle deferral (F5 / IGIE), with field gates:

```text
IGIE-EG1: invite received + engine available → GROUP_ACCEPT + SRD
IGIE-EG2: release pending → defer, zero malformed accept, intent retained
IGIE-EG3: release complete → resume accept → exactly one valid GROUP_ACCEPT
IGIE-EG4: duplicate invite → no accept burst
IGIE-EG5: satisfied accept → no duplicate GROUP_ACCEPT on later events
IGIE-EG6: ADR-0057 + release-ordering regression PASS
```

Field re-run criterion (Case A): M03 receives `GROUP_INVITE` → **`GROUP_ACCEPT` on wire** (then separately score media).
