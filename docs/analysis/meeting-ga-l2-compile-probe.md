# L2 (`faf8884`) Compile Probe — RCA5-A on post-L1 prefix

**Status:** ADOPTED finding (2026-10-09)  
**Authorization:** Offline probe only — no PR merge, no L2 branch push, `#203` remains Draft  
**Context:** Scheme B · base `origin/substrate/l1-ops` @ `413d4ff` (L1 Option A, no `d06f17c`)  
**Not:** merge authorization · listening-quality · field work

---

## Summary

| Checkpoint | `compileDebugKotlin` | Notes |
|------------|----------------------|--------|
| L1 prefix (`substrate/l1-ops` @ `413d4ff`) | PASS | Prior gate |
| L1 + cherry-pick **`faf8884`** only (local `probe/l2-rca5a` @ `5af8c67`) | **FAIL** | Unresolved L3-only symbols |
| L1 + `d06f17c` + `faf8884` (not run) | **Expected FAIL** | `d06f17c` already fails without L3 per L1 RCA |

**Conclusion:** **`faf8884` is not an independently compilable L2 closure** on top of the approved L1 prefix. New RCA5-A files import types that are **introduced only in L3 `1ab10ae`**, and those defining files are **absent from the `faf8884` commit tree** itself.

---

## Probe procedure

Worktree: `talkback-substrate-l1`

```text
git reset --hard origin/substrate/l1-ops   # 413d4ff
git checkout -b probe/l2-rca5a
git cherry-pick faf8884                    # → 5af8c67
./gradlew compileDebugKotlin
```

Cherry-pick: **clean** (5 files, +1486). Compile: **FAIL** (~13s).

---

## Representative unresolved symbols

All from new L2 integration files under `.../session/integration/`:

| Symbol / package | Introduced in (repo history) |
|------------------|------------------------------|
| `ConferenceSessionMediaBridge` | `1ab10ae` (Meeting GA baseline) |
| `MeetingProductMediaShadow` | `1ab10ae` |
| `MemberBindingFact`, `SessionMediaRuntimeSnapshot` | `1ab10ae` |
| `PipelinePlayoutResult`, `AbsoluteMediaPlayoutClock` | transport/playout stack (`1ab10ae` tree) |
| `ReplacementCutoverRc1`, `AudibleOwnershipState` | cutover stack (`1ab10ae` tree) |

`git ls-tree faf8884` contains **none** of the defining `ConferenceSessionMediaBridge.kt` / shadow transport paths.

---

## Historical lineage vs compile closure

On the GA integration line (`1ab10ae` ← `faf8884` ← `d06f17c` ← …):

- **Git parent order:** `faf8884` is the **parent** of `1ab10ae`.
- **Symbol order:** `faf8884` **references** types that **`1ab10ae` adds** to the tree.

So the branch was integrated as a **forward-dependent pair**: RCA5-A landed in history **before** the GA baseline commit that supplies its compile surface. A **single-commit L2 PR** onto post-L1 `main` cannot reproduce that closure without also landing (at least) the **`1ab10ae` introducer files** referenced by the patch.

This does **not** change the frozen final tip `9c88067`; it constrains **how** L2/L3 may be sliced into reviewable PRs.

---

## Implications for Scheme B (requires written adjudication)

1. **Inventory row “L2 depends on L1 (`d06f17c`)”** is **insufficient** for compile: L2 also depends on **L3 introducer symbols** (and `d06f17c` depends on the same GA surface per `meeting-ga-l1-compile-dependency-rca.md`).
2. **Options (pick one before opening L2 PR):**
   - **A.** Merge **L3 baseline slice** (`1ab10ae` or a agreed minimal file subset) **before** or **with** `faf8884` in one compile-closed PR.  
     **Static closure draft:** `meeting-ga-l2-dep-closure-review.md` + `artifacts/l2-closure/closure-tiered.txt` (~66 conference + L2 seeds).
   - **B.** **Reorder** documented layers: treat “L3 introducers required by RCA5-A” as a hard dependency edge (L2 PR base = post-L1 `main` + introducer closure, not L1 alone).
   - **C.** **Split `faf8884`** into a compilable subset on L1 — **new authoring**; out of scope unless explicitly authorized (not this probe).
3. **`d06f17c`:** Still deferred from L1; adding it **before** `faf8884` on L1 prefix does **not** fix L2 compile (missing bridge/shadow types remain).

---

## Unchanged constraints

- PR **#203** — Draft, not merged without human approval.
- PR **#200** — disposition unchanged; do not double-merge `faf8884`.
- P1/P2/P3 **CLOSED** · listening **UNKNOWN** · no encoder/ULE/ingress changes from this probe.

---

## Local probe branch

`probe/l2-rca5a` @ `5af8c67` exists only in **`talkback-substrate-l1`** worktree; **not pushed**. Safe to delete after review.
