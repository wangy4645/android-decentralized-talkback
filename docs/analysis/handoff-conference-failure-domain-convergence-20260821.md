# Handoff — Conference Failure Domain Architecture Convergence

**Date:** 2026-08-21  
**Session:** P0.1g / B2 / D-α / Architecture reassessment → Decision 1 + Failure Domain Contract  
**Status:** Architecture convergence **COMPLETE** · **CFC-1 PARTIAL PASS — Desk Gate** (PR-A1–A4) · Runtime wiring / Field **NOT AUTHORIZED**

---

## One-line state

> Product direction (8–10p decentralized Anchor-star) is **correct**. ADR-0056 is **retained**. The gap is **runtime fault domain ≠ logical topology domain**. **Decision 1 Option Z ACCEPTED** · **CFC-1 introduced** · **Failure Domain Contract L1–L4 frozen** · Next: **Phase A bounded authorization** (separate gate).

---

## Frozen evidence (do not re-litigate)

```text
RCA-B                         CLOSED — native SRD non-return on stuck edge
PO-1 / PO-2 / PO-2b           COMPLETE — shared Factory/signaling worker; M04 blocked before sdpApplyMutex
PO-3                          ACCEPTED — native SRD domain ownership contract
B2-2-A                        FAIL — 1 Factory cannot provide per-edge parallel native SRD
D-α                           REJECTED — Factory A ready → Factory B + same ADM construction → worker SIGSEGV/SIGABRT
                                 Evidence boundary: NOT "multi-Factory unsupported" — only "Factory B + shared ADM construction crash"
D-β                           UNKNOWN / NOT AUTHORIZED — deprioritized, last resort only
D-γ                           LONG-TERM OPTION / NOT AUTHORIZED — aligns with ADR-0056, not P0 fix
B2-1 Commit 1–3               RETAIN / PASS — lease, admission, observability (LEASE_BUSY, domain events)
Commit 4                      HOLD
P0.1g-A                       CLOSED / PASS — control dispatch isolation
P0.1g-B                       CLOSED / PASS — single-edge native SRD boundary (historical ID — do not reuse)
P0.1g Acceptance 1            HOLD — ORIGINAL · BLOCKED / NO VERIFIED PATH · NOT FAILED
Decision 1                      Option Z ACCEPTED
CFC-1                         INTRODUCED — upper-layer fault containment acceptance
Failure Domain Contract       FROZEN (L1–L4)
Implementation                NOT AUTHORIZED
Lab                           NOT AUTHORIZED
```

**Field devices:** M01 `HTUBB21B09220661` · M03 `MDX0220416001963` · D-α adj: `talkback/logs/dalpha-lab-spike-20260821-155612/`

---

## Root cause (final framing)

**Not:** Conference topology wrong · WebRTC bug · mutex fixable alone.

**Yes:** Logical topology is Anchor-centric (ADR-0056), but runtime fault domain is shared-factory-centric:

```text
Logical:     Anchor M01 ── Edge M02 / M03 / M04
Runtime:     1 PeerConnectionFactory → 1 native signaling worker → N PCs
Result:      M03 native hang → M04 blocked at JNI (negotiationSnapshot/signalingState) before sdpApplyMutex
```

Renaming to "Anchor Media Domain" does **not** remove native coupling while 1 Factory + N PC remains.

---

## Architecture conclusions (agreed)

1. **Do not overturn** 8–10p decentralized Anchor-star product direction.
2. **Do not pursue D-β** as primary path (replicating N×Factory×ADM on Android).
3. **Do not implement D-γ now** — directionally aligned with ADR-0056, but ConferenceAudioBus immature; Phase 2 not started.
4. **Do not collapse to single-PC-only** — transport edges per participant remain; clarify edge vs media-domain responsibility.
5. **B2-1 is foundation** regardless of final media architecture.
6. **P0.1g Acceptance 1** must not be silently downgraded to FAILED.

---

## Decision 1 — Option Z ACCEPTED

**Recorded in:** [conference-native-failure-containment-decision.md](./conference-native-failure-containment-decision.md) v0.2

```text
P0.1g Acceptance 1     HOLD — ORIGINAL · BLOCKED / NO VERIFIED PATH · NOT FAILED

CFC-1                  Upper-layer fault containment acceptance (NOT P0.1g-B reuse)

DOMAIN_BLOCKED         Legalized (L2 — Failure Domain Contract)

Native partition       NOT AUTHORIZED
D-β / D-γ              NOT AUTHORIZED
```

**Wording constraint:** Use `BLOCKED / NO VERIFIED PATH`, not `BLOCKED BY RUNTIME CAPABILITY`.

| Option | Status |
|--------|--------|
| **X** | Not selected — retain Original only |
| **Y** | Not selected — would amend P0.1g |
| **Z** | **SELECTED** — dual-track: Original HOLD + CFC-1 parallel |

---

## CFC-1 summary

**NOT** P0.1g-B (that ID is CLOSED with fixed historical meaning).

```text
CFC-1 PASS = Scenario E ∧ D ∧ C
Phase A target = CFC-1 PARTIAL (E ∧ D) only

CFC-1 PASS ≠ P0.1g PASS
CFC-1 PASS ≠ Acceptance 1 PASS
CFC-1 PASS ≠ native per-edge SRD independence proven
```

Details: [conference-failure-domain-contract.md](./conference-failure-domain-contract.md)

---

## Failure Domain Contract (L1–L4 frozen)

| Level | Terminal / outcome | Meaning |
|-------|-------------------|---------|
| **L1** Edge Failure | `EDGE_FAILED` | Self-attributed edge fact |
| **L2** Domain Contention | `DOMAIN_BLOCKED` | Impact attribution (cause/impact) |
| **L3** Domain Failure | `DOMAIN_FAILED` | Domain authority fact (Phase B) |
| **L4** Conference Failure | ConferenceHealth projection | MediaUsable adjudication (Phase B) |

Same incident may produce L1 on cause edge + L2 on impact edge. **Not severity escalation.**

---

## Three-phase roadmap (proposed — Phase A not yet authorized)

### Phase A — CFC-1 PARTIAL (E + D)

**Goal:** Eliminate invisible CONNECTING; explicit L1/L2 failure states + L-participant projection.

```text
M03 native hang → M03 EDGE_FAILED (Scenario E)
M04 domain impact → M04 DOMAIN_BLOCKED + attribution (Scenario D)
L-participant: impact edge not CONNECTING/invisible
```

**Scope:** ~20–30% Conference runtime (state/projection/observability), not PC/Factory rewrite.

**PASS target:** `CFC-1 PARTIAL PASS (E ∧ D)` — **NOT** CFC-1 FULL · **NOT** P0.1g.

**OUT OF SCOPE:** L3/L4 · native partition · D-β/D-γ · topology/recovery changes.

### Phase B — CFC-1 FULL + contract ADR alignment

- L3 domain authority evaluation · L4 MediaUsable / ConferenceHealth
- Scenario C full · Anchor Media Domain formalization (rename ≠ decouple native)
- Failure propagation graph ADR alignment

### Phase C — Later

- D-γ reassess after field stability
- Native partition only if product re-challenges Acceptance 1 with verified path

---

## Key documents (read order)

| Doc | Path | Status |
|-----|------|--------|
| Decision 1 | [conference-native-failure-containment-decision.md](./conference-native-failure-containment-decision.md) | **v0.2 — Option Z** |
| Failure Domain Contract | [conference-failure-domain-contract.md](./conference-failure-domain-contract.md) | **v0.1 — frozen** |
| Reassessment v0.2 | [conference-srd-native-domain-partition-architecture-reassessment.md](./conference-srd-native-domain-partition-architecture-reassessment.md) | OPEN (may align Decision 1 pointer) |
| B2-1 IA | [conference-srd-native-domain-isolation-implementation-authorization-001.md](./conference-srd-native-domain-isolation-implementation-authorization-001.md) | AUTHORIZED |
| ADR-0056 | [talkback/docs/adr/0056-conference-topology-first.md](../adr/0056-conference-topology-first.md) | ACCEPTED |

---

## Code touchpoints (Phase A planning only — not authorized)

```text
ConferenceNativeExecutionDomain.kt      — B2-1 lease (RETAIN — no semantic change)
ConferenceSrdNativeDomainAdmission.kt
ConferenceSrdNativeDomainObservability.kt
TalkbackCoordinator.kt
ConferenceRuntimeProjector.kt           — EDGE_FAILED / DOMAIN_BLOCKED projection
ConferenceParticipantDisplayState.kt    — L-participant
ConferenceAudioBus.kt                   — NOT Phase A; D-γ long-term
WebRtcSharedFactory.kt / SharedLocalAudio.kt — frozen; no partition work
```

---

## Posture block (fixed)

```text
P0.1g-A                    CLOSED / PASS
P0.1g-B                    CLOSED / PASS
P0.1g Acceptance 1         HOLD — ORIGINAL · BLOCKED / NO VERIFIED PATH · NOT FAILED
Decision 1                 Option Z ACCEPTED
CFC-1                      PARTIAL target (E+D)
CFC-1 FULL                 Phase B pending
Native partition           NOT AUTHORIZED
D-β / D-γ                  NOT AUTHORIZED
B2-1                       RETAIN
Implementation             NOT AUTHORIZED (Phase A separate gate)
Lab                        NOT AUTHORIZED
```

---

## Prohibited (frozen)

```text
D-β lab without separate authorization
D-γ implementation now
Commit 4 slot reuse
P0.1g Acceptance 1 field rerun as Original gate
Mark P0.1g Acceptance 1 as FAILED
Reuse P0.1g-B for CFC-1
Phase A PASS → P0.1g closure narrative
Revoke B2-1
Assume Anchor Media Domain name change fixes native coupling
2-edge D-β PASS → production authorization
```

---

## Resolved (grill 2026-08-21)

1. ~~Decision 1 Option Z vs P0.1g-B naming~~ → **CFC-1** · P0.1g-B remains CLOSED historical ID
2. ~~DOMAIN_BLOCKED legality~~ → Option Z legalizes L2
3. ~~CFC-1 E/D/C boundaries~~ → frozen in Failure Domain Contract
4. ~~Phase A scope~~ → CFC-1 PARTIAL (E+D) · L3/L4 Phase B
5. ~~Phase A mis-authorization defenses~~ → Q10 triple guard frozen

**Still open (Phase B / product):**

- `MediaUsable` product predicate for Scenario C / L4
- Recovery admission interaction with `EDGE_FAILED` at implementation time

---

## Next

```text
FROZEN — no PR-A5 until field need confirmed

If field required:
  PR-A5 Runtime Wiring Authorization
    → B2-1 real facts → existing CFC-1 pipeline
    → Field E/D
    → CFC-1 PARTIAL FIELD adjudication

Otherwise: stop here (desk gate sufficient)
```

1. **Merged artifact:** PR-A1–A4 (classification · projection · telemetry · desk validation)
2. **Do not:** PR-A5 without authorization · field without PR-A5 · P0.1g rerun

---

## Changelog

| Date | Change |
|------|--------|
| 2026-08-21 | Initial handoff — grill pending |
| 2026-08-21 | Grill complete; Decision 1 v0.2 · Failure Domain Contract v0.1; P0.1g-B→CFC-1纠偏 |
