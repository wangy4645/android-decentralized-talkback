# L2 dependency closure — static analysis artifacts

**Purpose:** Support Scheme B **Option A** — minimal L3 introducer slice for `faf8884`, without moving code yet.

| File | Description |
|------|-------------|
| `closure-tiered.txt` | **Primary list** — tiered BFS @ `1ab10ae` (~68 GA paths + stats) |
| `closure-file-list-v2.txt` | Over-broad identifier BFS (551 files) — **do not use** as merge scope |
| `_closure_bfs_tiered.py` | Regenerate `closure-tiered.txt` from repo root `talkback/` |
| `_closure_bfs.py` | Import-only first pass (under-counts) |

**Canonical write-up:** `../meeting-ga-l2-dep-closure-review.md`

**Not authorized here:** `git checkout` / cherry-pick / compile runs — design only until human GO on §5 of the write-up.
