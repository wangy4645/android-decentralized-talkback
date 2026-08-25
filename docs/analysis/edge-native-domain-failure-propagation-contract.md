# Edge → Native Domain Failure Propagation Contract

**ID:** edge-native-domain-failure-propagation-contract  
**Date:** 2026-08-25  
**Version:** v0.1  
**Type:** Architecture Contract · Failure Propagation  
**Status:** **DRAFT FROZEN (evidence-bound)** · Implementation **NOT AUTHORIZED** · Behavior change **NOT AUTHORIZED**

**Sole evidence source:** [M03 SRD_TIMEOUT Host PC Hang RCA](./m03-srd-timeout-host-pc-hang-rca-20260824.md) v0.1  
**Taxonomy parent:** [Conference Failure Domain Contract](./conference-failure-domain-contract.md) (L1/L2 terms reused; this document does not amend L3/L4)  
**A0.7 analog (do not import release watchdog as lease policy):** [A0.7 Bounded Release Terminality](../architecture/a0.7-bounded-release-terminality-amendment.md) — `FAILED ≠ NONE`

---

## Purpose

Answer one question only:

> **When an edge-local native failure occurs, when, how, and with what attribution may it propagate into the Native Execution Domain?**

This is **not** a GCT document. GCT accept async is RCA fact 1 (PASS) and is not reopened here.

This is **not** a PC-lifecycle document. Host gen-N reuse vs hard reset is out.

This is **not** lease-terminal implementation. Force-release is **undecided**. That is a later gate:

```text
RCA (evidence)
    ↓
This contract (propagation rules + open questions)
    ↓
Domain lease terminal semantics (separate)
    ↓
Implementation Authorization
```

---

## Evidence imported (frozen; do not re-argue)

From RCA session `e55028df-…` (2026-08-24 field):

```text
Case A: M03 host PC gen=73
        setRemoteDescription JNI never returns
        missing=NATIVE_CALL_EXIT
        EDGE_FAILED / SRD_TIMEOUT
        scenario=E

Case B: M03 lease not released after SRD_TIMEOUT
        shared-factory still held by M03
        M02 NATIVE_DOMAIN_BUSY holder=M03
        DOMAIN_BLOCKED
        scenario=D
        causeFact=LEASE_HELD_BY_CAUSE
```

Blast radius in that run: **M02 only**. M04 already completed SRD. Not a global synchronous native failure.

---

## Core distinctions (must not collapse)

### 1. Edge terminal ≠ domain execution stopped

```text
SRD_TIMEOUT / EDGE_FAILED
    = control-plane terminal on the cause edge
    ≠ proof that native setRemoteDescription has returned
    ≠ proof that signaling worker is idle
```

Field: after `SRD_TIMEOUT`, M03 still had **no** `SRD_NATIVE_CALL_EXIT` / `NATIVE_DOMAIN_EXECUTION_EXIT`. Watchdog classified `NATIVE_DOMAIN_EXECUTION_ACTIVE`.

A0.7 already taught:

```text
watchdog terminalization
    ≠
native execution actually stopped
```

### 2. Lease state ≠ release transaction state

```text
Lease state          — who currently holds domain admission (observed)
Release transaction  — whether a release of that hold has been admitted,
                       is running, has succeeded, or has failed
```

Field observation after M03 `SRD_TIMEOUT`:

| Axis | Observed |
|------|----------|
| Edge control terminal | `EDGE_FAILED` |
| Lease state | **RETAINED** (holder still M03 at M02 `LEASE_BUSY`) |
| Release transaction | **NONE observed** (no domain EXECUTION_EXIT; no explicit lease-release fact) |
| Native execution | **UNKNOWN / still inside JNI** |

Do **not** treat `EDGE_FAILED` as an implicit lease-release transaction.

### 3. LEASE_BUSY ≠ DOMAIN_BLOCKED ≠ EDGE_FAILED on impact

Unchanged from Failure Domain Contract:

```text
LEASE_BUSY       = runtime observation (input)
DOMAIN_BLOCKED   = L2 classified impact on the blocked edge
EDGE_FAILED      = L1 on the cause edge (or on an edge with its own native fault)
```

M02 `CONFERENCE_MEDIA_EDGE_FAILED … NATIVE_DOMAIN_BUSY` is **runtime log wording**. Classification in the same millisecond is L2 `DOMAIN_BLOCKED`, not an independent M02 native hang.

---

## Q1 — E → D trigger

**Question:** Is `SRD_TIMEOUT + missing=NATIVE_CALL_EXIT` enough to classify **domain execution unhealthy**?

**Answer (this contract):**

```text
SUFFICIENT to classify:
  L1 EDGE_FAILED on the cause edge (scenario=E)
  domain execution state = ACTIVE_OR_UNKNOWN (not proven idle)

NOT SUFFICIENT by itself to:
  declare Domain Failure (L3)
  declare the factory / signaling worker dead
  authorize a second native owner on the same domain
  force lease state to NONE
```

**E → D is not automatic from the hang alone.** E → D in the field required a **second edge attempting domain admission while lease was still RETAINED**.

```text
Cause edge: SRD_TIMEOUT + missing NATIVE_CALL_EXIT
    ↓
Lease remains RETAINED (observed)
    ↓
Impact edge: LEASE_REQUEST while holder = cause edge
    ↓
LEASE_BUSY (input)
    ↓
L2 DOMAIN_BLOCKED on impact edge (scenario=D)
    with mandatory attribution (cause, impact, holder, runtimeDomainRef)
```

If no other edge requests the domain while the hang lasts, Case B does not fire. That is **containment of blast radius**, not proof that the domain is healthy.

---

## Q2 — Lease after timeout

**Question:** After `SRD_TIMEOUT`, is lease `RELEASED` / `RETAINED` / `UNKNOWN`?

**Answer from evidence (this run):**

```text
RETAINED
```

Holder at M02 admission: `e55028df-…|M03`. No `NATIVE_DOMAIN_EXECUTION_EXIT` for M03 in the capture.

**Contract rule (observation, not policy):**

```text
Until an explicit domain lease-release fact is recorded,
lease after SRD_TIMEOUT MUST be treated as RETAINED or UNKNOWN,
never inferred as RELEASED from EDGE_FAILED.
```

**Not decided (next document — lease terminal semantics):**

```text
Should SRD_TIMEOUT force-release the lease?
```

Forbidden premature answer:

```text
timeout
    ↓
lease = NONE
    ↓
admit M02 into the same shared-factory domain
```

If JNI is still inside `setRemoteDescription`, that sequence can create **dual owner / runtime corruption**. Field has **not** proven native has stopped.

Allowed vocabulary until lease-terminal semantics exists:

| Term | Meaning |
|------|---------|
| `LEASE_RETAINED` | Holder still the cause edge (this field run) |
| `LEASE_RELEASED` | Explicit release fact observed (not this run) |
| `LEASE_UNKNOWN` | Holder / exit facts missing |
| `NATIVE_STILL_INSIDE` | missing `NATIVE_CALL_EXIT` after control timeout |
| `NATIVE_IDLE` | `NATIVE_CALL_EXIT` + domain EXECUTION_EXIT proven |

`LEASE_RELEASED` without `NATIVE_IDLE` is a **dangerous inferred state**. It is not authorized here.

---

## Q3 — When DOMAIN_BLOCKED is allowed, and blast radius

**Allowed:**

```text
IF cause edge has L1 EDGE_FAILED (or still holds domain execution)
AND impact edge has no independent native fault
AND impact edge is denied domain admission because holder = cause edge
THEN L2 DOMAIN_BLOCKED on impact edge
     attribution required:
       causeEdgeKey
       impactEdgeKey
       holderEdgeKey
       runtimeDomainRef
       causeFact (field: LEASE_HELD_BY_CAUSE)
```

**Blast radius (this evidence):**

```text
Maximum observed: other edges that request native domain admission
                  while cause lease is RETAINED.

Not observed: already-completed edges (M04).
Not proven:   every attached PC on the factory.
```

**Forbidden equivalences:**

```text
DOMAIN_BLOCKED on M02  ≠  M02 native SRD hang
DOMAIN_BLOCKED         ≠  Conference FAILED (L4)
scenario=D PASS        ≠  native isolation PASS
EDGE_FAILED on M03     ≠  domain reset authorized
```

**Serialization vs corruption:** denying M02 (`LEASE_BUSY`) while M03 still holds is the **safer observed behavior** relative to admitting M02 into an occupied native domain. Whether that deny should last until native idle, until an explicit quarantine, or until a future release transaction, is **not** decided here.

---

## Q4 — Recovery / admission while lease is retained

**Question:** While the cause edge retains the domain lease, may a new edge silently wait?

**Answer (this contract):**

```text
NO silent wait as the success path.

New edge domain admission while holder ≠ self MUST terminate as an
explicit fact on that edge:
  DOMAIN_BLOCKED  (classified)
  and/or PENDING with an auditable blocked-by-domain reason
  never indefinite CONNECTING / invisible wait
```

Field: M02 did not wait. It failed immediately with `NATIVE_DOMAIN_BUSY` and was classified `DOMAIN_BLOCKED`.

**Not authorized yet:**

```text
queue behind holder until lease RELEASED
retry SRD on impact edge
steal lease
reset factory
recover cause edge PC
```

Pending / retry policy is an Implementation Authorization concern after lease-terminal semantics. This contract only forbids **unattributed wait**.

---

## Propagation graph (normative)

```text
Edge-local native hang
    ↓
missing NATIVE_CALL_EXIT
    ↓
L1 EDGE_FAILED on cause edge          (E)
    ↓
lease state = RETAINED | UNKNOWN
    (NOT inferred RELEASED)
    ↓
IF another edge requests domain admission
    ↓
LEASE_BUSY input + holder = cause
    ↓
L2 DOMAIN_BLOCKED on impact edge      (D)
    full cause/impact attribution
```

```text
L1 without a second admission
    = no L2 in this model
    = hang still occupies the domain
    = domain not proven healthy
```

---

## Open questions (explicitly deferred)

These are **inputs** to “domain lease terminal semantics,” not answers in this contract:

| ID | Question | Constraint |
|----|----------|------------|
| LTS-1 | May `SRD_TIMEOUT` start a **lease release transaction**? | Transaction ≠ flipping lease to NONE |
| LTS-2 | Can lease become `RELEASED` while native is `NATIVE_STILL_INSIDE`? | Default suspicion: **no**; dual-owner risk |
| LTS-3 | If release transaction fails, is lease `RETAINED` or `QUARANTINED`? | `FAILED ≠ NONE` (A0.7 analog) |
| LTS-4 | What fact proves `NATIVE_IDLE`? | Need EXIT facts, not watchdog alone |
| LTS-5 | After true `NATIVE_IDLE` + `LEASE_RELEASED`, may a new edge enter? | Separate IA |

---

## Out of scope

```text
GCT START / ACCEPT async
SRD timeout numeric budget
Retry
Factory partition / second factory
New gate / barrier implementation
Host PC hard reset vs gen-N reuse
Native kill / Thread.interrupt
L3 DOMAIN_FAILED / L4 Conference FAILED product predicate
P0.1g Acceptance 1
M02 peer log as a missing prerequisite
```

---

## Status of implementation

```text
This contract freezes propagation attribution and the lease/native split.
It does not authorize code.
It does not authorize force-release.
It does not authorize treating EDGE_FAILED as domain idle.
```
