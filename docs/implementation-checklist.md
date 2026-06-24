# Implementation Checklist

## Phase A - Single-call MVP (done)

- [x] Define `moduleId` and `endpointId` constraints.
- [x] Define global endpoint key format `moduleId-endpointId`.
- [x] RF-mesh IP discovery (gossip sweep + NSD + optional static override) + UDP signaling.
- [x] WebRTC audio engine (real + stub).
- [x] Single-call PTT baseline.

## Phase B2 - Full-duplex conference (done in code)

- [x] `SessionType.CONFERENCE` + `MeshSessionMode` in `GroupSessionPayload`.
- [x] `conferenceCall()` reusing mesh signaling (`GROUP_INVITE` / `GROUP_JOIN`).
- [x] ICE connected → continuous capture (no floor control).
- [x] `setCallMuted` for conference self-mute.
- [x] Shared local `AudioTrack` via `SharedLocalAudio`.
- [x] `maxConferenceModules=8` separate from `maxGroupModules=5`.
- [ ] 8-module conference field test (manual).

## Phase B - Productization (in progress / done in code)

- [x] Floor Owner with versioned CAS (`FloorState`, `FLOOR_DENY`).
- [x] Per-session `TalkbackSession` + coordinator single-thread executor.
- [x] Gossip subnet sweep discovery (`MeshSweepGossipDiscovery`) + composite NSD + static override.
- [x] HELLO endpoint directory sync.
- [x] `StateSyncManager` for remote module/endpoint view.
- [x] `ChannelManager` + group call (`GROUP_INVITE` / `GROUP_ACCEPT`).
- [x] Group mesh completion (`GROUP_JOIN`, `GroupMeshPlanner`, `GroupSessionPayload`).
- [x] Group floor authority on initiator (`GroupFloorController`, broadcast GRANTED/DENY).
- [x] Module-level media (`ModuleMediaEngineFactory`, one PC per remote module).
- [x] Security config in production app (`sharedSecret`, whitelist, static peers).
- [x] JVM integration tests (`TalkbackCoordinatorIntegrationTest`, InMemory signaling).
- [ ] Multi-module group call field test (3+ modules, manual).
- [ ] 10-module discovery soak test (manual).

## Phase C - Engineering (partial)

- [x] ICE state callback + QoS monitor skeleton.
- [x] `PARTIAL_WAKE_LOCK` in foreground service.
- [x] `AudioRouter` / `ModuleAudioMixer` skeleton.
- [ ] WebRTC stats → QoS RTT/loss (needs stats polling).
- [ ] 30-minute / 8-hour stability test records.
- [ ] Weak-network (5% loss) test records.

## RF mesh integration (manual / with hardware team)

- [ ] Document RF task-key provisioning flow (out-of-band; app does not auto-erase).
- [ ] Verify discovery re-converges after RF key switch.
- [ ] Classified-task test: different RF keys mutually invisible in app.

## V3 roadmap (not started)

- [ ] SFU-lite media relay for large groups.
- [ ] DTLS-SRTP certificate policy.
- [ ] Reliable signaling (TCP/WebSocket).

## Acceptance KPIs

- Call setup first packet latency target: `< 1s` (same RF mesh).
- End-to-end voice latency target: `< 300ms`.
- 10 modules online: discovery and direct call remain stable.
- 30-minute continuous call: no crash.
