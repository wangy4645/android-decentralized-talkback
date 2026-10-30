# PRODUCT-CHAIN Field Soak — 2–3 Member Run Card

## Status

```text
PASS / FIELD-VALIDATED
Phase: PRODUCT-CHAIN FIELD SOAK (first field validation of control→media product main chain)

Authoritative run: product-chain-field-soak-20260907-144912
  M01 PASS · M02 PASS · M03 PASS
  evidence: talkback/logs/product-chain-field-soak-20260907-144912/

Prior smoke: product-chain-field-soak-20260907-144326 (M01 PASS)

Prerequisites (all CLOSED):
  Phase 1 receiver correctness      CLOSED @ N=10
  Phase 2 session/control wiring    CLOSED
  Raw CBOR / verifier               CLOSED
  MEDIA_KEY_PACKAGE decrypt         CLOSED
  Membership fact convergence       CLOSED

This soak:                          PASS / CLOSED
Real Mesh E2E:                      PASS / FIELD-VALIDATED (2-device)
  run: real-mesh-e2e-20260907-151200
  card: real-mesh-e2e-underlay-migration-run-card.md
Meeting UI cutover:                 HOLD
ADR-0056:                           KEEP
N=10 capacity re-proof:             FORBIDDEN (receiver frozen)
```

### Adjudication (20260907-144912)

| Device | verdict | admit OK/rej | leave/rejoin | teardown |
|--------|---------|--------------|--------------|----------|
| M01 | PASS | 5331 / 0 | leave clear + rejoinInstalled | leakPass |
| M02 | PASS | 5317 / 0 | leave clear + rejoinInstalled | leakPass |
| M03 | PASS | 5516 / 0 | leave clear + rejoinInstalled | leakPass |

Shared: decryptPass · CREATION/MEMBERSHIP/SOURCE ACCEPTED · membershipVersion=1 mediaKeyEpoch=2 · stale=STALE_MEMBER_CONTEXT · meshClaim=false

## Evidence scope (hard)

```text
PRODUCT-CHAIN FIELD SOAK
2–3 physical devices
Wi-Fi simulation underlay (wlan0)
NOT Real Mesh E2E
NOT N=10 capacity evidence
NOT ADR-0056 cutover evidence
NOT Meeting UI create/join as fact origin
```

PASS on this card means:

```text
field-validated:
  real signed wire facts
  → CBOR decode
  → signature verify
  → membership convergence
  → media key decrypt
  → authoritative publish
  → Coordinator thin hooks
  → SessionMediaWiring (forProduct)
  → multicast Phase 1 runtime
```

It does **not** mean Mesh acceptance, Meeting cutover, or capacity ladder re-open.

## Purpose

Prove the **already-closed product seam chain** is stable under real-device lifecycle:

```text
session create / join / leave / rejoin
  → install / revoke
  → real multicast audio into frozen Phase 1 pipeline
  → generation / stale fence still holds
  → 120s soak → clean teardown (no source / jitter / decoder / socket leak)
```

**No new architecture.** Field vehicle may only compose CLOSED seams and inject signed Profile 01 facts into `Profile01ConferenceMediaFactIngress`. Forbidden: `Phase1MediaHarness.installAuthority` as primary authority path; harness overlay of `membershipVersion` / `mediaKeyEpoch`.

## Devices

| Label | Role (primary) | Serial |
|-------|----------------|--------|
| M01 | Owner + sender | `HTUBB21B09220661` |
| M02 | Member + sender | `DSJ-2407041` (field map) / workspace alias `2d73067a` if remapped |
| M03 | Member + receiver (or 3rd sender) | `MDX0220416001963` |

Minimum: **2 members** (M01+M02). Preferred: **3** (M01+M02+M03).

USB note (unchanged from Phase 1 field prep): M02 may share USB with another board — prefer wireless ADB after `adr0060-phase1-field-device-prep.ps1`.

## Underlay

```text
UNDERLAY:   WIFI SIMULATION LAN (wlan0)
MULTICAST:  239.255.42.99:47099 (Slice4 constants unless card amendment)
REAL_MESH:  ASSUMED / UNPROVEN — do not extrapolate PASS to Mesh
```

## Execution vehicle (directed)

```text
ProductChainFieldSoakRunner (directed field injector)
  • ConferenceSessionMediaWiring.forProduct(context)
  • Profile01ConferenceMediaFactIngress
      + production/golden trust verify
      + MembershipConvergenceRegistry
      + MediaKeyPackageDecryptSeam
      + SessionMediaSupplementRegistry
  • ConferenceSessionMediaGbcPublisherBridge → registry
  • ConferenceSessionMediaCoordinatorDelegate
      onConferenceSessionStarted / onConferenceMemberMediaReady /
      onConferenceMemberRemoved / onConferenceSessionStopped
```

Fact order (same authoritative generation):

```text
1. CREATION signedFact
2. MEMBERSHIP signedFact          (if beyond Creation v0)
3. MEDIA_KEY_PACKAGE signedFact   (per recipient; decrypt → supplement)
4. SOURCE_DECLARATION signedFact  (per local sender)
5. Coordinator hooks → Wiring install
6. Multicast TX/RX 120s
7. leave / rejoin (new incarnation / supersede)
8. stopSession + leak asserts
```

Key material MUST come from **MEDIA_KEY_PACKAGE decrypt** (or registry filled by that decrypt).  
`Profile01SessionMediaSupplement` is only allowed as channelId carrier + decrypt output — not as long-term Phase1 harness key injection.

## Observation classes (only these four)

| # | Class | PASS signal |
|---|-------|-------------|
| O1 | Lifecycle | create/join → install; leave → revoke/hardFence; rejoin → new incarnation, old superseded |
| O2 | Multicast media | Real SRTP multicast enters frozen Phase 1 pipeline (admit → jitter → Top-K → decode → mix/AudioTrack path as applicable) |
| O3 | Generation fence | Wrong/`stale` membershipVersion or mediaKeyEpoch packets rejected; generation head unique |
| O4 | Teardown | After 120s soak + stop: zero admitted sources, empty jitter, no live decoder, transport scope ended, no orphan sockets |

## PASS gates

All required:

```text
G1  Session start publishes authoritative session fact; Wiring.hasSession == true
G2  Each remote member SOURCE_DECLARATION installs AdmittedMediaSource for correct moduleId/incarnation
G3  At least one remote audio path shows sustained admit>0 over soak window (O2)
G4  Injected stale SOURCE (lower generation or wrong membershipVersion/mediaKeyEpoch) does not reinstall
G5  leave/rejoin: old incarnation fenced; new incarnation installs
G6  120s soak completes without transport-scope failure
G7  stopSession / teardown: Wiring leak asserts PASS (sources/jitter/decoders/scope)
G8  Evidence tree written under logs/product-chain-field-soak-<runId>/
```

## FAIL routing

```text
FAIL O1/O5     → Phase 2 wiring / Coordinator hook regression
FAIL O2        → underlay or Phase 1 pipeline (do NOT reopen capacity; check iface/lock first)
FAIL O3        → Membership convergence / publish republication regression
FAIL O4/G7     → teardown leak — Wiring stopSession contract
FAIL decrypt   → MEDIA_KEY_PACKAGE decrypt seam (already CLOSED — treat as regression)
```

Never automatically:

```text
reopen Phase 1 N=10 ladder
claim Real Mesh E2E
claim ADR-0056 cutover
add Meeting UI work
enlarge recovery / timeout budgets
```

## Procedure (field)

```powershell
# 0) Build + prep (wireless preferred)
.\scripts\adr0060-phase1-field-device-prep.ps1 -Devices M01,M02,M03

# 1) Directed smoke (1 device, 5s) — composition check
.\scripts\product-chain-field-soak-step.ps1 -Step smoke -Devices M01

# 2) Formal 120s soak (2–3 devices)
.\scripts\product-chain-field-soak-step.ps1 -Step start -DurationSec 120 -Devices M01,M02,M03
.\scripts\product-chain-field-soak-step.ps1 -Step pull -RunId <runId from start>
```

Runner: `ProductChainFieldSoakRunner` + `ProductChainFieldSoakInstrumentedTest`  
(composes CLOSED Profile01 ingress + `ConferenceSessionMediaWiring.forProduct`; directed signed facts; Q5 decrypt fixture for keys).

## Report fields (minimum)

```text
runId, deviceLabel, role
creationFactDigest, membershipVersion, mediaKeyEpoch, generationFactDigest
installCount, revokeCount, staleRejectCount
admitFrames, rejectedStale, fenceEvents
soakSec, teardownLeakPass (bool + detail)
underlay: iface, multicast, meshClaim=FALSE
```

## Explicit non-goals

```text
Real Mesh multi-hop
N=10 physical senders / capacityGates
Meeting UI / Talk page cutover
ADR-0056 Anchor topology cutover
Production Keystore RSA enrollment as this card’s gate
  (decrypt seam CLOSED; product Keystore binding may remain lab-fixture for directed soak)
WiFi recovery / RNA / membership-first conference invite path
```

## Freeze after this card

```text
Phase 1 receiver correctness          CLOSED
Phase 2 session/control wiring        CLOSED
Raw CBOR / verifier                   CLOSED
MEDIA_KEY_PACKAGE decrypt             CLOSED
Membership convergence                CLOSED
2–3 member product-chain field soak   PASS / FIELD-VALIDATED

Wi-Fi simulation product chain        VALIDATED
Real Mesh E2E                         PASS / FIELD-VALIDATED (2-device)
  run: real-mesh-e2e-20260907-151200
  card: docs/analysis/real-mesh-e2e-underlay-migration-run-card.md
Meeting UI cutover                    HOLD
ADR-0056                              KEEP
Phase 1/2                             DO NOT REOPEN
N=10 capacity                         CLOSED — do not re-require for Mesh E2E
```

**Do not reopen** receiver, session wiring, CBOR, membership, or decrypt for further media-engine polish.  
Master freeze: `conference-media-foundation-freeze.md`  
Next major gate: `meeting-product-integration-gate.md` (shadow/controlled integration; ADR-0056 KEEP).

## Next action

1. ~~Land `ProductChainFieldSoakRunner` + instrumented launch~~ DONE
2. ~~Land `scripts/product-chain-field-soak-step.ps1`~~ DONE
3. Run directed smoke (`-Step smoke`), then formal 120s on M01–M03.
4. Adjudicate G1–G8; on PASS mark **PRODUCT-CHAIN FIELD-VALIDATED** (Mesh / UI / ADR-0056 still HOLD).

## References

- Phase 2 wiring: `phase2-conference-session-media-wiring-run-card.md`
- Membership convergence: Profile01MembershipConvergenceRegistry / Profile01MembershipConvergenceTest
- MEDIA_KEY_PACKAGE decrypt: Profile01MediaKeyPackageDecryptSeam / Q5 vectors
- Phase 1 soak pattern: `phase1-slice5b-three-source-soak-run-card.md`
- Device prep: `scripts/adr0060-phase1-field-device-prep.ps1`
