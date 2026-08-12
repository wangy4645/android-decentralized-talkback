# ADR-0052 Scope Consumer Inventory

**Purpose:** Enumerate all readers/writers of GROUP-scoped QoS before `NetworkQualityMonitor` scope split.  
**Status:** PROPOSED companion to ADR-0052 (2026-08-12). **No code changes in this document.**

**APIs in scope:**

| API | Current meaning |
|-----|-----------------|
| `qosMonitor.snapshot(moduleId)` | `groupSnapshots[moduleId]` only |
| `qosMonitor.isGroupConnected(moduleId)` | GROUP snapshot CONNECTED |
| `qosMonitor.updateIceState` / `updateGroupIceState` | Writes GROUP table |
| `onMeshIceStateChanged` | GROUP + CONFERENCE ICE → GROUP table |
| `qosSnapshotForModule` / `TalkbackRuntime.qosSnapshotForModule` | Delegates to `snapshot(moduleId)` |
| `iceStateForModule` | `snapshot(moduleId)?.iceState` |

**Legend — required scope after ADR-0052:**

| Tag | Meaning |
|-----|---------|
| **G** | Must read/write `MediaBearerScope.GROUP` |
| **C** | Must read/write `MediaBearerScope.CONFERENCE` |
| **U** | Unicast (`snapshotUnicast` / `isUnicastConnected`) — already isolated |
| **M** | Context-dependent — needs scope parameter or session-type branch |
| **P1** | Phase 1 migration priority (conference wedge / recovery) |
| **P2** | Phase 1 but GROUP-only — verify no regression |
| **P3** | Later / logging-only |

---

## Writers

| Location | API | Current | Target | Phase |
|----------|-----|---------|--------|-------|
| `NetworkQualityMonitor.kt:20-25` | `updateGroupIceState` | G write | Keep G | P2 |
| `NetworkQualityMonitor.kt:29-35` | `updateUnicastIceState` | U write | Keep U | — |
| `NetworkQualityMonitor.kt:70-71` | `updateIceState` | G write (alias) | Deprecate or scope-param | P1 |
| `TalkbackCoordinator.kt:9973` | `onMeshIceStateChanged` | G+C events → G | Split by `MediaBearerScope` from callback | **P1** |
| `TalkbackCoordinator.kt:9947` | `onUnicastIceStateChanged` | U write | Keep U | — |
| `TalkbackCoordinator.kt:4044` | test/simulate mesh | G write | Keep G | P3 |
| `TalkbackCoordinator.kt:4114` | test force CONNECTED | G write | Keep G | P3 |
| `TalkbackCoordinator.kt:811` | `acquireMeshEngine` | `resetGroup` | Also reset conference snapshot when replacing PC | P1 |
| `TalkbackCoordinator.kt:7844,8076,8264` | hangup / release | `resetRemote`/`resetGroup` | Scope-aware reset | P1 |

---

## Conference-critical consumers (Phase 1 — must migrate to **C**)

| File | Function / context | Line(s) | Gate / purpose | Phase |
|------|-------------------|---------|----------------|-------|
| `TalkbackCoordinator.kt` | `maybeLogConferenceRuntimeDecision` | 2773, 2813 | Log `hostIce=` | P1 |
| `TalkbackCoordinator.kt` | `auditReadinessBinding` | 3014 | Audit `hostIce` | P1 |
| `TalkbackCoordinator.kt` | `auditAuthorityTransition` | 3044-3050 | Audit `hostIceState` | P1 |
| `TalkbackCoordinator.kt` | `shouldDeferConferenceFullMesh` | 8787-8792 | Mesh defer until host link | **P1** |
| `TalkbackCoordinator.kt` | `isPeerMediaConnected` | 10254-10255 | UI + transmit + authority | **P1** |
| `TalkbackCoordinator.kt` | `isConferenceUiReady` | 10275-10283 | via `isPeerMediaConnected` | **P1** |
| `TalkbackCoordinator.kt` | `conferenceUiReadyBlockReason` | 2917 | via `isPeerMediaConnected` | P1 |
| `TalkbackCoordinator.kt` | `isConferenceAuthorityReachable` | 2860 | via `isPeerMediaConnected` | P1 |
| `TalkbackCoordinator.kt` | `scheduleConferenceHostLinkKick` | 10631-10637 | Kick if not connected | **P1** |
| `TalkbackCoordinator.kt` | `attemptConferencePeerOffer` path | 10707+ | Uses engine + prior gates | P1 |
| `TalkbackCoordinator.kt` | `buildRecoveryEdgeReachabilitySnapshot` | 5183 | `mediaRouteConnected` | **P1** |
| `TalkbackCoordinator.kt` | `ConferenceEdgeRecoveryController` inject | 453-454 | `isIceConnected` | **P1** |
| `TalkbackCoordinator.kt` | `conferenceBarrierSnapshot` | 8365 | `isIceConnected` diag | P1 |
| `TalkbackCoordinator.kt` | `connectedConferencePeerIds` | 8357 | via `isPeerMediaConnected` | P1 |
| `TalkbackCoordinator.kt` | `wireIceCallback` remoteTrack log | 9861 | Misleading `ice=` in log | P1 |
| `TalkbackCoordinator.kt` | `handleGroupJoin` recovery ingress | 6675, 6727 | `localIce` for glare/throttle | M / P1 |
| `TalkbackCoordinator.kt` | `reconnectConferenceMeshToOtherPeers` | 5942 | `isGroupConnected` filter | P1 |
| `TalkbackCoordinator.kt` | `onMeshIceStateChanged` CONNECTED branch | 10046-10055 | `completeGroupMesh` after host ICE | **P1** |
| `TalkbackCoordinator.kt` | `scheduleHostRejoinRetry` | 10895 | host rejoin gate | P1 |
| `TalkbackCoordinator.kt` | `isCurrentSpeakerReachable` area | 10958-10962 | host ICE check | P1 |
| `TalkbackCoordinator.kt` | `sessionPreferenceScore` | 10221-10224 | Conference session pick | P1 |
| `TalkbackCoordinator.kt` | `isConferenceHostLinkStable` | 11419 | host ICE gate | P1 |
| `TalkbackRuntime.kt` | `qosSnapshotForModule` | 303-304 | Public API | **P1** |
| `TalkViewModel.kt` | `logReachabilityProbe` | 1585, 1592 | `hostIce`, `iceConnectionState` | P1 |
| `TalkViewModel.kt` | endpoint QoS display | 1145 | UI ice display | M |
| `ConferenceRuntimeProjectionLogger.kt` | `formatDecision` | 84, 110 | Log field | P1 |
| `ConferenceAuditTimelineLog.kt` | readiness / authority audit | 244-284 | Log fields | P3 |
| `ConferenceBootstrapDeferral.kt` | `shouldDeferFullMesh` | 16-20 | Input `hostIceConnected` | P1 (caller fixes source) |

---

## GROUP PTT consumers (stay **G** — regression watch)

| File | Function / context | Line(s) | Purpose | Phase |
|------|-------------------|---------|---------|-------|
| `TalkbackCoordinator.kt` | `iceStateForModule` | 8817-8821 | Membership active members | P2 |
| `TalkbackCoordinator.kt` | `activeMemberModuleIds` | 8820-8821 | GROUP roster | P2 |
| `TalkbackCoordinator.kt` | `GroupMembershipSupport` callers | 8880, 12982, 13165 | Topology / health | P2 |
| `TalkbackCoordinator.kt` | `groupMeshReconciler` / stuck checking | 10395, 10415 | GROUP mesh recovery | P2 |
| `TalkbackCoordinator.kt` | `isSessionTransmitReady` (GROUP) | 10308 | GROUP transmit | P2 |
| `TalkbackCoordinator.kt` | `transmitPeerIds` / mesh retry | 8013, 8105, 8134 | GROUP mesh | P2 |
| `TalkbackCoordinator.kt` | `onMediaLinkLost` / reconnect | 11868, 11902, 11904 | GROUP reconnect | P2 |
| `TalkbackCoordinator.kt` | floor / backup standby | 11551, 11558 | GROUP conference aux | M |
| `TalkbackCoordinator.kt` | meeting invite dispatch | 1809, 1826, 1880 | Invitee ICE for MEETING_START | M |
| `TalkbackCoordinator.kt` | `dispatchMeetingInvites` reconnect | 1566 | Conference invite targets | M |
| `TalkbackCoordinator.kt` | `connectedMeshPeerIds` | 8134 | Mesh connected set | P2 |
| `TalkbackCoordinator.kt` | `observeGroupIceEnterChecking` | 9976-9977 | GROUP wedge diag | P2 |
| `TalkbackCoordinator.kt` | `maybeEmitIceTopologySnapshot` | 2416 | Topology snapshot | P2 |
| `TalkbackCoordinator.kt` | `remoteModuleStates` / roster | 12219, 12336, 12363 | Peer display | P2 |
| `TalkbackCoordinator.kt` | `isRemoteModuleReachable` | 11124, 11198 | Callable roster | P2 |
| `TalkbackCoordinator.kt` | `recoverStuckCheckingGroupPeers` | 10573-10581 | GROUP L0 | P2 |
| `GroupRuntimeHealthProjector.kt` | health input | via `iceStateForModule` | GROUP health P0 | P2 |
| `TalkbackRuntimeManager.kt` | QoS map for UI | 631 | Multi-module display | M |

---

## Mixed session-type (**M** — branch or scope param required)

| File | Function | Line(s) | Notes |
|------|----------|---------|-------|
| `TalkbackCoordinator.kt` | `isPeerMediaConnected` | 10254 | Used for GROUP + CONFERENCE — split or `session.type` |
| `TalkbackCoordinator.kt` | `isSessionTransmitReady` | 10286-10308 | CONFERENCE branch vs GROUP |
| `TalkbackCoordinator.kt` | `isSessionMediaNegotiating` | 10366-10415 | UNICAST vs mesh |
| `TalkbackCoordinator.kt` | `handleGroupJoin` | 6675+ | CONFERENCE session but GROUP-named signal |
| `TalkbackCoordinator.kt` | `getOrCreateMeshEngine` / observe | 781 | Conference engine + GROUP qos in trace |
| `TalkbackCoordinator.kt` | `computeIceRestartGateProbe` | 6853+ | Conference-only but uses engine snapshot |
| `TalkbackCoordinator.kt` | `conferenceNetworkIndicator` | 3829 | Merges all snapshots — needs scope labels |

---

## Unicast (**U** — already correct)

| File | API | Line(s) |
|------|-----|---------|
| `TalkbackCoordinator.kt` | `snapshotUnicast` / `isUnicastConnected` | 10258, 10352, 10404, 8260 |
| `TalkbackCoordinator.kt` | `onUnicastIceStateChanged` | 9943-9947 |
| `NetworkQualityMonitor.kt` | `unicastSnapshots` | 17, 29-35, 63, 101-102 |

---

## Recovery / admission cross-links (Phase 2 — not QoS-only)

| File | Function | Line(s) | ADR-0052 decision |
|------|----------|---------|-------------------|
| `TalkbackCoordinator.kt` | `acceptGroupInvite` | 5916 | Calls `scheduleConferenceHostLinkKick` — needs admission barrier |
| `TalkbackCoordinator.kt` | `scheduleConferenceHostLinkKick` | 10622-10642 | Defer until admission READY |
| `ConferenceEdgeRecoveryController.kt` | `onIceStateChanged` | 2971-3080 | DISCONNECTED/FAILED only; not CLOSED |
| `TalkbackCoordinator.kt` | `onIceRestart` inject | 425-445 | Same engine path as kick |
| `TalkbackCoordinator.kt` | `dispatchRecoveryReattachOutcome` | 5126+ | Reachability uses GROUP connected |

**Concurrency note:** `coordinatorExecutor` (single) vs `scheduler` (`newSingleThreadScheduledExecutor` at line 919) — kick/recovery not serialized with accept by design today.

---

## Public / test surfaces

| File | Symbol | Phase |
|------|--------|-------|
| `TalkbackCoordinator.kt` | `qosSnapshotForModule` | P1 |
| `TalkbackCoordinator.kt` | `qosSummary` / `formatSummary` | P3 — label scopes in summary |
| `TalkbackRuntime.kt` | `qosSnapshotForModule` | P1 |
| `SessionFsmConvergenceIntegrationTest.kt` | qos read | P3 update tests |
| `UnicastPttRecoveryIntegrationTest.kt` | qos read | P3 |
| `IceConnectivityTest.kt` | `isGroupConnected` | P2 |
| `ConferenceBootstrapDeferralTest.kt` | `hostIceConnected` param | P1 |
| `ConferenceRuntimeProjectionLoggerTest.kt` | hostIce log | P3 |
| `TalkbackCoordinatorIntegrationTest.kt` | `hostIceRequired` | P1 |

---

## Migration checklist (pre-implementation)

- [ ] Add `conferenceSnapshots` (or scoped map) without removing `groupSnapshots`
- [ ] Route `MediaBearerScope.CONFERENCE` ICE callbacks to C table only
- [ ] Deprecate unscoped `snapshot(moduleId)` internally; ban new callers
- [ ] Migrate all **P1** rows in § Conference-critical
- [ ] Audit **M** rows for session-type branch
- [ ] Verify **G** rows still call GROUP scope only
- [ ] Add log fields: `groupIce` vs `conferenceHostIce` in runtime decision
- [ ] Phase 2: admission phase gate on kick + outbound recovery
- [ ] Phase 3: negotiation serialization per `(sessionId, remoteModuleId)`
- [ ] Field re-run: `adr0051-pr1` scenario or dedicated ADR-0052 run card

---

## Counts ( `talkback/android-board-talkback` + `talkback-app` )

| Category | Approx. call sites |
|----------|-------------------|
| `qosMonitor.snapshot(` | 45+ in `TalkbackCoordinator.kt` |
| `isGroupConnected` | 35+ in `TalkbackCoordinator.kt` |
| Conference-critical (must fix) | 25 rows above |
| GROUP-only (keep G) | 20+ rows |
| Unicast (OK) | 5 rows |

**Highest risk:** any remaining `snapshot(moduleId)` in CONFERENCE code paths after Phase 1.
