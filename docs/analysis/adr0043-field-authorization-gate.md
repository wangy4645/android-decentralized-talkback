# ADR-0043 — Field Authorization Gate

**Status:** **APPROVED** · **gate definition only** · **Field run NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Parents:** [adr0043-appendix-b-adjudication.md](./adr0043-appendix-b-adjudication.md) (**PASS**) · [adr0043-implementation-authorization.md](./adr0043-implementation-authorization.md) · [0043-conference-recovery-membership-context-boundary.md](../adr/0043-conference-recovery-membership-context-boundary.md)

---

## Purpose

Define what **Field authorization** would observe and adjudicate — without opening field runs yet.

```text
Appendix B PASS              →  prerequisite met
Field Authorization Gate     →  APPROVED (this doc)
Field observation run        →  NOT AUTHORIZED (separate authorization)
```

---

## What Field authorization MAY observe

```text
Issuer-path log chain on requester devices (typically M03):
  MEMBERSHIP_CONTEXT_EXISTENCE_QUERY_SENT
  MEMBERSHIP_CONTEXT_EXISTENCE_EVIDENCE
  GROUP_RESYNC_DISPATCH_BLOCKED (when applicable)
  MEMBERSHIP_CONVERGENCE_REQUESTED
  GROUP_RESYNC_REQUEST_SENT

Authority-path on membership authority (typically M01):
  MEMBERSHIP_CONTEXT_EXISTENCE_QUERY_RECEIVED
  MEMBERSHIP_CONTEXT_EXISTENCE_RESPONSE_SENT
  GROUP_RESYNC_HANDLER_ACCEPTED / terminal handler outcomes

Cross-device correlation:
  same channel · same decision epoch (F1) · same scope (F4)
  response source = channel membership authority
```

---

## What Field authorization MUST NOT adjudicate

```text
WiFi flap success rate
Recovery latency / SLA
ICE restart outcome
M03 ONLINE time
UI banner / syncing duration
Completion predicate satisfaction
Membership eventual convergence
NO_MEMBERSHIP_CONTEXT handler semantics (Seam II)
ADR-0042 transport behavior
```

Those belong to separate tracks (RNA lifecycle · Seam II · F2/F3/F5 · ADR-0042).

---

## PASS / FAIL criteria (Field gate)

**PASS** — across authorized field episodes, every conference-recovery `GROUP_RESYNC_REQUEST_SENT` is traceable to:

```text
authority-grounded PRESENT
        +
same scope (F4)
        +
same decision epoch (F1)
        +
O1 allow
```

**FAIL** — any observed:

```text
GROUP_RESYNC_REQUEST_SENT without PRESENT chain
digest / transport / local belief → PRESENT
UNKNOWN treated as PRESENT or ABSENT
non-authority response promoted to PRESENT
handler soften or synthetic membership introduced
```

**NOT FAIL:**

```text
recovery incomplete
UI shows SYNCING
intent obligation open (DEFERRED_INTENT_UNCOVERED)
membership slow to converge
```

---

## Episode constraints (when authorized)

```text
Devices: defined test topology (e.g. M01/M02/M03) unless run card explicitly changes topology
No directed WiFi flap protocol (not Directed #5)
No completion bypass
No timeout / UI / handler changes for field qualification
```

---

## Gate transition

```text
Appendix B PASS                    → COMPLETE
Field Authorization Gate APPROVED  → COMPLETE (this doc)
Field observation run AUTHORIZED   → NOT YET (separate run card)
Field adjudication PASS            → Field CLOSED (Seam I)
```

---

## Explicit deferrals (remain closed)

```text
Seam II (wait / establish / terminate)
F2 / F3 / F5 freshness extensions
O2 / O3 / T3
Handler NO_MEMBERSHIP_CONTEXT change
ADR-0042 transport reopen
```

---

## One-line statement

> Field Authorization Gate APPROVED: gate definition only — field may only qualify Seam I issuer/authority log chains; field observation run remains a separate authorization.
