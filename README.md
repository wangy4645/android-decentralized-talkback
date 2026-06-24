# Android Board Decentralized Talkback

This repository contains a reference implementation for decentralized push-to-talk (PTT) on Android communication modules over **RF mesh** (flat IP subnet), not dependent on external Wi-Fi/Ethernet infrastructure.

## Scope

- No central server.
- Each module has an IP and can host one or many endpoints.
- Module-to-module and endpoint-to-endpoint talkback.
- First delivery focuses on PTT only.

## Project layout

- `android-board-talkback/` core Android/Kotlin implementation library.
- `talkback-app/` production app (UI + foreground service + runtime manager).
- `docs/` protocol, rollout, and acceptance notes.
- `docs/系统设计-安卓无中心对讲.md` architecture design document.
- `docs/现场测试方案与执行手册.md` field test plan and sign-off runbook.
- `docs/保密组网使用手册.md` operator guide for RF mesh task groups (Route A).
- `docs/零配置寻址-gossip-sweep实现计划.md` zero-config discovery plan (subnet sweep + gossip).

## Next

Open `docs/implementation-checklist.md` for phased execution and acceptance.

### Automated tests (L2/L3)

```bat
copy local.properties.example local.properties
rem Edit sdk.dir, then:
scripts\run-unit-tests.bat
```

Field log collection: `scripts\collect-logcat.ps1` — see `docs/现场测试方案与执行手册.md` §17.

## Current implementation status

- RF-mesh IP discovery: gossip subnet sweep (primary) + Android NSD + optional static peer override (`CompositeModuleDiscoveryService`); RF task key is the admission boundary (out-of-band)
- Decentralized UDP signaling (`UdpSignalingChannel`)
- Floor Owner with versioned CAS (`FloorState`, `FLOOR_GRANTED` / `FLOOR_DENY`)
- Per-session isolation (`TalkbackSession`) and single-thread coordinator
- Module-level WebRTC (`ModuleMediaEngineFactory`, one PC per remote module)
- Group call (`groupCall`, `GROUP_INVITE` / `GROUP_ACCEPT`) and `ChannelManager`
- Full-duplex conference (`conferenceCall`, `SessionType.CONFERENCE`, up to 8 modules)
- HELLO endpoint directory sync and `StateSyncManager`
- QoS monitor skeleton (ICE state) and `AudioRouter` placeholder
- Multi-endpoint local registry (`EndpointRegistry`)
- Production app: shared secret, module whitelist, zero-config gossip discovery, optional static peer override, `WAKE_LOCK`, group PTT + full-duplex conference
- Docs: `docs/交付验收清单.md` (V1/V2), `docs/V3-roadmap.md`, `docs/architecture-review-summary.md`

## Runtime bootstrap

Use `TalkbackRuntimeFactory` to construct runtime in app code:

- `AudioEngineMode.REAL_WEBRTC`: real audio transport over RF-mesh IP
- `AudioEngineMode.STUB`: protocol-flow bring-up without live audio

## talkback-app quick start

- Build: `gradlew :talkback-app:assembleDebug`
- Install `talkback-app-debug.apk` on each Android board on the same RF mesh (same task key).
- Settings → My Identity: set `moduleId`, `endpointId`, channel ID; enable conference mode if needed.
- Settings → Task Groups: set shared secret (peers auto-discovered; static override optional).
- Settings → Service: Start Service.
- Talk tab: group PTT (hold to talk) or conference (continuous mic, tap PTT to mute).
