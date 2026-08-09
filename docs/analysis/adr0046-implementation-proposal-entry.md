# ADR-0046 Implementation Proposal Entry

**Status:** **OPEN** (2026-08-09) · **Planning / Design Proposal AUTHORIZED** · **Runtime NOT AUTHORIZED until Design Decision**  
**Parent:** [0046-successor-admission-terminal-convergence-contract.md](../adr/0046-successor-admission-terminal-convergence-contract.md)  
**Observation:** [mobile-validation-successor-recovery-pending-observation.md](./mobile-validation-successor-recovery-pending-observation.md)

```text
Impl-Auth re-open (owner explicit)
        |
        v
Planning / Design Proposal  ✅ AUTHORIZED
        |
        v
Design Decision Q1          ✅ M1+M2 (binding + non-vacuous)
        |
        v
Design Proposal detail      ⏳ IN PROGRESS
        |
        v
Runtime Implementation      ❌ NOT AUTHORIZED YET
```

---

## Authorization triad (satisfied for re-open)

| Entry | Value |
|-------|--------|
| **Owner** | Explicit product/engineering owner (session grant 2026-08-09) |
| **Proposal entry** | This document |
| **Validation entry** | Post-design field: natural or authorized successor-path compliance check (SUCCESS and/or FAILED_MEDIA reachability **or** auditable contract-binding evidence at `ADMIT_SUCCESSOR`); **not** same-stimulus MISS loops as a “fix” |

---

## Goal (compliance, not “fix Sync”)

Make every `ADMIT_SUCCESSOR` / new successor obligation episode **carry** an admission-time auditable terminal convergence contract per ADR-0046 (T1'–O1').

```text
Goal:
  ADMIT_SUCCESSOR binds non-purely-external terminal convergence obligation

Not goal:
  UVCP / SYNCING copy change
  ADR-0045 clear expansion
  ADR-0038 predicate change
  “Recovery broken” narrative
  absorb M03→M01
```

---

## Frozen fences (unchanged)

```text
✗ amend ADR-0038 / 0045 / 0044
✗ UVCP / EndpointStatus rewrite
✗ ICE auto-terminal as phase owner
✗ SuccessorPolicy as default without Design Decision
✗ watchdog / timeout / retry numbers before Design Decision
✗ runtime patch before Design Decision accepted
```

Producer boundary (observation B1): terminal writes remain Recovery Controller + existing CompletionPolicy family — **unless** Design Decision explicitly re-opens that (default: do not).

---

## Design Decision (Q1 sealed)

> How shall admission-time contract binding be **realized** so S1' is not vacuous on the settling-defer path?

| Id | Role | Status |
|----|------|--------|
| **M1** | **Contract binding point** at `ADMIT_SUCCESSOR` (T1' / U1') | **ACCEPTED as necessary** |
| **M2** | **Non-vacuous coverage** for deferred `NEGOTIATION_SETTLING` / evaluable exit semantics (S1' / P1') | **ACCEPTED as required design-review dimension** |
| **M3** | Other Recovery-family binding | **REJECTED** (no evidence for new Recovery family) |

**Composite:** **M1 + M2** — not two parallel implementation tracks.

```text
M1 = where the contract attaches (admission)
M2 = that the contract remains non-hollow after defer/settling

≠ M1-only (risk: named contract, hollow obligation)
≠ M2-only (risk: fix settling without admission binding)
≠ M3 (new Recovery family / new owner)
```

**Still NOT authorized:** concrete mechanism, budgets, runtime code.

---

## Design Decision Q2 — sealed (boundary / validation)

| Id | Adjudication |
|----|--------------|
| **B1'** | **Minimal blast radius (baseline).** Touch only `ConferenceEdgeRecoveryController` successor admission / attempt-lifecycle seams and existing CompletionPolicy-family **call relationships**. No new Policy type; no new lifecycle type; no UVCP/presentation change; no ADR-0038 predicate text-semantic change. **B2** only via later explicit upgrade if B1' insufficient. **B3** rejected. |
| **V-desk'** | Desk/unit proof: after `ADMIT_SUCCESSOR`, contract binding is auditable **and** defer/settling does not form a hollow obligation residency. Proves contract presence + obligation integrity — **not** forced SUCCESS, not all-terminal coverage, not timeout definition. |
| **V-field'** | Authorized field observation of at least one non-purely-external terminal-convergence-related evidence **or** proof that contract binding was non-hollow in a real episode. No manufactured FAILED_MEDIA; no forced WiFi drops for evidence; one observation ≠ runtime rule. |

```text
B1' = approved design boundary baseline
B2 = escalate only if B1' proven insufficient
B3 = rejected
```

---

## Design Decision Q3 — sealed (C1' design gate)

| Id | Adjudication |
|----|--------------|
| **C1'** | Written Design Proposal closure is the minimum gate before **Runtime Authorization grill**. Must include M1 seam+ownership, M2 non-hollow under existing authority, non-goals/frozen domains, V-desk' case list. Must **not** require code, PR, passing runtime tests, or timeout/budget/retry definition. C2 optional; C3 = runtime-phase deliverable; C4 rejected. |

**C1' package:** [adr0046-design-proposal-c1.md](./adr0046-design-proposal-c1.md) — **DP-ACCEPT** (C1' complete). Next: **Runtime Authorization grill**.

---

## Stop / escalate

```text
Stop if design requires amending 0038 / 0045 / 0044
Stop if design makes ICE/UVCP a terminal writer
Stop if design treats SUCCESSOR_REPLACED as prior S1' satisfaction
Escalate: new ADR if O1' boundary conflict
Escalate B1' → B2 only with explicit decision
```
