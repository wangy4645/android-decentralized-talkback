# PR #200 Disposition — RCA5-A vs Scheme B L2

**Status:** **ADOPTED** (2026-10-09)  
**Program:** Scheme B layered substrate → `origin/main`, final tip `9c88067`  
**Inventory:** `meeting-ga-substrate-layered-pr-boundary-inventory.md`

---

## Facts

| Item | Value |
|------|--------|
| PR | https://github.com/wangy4645/android-decentralized-talkback/pull/200 |
| Title | fix(media): RCA5-A serialize shadow ingress under pipeline lock |
| Base (at open) | `investigate/ui-anchor-0056-198` |
| Head | `fix/rca5a-ingress-pipeline-lock` @ **`faf8884`** |
| Commits on PR | `d06f17c` (SRD fence) + **`faf8884`** (RCA5-A) |
| Target history | `faf8884` is **L2** tip in Scheme B; `d06f17c` is **L1** tip |

---

## Decision

**Disposition: close PR #200 as superseded by Scheme B layered landing.**

- RCA5-A (`faf8884`) will enter `main` **only** via **substrate L2** PR (base = post-L1 `main`, head = `faf8884`), not via parallel merge of PR #200.
- SRD fence (`d06f17c`) will enter `main` via **substrate L1** PR, not via PR #200.
- **Forbidden:** merging PR #200 into any base after L1/L2 land the same commits — duplicate expression of the same patches.

---

## Rationale

1. PR #200 bundles L1-end + L2 in one review against `investigate/…`, which does not match the frozen L0→L6 sequence or `main` as integration target.
2. `faf8884` is already in the audited baseline path to `9c88067`.
3. Closing #200 removes a second merge vehicle and forces a single RCA5-A admission path (L2).

---

## Execution note (when landing begins)

1. Close PR #200 with reference to this disposition and L2 PR (when opened).
2. Do **not** reopen P1/P2/P3 or listening-quality work.
3. Open **L0** before L1; open **L2** only after L1 merges.

---

## Alternatives considered (rejected)

| Option | Why rejected |
|--------|----------------|
| Merge PR #200 as-is into `investigate/…` then rebase to `main` | Leaves parallel history; still duplicates L1/L2 if baseline path also lands |
| Retarget PR #200 as L2-only onto post-L1 `main` | Possible mechanically, but PR still contains `d06f17c`; cleaner to close and use `substrate/l2-rca5a` branch at `faf8884` |
| Cherry-pick `faf8884` alone onto `main` without L1 | Violates frozen dependency (L2 base must be `d06f17c`) |
