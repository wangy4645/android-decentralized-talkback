# ADR-0043 Option Matrix (A / B / C)

**Status:** **DECIDED** · supports [ADR-0043](../adr/0043-conference-recovery-membership-context-boundary.md)  
**Date:** 2026-08-08  
**Decision:** **ACCEPT Option A** · Implementation **NOT AUTHORIZED**

---

## Decision board

```text
ADR-0042                  CLOSED
RCA-0036                  CLOSED (observation)
ADR-0043                  ACCEPTED · OPTION_A
Implementation            NOT AUTHORIZED
Standalone B              REJECTED
Option C                  DEFERRED (insufficient evidence)
```

---

## Constraint (unchanged)

Snapshot emit requires `TalkbackSession` → standalone **B incomplete**.

---

## Outcome

| Option | Result |
|--------|--------|
| **A** | **ACCEPTED** — recovery must establish/restore accepted membership context before GROUP_RESYNC needing snapshot |
| **B** | **REJECTED** standalone |
| **C** | **DEFERRED** — reopen only if accepted TalkbackSession context cannot be restored |

Next gate (not opened): **ADR-0043 implementation plan review** — answer *when* recovery has enough info to establish context; still no code until that plan is APPROVED.
