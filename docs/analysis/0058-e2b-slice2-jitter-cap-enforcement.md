# ADR-0058 E2b Slice 2 — Jitter Capacity Enforcement

## Status

**PASS / CLOSED** (2026-09-02)

```text
Target:   R12 only
Contract: FROZEN — no changes
ADR-0057: OUT OF SCOPE
```

**Change:** `MediaExecutionPipeline.admitFrame` consults `JitterSourceAllocator` before creating jitter state.

**Tests:** `Profile03E2bSlice2JitterCapTest`

---

## Slice 2 EXIT

| Gate | Result |
|------|--------|
| R12 REJECT_NEW on production pipeline | PASS |
| R12 RECLAIM_EXISTING_THEN_ALLOCATE | PASS |
| Unbound source NOT_ADMITTED | PASS |
| active jitter states ≤ 10 | PASS |
| R01–R11, R15, R16 regression | PASS |

```text
E2b after Slice 2:
  PASS: 14 (R01–R12, R15, R16)
  GAP:   2 (R13, R14)
  CONTRACT_CONTRADICTION: 0
```

---

## Next

Slice 3: C-IG-01 product Android seams (`MulticastLock` / socket rebind) → R13/R14
