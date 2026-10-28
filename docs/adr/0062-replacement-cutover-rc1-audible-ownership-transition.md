# ADR-0062: Replacement Cutover RC1 — Audible Ownership Transition

## Status

**ACCEPTED — FROZEN** (2026-09-10)

Single ADR covering **rollback safety** and **cutover policy** (Sweep-1 blockers merged).

```text
Phase A                 CLOSED / FIELD-PROVEN
Replacement Sweep-1     COMPLETE / FROZEN

CUTOVER BLOCKER (RC1 closes both):
  1. Rollback safety
  2. Cutover policy

PILOT BLOCKER (orthogonal):
  G-RES-3 N=9

ADR-0056                KEEP
cutover                 NOT AUTHORIZED until RC1 field PASS
```

---

## Decision

**Audible ownership is resource-level mutual exclusion.** At any instant exactly one production audible owner may be active:

```text
ANCHOR   = ADR-0056 WebRTC conference remote playback (+ ConferenceAudioBus relay)
MULTICAST = Profile01 session media production AudioTrack
```

Forbidden:

```text
ADR-0056 remote AudioTrack ACTIVE  AND  multicast production AudioTrack ACTIVE
volume mute as substitute for ownership transfer
```

---

## State machine (frozen)

```text
ANCHOR_ACTIVE
    │  pilot flag + replacement ready + shadow healthy
    ▼
CUTOVER_ARMED
    │  ownership handoff
    ▼
MULTICAST_ACTIVE
    │  rollback trigger
    ▼
ANCHOR_ACTIVE
```

### Cutover handoff (frozen order)

```text
1. fence new-path production playout (no live production AudioTrack)
2. release ADR-0056 audible ownership (remote playback off; bus cleared)
3. acquire multicast production AudioTrack
4. verify single-owner invariant
5. MULTICAST_ACTIVE
```

### Rollback handoff (frozen order)

```text
1. fence multicast audible output
2. release multicast production AudioTrack
3. restore ADR-0056 audible ownership
4. verify single-owner invariant
5. ANCHOR_ACTIVE
```

---

## Rollback triggers (v1 — frozen)

```text
MANUAL
PRODUCTION_AUDIOTRACK_ACQUIRE_FAILURE
REPLACEMENT_RUNTIME_FATAL_FAILURE
OWNERSHIP_INVARIANT_VIOLATION
```

**Not rollback triggers:** single late packet · PLC · jitter spike · brief AudioTrack underrun · Profile03 Health transitions.

---

## Pilot scope (frozen)

```text
Controlled pilot
2-device scope
GA kill-switch for audible cutover only (`ReplacementCutoverRc1.multicastAudibleEnabled`, default ON).
Does not disable shadow multicast runtime. R1 wires readiness-driven automatic cutover in release builds.
DEBUG adb broadcast remains for harness / kill-switch tests only.
ADR-0056 retained as rollback authority
rollback available on deterministic triggers
NO claim of N=9 readiness
NO claim of ADR-0056 deletion
```

---

## RC1 closure slice

```text
Replacement Cutover RC1 — Audible Ownership + Rollback

ADR/policy freeze (this document)
→ AudibleOwnershipController
→ explicit pilot flag
→ Anchor ↔ Multicast handoff
→ single-owner assertion
→ focused unit tests
→ 2-device field (A/B/C windows)
```

Field PASS verdict: `PASS_RC1` → Rollback blocker CLOSED · Cutover policy blocker CLOSED · **READY_FOR_CONTROLLED_PILOT** (not ADR-0056 DELETE · not N=9 READY).

---

## References

- Sweep-1: `meeting-product-integration-replacement-readiness-review.md`
- Phase A closure: `meeting-product-integration-phase-a-sr6-field-closure.md`
- Field card: `replacement-cutover-rc1-field-run-card.md`
- Implementation: `AudibleOwnershipController` · `ReplacementCutoverRc1`
