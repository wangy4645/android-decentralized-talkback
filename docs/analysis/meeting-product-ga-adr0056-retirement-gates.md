# GA / ADR-0056 Retirement — Final Evidence Gates

**Status:** **FROZEN / GA AUTHORIZED** — main line CLOSED; G3b NOT SCHEDULED until Retirement Track opens  
**Prerequisite:** Controlled Pilot minimum evidence set **CLOSED / 3× FIELD-PROVEN**  
**Evidence:** `meeting-product-controlled-pilot-evidence-set.md`  
**Harness:** `talkback/scripts/meeting-product-ga-evidence-set-orchestrator.ps1`

---

## Posture lock

```text
Phase A                     CLOSED / FIELD-PROVEN
Replacement Sweep-1         CLOSED / FROZEN
RC1 Audible Ownership       CLOSED / FIELD-PROVEN

Controlled Pilot
  Run-1                     PASS
  Run-2                     PASS
  Run-3                     PASS
  Minimum Evidence Set      CLOSED / 3× FIELD-PROVEN
  multicast use per run     ~77–82s (cutover→play→end→teardown proven)

Controlled-pilot readiness  100%
Controlled pilot evidence   PASS

ADR-0056                    KEEP / rollback path
G1 G-RES-3 @ N=9            CLOSED / FIELD-PROVEN (3× VALID PASS)
G2 Real Mesh 3-node         CLOSED / FIELD-PROVEN
G3a stability               CLOSED / FIELD-PROVEN
G3b extended soak           NOT SCHEDULED (retirement track only)

GA Authorization Review     FROZEN / AUTHORIZED
Meeting product main line   CLOSED
  closure: meeting-product-ga-authorization-review.md
ADR-0056 deletion           NOT AUTHORIZED (needs G3b + Retirement Review)
```

**Development mode:** productization / release — ordinary Meeting bugs are **GA production defects**, not feasibility gate failures.

**Do not:** schedule G3b without Retirement Track · Run-4/5/10 for identical scope · reopen G1/G2/G3a integration gates · interpret `jitterHits` log-token counts as jitter failures.

---

## Last engineering questions (split)

> **GA:** Does multicast have enough scale / real underlay / minimum duration evidence to become the **default Meeting media path** while ADR-0056 remains rollback?

> **Retirement:** Does longer soak + field confidence justify **deleting** ADR-0056?

Controlled pilot answered short-path product use. Remaining gates answer scale, real mesh, and **time-dimension** risk — with GA vs retirement **decoupled**.

---

## Final gates (parallel — not serial)

### G1 — Scale

```text
G-RES-3 N=9
→ 3× VALID PASS
```

Blocks: N=9 envelope · ADR-0056 deletion.  
Does **not** block controlled pilot (already CLOSED).

### G2 — Real deployment

```text
≥3-node Real Mesh
→ non Wi-Fi simulation
→ operational / media / teardown
```

Blocks: MANET 3-device pilot sign-off · GA default-path claim on real underlay.

### G3 — Stability (split)

**2h is an engineering soak window, not a system-correctness theoretical threshold.**

```text
G3a — GA minimum stability          ≥15 min continuous production multicast
G3b — extended soak (post-GA)       ≥2h continuous production multicast
```

| Tier | Duration | Blocks |
|------|----------|--------|
| **G3a** | ≥15 min | **GA Authorization Review** |
| **G3b** | ≥2h | **ADR-0056 Retirement Review** only — does **not** block GA |

G3a / G3b hard FAIL (unchanged):

```text
dualOwner = 0
runtimeFatal = 0
unexpectedRollback = 0
production media continues
session remains operational
teardownEmpty = true
```

**Soft telemetry (observe, do not auto-FAIL):**

```text
jitterHits log-token counts     → NOT jitter failure count
PLC · isolated underrun         → observe only
```

---

## Exit — two reviews (not one)

### When G1 + G2 + G3a PASS → GA Authorization Review

```text
Decide:
  • multicast as default Meeting media path?
  • GA scope (node count / underlay)?
  • ADR-0056 KEEP as rollback (expected default at this stage)
```

GA authorization posture (frozen 2026-09-15):

```text
ADR-0058                    GA DEFAULT PRODUCTION PATH
ADR-0056                    retained rollback path
Pilot flag                  lifted for GA scope
G3b >=2h                    NOT SCHEDULED — retirement confidence only
ADR-0056 deletion           NOT AUTHORIZED until Retirement Track opens
```

### When G3b (+ field confidence) PASS → ADR-0056 Retirement Review

```text
Decide:
  • timeline / conditions to remove Anchor rollback
  • ADR-0056 deletion schedule
```

```text
GA AUTHORIZED  ≠  ADR-0056 DELETED
```

---

## Parallel execution plan

| Gate | Track | Blocks other gates? | Blocks GA? | Blocks ADR-0056 delete? |
|------|-------|---------------------|------------|-------------------------|
| G1 G-RES-3 N=9 | Scale | No | Yes | Yes |
| G2 Real Mesh 3-node | Deployment | No | Yes | Yes |
| **G3a ≥15 min** | Stability (GA) | No | **Yes** | No |
| G3b ≥2h | Stability (retirement) | No | **No** | Yes |

Run G1 / G2 / G3a in parallel. G3b may run after or alongside but **must not delay GA Review**.

### Semantic independence (frozen)

```text
G1  N=9 capacity              — 3× VALID PASS @ N=9 only
G2  Real underlay             — ≥3 real nodes, same mesh L3 (one run)
G3a Duration (GA minimum)     — ≥15 min production multicast soak (PaG2 3-node)
G3b Duration (retirement)     — ≥2h soak — does NOT substitute G1/G2; does NOT block GA

G3b @ 8h does NOT substitute G1.
G1 3× PASS does NOT substitute G2 real underlay.
G3a PASS does NOT substitute G3b for ADR-0056 deletion.
No G4/G5 after GA gates PASS.
```

### Acceleration rules (frozen)

```text
INVALID          → fix harness/environment → rerun (do not stop other gates)
PASS             → close that gate immediately
VALID FAIL       → RCA/Kotlin only for that gate
```

Per-gate scripts:

```text
G1   meeting-product-ga-g1-gres3-qualifying-set.ps1
G2   meeting-product-ga-g2-real-mesh-field-first-run.ps1 (-ThirdPeer M04)
G3a  meeting-product-ga-g3-stability-field-first-run.ps1 -Tier G3a  (-SoakSec 900 default)
      executionPath=PaG2_3node_real_mesh (G2 FIELD-PROVEN topology; T0=multicast ACTIVE)
      DO NOT use PaG3 two-node path for G3a
G3b  meeting-product-ga-g3-stability-field-first-run.ps1 -Tier G3b  (-SoakSec 7200)
```

---

## References

- GA authorization: `meeting-product-ga-authorization-review.md`
- G3a evidence set: `talkback/logs/ga-g3a-t1-teardown-field-20260915-091534/G3A_EVIDENCE_SET.txt`
- Controlled pilot closure: `meeting-product-controlled-pilot-evidence-set.md`
- Integration gate: `meeting-product-integration-gate.md`
- Readiness review: `meeting-product-integration-replacement-readiness-review.md`
- G-RES-3: `docs/adr/0060-conference-media-transport-resilience-contract.md`
- Real Mesh E2E: `real-mesh-e2e-underlay-migration-run-card.md`
- ADR-0056: `docs/adr/0056-*.md` (KEEP)
