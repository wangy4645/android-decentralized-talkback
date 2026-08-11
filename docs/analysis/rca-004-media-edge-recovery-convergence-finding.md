# RCA-004 鈥?Media Edge Recovery Convergence Finding (A1鈥揂3)

**Status:** **FINDING COMPLETE** (adjudicated)  
**Date:** 2026-08-11  
**Entry:** [rca-004-media-edge-recovery-convergence-audit-entry.md](./rca-004-media-edge-recovery-convergence-audit-entry.md)  
**Evidence:** `logs/rca003-ic-uvcp-gray-20260811-064426/` 路 gray-1 `062506`  
**Code:** `ConferenceEdgeRecoveryController` 路 `EdgeRecoveryModels` 路 `RecoveryNegotiationAuthority`  
**Next (optional):** [ADR-0050 Negotiation Admission Handoff](../adr/0050-negotiation-admission-handoff.md) 鈥?**ACCEPTED (semantics); impl NOT AUTHORIZED** (await product auth)

## Adjudication (frozen)

```text
RCA-004 Media Edge Recovery Convergence

Status:
  FINDING COMPLETE

Root class:
  Negotiation Admission / Dual-role Ownership mismatch

Not:
  WiFi
  ICE transport
  Phase-2 Delivery
  UVCP
  Edge key absence
  Edge-scoped ownership redesign (store already per-edge)
```

**Black-hole translation:** recovery media action can exist; **negotiation admission** does not follow the media-action actor 鈫?execution deadlock.

```text
Edge(P)
   鈹溾攢 Negotiation Owner = P (remote)  鈫?"you may not drive offer/ICE"
   鈹斺攢 Media Action Owner = Local      鈫?"I should HOST_RESTART"
        鈫?DEADLOCK
```

Finding-1 (`NON_OWNER_BLOCKED`) and Finding-2 (`NO_MEDIA_ACTION_OWNER` via PENDING) are **two surfaces of one mechanism**: recovery actor cannot obtain effective execution authority.

---

## Finding card

```text
Finding-1:
  M01鈫扢02 blocked by negotiation-owner 鈮?local at ICE restart dispatch
  (NEGOTIATION_NON_OWNER_BLOCKED; media-action may already be HOST_RESTART/DEFERRED)

Finding-2:
  M03鈫扢02 missing assigned media-action (stuck PENDING) because the same
  NEGOTIATION_NON_OWNER_BLOCKED returns before assignMediaActionOwner(HOST_RESTART);
  watchdog then classifies NO_MEDIA_ACTION_OWNER (!isAssigned includes PENDING)

Common invariant: YES
  On ICE_RESTART_ONLY inbound edges to the flapped peer, negotiation owner is
  elected as the remote (flapped) module; local actor cannot pass ICE-restart
  admission 鈫?inbound media action does not complete.
```

---

## A1 鈥?Ownership Binding (fact)

```text
Owner Scope:
[ ] Conference
[ ] Session
[x] Endpoint          鈥?remoteModuleId half of key; negotiation owner is a moduleId
[x] Edge              鈥?store key = ConferenceEdgeKey(sessionId, remoteModuleId)
[x] Attempt           鈥?mediaActionOwner / disposition live on EdgeRecoveryRecord
                        with recoveryAttemptId; obligationGeneration on same record
```

**Bind sites**

| Concept | Binding |
|---------|---------|
| Edge store | `ConcurrentHashMap<ConferenceEdgeKey, EdgeRecoveryRecord>` |
| Edge key | `(sessionId, remoteModuleId)` 鈥?**per local observer鈥檚 edge to remote** |
| Media-action owner | `EdgeRecoveryRecord.mediaActionOwner` enum (`HOST_RESTART` / `PARTICIPANT_REATTACH` / 鈥? + `mediaActionOwnerModuleId` in logs (defaults `localModuleId`) |
| Negotiation owner | `EdgeRecoveryRecord.canonicalNegotiationOwnerModuleId` (module id string) |
| Negotiation election key | `RecoveryNegotiationKey(sessionId, edgeModuleId, recoveryEpisodeId)` |

**Writer:** `assignMediaActionOwner(record, owner, 鈥?` mutates the **edge record**.  
**Not:** a single conference-global media-action owner object.

---

## A2 鈥?Obligation Granularity (fact)

```text
Episode / edge record:     per ConferenceEdgeKey (local view of remote)
Delivery obligation:       fields on same EdgeRecoveryRecord (reattach delivery, offer phase)
Media-action obligation:   mediaActionOwner + disposition on same record
obligationGeneration:      on EdgeRecoveryRecord; preserved across attempts while OPEN
```

Architecture observed:

```text
Each local device holds independent edges[sessionId, remote]:
  M01.edges[M02]  鈮? M03.edges[M02]  鈮? M02.edges[M01]
```

There is **no** shared cross-peer 鈥淩ecoveryEpisode(M02)鈥?object that closes all inbound edges together.  
Outbound CONNECTED on M02 does **not** clear or complete M01/M03鈥檚 edge records to M02.

---

## A3 鈥?Admission Predicate

### Case M01 鈥?`NEGOTIATION_NON_OWNER_BLOCKED`

**Predicate (code):** `issueBoundedIceRestart` 鈫?after gates:

```text
negotiationOwner = ensureCanonicalNegotiationOwner(...)
if (negotiationOwner != localModuleId) {
    log NEGOTIATION_NON_OWNER_BLOCKED
    return   // no ICE restart dispatch, no assignMediaActionOwner here
}
assignMediaActionOwner(HOST_RESTART)
// dispatch ice restart
```

**Why HOST_RESTART intent does not get admission**

1. `initiatesReattach=false` 鈫?`ICE_RESTART_ONLY`  
2. Bootstrap / resolve: with no existing wire owner, coordinator owner = **remote** when `!initiatesReattach` (`RecoveryNegotiationAuthority.bootstrapCoordinatorOwner`)  
3. Field: `existing_owner=M02` / `owner=M02 local=M01`  
4. Local may still log `RECOVERY_MEDIA_ACTION_ASSIGNMENT 鈥?HOST_RESTART` from `resolveMediaActionOwner` **before** `issueBoundedIceRestart`; that log is **not** the same as a successful `assignMediaActionOwner` past the negotiation check  
5. Deferred path (`recordMediaActionDeferred`) **does** set `mediaActionOwner=HOST_RESTART` without passing the negotiation check 鈥?so M01 can be 鈥渁ssigned but blocked from dispatch鈥?

**Semantic:** negotiation ownership (who may drive offer/ICE restart) 鈮?media-action enum claim. Local HOST_RESTART claim is rejected at **negotiation actor** gate.

### Case M03 鈥?`NO_MEDIA_ACTION_OWNER`

**Field sequence (`064426`):**

```text
EDGE_STARTED initiatesReattach=false  鈫?mediaActionOwner := PENDING
RECOVERY_MEDIA_ACTION_ASSIGNMENT HOST_RESTART
NEGOTIATION_NON_OWNER_BLOCKED owner=M02 local=M03
鈥?(no RECOVERY_ICE_RESTART_DISPATCHED / no OWNER_ASSIGNED ACTIVE)
timeout 鈫?EXPLICIT_ABORT NO_MEDIA_ACTION_OWNER
```

**Why 鈥渘o owner鈥?at timeout**

```text
PENDING.isAssigned() == false
assignMediaActionOwner(HOST_RESTART) only after negotiationOwner == local
NON_OWNER_BLOCKED returns earlier 鈫?record stays PENDING
watchdog:
  !mediaActionOwner.isAssigned() 鈫?abortReason = NO_MEDIA_ACTION_OWNER
```

So M03 is **not** 鈥渘ever tried to create an action鈥? it is **鈥渋ntent logged, admission denied, assign never committed, classified as missing owner.鈥?*

Hypothesis A/B from entry:

| Option | Result |
|--------|--------|
| A) no recovery action created | **Rejected** 鈥?EDGE_STARTED + ASSIGNMENT log exist |
| B) owner creation failed / not propagated | **Closer** 鈥?assign past negotiation gate never runs; stays PENDING |

---

## Common invariant

```text
YES 鈥?shared mechanism class:

Inbound ICE_RESTART_ONLY recovery toward flapped peer P:
  negotiationOwner elects P (remote)
  local L attempts ICE restart / HOST_RESTART
  L 鈮?negotiationOwner 鈫?NEGOTIATION_NON_OWNER_BLOCKED
  鈫?inbound edge media action does not complete
  鈫?ice stays FAILED/CHECKING (symptom)
  鈫?UI degraded is correct

Surface difference M01 vs M03:
  M01 often reaches DEFERRED/ASSIGNED HOST_RESTART then still blocked on dispatch
  M03 stays PENDING 鈫?timeout label NO_MEDIA_ACTION_OWNER
```

**Not claimed:** scope must become edge-scoped redesign (store is already per-edge; tension is **role split + admission**, not missing edge keys).

---

## What this is / is not

| Is | Is not |
|----|--------|
| Admission / dual-owner (negotiation vs media-action) seam | UVCP / RCA-003 regression |
| Why M02 is an inbound black hole while outbound CONNECTED | Proof to add retry / ICE timeout |
| Shared invariant across M01 & M03 signatures | Ready-made fix design |

---

## Gate for P2 (product-authorized only)

**Do not** open Edge-scoped ownership redesign (A1: store already per-edge).

Thin ADR (**0050** 鈥?ADR-0046 already taken):

> [ADR-0050 Negotiation Admission Handoff](../adr/0050-negotiation-admission-handoff.md) 鈥?**ACCEPTED (semantics); impl NOT AUTHORIZED** 路 Option **A** preferred 路 **INV-1..3** frozen

```text
INV-1  lease 鈮?ownership transfer
INV-2  lease = single edge + single recovery episode
INV-3  lease expiry 鈮?recovery failed
```

IC (when authorized): one ICE-restart admission gate only 鈥?not assignMediaActionOwner / Phase-2 / Completion / UVCP.

```text
No field soak 路 no Phase-2 路 no Delivery 路 no Ownership supersede reopen 路 no UVCP
```
