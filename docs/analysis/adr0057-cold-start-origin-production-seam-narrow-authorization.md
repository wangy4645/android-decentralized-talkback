# ADR-0057 Track C — Cold-Start Origin Production Seam Narrow Authorization

## Status

**AUTHORIZED — IMPLEMENTATION CUT** (2026-08-31)

```text
ADR-0057 Track C — F3

Finding:
  COLD-START GENERATION ORIGINATION GAP
  CONFIRMED

Classification:
  MISSING PRODUCTION ORIGIN SEAM

New architecture contract:
  NOT REQUIRED

Implementation cut:
  JUSTIFIED / AUTHORIZED

Required:
  CSO-IA-T1..T4
  CSO-EG1..EG6

GBC Core:
  PASS / FROZEN

ACQ Repair:
  FIELD CONFORMANCE PASS / CLOSED

Trust / VB / PV / PTI / PAP / OE / PW:
  PASS / FROZEN

Track C:
  OPEN

ADR-0057 COMPLETE:
  NO
```

**Parents:**

- [F3 seam audit](./adr0057-cold-start-origin-production-seam-audit.md)
- [Track C Case A adjudication](./adr0057-track-c-case-a-field-adjudication.md)
- [ACQ repair](./adr0057-acquisition-send-on-locate-wiring-repair.md) — **FIELD PASS / CLOSED**
- [GBC Contract Q1/Q2](../adr/0057-group-bootstrap-convergence-contract.md) — **FROZEN**

---

## Frozen gap

```text
authoritative bootstrap candidate
        +
GBC has no legal Current
        ↓
origin eligibility
        ↓
generation identity allocation
        ↓
PV-2 issuance
        ↓
authorized local-origin acceptance
        ↓
retained Current / re-provide ready
```

---

## CSO-IA-T1 — Origin authority from bootstrap candidate

```text
Cold-start origin MAY execute only if:

  local module == current authoritative bootstrap candidate
AND
  GBC has no legal Current for this channel
```

**Forbidden triggers (not authority):**

```text
UNKNOWN → origin
INSUFFICIENT → origin (may only trigger reevaluation)
waitingForPrimary → origin
no holder → origin
timeout → origin
```

## CSO-IA-T2 — Production genesis allocator

Per-channel, create-once, durable, restart-safe generation identity.
Not test `genesisAuthority("G1")` literals.

Successor generation semantics: **out of scope**.

## CSO-IA-T3 — Full PV-2 issuance transaction

```text
eligible origin
→ immutable AuthoritativeGenerationFact material (via signed authority)
→ PV-2 prepare → sign → finalize → publish gate
```

Forbidden: Coordinator.sign, unsigned FactStore writes, bypass finalize.

## CSO-IA-T4 — Authorized local-origin acceptance

```text
PV-2 finalized local issuance
→ VerificationBoundary (production verifier)
→ acceptVerifiedFact / equivalent GBC ingress
→ FactStore retain + verification material for re-provide
```

Local origin may bypass redundant remote-wire loop; **must not** bypass
authoritative-fact acceptance semantics.

---

## Execution model

Independent reevaluation seam — **not** `handleGenerationFactResponseInsufficient → originate()`:

```text
relevant state change
  → evaluateColdStartOriginEligibility()
  → attemptColdStartOriginIfEligible()
```

`INSUFFICIENT` may trigger reevaluation; it is not authority.

---

## Minimum fixtures (CSO-EG1..EG6)

| Gate | Requirement |
|------|-------------|
| EG1 | candidate + no legal Current → exactly one genesis issuance |
| EG2 | follower + UNKNOWN/INSUFFICIENT → no origin |
| EG3 | duplicate reevaluation → no duplicate genesis issuance |
| EG4 | after PV-2 finalize, local GBC retains same Current Fact |
| EG5 | follower FACT_REQUEST → origin re-provides exact Fact → VB/F1 |
| EG6 | restart restore — no second genesis; ADR-0057 regression PASS |

---

## Authorized scope

- Cold-start origin eligibility bridge
- Production genesis generation identity allocator
- RuntimeFactory → PV-2 issuance wiring
- Authorized local-origin acceptance / retain seam
- Re-provide integration
- CSO-EG1..EG6

## Not authorized

Successor redesign, election redesign, HELLO authority, timer origin,
INSUFFICIENT-as-authority, ACQ changes, VB/F1 semantic changes,
trust/PAP/OE/PW changes, PTT/Floor, Group/media fixes.

---

## Sign-off

```text
☑ F3 audit complete
☑ CSO-IA-T1..T4 frozen
☑ Implementation cut authorized
☑ CSO-EG1..EG6 PASS (Adr0057ColdStartOriginFixturesTest + ADR-0057 regression)
☑ Track C Case A re-run — CSO FIELD CONFORMANCE PASS
   evidence: logs/adr0057-track-c-case-a-20260831-160349
☑ Generation convergence FIELD PASS (frozen)
□ Group bootstrap residual — read-only audit complete; implementation NOT authorized
   audit: docs/analysis/adr0057-post-convergence-group-bootstrap-residual-audit.md
```

### Implementation map

| Invariant / Gate | Location |
|------------------|----------|
| CSO-IA-T1 eligibility | `ColdStartOriginOrchestrator.evaluateColdStartOriginEligibility` |
| CSO-IA-T2 allocator | `ChannelGenesisGenerationIdentityAllocator` + `FileChannelGenesisIdentityPersistence` |
| CSO-IA-T3 PV-2 | `GenerationFactOriginEmissionSeam` via `GbcOriginComposition` |
| CSO-IA-T4 acceptance | `AuthorizedLocalOriginAcceptance` → `VerificationBoundary` |
| Reevaluation seam | `TalkbackCoordinator.reevaluateColdStartOrigin` (INSUFFICIENT + bootstrap reconcile) |
| EG1..EG6 | `Adr0057ColdStartOriginFixturesTest` |
