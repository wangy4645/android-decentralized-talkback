# IA-002 Implementation Execution Authorization

**ID:** conference-edge-executor-admission-protection-ia-002-execution  
**Date:** 2026-08-21  
**Type:** Implementation Execution Authorization · Bounded  
**Status:** **AUTHORIZED** · field validation **PASS** (`logs/ia002-admission-20260821-120004/`)

**Parent contract:** [conference-edge-executor-task-completion-contract.md](./conference-edge-executor-task-completion-contract.md) (ACCEPTED v0.1 FROZEN)  
**Parent authorization:** [conference-edge-executor-admission-protection-ia-002.md](./conference-edge-executor-admission-protection-ia-002.md)

---

## Goal

Satisfy **MEDIA_CRITICAL admission contract only** — not full P0.1g Acceptance 1.

---

## Authorized scope

| Package | Authorized |
|---------|------------|
| IA-002-1 Task classification | `MEDIA_CRITICAL` / `MEDIA_CONTROL` / `OBSERVATION` on first-batch workloads |
| IA-002-2 Observability | `EDGE_TASK_SUBMIT/START/END/BLOCKED` + category/origin/timestamps |
| IA-002-3 Admission protection | OBSERVATION must not deny MEDIA_CRITICAL `START` |

**Constraints:** no business semantic change · no call-order change · no timeout change.

---

## Explicitly NOT authorized

```text
executor architecture rewrite
thread pool redesign
priority scheduler redesign
WebRTC getStats fix
SRD timeout adjustment
retry / ICE restart
Recovery / Ready / Topology / Anchor
```

---

## Completion criterion

**PASS:**

```text
OBSERVATION blocked (no END)
        |
        v
SRD_APPLY START
```

**Still allowed after IA-002:**

```text
SRD_APPLY START → native failure   (P0.1g-B / WebRTC — out of IA-002 scope)
```

**Not required:** M04 full conference success.

---

## Next after implementation

```text
Desk validation (single edge · observation poison · verify MEDIA_CRITICAL START)
        |
        v
P0.1g Acceptance 1 resume authorization (separate)
```

---

## Status board

```text
Conference Initial Anchor        ACCEPTED
IA-001                           PASS
P0.1g Acceptance 1                HOLD
Edge Executor Contract            ACCEPTED v0.1 FROZEN
IA-002 Execution                  AUTHORIZED → IN PROGRESS
Scope                             MEDIA_CRITICAL admission only
```
