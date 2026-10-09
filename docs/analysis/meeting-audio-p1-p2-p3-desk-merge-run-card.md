# Meeting Audio — P1 / P2 / P3 Desk Merge Run Card

**Status:** **CLOSED** (2026-10-09)  
**Scope:** JVM desk + late-join regression only — **not** field listening-quality proof.

```text
P1 local Top-K self-mix exclusion     DESK PASS / KEEP / CLOSED
P2 per-source Opus decoder isolation  DESK PASS / KEEP / CLOSED
P3 jitter / store single authority      DESK PASS / REGRESSION PASS / KEEP / CLOSED
Four-machine Meeting listening quality  UNKNOWN (no before/after)
```

**Frozen for this line:** encoder, ULE, gain, topology, ingress policy; no new RCA or observation phase.

---

## Canonical merge gate (production source of truth)

**Tree:** `talkback/android-board-talkback` (Gradle root: `talkback/`).

### Command A — production compile (required)

```powershell
Set-Location d:\workspace\project\talkback\talkback
.\gradlew.bat :android-board-talkback:compileDebugKotlin --no-daemon
```

**Pass:** `BUILD SUCCESSFUL`.

### Command B — scoped JVM desk on canonical

**Not available today** without fixing unrelated `src/test` compile errors (e.g.
`RealIceRestartRecoveryExecutorTest.kt` unresolved references).  
`testDebugUnitTest` always runs `compileDebugUnitTestKotlin` for the whole module.

Do **not** change Gradle test filters or source sets solely to paper over this; use Command C
for desk until canonical unit-test compile is green.

---

## Desk + F9.2 regression (historical field-product vehicle)

**Tree:** `talkback-field-product/` — mirrors canonical P1/P2/P3 production + desk tests;
retains prior signed desk record. **Not** a second production source of truth.

### Command C — single combined desk + F9.2 (verified 2026-10-09)

```powershell
Set-Location d:\workspace\project\talkback\talkback-field-product
.\gradlew.bat :android-board-talkback:compileDebugKotlin `
  :android-board-talkback:testDebugUnitTest `
  --tests "com.talkback.core.conference.session.integration.Profile01Gurgle*" `
  --tests "com.talkback.core.conference.session.integration.Profile01P3*" `
  --tests "com.talkback.core.conference.session.integration.Profile01A3PeerLocalTopKExclusionDeskTest" `
  --tests "com.talkback.core.conference.session.integration.Profile01P2*" `
  --tests "com.talkback.core.conference.transport.Phase1ThreeSourcePipelineTest" `
  --tests "com.talkback.core.conference.session.integration.Profile01F92LateJoinSourceMixAlignmentDeskTest" `
  --no-daemon
```

**Pass:** `BUILD SUCCESSFUL`.

**Desk methods (13, P3 sign-off set — F9.2 separate class):**

| Filter | Classes / notes |
|--------|------------------|
| `Profile01Gurgle*` | Local self-mix, shared decoder, payload store bypass (6 methods) |
| `Profile01P3*` | PLC/SILENCE no store decode (1) |
| `Profile01A3PeerLocalTopKExclusionDeskTest` | P1 Top-K exclusion (1) |
| `Profile01P2*` | Stale incarnation decoder isolation (3) |
| `Phase1ThreeSourcePipelineTest` | Phase1 three-source pipeline (2) |

**F9.2 regression:** `Profile01F92LateJoinSourceMixAlignmentDeskTest` (6 methods) — included in Command C.

Optional P1 APM reverse PCM contract (not in the 13-count set):

`--tests com.talkback.core.conference.session.integration.Profile01P1ApmPlayoutReversePcmContractDeskTest`

---

## Merge checklist (minimal)

1. Command A on **canonical** — PASS.
2. Command C on **field-product** — PASS (desk 13 + F9.2 six).
3. Do **not** infer field audio improvement from (1) or (2).

---

## When canonical test compile is fixed

Replace Command B with the same `--tests` list as Command C (without switching production tree).
Until then: **two commands** — A (canonical) + C (field-product desk).
