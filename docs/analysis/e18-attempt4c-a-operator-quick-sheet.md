# E.18 Attempt-4c-A Operator Quick Sheet

**Not a contract.** Convenience only -- judgments live in [e18-attempt4c-a-preflight.md](./e18-attempt4c-a-preflight.md).

---

**Goal:** authority absent -> `UNWIRED` (not `CHECKED(false)`)

---

## Before start

```text
[ ] M02 host stable
[ ] M03 joined
[ ] M01 authority silence confirmed (no HELLO authority evidence)
[ ] lastSeenAuthorityDigestByChannel absent on M02
```

**Stop if any appear before T0:**

```text
[ ] authorityHash
[ ] CHECKED=true
[ ] membershipEpochConverged=true
```

-> `ABORT_NOT_CASE_A_PRECONDITION` (see preflight)

---

## Run (T0-T4)

```text
T0  WATCH armed
T1  confirm M01 still not sending authority HELLO
T2  M03 WiFi OFF (~30s)
T3  wait recovery ingress / resolver fact
T4  M03 WiFi ON
```

---

## Expect

```text
AUTHORITY_UNWIRED / membershipProbeDisposition=UNWIRED
completion HOLD or WAITING (not RECOVERED)
```

---

## If you see (operator halt cues -- full labels in preflight)

| Observation | Cue |
|-------------|-----|
| `AUTHORITY_DIGEST_MISSING` only | insufficient -- do not promote |
| `CHECKED(false)` | Case-C -- stop, not A |
| `RECOVERED` while authority absent | E18 violation review -- stop |

---

*Print or keep beside device. Merge #117 + #118 before field use.*