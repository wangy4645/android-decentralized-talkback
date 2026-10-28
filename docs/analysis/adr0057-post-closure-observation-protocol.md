# Post-ADR-0057 — Observation & Failure-Domain Protocol

## Status

**ACTIVE** (governance; not an ADR)

```text
ADR-0057:     COMPLETE / CLOSED
F8-FIX-1:     FROZEN
F9:           NOT OPEN
```

**Parent:** [ADR-0057 complete closure](./adr0057-complete-closure.md)

---

## Core rule

```text
Symptom is not ownership.
```

Observations such as:

```text
"Syncing channel"
"transmitReady=false"
"ICE CHECKING"
"OPERATIONAL regression"
```

are **observations**, not ADR-0057 fingerprints.

---

## Reopen policy

```text
Reopen ADR-0057 ONLY when evidence contradicts
a frozen ADR-0057 invariant.

Otherwise: classify a new failure domain.
```

---

## Classification (default path)

| Symptom class | Likely domain | Action |
|---------------|---------------|--------|
| Syncing / readiness UI | bootstrap · media · playback · presentation | New incident; name domain |
| ICE drop after stable session | transport · network handoff · native resource | Post-ADR-0057 transport/network |
| Conference / Anchor | ADR-0058 / 0056 track | Independent ADR |
| Owner blocks recovery | ADR-0039 triggers A–E | Independent track |
| MANET host candidate missing (prod) | production-artifact integration | P0 incident — **not** ADR reopen |

**Anti-pattern:**

```text
See Syncing → suspect generation → reopen GBC → reopen ADR-0057
```

---

## Frozen ADR-0057 invariants (summary)

```text
authoritative generation convergence on admitted topology
bounded acquisition obligation closure
GROUP control bootstrap completion
lineage-safe ACCEPT/ICE on current PC
required admitted edges ICE_CONNECTED
stable GROUP readiness convergence (SELF_RECOVER)
```

Violation requires **specific contradicting evidence**, not symptom similarity.

---

## Post-closure engineering (COMPLETE)

```text
P0.1 Production integration:  PASS / CLOSED
P0.2 Production smoke:      PASS / CLOSED
Case A:                     regression smoke only
```

See [P0 engineering closure](./adr0057-p0-engineering-closure.md).
| Task | Doc |
|------|-----|
| F8-FIX-1 production integration | [production artifact integration](./adr0057-f8-fix1-production-artifact-integration.md) |
| Production Case A smoke | [production smoke run card](./adr0057-production-case-a-smoke-run-card.md) |
