# Meeting Product Integration — Replacement Readiness Review

**Status:** **CLOSED / FROZEN** (2026-09-10)  
**Prerequisite:** Phase A **CLOSED / FIELD-PROVEN** — `meeting-product-integration-phase-a-sr6-field-closure.md`  
**RC1 closure:** `replacement-cutover-rc1-field-closure.md`  
**Controlled pilot:** `meeting-product-controlled-pilot-evidence-set.md` — **CLOSED / 3× FIELD-PROVEN**  
**Next:** GA / ADR-0056 Retirement gates — `meeting-product-ga-adr0056-retirement-gates.md`

```text
Phase A integration              100%  CLOSED / FIELD-PROVEN
Replacement Sweep-1              CLOSED / FROZEN
RC1 Audible Ownership          CLOSED / FIELD-PROVEN

Controlled Pilot                 CLOSED / 3× FIELD-PROVEN
Controlled-pilot readiness     100%
Controlled pilot evidence      PASS

Rollback blocker               CLOSED / FIELD-PROVEN
Cutover policy blocker         CLOSED / FIELD-PROVEN
Full replacement / GA          NOT AUTHORIZED (~96–97%)
ADR-0056 deletion              NOT AUTHORIZED

PA-SR1–SR6                     CLOSED / FIELD-PROVEN
NO SR7 · NO RC2                integration + flip gates CLOSED
ADR-0056                       KEEP / rollback path
G-RES-3                        PILOT/SCALE BLOCKER
Long stability soak            KNOWN RISK

CODE DEFAULT                   NO KOTLIN CHANGE
```


**Sweep-1 posture:** default **NOT BLOCKER** unless invariant violation, explicit FAIL, or missing safety proof for user-hearable ownership transfer. ADR-0060 G-RES bundle ≠ automatic cutover block when ADR-0056 remains authoritative rollback.

---

## Nature lock

This review is **evidence adjudication + risk classification + cutover strategy** — **not** integration implementation.

```text
IN SCOPE     Classify remaining gaps · map to cutover/pilot blockers · define evidence plan
OUT OF SCOPE Re-open Phase A SR chain · preemptive Kotlin · harness re-proof of closed gates
             Mix capacity/stability back into integration closure criteria
```

---

## Verdict taxonomy (Sweep-1 frozen)

| Verdict | Rule |
|---------|------|
| **NOT BLOCKER** | Sufficient evidence for controlled user-hearable cutover with ADR-0056 coexistence |
| **CUTOVER BLOCKER** | Explicit FAIL affecting replacement safety **or** missing proof required to transfer user-hearable ownership |
| **PILOT BLOCKER** | Blocks real MANET pilot / declared production envelope — not initial code-path switch |
| **KNOWN RISK** | Documented limitation with containment; accepted for cutover discussion |
| **DEFERRED** | Out of current cutover horizon |

Distinction enforced: **code-path switch** (Phase B enable + ADR-0056 rollback available) vs **MANET pilot / N_max envelope / ADR-0056 deletion**.

---

## Cutover Blocker Sweep-1 — decision table

| Item | Current evidence | Verdict | Missing evidence | Action before cutover |
|------|------------------|---------|----------------|----------------------|
| **G-RES-1** | P02 A1–A10 adjudication **CLOSED**; unicast fan-out **not in product runtime**; Phase A multicast path **FIELD-PROVEN** M02→M01 | **NOT BLOCKER** | Unicast correctness impl (Profile 02 C1–C8) | — (defer to MANET pilot track) |
| **G-RES-2** | P03 R1–R10 semantics **CLOSED**; mid-session downgrade impl **PENDING**; Phase A operated without downgrade requirement | **NOT BLOCKER** | C1–C10 + X1–X3 implementation PASS | — (required before ADR-0056 deletion / hostile RF standalone) |
| **G-RES-3** | C1–C4 contract **CLOSED**; **0/3** VALID PASS @ N=9; Phase 1 receiver @ N=10 **CLOSED** (orthogonal) | **PILOT BLOCKER** | 3×VALID PASS @ N=9 per E1 | Run G-RES-3 benchmark harness before declaring N=9 envelope or deleting ADR-0056 |
| **G-RES-4/5** | G-RES-4 P1–P7 **CLOSED**; pub/consume impl PENDING; E2b R01–R16 unit **16/16 PASS** (`0058-e2b-r01-r16-conformance-audit.md`); resilience C1–C10 PENDING | **NOT BLOCKER** | G-RES-4 C1–C12 + resilience extension | — (orthogonal to Phase A shadow→AudioTrack flip) |
| **Real Mesh 3-device** | 2-device Real Mesh **PASS** (`real-mesh-e2e-20260907-151200`); Phase A 2-device **PASS**; M03 same-L3 **ENV_BLOCKED**; 3-device product-chain Wi-Fi sim **PASS** (`product-chain-field-soak-20260907-144912`) | **NOT BLOCKER** | Same-underlay 3-node Real Mesh field | — (required before MANET 3-device pilot sign-off) |
| **Capacity / stability soak** | Product-chain 3-device soak **PASS**; Real Mesh 120s **PASS**; Phase A SR1–SR6 incl. teardown **PASS**; long-duration Meeting product soak **not closed** | **KNOWN RISK** | Extended product-path endurance card (optional) | Accept Phase A + SR6 quiescence as cutover baseline; schedule soak before wide pilot |
| **Rollback** | RC1 field **PASS** — manual rollback · anchor restore · no zombie multicast (`replacement-cutover-rc1-field-20260910-140843`) | **CLOSED** | — | — |
| **Cutover policy** | ADR-0062 **ACCEPTED** · RC1 field **PASS** — arm/execute · production writes · single-owner invariant | **CLOSED** | — | — |

### Sweep-1 tally (frozen)

```text
Replacement Readiness Review — Cutover Blocker Sweep-1
Candidate items:  8
NOT BLOCKER:      4   (G-RES-1, G-RES-2, G-RES-4/5, Real Mesh 3-device)
KNOWN RISK:       1   (Capacity/stability soak)
PILOT BLOCKER:    1   (G-RES-3 — scale/deletion track; does not block Controlled Pilot)
CUTOVER BLOCKER:  0   (Rollback + Cutover policy — CLOSED via RC1 field)
```

**RC1 CLOSED / FIELD-PROVEN** — `0062-replacement-cutover-rc1-audible-ownership-transition.md`  
Evidence: `talkback/logs/replacement-cutover-rc1-field-20260910-140843/`  
G-RES-3 runs on the **Scale/Deletion** track in parallel with Controlled Pilot Run-1.

### Rationale notes (Sweep-1)

**G-RES-3 — PILOT BLOCKER, not CUTOVER BLOCKER:** Evidence PENDING is not FAIL. G-RES-3-C4-I10: PASS proves N=9 unicast fan-out transport capacity only. Phase A cutover targets 2-device operational envelope already field-proven. Blocks **N=9 declaration** and **ADR-0056 deletion** — not shadow→user-hearable switch with Anchor rollback.

**G-RES-1/2/4 — NOT BLOCKER:** Contract adjudication complete; no field FAIL on current multicast path. ADR-0060 formal bundle gates **deletion** and **standalone MANET resilience** — not first controlled cutover while ADR-0056 remains authoritative.

**Real Mesh 3-device — NOT BLOCKER:** ENV_BLOCKED is topology availability, not integration regression. 2-device Real Mesh + Phase A closure sufficient for controlled cutover scope.

---

## Out-of-sweep items (unchanged)

| Item | Verdict | Notes |
|------|---------|-------|
| Phase A shadow boundaries | Absorbed into cutover policy | Resolved when cutover ADR defines AudioTrack enable |
| M01→M02 RX asymmetry | **DEFERRED** | Field limitation; scope cutover to M02→M01 if needed |
| ADR-0039 owner conflict | **DEFERRED** | Independent WiFi recovery track |

---

## What Phase A already closed (do not re-litigate)

```text
Control-fact origin + Profile01 apply (J0)
Shadow runtime start/stop wiring (SR1–SR2)
Source chain + post-bind publication (SR3)
TX → RX → ingress → playout reachability (SR4)
Leave/rejoin without stale resurrection (SR5)
Meeting end → TEARDOWN_EMPTY + quiescence (SR6)
```

---

## Exit criteria — SATISFIED

```text
1. Sweep-1 decision table ACCEPTED (this document)           ✓
2. CUTOVER BLOCKER set empty (RC1 field PASS)                ✓
3. Controlled Pilot Run-1 authorized (Pilot Track)           ✓
4. G-RES-3 + soak on Scale/Deletion track (parallel)         authorized — not blocking pilot
```

Review **CLOSED**. Controlled Pilot minimum evidence set **CLOSED / 3× FIELD-PROVEN**.  
**Next:** G1 Scale · G2 Real Mesh · G3 Stability (parallel) — `meeting-product-ga-adr0056-retirement-gates.md`.  
Full production cutover and ADR-0056 deletion remain **NOT AUTHORIZED** until Retirement Review.

---

## Explicit non-goals

```text
PA-SR7 or any new Phase A integration gate
Re-proof closed SR1–SR6 without new ADR
Conflate G-RES-3 with Phase A closure
Claim cutover ready from integration PASS alone
```

---

## References

- Phase A closure: `meeting-product-integration-phase-a-sr6-field-closure.md`
- Integration gate: `meeting-product-integration-gate.md`
- ADR-0060 resilience / G-RES blockers: `docs/adr/0060-conference-media-transport-resilience-contract.md`
- ADR-0056: `docs/adr/0056-conference-topology-first.md`
- Real Mesh E2E: `real-mesh-e2e-underlay-migration-run-card.md`
- G-RES-3 adjudication: `0060-gres3-c1-product-capacity-envelope-adjudication.md` (and related 0060-gres* docs)
