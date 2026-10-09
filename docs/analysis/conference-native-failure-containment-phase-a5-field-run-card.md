# PR-A5 Field Run Card — CFC-1 PARTIAL FIELD (E + D)

**ID:** conference-native-failure-containment-phase-a5-field-run-card  
**Date:** 2026-08-22  
**Authorization:** [PR-A5](./conference-native-failure-containment-phase-a-runtime-wiring-authorization.md) **ACCEPTED**  
**Gate:** CFC-1 PARTIAL FIELD only · **NOT** P0.1g Acceptance 1 · **NOT** CFC-1 FULL

---

## Tags (required on every log / adjudication)

```text
Phase A · PR-A5 · CFC-1 PARTIAL FIELD · E+D only
B2-1 RETAIN · Acceptance 1 HOLD · L3/L4 OUT OF SCOPE
```

---

## Topology pre-gate

```text
HOST = M01
CURRENT_ANCHOR = M01
```

If `anchor != M01` → **INVALID RUN** (do not adjudicate CFC-1).

**Devices:** M01 `HTUBB21B09220661` · M03 `MDX0220416001963` · M02/M04 as available for Scenario D.

---

## Scenario E — cause edge hang

**Inject / observe:** M01→M03 SRD native non-return (existing hang path).

**Must see in logs:**

```text
CONFERENCE_MEDIA_EDGE_FAILED ... reason=SRD_TIMEOUT  (or EDGE_LOCAL_FAILURE on cause)
CONFERENCE_FAILURE_CAUSE_INPUT ...
CONFERENCE_FAILURE_L1_CLASSIFIED ...
CONFERENCE_FAILURE_PROJECTED ... displayState=VISIBLE_EDGE_FAILED
CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=E
```

**Must NOT:** indefinite CONNECTING on M03 participant after classification.

---

## Scenario D — domain contention impact

**Observe:** while M03 holds native lease / hangs, M04 SRD hits `NATIVE_DOMAIN_LEASE_BUSY`.

**Must see:**

```text
NATIVE_DOMAIN_LEASE_BUSY ... holderEdgeKey=...M03...
CONFERENCE_FAILURE_L2_CLASSIFIED ... causeEdgeKey=...M03... causeFact=LEASE_HELD_BY_CAUSE
  runtimeDomainRef=shared-factory
CONFERENCE_FAILURE_PROJECTED ... displayState=VISIBLE_DOMAIN_BLOCKED blockedByDomain=true
CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=D
```

**Must NOT:** M04 classified as EDGE_FAILED for peer-caused block · auto Conference DEGRADED as PASS criterion.

---

## Adjudication

| Result | Condition |
|--------|-----------|
| **CFC-1 PARTIAL FIELD PASS** | E PASS ∧ D PASS |
| **PARTIAL** | E only or D only — not Field PASS |
| **FAIL** | invisible CONNECTING after classification · missing attribution · impact EDGE_FAILED for Busy |
| **INVALID** | wrong anchor · admission stuck · recovery intervenes |

**PASS wording (only):**

```text
CFC-1 PARTIAL FIELD PASS (Scenario E ∧ D)
P0.1g Acceptance 1 remains HOLD — ORIGINAL
```

---

## Out of scope this run

```text
P0.1g Acceptance 1
native isolation
D-β / D-γ
L3 / L4 / MediaUsable
topology / recovery fixes
```

---

## Record

| Field | Value |
|-------|-------|
| Run id / log dir | |
| Scenario E | PASS / FAIL / INVALID |
| Scenario D | PASS / FAIL / INVALID |
| Field verdict | |
| Notes | |
