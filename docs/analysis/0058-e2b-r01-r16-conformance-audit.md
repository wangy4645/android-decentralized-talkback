# ADR-0058 Profile 03 — E2b R01–R16 Conformance Audit

## Status

```text
E2a:                    PASS (unchanged)
Implementation:         AUTHORIZED
E2b:                    SLICE 3 COMPLETE — 16 PASS · 0 GAP · 0 CONTRADICTION
Contract:               FROZEN — no contradiction found

ADR-0057:               CLOSED / OUT OF SCOPE
```

**Slice 1:** [0058-e2b-slice1-unified-orchestrator.md](./0058-e2b-slice1-unified-orchestrator.md)

**Slice 2:** [0058-e2b-slice2-jitter-cap-enforcement.md](./0058-e2b-slice2-jitter-cap-enforcement.md)

**Slice 3:** [0058-e2b-slice3-product-seams.md](./0058-e2b-slice3-product-seams.md)

**Authority input:** [0058-profile-03-r01-r16-acceptance-spec.md](./0058-profile-03-r01-r16-acceptance-spec.md)

**Audit method:** Map each R0x to production implementation owner, executable harness, and
evidence. Unit test green ≠ E2b PASS. Harness must exercise production behavior under test,
not manufacture success outside the component under test.

**Architectural context (material to gaps):**

```text
com.talkback.core.conference.* exists as authorized implementation scaffolding.
It is NOT wired into GROUP PTT / session / WebRTC production path.

Production P03 is split across facades sharing types but not a single orchestrator:

  AuthorityWiringRuntime     → P01 facts + P02 wire + MediaExecutionPipeline
  ConferenceMediaSelectionRuntime → Top-K + DecodeEligibilityGate
  MediaExecutionPipeline     → jitter / reorder / PLC / decode-count (no Opus)
  DecodeMixRuntime           → Opus seam / warm-up / mix (separate selection instance)
  AndroidRuntimeResources    → jitter cap / transport / playout (separate selection instance)
  HealthBoundaryRuntime      → Q8 evidence boundary (separate selection instance)

C-IG-01 product assembly: `AndroidRuntimeResources.forAndroidProduct`
  → AndroidWifiMulticastLockSeam (WifiManager.MulticastLock)
  → MulticastSocketTransportRebindSeam (java.net.MulticastSocket)
Harness defaults (E2b-05 R12/R15/R16): FakeMulticastLockSeam, FakeTransportRebindSeam, RecordingAudioTrackSeam
```

---

## Verdict summary

```text
E2b R01–R16 (Profile 03)

PASS:                   16
IMPLEMENTATION_GAP:      0
CONTRACT_CONTRADICTION:  0

Contract:               FROZEN
E2b formal gate:        CLOSED (unit conformance) — field / session wiring NOT in this gate
```

| Case | Verdict (post–Slice 3) |
|------|------------------------|
| R01 | **PASS** — `ConferenceMediaExecutionOrchestrator` unified chain |
| R02 | **PASS** — Top-K == decode == mix identity sets |
| R03–R12, R15, R16 | **PASS** (unchanged) |
| R13, R14 | **PASS** — product C-IG-01 Android seams |

| Group | Cases | Audit note |
|-------|-------|------------|
| Top-K / decode order | R01–R03 | PASS on unified orchestrator (Slice 1) + selection seam |
| Jitter / PLC / late | R04–R07 | PASS on MediaExecutionPipeline + eligibility gate |
| Warm-up / fence / epoch | R08–R10 | PASS on DecodeMixRuntime + AuthorityWiringRuntime |
| Resource caps | R11–R12 | R11 PASS; R12 cap enforced on pipeline jitter map |
| Transport / degradation | R13–R16 | R15/R16 PASS on evidence logic; R13/R14 PASS on product Android seams |

---

## R01–R16 matrix

| Case | Frozen expectation (short) | Impl owner | Harness | Evidence | Verdict | Gap |
|------|---------------------------|------------|---------|----------|---------|-----|
| **R01** | 2 admitted → Top-K → decode → mix ≤4; peak ≤0.89 FS | `ConferenceMediaExecutionOrchestrator` | `Profile03E2bSlice1OrchestratorTest` `r01_*` | topK==decode==mix={S1,S2}; peak OK; fence OPEN | **PASS** | — |
| **R02** | 6×V=1 → Top-4 decode+mix; excluded admitted | `ConferenceMediaExecutionOrchestrator` | `Profile03E2bSlice1OrchestratorTest` `r02_*` | identity sets aligned; S5/S6 excluded | **PASS** | — |
| **R03** | V=0 yields seat; challenger enters without full Hold; V=0 ≠ fence | `TopKSelector` + `VoiceLevelStore` via `ConferenceMediaSelectionRuntime` | `Profile03E2b02` `r03_v0YieldsSeat_whileHoldWouldStillRun` | S_hold evicted while HoldMs active; S_challenger eligible; V=0 fence OPEN | **PASS** | — |
| **R04** | Reorder in window → media-time decode order; 0 LATE | `PerIncarnationJitterBuffer` + `MediaExecutionPipeline.pullSlot` | `Profile03E2b03` `r04_reorder_mediaTimeDelivery` | decodeOrderSlots [0,1,2]; lateForPlayoutCount=0; `isDecodeEligible` checked before decodeCount++ | **PASS** | — |
| **R05** | Late >120ms → LATE_FOR_PLAYOUT; no decode; no solo Health flip | `PerIncarnationJitterBuffer` + `MediaExecutionPipeline`; `HealthBoundaryRuntime` | `Profile03E2b03` `r05_lateOver120ms_lateForPlayout_noDecode`; `Profile03E2b06` `r05_crossCheck_singleLate_doesNotIndependentlyFlipHealth` | disposition LATE_FOR_PLAYOUT; decodeCount=0; authority snapshot unchanged | **PASS** | — |
| **R06** | 3 lost slots → PLC×3; eligible; no fence | `PlcController` + `MediaExecutionPipeline` | `Profile03E2b03` `r06_threeLostSlots_plcExactly3` | plcCount=3; fence OPEN; PLC gated by `isDecodeEligible` | **PASS** | — |
| **R07** | 6 lost → PLC×5 then silence; no fence/authority/Health alone | `PlcController` + `MediaExecutionPipeline`; `HealthBoundaryRuntime` | `Profile03E2b03` `r07_sixLostSlots_plc5ThenSilence`; `Profile03E2b06` `r07_crossCheck_plcExhaustion_doesNotIndependentlyFlipHealth` | 5 PLC + 1 SILENCE_GAP; authority unchanged | **PASS** | — |
| **R08** | Warm-up ≤40ms; no mandatory wait when PCM usable | `WarmupGate` + `DecodeMixRuntime` | `Profile03E2b04` `r08a_immediatelyUsable_noMandatory40msWait`; `r08b_warmupCompletesWithin40ms`; `cE2b04_04_warmupExpiry_noFenceOrTopKChange` | Case A mixable at t=0; Case B usable at 25ms≤40; expiry releases transition only | **PASS** | — |
| **R09** | HARD FENCE immediate; queued/late work cannot decode/PLC | `AdmittedMediaSourceRegistry` + `MediaExecutionPipeline` + `ConferenceMediaSelectionRuntime`; `AuthorityWiringRuntime.revokeSource` | `Profile03E2b02` `r09_hardFence_blocksDecode_latePacketsDoNotResurrect`; `Profile03E2b03` `r09_queuedFrameThenFence_noDecodeNoPlc`; `Profile03E2b07` `a05_revocationRace_queuedFrame_noDecodeNoPlc` | fence before pullSlot; FENCED_SKIP; decodeCount/plcCount=0; revoke fences pipeline+selection synchronously | **PASS** | — |
| **R10** | Epoch-7 fence blocks queued F7; Epoch-8 fresh state | `AuthorityFactStore` + `AuthorityWiringRuntime` + `MediaExecutionPipeline` | `Profile01E2b07` `a04_newEpoch_freshCapability_noOldStateInheritance` | retireMediaKeyEpoch fences; pullSlot(inc=7)→FENCED_SKIP; inc=8 new key/admitted; inc=7 not eligible | **PASS** | — |
| **R11** | 5 live decoders; mix≤4; #5 transition cannot become mix #5 | `LiveDecoderPool` + `DecodeMixRuntime` | `Profile03E2b04` `r11_fiveLiveDecoders_mixStillFour` | activeMix=4; live=5 transition; `produceMixablePcm(S5)` null; harness uses direct `decoderPool.allocate` for transition seat (fixture assist, not bypass of eligibility) | **PASS** | — |
| **R12** | 11th jitter → REJECT or RECLAIM; active≤10 | `MediaExecutionPipeline` + `JitterSourceAllocator` | `Profile03E2bSlice2JitterCapTest`; `Profile03E2b05` (facade) | allocator outcome on production pipeline; cap enforced | **PASS** | — |
| **R13** | MulticastLock follows transport scope; silence/V=0/empty Top-K must not release | `AndroidRuntimeResources` + `AndroidWifiMulticastLockSeam` | `Profile03E2bSlice3ProductSeamsTest` `r13a_*` / `r13b_*`; `Profile03E2b05` (fake regression) | lock held across soft reclaim; released only on `endTransportScope`; real `WifiManager.MulticastLock` | **PASS** | — |
| **R14** | Rebind preserves authority/fence/generation; transport-only | `AndroidRuntimeResources.rebindTransport` + `MulticastSocketTransportRebindSeam` | `Profile03E2bSlice3ProductSeamsTest` `r14_*`; `Profile03E2b05` (fake regression) | old socket closed; new socket open; authoritySnapshot unchanged; fence preserved; lock still held | **PASS** | — |
| **R15** | AudioTrack fail → evidence only; no authority mutation | `AndroidRuntimeResources.playout` | `Profile03E2b05` `r15_audioTrackFailure_evidenceOnly_noAuthorityMutation` | AUDIOTRACK_UNDERRUN evidence; authoritySnapshot unchanged; Top-K/fence/caps frozen | **PASS** | — |
| **R16** | Allocation exhaustion → reject + evidence; caps unchanged | `LiveDecoderPool` + `JitterSourceAllocator` via `AndroidRuntimeResources` | `Profile03E2b05` `r16_resourceExhaustion_rejects_capsUnchanged` | decoder #6 null; jitter #11 REJECT_NEW; evidence recorded; caps constants unchanged | **PASS** | — |

---

## Strict-review groups (user focus)

### 1. R01–R03 Top-K before decode

```text
DecodeEligibilityGate + MediaExecutionPipeline.pullSlot:
  production seam EXISTS and is exercised (R04–R07, R09).

R01/R02 full spec:
  requires observable decode set on production path.
  Current evidence stops at eligibility (R02) or mix on isolated DecodeMixRuntime (R01).

Verdict: R03 PASS; R01/R02 IMPLEMENTATION_GAP
```

### 2. R09/R10 HARD FENCE before teardown

```text
registry.hardFence → immediate HARD_FENCED
pipeline.invalidateForHardFence / pullSlot FENCED_SKIP
AuthorityWiringRuntime.revokeSource / retireMediaKeyEpoch: fence before any teardown wait

No "destroy native first, then fenced" pattern in audited code.

Verdict: R09/R10 PASS on implemented authority + execution path
```

### 3. R13/R14 transport vs authority

```text
AndroidRuntimeResources: transport scope owns lock lifecycle; rebind copies transport handle only.
Product assembly (`forAndroidProduct`): WifiManager.MulticastLock + MulticastSocket rebuild/re-join.
Tests prove authority isolation on production seams (Slice 3) and coordinator (E2b-05 fakes).

Verdict: PASS (product integration). Session / Meeting wiring remains out of scope.
```

### 4. R15/R16 degradation ≠ authority

```text
playout failure and cap exhaustion record RuntimeDegradationEvidence only.
authoritySnapshot() unchanged in harness assertions.

Verdict: R15/R16 PASS on degradation evidence contract
```

---

## Harness inventory (candidate evidence — not verdict)

| Harness | Claims | Production under test? |
|---------|--------|------------------------|
| `Profile03E2b02AdmissionTopKHarnessTest` | R01/R02/R03/R09 subsets | `ConferenceMediaSelectionRuntime` — yes; decode/mix — no |
| `Profile03E2b03JitterPlcHarnessTest` | R04–R07, R09 queue race | `MediaExecutionPipeline` — yes |
| `Profile03E2b04DecodeMixHarnessTest` | R01 mix, R08, R11 | `DecodeMixRuntime` — yes; isolated from pipeline/wire |
| `Profile03E2b05AndroidResourcesHarnessTest` | R12–R16 coordinator | `AndroidRuntimeResources` — yes; fake IG-01 seams (regression) |
| `Profile03E2bSlice3ProductSeamsTest` | R13/R14 product | `AndroidWifiMulticastLockSeam` + `MulticastSocketTransportRebindSeam` via `forAndroidProduct` |
| `Profile03E2b06HealthBoundaryHarnessTest` | Q8 cross-checks (R05/R07 adjunct) | `HealthBoundaryRuntime` — yes |
| `Profile01E2b07AuthorityWiringHarnessTest` | R09/R10 authority (A02–A05) | `AuthorityWiringRuntime` — yes |

---

## Minimal implementation slice order (if gaps accepted)

```text
Slice 1 — Unified execution orchestrator
  Wire admit → MediaExecutionPipeline → DecodeMixRuntime → playout
  Single shared ConferenceMediaSelectionRuntime instance
  → closes R01, R02 decode observability

Slice 2 — Jitter cap integration
  MediaExecutionPipeline admitFrame must consult JitterSourceAllocator
  → closes R12 on integrated path

Slice 3 — C-IG-01 product seams
  Real MulticastLock + transport rebind behind seams (no test backdoors)
  → closes R13/R14 product integration
  **DONE** — see 0058-e2b-slice3-product-seams.md

Slice 4 — Field / multicast path
  ADR-0059 capacity gate (independent of E2b unit conformance)
```

Authority fence / epoch isolation (Slice 0 foundation) is **already implemented** — do not re-architect R09/R10.

---

## Adjudication

```text
E2b R01–R16 Conformance Audit: COMPLETE (post–Slice 3)

PASS:                   16  (R01–R16)
IMPLEMENTATION_GAP:       0
CONTRACT_CONTRADICTION:  0

Contract:               FROZEN
E2b unit-conformance:   CLOSED
Session / Meeting wiring / field multicast: NOT CLAIMED
```

**Not claimed by this audit:**

```text
E1 Q9 corpus re-confirmation (separate; Profile02Q9E1RegressionTest is candidate PASS)
TalkbackSession / Meeting UI wiring
Field / multicast runtime PASS
ADR-0056 deletion / cutover
```

---

## Relation to ADR-0057

```text
ADR-0057: CLOSED / FROZEN / ZERO WORK
GROUP PTT bootstrap: frozen underlay for Conference
This audit does not modify, reopen, or couple to ADR-0057
```
