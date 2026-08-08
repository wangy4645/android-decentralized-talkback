# ADR-0043 Appendix B — Passive Observation Checklist

**Status:** **ACTIVE** · **passive observation only** · **Field NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Baseline:** PR **#128 MERGED** (`7820d87`) · Seam I v0 on `main`  
**Parents:** [adr0043-implementation-authorization.md](./adr0043-implementation-authorization.md) · [adr0043-implementation-verification-gate.md](./adr0043-implementation-verification-gate.md) · [0043-conference-recovery-membership-context-boundary.md](../adr/0043-conference-recovery-membership-context-boundary.md)

---

## Status board

```text
ADR-0043 Seam I v0:     MERGED (main)
Implementation:         merged baseline
Desk verification:      PASS (19/19)
Appendix B:             ACTIVE (this doc)
Field authorization:    NOT AUTHORIZED
ADR-0042 stash:         isolated — do not reopen yet
```

---

## Scope

```text
Purpose:
  Verify Seam I v0 does not bypass P1+O1 authorization boundary.

Not:
  field validation
  recovery success qualification
  WiFi flap evaluation
  performance measurement
```

This is **not** Directed #5. Not a test plan. Not field authorization. Passive log observation during normal conference recovery usage only.

---

## When to use

- Normal usage: conference call, backgrounding, natural disconnect, weak network
- Post-merge smoke on `main` after PR #128
- Incident post-mortem: attach observation record to tickets

**Devices (field conventions):** SSID `happy` · M01 `HTUBB21B09220661` · M02 `2d73067a` · M03 `MDX0220416001963`

---

## Observation points

### 1. No PRESENT → no GROUP_RESYNC

**Expected when P1 evidence is absent:**

```text
GROUP_RESYNC_DISPATCH_BLOCKED reason=ADR0043_*
```

**Must hold:**

```text
No P1 PRESENT
        ↓
No GROUP_RESYNC_REQUEST_SENT
```

| Observation | Verdict |
|-------------|---------|
| Blocked log present; no premature `GROUP_RESYNC_REQUEST_SENT` | **PASS** |
| `digest checked` + `transport recovered` → `GROUP_RESYNC_REQUEST_SENT` without prior authority PRESENT chain | **ADR-0043 violation** |

---

### 2. UNKNOWN path → probe (not dispatch)

**Expected when evidence is not yet available:**

```text
MEMBERSHIP_CONTEXT_EXISTENCE_QUERY_SENT
```

**Then either:**

```text
response PRESENT
        ↓
authorization evaluation
        ↓
GROUP_RESYNC_REQUEST_SENT
```

**Or:**

```text
response UNKNOWN / ABSENT
        ↓
blocked (no GROUP_RESYNC_REQUEST_SENT)
```

**Forbidden:**

```text
UNKNOWN
        ↓
assume membership exists
        ↓
dispatch GROUP_RESYNC
```

| Observation | Verdict |
|-------------|---------|
| UNKNOWN withholds dispatch; probe fires; no `RECOVERY FAILED` from UNKNOWN alone | **PASS** |
| UNKNOWN treated as PRESENT or ABSENT | **ADR-0043 violation** |

---

### 3. PRESENT ≠ GRANT (O1 still required)

**PRESENT received is necessary, not sufficient.**

Must observe authorization evaluation before dispatch — not automatic `GROUP_RESYNC_REQUEST_SENT` on PRESENT alone when O1 constraints deny.

**Check:**

```text
PRESENT != GRANT
```

| Observation | Verdict |
|-------------|---------|
| `MEMBERSHIP_CONTEXT_EXISTENCE_EVIDENCE answer=PRESENT` precedes allowed dispatch; supplemental O1 deny still blocks | **PASS** |
| PRESENT alone always yields dispatch regardless of issuer rules | **O1 boundary violation** |

---

### 4. Authority mismatch (IP-001)

**If response source is not the channel membership authority:**

**Expected:**

```text
MEMBERSHIP_CONTEXT_EXISTENCE_RESPONSE_REJECTED reason=AUTHORITY_MISMATCH
```

or O1 block:

```text
GROUP_RESYNC_DISPATCH_BLOCKED reason=ADR0043_AUTHORITY_MISMATCH
```

**Forbidden:**

```text
non-authority response
        ↓
PRESENT accepted
        ↓
GROUP_RESYNC_REQUEST_SENT
```

| Observation | Verdict |
|-------------|---------|
| Spoof / wrong responder rejected | **PASS** |
| Non-authority response promoted to PRESENT | **IP-001 violation** |

---

## PASS / FAIL roll-up

**PASS** — every `GROUP_RESYNC_REQUEST_SENT` on the conference-recovery issuer path is traceable to:

```text
authority-grounded PRESENT
        +
same scope (F4)
        +
same decision epoch (F1)
        +
O1 allow
```

**NOT PASS criteria:**

```text
conference recovered successfully
UI shows ONLINE
ICE reconnected
membership eventually converged
```

Recovery outcome is **out of scope** for this checklist.

---

## Log chain reference (issuer path)

```text
MEMBERSHIP_CONTEXT_EXISTENCE_QUERY_SENT     (probe)
MEMBERSHIP_CONTEXT_EXISTENCE_EVIDENCE       (P1 cache)
GROUP_RESYNC_DISPATCH_BLOCKED reason=ADR0043_*  (O1 withhold)
MEMBERSHIP_CONVERGENCE_REQUESTED            (pre-dispatch intent)
GROUP_RESYNC_REQUEST_SENT                   (dispatch fact — not convergence)
```

---

## Explicit non-observations

This Appendix does **not** record:

```text
recovery latency
WiFi flap success rate
ICE restart outcome
timeout / watchdog behavior
retry count
UI / banner state
NO_MEMBERSHIP_CONTEXT handler adjudication
```

Those belong to:

```text
Seam II
F2 / F3 / F5
Field authorization
ADR-0042 transport (separate track)
```

---

## Violation routing

| Phenomenon | Route |
|------------|-------|
| `GROUP_RESYNC_REQUEST_SENT` without PRESENT chain | ADR-0043 Seam I regression |
| `digest checked` alone preceded dispatch | IP-001 / ADR-0043 violation |
| UNKNOWN → recovery failure escalation | UNKNOWN semantics violation |
| Authority context missing at handler | Handler terminal (unchanged) — **not** issuer bypass fix |
| Recovery slow but gate logs correct | Observation only — not Appendix B FAIL |

---

## Suggested sequence (post-merge)

```text
1. Build from main (7820d87+)
2. Normal conference recovery episode (no directed flap)
3. Collect logs from requester (typically M03)
4. Walk observation points 1–4
5. Record PASS/FAIL per point
6. If all PASS → consider Field authorization gate (separate doc)
7. ADR-0042 stash — handle only after clean ADR-0043 baseline
```

---

## One-line statement

> Appendix B: passive log observation that Seam I v0 withholds GROUP_RESYNC without authority PRESENT + O1 allow; not field validation, not recovery success.
