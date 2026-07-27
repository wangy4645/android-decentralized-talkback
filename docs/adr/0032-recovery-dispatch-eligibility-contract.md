# ADR-0032: Recovery Dispatch Eligibility Contract (R28-N) (ADR-CONF-009)

## Status

**Draft** (2026-07-26) 鈥?promote to **Accepted** after the implementation turns the red evidence set in 搂 8 green.

Complements **ADR-0022** (recovery completion ownership; R28 frozen 2026-07-26). Does **not** reopen the R28 freeze: ADR-0022 froze *who owns completion once recovery has happened*; this ADR freezes *which facts may admit a recovery action in the first place*. Does **not** redefine PRR (ADR-0022 搂 R28-PRR), link qualification (搂 R28-L.1), or discovery transport ownership.

Continues the `INV-REC-*` series of ADR-0022 at **INV-REC-010**.

## Summary

Case B soak `obs-r28-prr-caseb-20260726-205538` proved PRR and link qualification both correct while recovery still failed. Root cause is not a missing layer but a **plane provenance violation**: recovery action admission consumes media-plane facts, so the action that restores media requires media to already be restored.

```text
ICE disconnected
        鈫?mediaRouteConnected = false
        鈫?canDispatchRecoverySignal = false
        鈫?ICE restart / reattach not permitted
        鈫?ICE stays disconnected
```

This ADR freezes:

1. **Field provenance model** 鈥?every `EdgeReachabilitySnapshot` field declares its plane and its permitted consumers
2. **Dispatch eligibility contract** 鈥?initiation MUST NOT consume media-plane facts
3. **Completion eligibility contract** 鈥?completion MAY consume media-plane facts
4. **Waiting reason semantics** 鈥?dispatch and completion no longer share `WAITING_FOR_ROUTE`

```text
Plane            Fact                      May admit
鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
Transport        linkReady                 dispatch + completion
Discovery        peerDiscovered            dispatch + completion
Signaling        peerSignalingReachable    dispatch + completion
Media            mediaRouteConnected       completion only
Authority        authorityReachable        completion only
```

## 1. Context

### Case B evidence (`obs-r28-prr-caseb-20260726-205538`, M02 host, M03 WiFi flap)

| Layer | Gate | Result |
|---|---|---|
| PRR | G-PRR | **PASS** 鈥?peer observed the new transport epoch |
| Link qualification | G-L4-2 | **PASS** 鈥?`BIDIRECTIONAL_READY` reached |
| Recovery | G-R28 | **FAIL** 鈥?M02鈫扢03 closed via `MEMBERSHIP_LEFT` |

Observed edge asymmetry:

- **M01鈫扢03** reached `EDGE_RECOVERED` (M01 is a participant whose own view of host M02 never left `CONNECTED`, so every media-plane gate read `true` and all locks were bypassed).
- **M02鈫扢03** stayed `MEDIA_NOT_READY` / `WAIT_FOR_INBOUND` with no permitted action.
- **M03鈫扢02** logged `RECOVERY_REATTACH_DEFERRED reason=WAITING_FOR_ROUTE routeConverged=false`.

This confirms the invariant chain ADR-0022 already predicted, extended one step:

```text
PRR_EFFECT_ESTABLISHED  鈮? BIDIRECTIONAL_READY  鈮? EDGE_RECOVERED
```

### The rule already existed in code, without a normative home

`ConferenceEdgeRecoveryController` documents the intended contract in three places, attributed to `INV-REC-009`:

```text
Action gate for host ICE restart dispatch (ADR-0022 INV-REC-009).
MUST NOT require media routeConverged.
```

ADR-0022 `INV-REC-009` is about **post-terminal fact admission** and says nothing about media route. The rule was therefore never normative, never reviewable, and never enforced. This ADR gives it a home and corrects the citation.

## 2. Problem

R28's intended layering:

```text
Transport capability 鈫?Recovery eligibility 鈫?Recovery action 鈫?Media recovery
```

The implemented layering:

```text
Media state 鈫?Recovery eligibility 鈫?Media recovery action 鈫?Media state
```

The predicate that admits recovery actions is defined as:

```text
canDispatchRecoverySignal() = linkReady && peerDiscovered && routeConverged
routeConverged              = qosMonitor.isGroupConnected(remoteModuleId)
                            = IceConnectivity.isConnected(groupSnapshots[remote]?.iceState)
```

`routeConverged` is a media fact. Its own kdoc says `Do not use for recovery initiation`, yet it gates three initiation paths and both role branches of the capability projection.

### Four locks in series on one edge

A single edge is blocked four independent times by the same class of defect:

```text
Lock 2   participant cannot send reattach            (media route down)
Lock 3   host refuses the inbound reattach it did receive (media route down)
Lock 1   host cannot dispatch ICE restart            (media route down)
Lock 6   host cannot conclude RECOVERED              (authorityReachable is also a media fact)
```

Locks 2 and 3 form a two-node mutual deadlock: the host's `controlPlaneStarted` can only be set by an inbound reattach, and the inbound reattach is refused because media is down. Fixing any single lock produces no field-visible change.

## 3. Field provenance model

`EdgeReachabilitySnapshot` becomes a five-field record. Each field declares one plane; the plane determines who may consume it.

```kotlin
data class EdgeReachabilitySnapshot(
    val linkReady: Boolean,              // transport plane 鈥?channel readiness
    val peerDiscovered: Boolean,         // discovery plane 鈥?dialable address exists
    val peerSignalingReachable: Boolean, // signaling plane 鈥?recent inbound signal from peer
    val mediaRouteConnected: Boolean,    // media plane 鈥?mesh ICE connected
    val authorityReachable: Boolean      // authority plane 鈥?conference authority reachable
)
```

| Field | Source | Permitted consumers |
|---|---|---|
| `linkReady` | `resolveChannelReadiness(channelId) == READY` | dispatch, completion |
| `peerDiscovered` | `resolvePeerForModule(remoteModuleId) != null` | dispatch, completion |
| `peerSignalingReachable` | `isModuleReachable(moduleId, state)` 鈥?HELLO within `moduleStaleMs` | dispatch, completion |
| `mediaRouteConnected` | `qosMonitor.isGroupConnected(remoteModuleId)` | completion, materiality |
| `authorityReachable` | authority reachability (**see 搂 10 known gap**) | completion |

### `linkReady` keeps its name deliberately

`linkReady` is channel readiness, **not** `LinkQualificationState`. Renaming it to `linkQualificationReady` would pull the transport-scoped qualification state of R28-L.1 into a per-edge recovery snapshot and invert the frozen dependency direction:

```text
LinkQualificationTracker  鈫? BIDIRECTIONAL_READY  鈫? Recovery reads snapshot
```

Recovery MUST NOT absorb qualification state. Three distinct link-ish concepts therefore remain explicitly separate: channel readiness (`linkReady`), transport qualification (`linkQualificationSnapshot()`, read separately by the controller), and media route (`mediaRouteConnected`).

### `peerSignalingReachable` has an existing producer

No new layer is introduced. The fact already exists and is already consumed elsewhere (host re-invite gate). PRR contributes to it indirectly by causing inbound HELLO after an epoch transition; recovery reads the projection, never PRR state.

```text
PRR (epoch announcement)
        鈫?inbound HELLO observed 鈫?moduleLastHelloMs
        鈫?isModuleReachable  鈫? peerSignalingReachable   (projection)
        鈫?Recovery dispatch eligibility
```

## 4. Dispatch eligibility contract

```text
canAttemptRecovery()        = linkReady && peerDiscovered

canDispatchRecoverySignal() = linkReady && peerDiscovered && peerSignalingReachable
```

Applies to every initiation path: participant reattach send, host inbound reattach admission, host ICE restart dispatch, and both role branches of `projectRecoveryCapabilitySignature`.

Host ICE restart remains additionally gated by `controlPlaneStarted`. That is **not** a media dependency and stays in force:

```text
              PRR
               鈫?     peerSignalingReachable
               鈫?     Recovery dispatch eligibility
               鈫?     participant reattach  鈫? host REATTACH_ACCEPTED  鈫? controlPlaneStarted
               鈫?          ICE restart
               鈫?      mediaRouteConnected
```

## 5. Completion eligibility contract

Completion is an observation of success, not an action, so the media dependency is legitimate and MUST be retained:

```text
canCompleteRecovery() = linkReady
                     && peerDiscovered
                     && mediaRouteConnected
                     && authorityReachable
```

Note the shape change: completion is defined directly, no longer as `canDispatchRecoverySignal() && authorityReachable`. Deriving completion from the dispatch predicate is what conflated the two planes in the first place.

## 6. Waiting reason semantics

`WAITING_FOR_ROUTE` currently carries two meanings, which is why the field logs were misleading. The reason set is split by phase.

| Phase | Permitted waiting reasons |
|---|---|
| Dispatch | `WAITING_FOR_LINK`, `WAITING_FOR_DISCOVERY`, `WAITING_FOR_PEER_SIGNALING` |
| Completion | `WAITING_FOR_ROUTE`, `WAITING_FOR_AUTHORITY` |

`WAITING_FOR_PEER_SIGNALING` is new. `WAITING_FOR_ROUTE` is retained but narrowed to media convergence, and MUST NOT appear on any dispatch path.

## 7. Invariants

```text
INV-REC-010 鈥?Action eligibility MUST NOT depend on the capability it restores

Recovery action eligibility MUST NOT consume the media-plane state that the
action is intended to restore. ICE_DISCONNECTED MUST NOT prevent ICE restart
dispatch, reattach dispatch, or inbound reattach admission when transport,
discovery, and signaling reachability are valid.
```

```text
INV-REC-011 鈥?Field provenance

Every EdgeReachabilitySnapshot field MUST declare exactly one plane. An
eligibility predicate MUST NOT consume a field whose plane is at or above the
plane the predicate admits action on. Adding a field without declaring its
plane and permitted consumers is a boundary violation.
```

```text
INV-REC-012 鈥?Phase-scoped waiting reasons

A waiting reason MUST identify the phase that is blocked. Dispatch-phase
blocking MUST NOT be reported as WAITING_FOR_ROUTE, and completion-phase
blocking MUST NOT be reported as WAITING_FOR_PEER_SIGNALING.
```

```text
INV-REC-013 鈥?Eligibility tests MUST exercise the production predicate

An eligibility gate injected into a controller MUST NOT default to a
permissive constant in tests, and eligibility behaviour MUST NOT be verified
solely through ICE simulation harnesses. Every gate requires at least one test
wired to the same predicate the coordinator wires in production.
```

`INV-REC-013` exists because the violation survived the R28 freeze: the one controller-level gate had a `{ _, _ -> true }` test default, so no unit test ever exercised the production wiring.

### Citation correction

Three code comments attribute 鈥淢UST NOT require media `routeConverged`鈥?to ADR-0022 `INV-REC-009`. They MUST be re-pointed at **ADR-0032 INV-REC-010**. ADR-0022 `INV-REC-009` remains unchanged and continues to govern post-terminal fact admission only.

## 8. Test evidence

Red set captured 2026-07-26 before implementation. `com.talkback.core.session.*`: 232 tests, 6 failed 鈥?all intentional.

| Lock | Site | Test | State |
|---|---|---|---|
| 1 | `TalkbackCoordinator` wires `canDispatchRecoveryMediaAction` to `canDispatchRecoverySignal()` | `ConferenceEdgeRecoveryControllerTest.dispatchContract_host_mediaDown_stillDispatchesIceRestart` | red |
| 2 | `dispatchRecoveryReattachOutcome` participant send gate | `TalkbackCoordinatorIntegrationTest.conference_s13b_recoveryReattachProbeMarkers` | red (pre-existing) |
| 3 | host inbound reattach admission gate | `TalkbackCoordinatorIntegrationTest.conference_dispatchContract_hostMustNotDeferInboundReattachOnMediaRoute` | red |
| 4 | `projectRecoveryCapabilitySignature` participant branch | `RecoveryDispatchEligibilityContractTest.capability_participant_mediaDown_allowsReattachDispatch` | red |
| 5 | `projectRecoveryCapabilitySignature` host branch | `capability_host_mediaDown_controlPlaneStarted_allowsIceRestart`, `capability_host_mediaDown_noControlPlane_waitsForInboundNotRoute` | red |
| 鈥?| predicate definition | `dispatch_mustNotRequireMediaRoute`, `dispatch_waitingReason_mustNotBeRouteWhenOnlyMediaIsDown` | red |
| 6 | `authorityReachable` self-authority | none | **not pinned** 鈥?see 搂 10 |

Guard tests that MUST stay green throughout the fix, preventing over-correction:

- `completion_stillRequiresMediaRoute` 鈥?completion keeps its media dependency
- `attempt_stillBlockedByTransportAndDiscovery` 鈥?transport and discovery still block initiation

Lock 3's evidence is the strongest single artifact, because one run reproduces the whole two-node deadlock: the participant dispatched successfully (its own media route was up), the host received it and acknowledged receipt, then refused it purely on its own media state.

```text
RECOVERY_REATTACH_INBOUND remote=M01 deliveryState=RECEIVED senderAttempt=1
RECOVERY_REATTACH_RECEIPT to=M01 deliveryState=REMOTE_RECEIPT_ACKED
RECOVERY_REATTACH_INBOUND_DEFERRED remote=M01 deferredReason=MEDIA_NOT_READY
```

Three tests that had encoded the defect as required behaviour were removed from `EdgeReachabilitySnapshotTest`: `gate_s13bSoak_routeNotConverged_blocksRouteDependentDispatch`, `capability_participant_routeDown_waitsForRouteBeforeDispatch`, `capability_host_routeBlocked_staysWaitingForRoute`. Materiality coverage was preserved by re-expressing the transition over the discovery plane.

## 9. Legitimate media-plane reads

Not every `mediaRouteConnected` read is a violation. These stay:

| Site | Use | Verdict |
|---|---|---|
| `ROUTE_CONVERGED` trigger materiality | a trigger named for the route checking the route | keep |
| `ICE_RESTORED` trigger materiality | already reads `linkReady && peerDiscovered` only | keep |
| completion evidence naming | `ROUTE_CONVERGED` evidence label | keep |
| synthetic snapshot with `linkReady = iceUp` | fabricates a transport fact from ICE | **remove** |

## 10. Known gap 鈥?`authorityReachable`

`authorityReachable` is currently sourced from `isPeerMediaConnected(hostModuleId)`, which is a media fact, and on the host it evaluates the host against itself and is therefore structurally `false`. Combined with 搂 5 this means a host edge can never conclude `RECOVERED` through the capability path.

This is the same class of defect as locks 1鈥? but sits in **completion projection correctness**, not in the dispatch deadlock. It MUST NOT block this ADR's implementation.

```text
Known gap: authorityReachable self-authority semantics require a follow-up
test seam. Tracked separately; not a prerequisite for INV-REC-010.
```

## 11. Non-goals

- PRR scope, lifecycle, or facts (ADR-0022 搂 R28-PRR remains frozen)
- Link qualification state ownership or `BIDIRECTIONAL_READY` derivation (搂 R28-L.1)
- Discovery transport lifecycle (M-C)
- Recovery episode/attempt/obligation lifecycle (ADR-0022 R28-H/J/M)
- Relaxing completion requirements or forcing `FAILED` to make soaks pass
- The M01/M02 asymmetry as a separate defect 鈥?搂 1 explains it as all locks being bypassed, so it needs no separate investigation to justify this ADR

## 12. Implementation order (non-normative)

1. Split `EdgeReachabilitySnapshot` into five fields; add `WAITING_FOR_PEER_SIGNALING`
2. Re-source `peerSignalingReachable` from `isModuleReachable`; rename `routeConverged` 鈫?`mediaRouteConnected`
3. Redefine `canDispatchRecoverySignal()` and `canCompleteRecovery()` per 搂 4 / 搂 5
4. Remove the synthetic `linkReady = iceUp` snapshot (搂 9)
5. Re-point the three `INV-REC-009` citations at `INV-REC-010`
6. Replace the controller test harness `{ _, _ -> true }` gate default with production semantics (`INV-REC-013`)
7. Confirm the 搂 8 red set turns green and the guard tests stay green
8. Replay Case B on devices; expect `G-PRR` / `G-L4-2` / `G-R28` all PASS
9. Promote this ADR to Accepted; open the `authorityReachable` follow-up (搂 10)

## References

- [ADR-0022](./0022-recovery-completion-ownership.md) 鈥?recovery completion ownership, obligation lifecycle, R28-PRR, link qualification (R28 frozen)
- [ADR-0021](./0021-conference-edge-recovery-lifecycle.md) 鈥?edge recovery lifecycle
- Case B soak: `logs/obs-r28-prr-caseb-20260726-205538`
