# Phase 2 — Conference Session Media Wiring

## Status

```text
Phase 2 architecture:            CLOSED
Slice 1–5:                       CLOSED (wiring / adapter / GBC / Profile01 ingress)
Raw CBOR / verifier:             CLOSED
MEDIA_KEY_PACKAGE decrypt:       CLOSED
Membership fact convergence:     CLOSED
TalkbackCoordinator:             THIN DELEGATION (hooks only)
Phase 1 receiver correctness:    CLOSED @ N=10 — DO NOT REOPEN
ADR-0056:                        KEEP (no cutover)
capacityGates:                   NONE (receiver frozen)

2–3 member PRODUCT-CHAIN field soak: PASS / FIELD-VALIDATED
  card: product-chain-2-3-member-field-soak-run-card.md
  run:  product-chain-field-soak-20260907-144912 (M01/M02/M03)
Wi-Fi product-chain:             FIELD-VALIDATED
Real Mesh product-chain:         FIELD-VALIDATED (2-device)
  card: real-mesh-e2e-underlay-migration-run-card.md
  run:  real-mesh-e2e-20260907-151200 (M02→M01)
3-device Real Mesh:              NOT PROVEN / ENV_BLOCKED
Meeting product integration:     NEXT MAJOR GATE
ADR-0056 cutover:                HOLD / KEEP

Master freeze: conference-media-foundation-freeze.md
```

## Roadmap (frozen order)

```text
Phase 1 Receiver                         ✅ CLOSED @ N=10
          ↓
Phase 2 Conference Session Media Wiring  ✅ CLOSED
          ↓
Wi-Fi product-chain field soak           ✅ FIELD-VALIDATED
          ↓
Real Mesh E2E (2-device)                 ✅ FIELD-VALIDATED
          ↓
Meeting Product Integration Gate         ← NEXT
          ↓
ADR-0056 Cutover Gate                    HOLD
```

## Overall posture

```text
Receiver engine             CLOSED
Session/control wiring      CLOSED
Product-chain (Wi-Fi)       FIELD-VALIDATED
Product-chain (Real Mesh)   FIELD-VALIDATED (2-device)
Meeting integration         NEXT — shadow/controlled first
ADR-0056                    KEEP
```

## Purpose

Connect the **validated media plane** to the **real Conference session lifecycle**.

Phase 1 proved receiver behavior under harness. Phase 2 must prove that **session create / join / leave / member source lifecycle** correctly drives:

```text
session member fact
    → AdmittedMediaSource install / hardFence
    → multicast sender lifecycle (per local source)
    → multicast receiver lifecycle (shared, N=10 pipeline)
    → teardown on leave / session end
```

Without this wiring, Real Mesh E2E only re-proves transport — not the conference system.

## In scope

```text
Real TalkbackSession / Conference session hooks
Member join → AdmittedMediaSource + authority install
Member leave / revoke → hardFence + jitter release + sender stop
Local PTT / source activation → sender start/stop bound to session
Shared receiver bound to session lifetime (playout clock start/stop)
Wire AuthorityWiringRuntime ↔ ConferenceMediaExecutionOrchestrator (product path)
Session-scoped observability (install count, active sources, fence events)
Harness + minimal field card for lifecycle (NOT N=10 device requirement)
```

Receiver pipeline, Profile 03 caps, promotion fence, playout lock model — **frozen, reuse as-is**.

## Out of scope (explicit)

```text
Meeting UI cutover
ADR-0056 Anchor / topology cutover
Automatic unicast fallback
Receiver capacity / Top-K / jitter cap changes
Real Mesh multi-hop field soak (deferred to post–Phase 2)
10-device field evidence
Directed re-validation of Phase 1 scaling ladder
```

## Validation strategy (no 10 phones)

Phase 2 is **lifecycle correctness**, not receiver capacity re-proof.

| Layer | Method |
|-------|--------|
| Unit / harness | Session fixture drives install→play→leave→fence; assert no orphan sources/decoders |
| Integration | 2–4 real devices (M01–M04 pool): join session, 2–3 network senders, optional synthetic sources on RX for occupancy |
| N=10 receiver path | **Session-driven synthetic injection on one RX** (same pattern as Phase 1 10-source), triggered by session membership facts — not 10 physical senders |
| PASS criteria | All members admitted/fenced with session; sender/receiver start/stop symmetric; no leak after leave; receiver caps unchanged when session ends |

```text
Phase 2 PASS ≠ “10 phones in one room”
Phase 2 PASS = “session lifecycle owns the same media path Phase 1 validated”
```

## Suggested exit gates

```text
G1  Session join installs AdmittedMediaSource for each remote member (authority-aligned)
G2  Session leave / revoke hardFences source; jitter + sender torn down
G3  Local source lifecycle (PTT/active) starts/stops multicast sender within session
G4  Receiver playout starts on first admitted remote; stops on session end
G5  No orphan pipelines after N join/leave cycles (leak harness)
G6  Minimal field: 2–3 member session on M01–M04, 120s soak, clean teardown
```

## Dependencies (already closed)

- Profile 03 E2b unit conformance (R01–R16)
- Phase 1 receiver @ N=10 (structural + promotion fence)
- `ConferenceMulticastMediaPipeline` / `ConferenceMediaExecutionOrchestrator`
- `AuthorityWiringRuntime` + `AdmittedMediaSource` boundary

## References

- Phase 1 closure: `phase1-receiver-scaling-10source-run-card.md`
- E2b Slice 3 deferral: `0058-e2b-slice3-product-seams.md` (“Product session wiring — not authorized by this slice”)
- Profile 03: `0058-implementation-profile-03-media-runtime.md`
- ADR-0056: keep; cutover gate remains downstream

## Next action

Phase 2 seams CLOSED. Do **not** add architecture.

**Authorized next:** PRODUCT-CHAIN 2–3 member field soak  
→ `product-chain-2-3-member-field-soak-run-card.md`
