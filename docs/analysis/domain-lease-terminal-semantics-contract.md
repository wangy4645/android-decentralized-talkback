# Domain Lease Terminal Semantics Contract

**ID:** domain-lease-terminal-semantics-contract  
**Date:** 2026-08-25  
**Version:** v0.1  
**Type:** Architecture Contract · Lease Occupancy  
**Status:** **APPROVED / FROZEN** · Implementation **NOT AUTHORIZED** · Behavior change **NOT AUTHORIZED**

```text
FORCE_RELEASE · PC HARD RESET · NATIVE RESET · FACTORY PARTITION
    = UNAUTHORIZED / DEFERRED
```

**Upstream (do not reopen):**

```text
GCT Transition Contract              ACCEPTED
GCT Implementation Mapping           FROZEN
M03 SRD_TIMEOUT RCA                  FROZEN
Edge → Native Domain Propagation     FROZEN
A0.7  FAILED ≠ NONE                  analog only (engine ownership, not this lease)
PO-3  at-most-one holder             retained; PO-3.2 DOMAIN_RELEASE-on-failure restricted below
```

**Evidence:** [M03 SRD_TIMEOUT Host PC Hang RCA](./m03-srd-timeout-host-pc-hang-rca-20260824.md)  
**Propagation rules:** [Edge → Native Domain Failure Propagation Contract](./edge-native-domain-failure-propagation-contract.md)

**Downstream (not this document):**

```text
force-release capability
PC recovery / native reset
GCT host PC hard reset vs gen-N reuse
Implementation Authorization
```

---

## Purpose

Define **when a native-domain lease may be empty**, and forbid substituting other terminals for that emptiness.

This document does **not** answer “how to release a hung JNI.” It answers which states **must not be swapped**.

---

## Supreme invariant

```text
LEASE_NONE
    MUST NOT be asserted
    solely because the control-plane release transaction terminalized.
```

Operational form:

```text
EDGE_FAILED            ≠  LEASE_RELEASED / LEASE_NONE
MEDIA_RELEASE_FAILED   ≠  LEASE_RELEASED / LEASE_NONE
WATCHDOG_TIMEOUT       ≠  LEASE_RELEASED / LEASE_NONE
DOMAIN_BLOCKED         ≠  LEASE_RELEASED / LEASE_NONE
ReleaseTxn terminal    ≠  LEASE_NONE
```

`RETAINED → NONE` is allowed **only** from an explicit completion / ownership handoff that cannot collide with a still-running native owner.

---

## Four axes (must not collapse)

These are independent machines. A fact on one axis is **not** a fact on another.

```text
ReleaseTxn state
    ≠
Native execution state
    ≠
Lease state
    ≠
Edge / Domain terminal state
```

| Axis | What it records | Example facts (this field) |
|------|-----------------|----------------------------|
| **ReleaseTxn** | Control-plane release *transaction* (A0.7: RUNNING → RELEASED \| FAILED) | Not the M03 SRD hang path; `MEDIA_RELEASE_FAILED` still ≠ lease empty |
| **Native execution** | Whether holder JNI/native work has entered and returned | M03: `SRD_NATIVE_CALL_ENTER`, **no** `NATIVE_CALL_EXIT` → `NATIVE_STILL_INSIDE` |
| **Lease** | Who occupies domain *admission* | M03: GRANTED then **RETAINED**; M02: BUSY holder=M03 |
| **Edge / Domain terminal** | L1/L2 classification | M03 `EDGE_FAILED`; M02 `DOMAIN_BLOCKED` |

Field after M03 `SRD_TIMEOUT` (imported, frozen):

| Axis | Value |
|------|--------|
| Edge / Domain terminal | `EDGE_FAILED` (L1) |
| ReleaseTxn | no domain-lease release transaction observed |
| Native execution | `NATIVE_STILL_INSIDE` |
| Lease | `RETAINED` (holder=M03) |

That tuple is **legal**. Forcing Lease to `NONE` to “match” `EDGE_FAILED` is **illegal**.

---

## Vocabulary

### Lease occupancy (this contract)

```text
NONE         slot empty; no holder; new GRANTED may be considered
REQUESTED    admission in flight (not occupancy)
GRANTED      holder admitted (ACTIVE); may execute
RETAINED     holder still occupies; includes ACTIVE after enter,
             and containment STUCK / QUARANTINED
DENIED       not granted; holder remains the other edge (BUSY)
UNKNOWN      occupancy facts insufficient; MUST be treated as occupied
             for admission (not as NONE)
```

`LEASE_RELEASED` is a **transition fact** (`RETAINED → NONE` or `GRANTED → NONE` under Q3). It is **not** a durable occupancy that can be inferred from an edge terminal.

Code names `ACTIVE` / `STUCK` / `QUARANTINED` (`ConferenceNativeExecutionDomain`) are **substates of occupied** (`GRANTED` or `RETAINED`). They are **not** `NONE`. Clearing those to empty slot is the same `RETAINED → NONE` question as Q3.

### Native execution (minimum)

```text
NATIVE_IDLE            EXIT facts present (NATIVE_CALL_EXIT + domain EXECUTION_EXIT)
                       OR native never entered under this lease
NATIVE_STILL_INSIDE    NATIVE_CALL_ENTER (or EXECUTION_ENTER) without matching EXIT
NATIVE_UNKNOWN         enter/exit set incomplete; treat as not idle
```

### Force-release

```text
Force-release capability
    = UNAUTHORIZED / DEFERRED
```

This contract does **not** define a force-release procedure, API, timeout, or quarantine-clear policy that empties the slot while native is not idle.

---

## Q1 — Lease acquire (`GRANTED`)

**When may the domain assert `GRANTED` for edge B?**

```text
GRANTED(B) ONLY IF:

  1. Lease occupancy is NONE
     OR (B is already the holder AND occupancy is GRANTED/ACTIVE
         AND this is idempotent re-admit of the same holder)
  2. Native execution for any prior holder is NATIVE_IDLE
     (vacuously true if occupancy is already NONE)
  3. Domain is not in occupied containment that blocks grants
     (STUCK / QUARANTINED / RETAINED by A ≠ B)
```

**Must deny (`DENIED` / `LEASE_BUSY`):**

```text
occupancy RETAINED by A ≠ B
occupancy UNKNOWN
native NATIVE_STILL_INSIDE or NATIVE_UNKNOWN for current holder
```

**Must not grant:**

```text
because B’s GROUP_ACCEPT arrived
because A is already EDGE_FAILED
because a watchdog fired
because a ReleaseTxn terminalized
to “unblock” DOMAIN_BLOCKED
```

PO-3 “at most one holder per domain” remains. A second `GRANTED` while the slot is occupied is **dual owner**.

---

## Q2 — Lease hold (`RETAINED` / `UNKNOWN`)

**While native execution has not returned, how is occupancy written?**

After `GRANTED`, occupancy stays **occupied** until a Q3-legal transition to `NONE`.

```text
GRANTED
    ↓
native ENTER
    ↓
no matching EXIT
    ↓
occupancy = RETAINED
native     = NATIVE_STILL_INSIDE
```

If ENTER/EXIT/holder facts are missing or contradictory:

```text
occupancy = UNKNOWN
admission  = treat as occupied (deny others)
MUST NOT interpret UNKNOWN as NONE
```

**Allowed while RETAINED / UNKNOWN:**

```text
L1 EDGE_FAILED on holder (control terminal)
L2 DOMAIN_BLOCKED on a later requester
watchdog / SRD_TIMEOUT facts
containment substate ACTIVE → STUCK / QUARANTINED
  (still occupied; still not NONE)
```

**Forbidden while RETAINED / UNKNOWN:**

```text
assert LEASE_NONE
GRANTED to a different edge
treat holder as idle
```

Field: M03 `missing=NATIVE_CALL_EXIT` + `state=NATIVE_DOMAIN_EXECUTION_ACTIVE` is the canonical `RETAINED` + `NATIVE_STILL_INSIDE` hold.

---

## Q3 — Lease terminate (`RETAINED` → `NONE`)

**What evidence may empty the slot?**

`NONE` requires an **explicit completion or ownership handoff** that cannot race a still-running native owner.

### Legal (sufficient)

```text
Holder H:
  NATIVE_CALL_EXIT
  AND NATIVE_DOMAIN_EXECUTION_EXIT
  AND lease-release fact by H (same edgeKey)
  AND native = NATIVE_IDLE
      → occupancy NONE
```

Idempotent: if occupancy is already `NONE`, stay `NONE`.

Same-holder success path (M04 in the RCA) is this case: EXIT then domain EXECUTION_EXIT, then slot free for a later edge.

### Not legal (never sufficient by themselves)

```text
EDGE_FAILED
SRD_TIMEOUT / SRD_WATCHDOG_GAP
WATCHDOG_TIMEOUT (any control watchdog)
MEDIA_RELEASE_FAILED / ReleaseTxn FAILED or RELEASED
DOMAIN_BLOCKED on self or on a peer
EDGE_TASK_END missing or present
L1/L2 classification
“we need to admit the next peer”
```

### Ownership handoff (reserved, not specified)

A future document may define a handoff fact. This contract only constrains it:

```text
Handoff MAY empty the slot
ONLY IF it proves the previous native owner is NATIVE_IDLE
     OR transfers occupancy without creating a second GRANTED
        while NATIVE_STILL_INSIDE remains on the old owner.

Handoff MUST NOT be:
  timeout
  inferred NONE
  force-release (UNAUTHORIZED / DEFERRED)
```

No handoff protocol is authorized here.

### Late native completion after control terminal

If control already emitted `EDGE_FAILED` / `SRD_TIMEOUT`, and native **later** returns:

```text
Late EXIT is telemetry / absorption (A0.7 analog)
    ↓
it MAY then complete a Q3-legal RETAINED → NONE
    because native is now NATIVE_IDLE
    ↓
it MUST NOT reopen the edge as SUCCESS
    (edge terminal stays EDGE_FAILED)
```

Late EXIT **without** a recorded lease-release fact still does not imply `NONE`. Both idle **and** explicit release fact are required for Q3.

---

## Q4 — Lease vs exception terminals

**May `EDGE_FAILED` / `DOMAIN_BLOCKED` / watchdog change the lease?**

| Event | May change Edge/Domain terminal? | May change Native axis? | May change Lease occupancy to NONE? | May change occupied substate? |
|-------|----------------------------------|-------------------------|-------------------------------------|-------------------------------|
| `EDGE_FAILED` / `SRD_TIMEOUT` | **Yes** (L1 on cause) | **No** (does not prove EXIT) | **No** | **Yes** (e.g. ACTIVE → STUCK) |
| Watchdog terminal | **Yes** (control) | **No** | **No** | **Yes** (containment) |
| `DOMAIN_BLOCKED` | **Yes** (L2 on impact) | **No** | **No** | **No** (impact is not holder) |
| `MEDIA_RELEASE_FAILED` | engine ReleaseTxn only | **No** | **No** | **No** (different object) |

```text
Exception terminals MAY classify and MAY contain.
They MUST NOT vacate the lease.
```

`DOMAIN_BLOCKED` is the legal impact of **RETAINED** + a second `REQUEST`. Vacating the lease to avoid `DOMAIN_BLOCKED` inverts the invariant and risks dual owner.

---

## Illegal sequence (frozen anti-pattern)

```text
watchdog / EDGE_FAILED
    ↓
fake LEASE_NONE
    ↓
GRANTED(new edge)
    ↓
old JNI still running
    ↓
double owner / shared-factory corruption
```

A0.7 forbade the analog on engine ownership (`FAILED → accidental NONE`). This contract forbids it on **native domain lease**.

---

## PO-3.2 restriction (surgical)

PO-3.2 allowed:

```text
NATIVE_EXECUTION
    + SUCCESS            → DOMAIN_RELEASE
    + EDGE_LOCAL_FAILURE → DOMAIN_RELEASE / TERMINAL_STATE
```

**This contract restricts the failure arm:**

```text
EDGE_LOCAL_FAILURE / EDGE_FAILED
    → MAY terminalize the edge
    → MUST NOT DOMAIN_RELEASE to NONE
      unless Q3 native-idle + explicit release fact hold
```

PO-3.1 “at most one holder” is unchanged. Failure observability remains required. Only **vacating occupancy on failure without native idle** is withdrawn.

---

## Out of scope

```text
How to stop hung setRemoteDescription
Force-release procedure
clearQuarantine policy that yields NONE while native not idle
PC generation reset / GCT host PC recycle
Timeout numeric budget / retry
L3/L4 product predicates
Admitting a queued edge after DOMAIN_BLOCKED
```

---

## Evaluation gate (after this freeze)

Only after this contract is accepted as the occupancy boundary:

```text
force-release / PC recovery / native reset
        = separate evaluation
        = not implied by EDGE_FAILED
        = not implied by this freeze
```

Until then:

```text
Force-release capability = UNAUTHORIZED / DEFERRED
```
