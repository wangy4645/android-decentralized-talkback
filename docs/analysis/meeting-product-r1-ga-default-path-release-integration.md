# R1 — GA Default Path Release Integration

**Status:** **IMPLEMENTATION COMPLETE** — R2 field gate **ACTIVE** (2026-09-15)  
**Prerequisite:** GA Authorization Review **FROZEN / AUTHORIZED**  
**Blocks:** Release Candidate · MEETING RELEASE CANDIDATE

```text
GA architecture      CLOSED / AUTHORIZED
GA qualification     CLOSED / FIELD-PROVEN
R1 implementation    COMPLETE (readiness-driven auto cutover)
R2-A field         PENDING (3-node preflight; M03 required)
R2-B kill-switch   PASS (unit/debug focused evidence)
Release smoke        ACTIVE — see meeting-product-r2-release-smoke-run-card.md
```

---

## Goal

Wire the **already-authorized** ADR-0058 multicast path as the **release build default** — no adb broadcast, no manual pilot enable, no harness cutover.

```text
Meeting Start
    ↓
ADR-0058 multicast        ← DEFAULT (when readiness satisfied)
    │
    ├─ normal operation
    │
    └─ explicit kill-switch / rollback
           ↓
       ADR-0056 Anchor
```

---

## Scope (frozen)

### R1-1 — Kill-switch semantics

```text
pilotEnabled  →  multicastAudibleEnabled
default       =  true
scope         =  audible ownership cutover only
does NOT      =  disable shadow multicast runtime / observability
does NOT      =  automatic runtime fallback policy
```

Implementation: `ReplacementCutoverRc1.multicastAudibleEnabled`

### R1-2 — Readiness-driven automatic cutover

Trigger when **existing facts** are all true:

```text
Meeting operational          (session not in teardown / ingress not blocked)
multicast session materialized   (ConferenceSessionMediaBridge.hasSession)
local TX binding ready           (local module incarnation in catalog)
audible playout path ready       (Profile01ShadowPlayoutClockSeam armed)
ownership == ANCHOR_ACTIVE
        ↓
CUTOVER_ARMED → execute → MULTICAST_ACTIVE
```

**Not** timer-driven · **not** first-packet-driven.

Implementation:
- `MulticastAudibleCutoverReadiness`
- `MulticastAudibleAutomaticCutover`
- Hooks: `ConferenceSessionMediaCoordinatorDelegate` (session started + local member media ready)

### R1-3 — ADR-0062 role update (documentation only)

```text
ANCHOR_ACTIVE     = startup-safe state + rollback target
MULTICAST_ACTIVE  = GA default steady state
```

State machine transitions **unchanged** — RC1/G3a field evidence preserved.

### R1-4 — GA documentation correction

Authorization ≠ implementation. See `meeting-product-ga-authorization-review.md` § Pilot / kill-switch.

---

## Out of scope

```text
NO G3b
NO ADR-0056 deletion
NO N>9 re-qualification
NO hostile-RF / G-RES-2
NO multicast redesign
NO soft-ledger observability work
NO performance optimization without production defect
```

---

## R2 — Release smoke (after R1)

**Run card:** `meeting-product-r2-release-smoke-run-card.md`  
**Orchestrator:** `talkback/scripts/meeting-product-r2-release-smoke-field-first-run.ps1`

Single short RC smoke on **release build** (no adb/debug broadcast):

```text
clean install / upgrade
→ start service
→ 3-node real mesh
→ Create / Join Meeting
→ automatic multicast default (AUTO_CUTOVER_SUCCEEDED)
→ bidirectional / multi-source voice
→ run a few minutes
→ End
→ SESSION_STOPPED + TEARDOWN_EMPTY
```

Kill-switch focused check:

```text
multicastAudibleEnabled=false (debug or deployment control)
→ Meeting
→ remains ANCHOR_ACTIVE
→ no ownership violation
```

**One default-path PASS + one kill-switch PASS → R1 CLOSED → RELEASE CANDIDATE.**

---

## References

- GA authorization: `meeting-product-ga-authorization-review.md`
- Integration gate: `meeting-product-integration-gate.md`
- ADR-0062: `docs/adr/0062-replacement-cutover-rc1-audible-ownership-transition.md`
