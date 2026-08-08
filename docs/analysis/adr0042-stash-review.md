# ADR-0042 Stash — Independent Review (No Pop)

**Status:** **REVIEW ONLY** · **stash NOT applied** · **main unchanged**  
**Date:** 2026-08-08  
**Stash:** `stash@{0}` — `local backlog and adr0042 docs`  
**Context:** ADR-0043 CLOSED; evaluate whether ADR-0042 work should re-enter mainline

---

## Stash scope

```text
stash@{0} (3 files):
  docs/adr/0042-recovery-reattach-transport-delivery-semantics.md  (doc edits)
  docs/analysis/adr0042-implementation-plan.md                     (doc edits)
  talkback-app/build.gradle.kts                                    (build config)
```

**No runtime / signaling / recovery code** in this stash.

---

## Relationship to current tracks

```text
ADR-0043 Seam I:     CLOSED — do not mix
RNA intent lifecycle: may intersect transport attribution
ADR-0042:            transport SENT/delivery semantics (reattach consumer)
```

ADR-0042 addresses:

> Whether `SENT` truth and delivery confirmation are correctly attributed on the reattach path.

RNA `DEFERRED_INTENT_UNCOVERED` may involve transport, but Appendix B evidence shows **reattach delivered** and **ICE connected** on M03 edge — transport is not the primary stall signal in that episode.

---

## Recommendation

```text
Do NOT pop stash onto main directly.

Instead:
  1. Create/reuse branch from main (clean baseline post d2ac064)
  2. Cherry-pick or selectively apply doc-only changes if still accurate
  3. Review build.gradle.kts change in isolation — justify or discard
  4. Confirm ADR-0042 implementation branch status separately
     (adr0042-p0-reattach-transport-truth — desk PASS per implementation plan)
  5. Field validation for ADR-0042 remains PAUSED (per implementation plan)
```

---

## Decision gate (not yet made)

| Option | When |
|--------|------|
| Merge ADR-0042 P0 to main | After merge review + no ADR-0043 conflict |
| Discard stash docs | If superseded by branch state |
| Keep stash isolated | Until RNA/transport hypothesis clarified |

---

## One-line statement

> ADR-0042 stash is doc/build only — review on isolated branch; do not pop onto ADR-0043-clean main without explicit authorization.
