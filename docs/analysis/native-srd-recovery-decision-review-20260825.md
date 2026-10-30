# Native SRD Recovery — Decision Review

**ID:** native-srd-recovery-decision-review-20260825  
**Date:** 2026-08-25  
**Type:** Architecture Decision Review  
**Status:** **DECIDED** · B1 authorized via [edge-srd-recovery-ia-v0.1-b1.md](./edge-srd-recovery-ia-v0.1-b1.md) · B2/C impl **NOT AUTHORIZED**

**Prerequisite (frozen):** [gct-ia001-clean-start-adjudication-20260825.md](./gct-ia001-clean-start-adjudication-20260825.md) — Shared Native SRD Fault  
**Upstream contracts (unchanged):** [Failure Domain Contract](./conference-failure-domain-contract.md) · [Decision 1 Option Z](./conference-native-failure-containment-decision.md) · [Phase A Authorization](./conference-native-failure-containment-phase-a-authorization.md) · [Coordinator ↔ Media Execution Boundary](../architecture/coordinator-media-execution-boundary.md)

```text
Clean-start adjudication     FROZEN
        ↓
This Decision Review         DECIDED
        ↓
[edge-srd-recovery-ia-v0.1-b1.md]  B1 AUTHORIZED
        ↓
Implementation PR(s) · desk gate
        ↓
Field (run card — NOT authorized by IA)
        ↓
B2 IA (NOT STARTED)
```

---

## Decision question

After **Shared Native SRD Fault** on `shared-factory`, what is the **minimum production capability** we must design toward?

Not: “pick recovery code now.”  
Yes: “which recovery **architecture** is the product line, and what is explicitly deferred?”

---

## Primary defect (recap — do not reopen)

```text
shared-factory
    + sequential host SRD (setRemoteDescription answer)
    → one edge's native call may never return

failure edge     = order-dependent participant
blast radius     = lease-contending edges (DOMAIN_BLOCKED)
```

Cross-run: **first** sequential SRD PASS · **second** HANG (Round 1 + Round 2).  
Lineage MATCH on all edges. Lifecycle churn **not** root cause.

---

## Options adjudicated

| Option | Name | Role in product | Decision |
|--------|------|-----------------|----------|
| **A** | Accept permanent `DOMAIN_BLOCKED` | Terminal **failure state** only | **REJECT** as recovery strategy |
| **B** | Edge-level recovery / re-realization | **Primary recovery line** | **SELECTED** (as B1 + B2) |
| **C** | Native execution domain reset | Escape hatch | **DEFERRED** — not authorized until safety proven |
| **D** | Domain quarantine | Containment / protection | **ACCEPT** as adjunct to B — not sole recovery |

> Note: Option letters here match product intent. Prior adjudication “open question” list used a different A–D mapping; **this review supersedes that list.**

---

## Option A — Rejected as recovery strategy

**Acceptable as:** explicit terminal when recovery exhausts or is unsafe.

**Not acceptable as:** long-term strategy.

```text
M02 SRD hang
    → M02 EDGE_FAILED
    → M03/M04 DOMAIN_BLOCKED
    → never recover
```

8–10 person Conference would degrade to “correctly logged permanent failure.” Unacceptable for a push-to-talk product.

**Verdict:** A is a **failure outcome**, not a recovery capability.

---

## Option B — Selected (primary line)

**Intent:** Product failure boundary stays at **Edge**; runtime safety boundary stays at **Native Domain** — consistent with frozen four-layer model and Option Z.

**Not:**

```text
M02 hang → force M02 lease NONE → M03 enters native anyway     (unsafe)
M02 hang → reset entire shared-factory → all edges torn down     (Option C; not first)
```

**Yes:**

```text
M02
  → EDGE_FAILED (classified)
  → domain lease safety preserved
  → edge recovery intent (control plane)
  → re-realization when native admission is safe
```

### Isolation units (frozen)

```text
Edge           → user / business recovery unit
Native Domain  → runtime safety unit
Conference     → availability adjudication unit
```

`M02 SRD hang` must first become `M02 EDGE_FAILED`. Whether `M03/M04` can proceed is a **separate** domain-contention question — not automatic Conference death.

### B split — capability boundary

B is **two capabilities**, not one “fix the PC” button.

#### B1 — Edge failure classification + recovery intent (control plane)

```text
Shared Native SRD Fault observed
    ↓
failure edge → EDGE_FAILED (L1, self-attributed)
    ↓
recovery intent recorded for that edgeKey
    ↓
Coordinator not blocked by hung native thread
```

**In scope for first Recovery IA:** intent, state, gates, facts.  
**Out of scope:** assuming native retry is immediately possible.

Hard constraints (carry forward from Phase A / execution boundary):

```text
LEASE_RETAINED / UNKNOWN  → must not be force-cleared
DOMAIN_BLOCKED            → must not be masked as PENDING
Coordinator               → must not be dragged by SRD hang
```

#### B2 — Safe re-realization after native admission opens

```text
EDGE_FAILED edge
    ↓
recovery intent active
    ↓
native domain idle / safely admissible  (precondition TBD)
    ↓
new Conference media realization for that edge only
```

**Critical open precondition (blocks B2 impl):**

```text
SRD hang without:
  SRD_NATIVE_CALL_EXIT
  NATIVE_DOMAIN_EXECUTION_EXIT
  lease release
→ same domain cannot be reused for retry
```

Therefore:

> **B1 can ship before B2.** Edge Recovery is first a **control-plane** capability; native re-entry is second.

B2 **does not** mean “same hung PC, same domain, immediate retry.” It means **safe re-realization when admission preconditions are met** — mechanism to be defined in Recovery IA, not assumed here.

---

## Option C — Deferred (escape hatch only)

Strongest capability, largest blast radius.

```text
shared-factory domain reset
    → may affect every PeerConnection on domain
    → 1 edge fault → 2+ edges → entire conference
```

Not proven safe, repeatable, or non-destructive to healthy edges on same domain.

**Verdict:** Document as **future escape hatch** (C). **Not authorized** in first Recovery IA. May be revisited only after B1/B2 boundary is implemented and field-gated.

**Explicitly deferred with C:** force-release · PC hard reset · native reset · Factory partition.

Current evidence does **not** support “single PC corruption” as root cause. Domain-level sequential SRD non-return is the stronger fact. PC reset would mis-frame a **domain execution fault** as a **single-PC fault**.

---

## Option D — Accepted as protection (adjunct)

Quarantine is **containment policy**, not complete recovery.

```text
SRD native hang
    ↓
DOMAIN_QUARANTINED (policy)
    ↓
new native admissions blocked on domain
    ↓
existing healthy edges may continue (goal)
```

**Verdict:** D complements B — blocks unsafe admission while B1 intent and B2 re-realization are evaluated. D alone does not answer “can the meeting recover?”

Aligns with Phase A `DOMAIN_BLOCKED` visibility (Scenario D) without elevating it to terminal product behavior (rejected A).

---

## Decision record

```text
PRIMARY:     B — Edge SRD Recovery / Re-realization
             split B1 (intent) + B2 (safe re-realization)

PROTECTION:  D — Domain quarantine / admission block (adjunct)

ESCAPE:      C — Native domain reset (deferred; field-gated later)

TERMINAL:    A — Permanent DOMAIN_BLOCKED (failure state only)
```

**Vote recorded:** B selected · D as protection · C deferred · A rejected as strategy.

---

## Explicitly NOT authorized (this review)

```text
Implementation PRs
force-release / lease force-clear
PC hard reset / per-PC recovery
Factory partition / native reset
lifecycle de-dup · double createOffer · pending drain optimization
SRD timeout / retry semantic change
WiFi recovery four protected domains
CFC-1 FULL / L3 DOMAIN_FAILED / topology changes
```

---

## Next artifact

**Edge SRD Recovery IA** — small scope, derived from this review:

| Wave | Capability | Native touch |
|------|------------|--------------|
| B1 | Failure edge classification continuity · recovery intent · coordinator liveness · admission gates | None required for first slice |
| B2 | Safe re-realization preconditions · edge-scoped media restart | Only after idle/safe admission defined |
| C | Domain reset escape hatch | Separate IA + field proof |

Recovery IA must state:

1. B1 deliverables and pass criteria  
2. B2 preconditions (`safe native admission` definition)  
3. Explicit deferral of C and all PC/domain reset shortcuts  
4. Relationship to Phase A E+D projection (no regression)

---

## Frozen wording

```text
Shared Native SRD Fault          (primary defect)
Edge                             (recovery unit — product)
Native Domain                    (safety unit — runtime)
B1                               (recovery intent — control plane)
B2                               (re-realization — after safe admission)
D                                (quarantine — protection, not recovery alone)
C                                (domain reset — escape hatch, deferred)
A                                (permanent block — terminal state, not strategy)

Do NOT write:
  M03 SRD hang (primary)
  PC reset fixes Shared Native SRD Fault
  force-clear lease as default recovery
  one IA solves all runtime problems
```

---

## Changelog

| Date | Change |
|------|--------|
| 2026-08-25 | Initial decision review · B primary · B1/B2 split · D adjunct · C deferred · A terminal-only |
