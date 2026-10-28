# PA-SR4-TX — Field Run Card (Shadow Multicast Transmit Gate)

**Authorization:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-tx-implementation-authorization.md`  
**Design:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-tx-design.md`  
**Parent:** `meeting-product-integration-phase-a-sr4-field-run-card.md` (SR4 full gate — still open)  
**Status:** **CLOSED / FIELD-PROVEN** (20260909-171232 PASS)

```text
PA-SR4 full       VALID FAIL / RX NEXT after TX proven
PA-SR4-TX         CLOSED / FIELD-PROVEN
PA-SR4-RX         DESIGN+AUTH FROZEN (harness TBD)
ADR-0056          KEEP
```

---

## 1. Question this gate answers

Does the **host** establish shadow session wiring and emit **protected multicast datagrams** when production PTT capture runs — without claiming peer ingress or PA-SR4 PASS?

```text
Host SHADOW_SESSION_STARTED APPLIED
→ M01 PTT (3× stimulus)
→ SHADOW_TX_ACTIVITY packetsSent > 0
```

---

## 2. Hard boundaries

| # | Boundary |
|---|----------|
| 1 | PRE-G / PRE-I / PRE-J + J0 PASS — else `INVALID_*` |
| 2 | **Host** must show `WIRING_SESSION_STARTED` + `SHADOW hook=SESSION_STARTED outcome=APPLIED` |
| 3 | `INGRESS_ACTIVITY` / `PLAYOUT_ACTIVITY` — **observe only**, not PASS criteria |
| 4 | No manual wire replay / synthetic datagram inject |
| 5 | M01 `MEMBER_MEDIA_READY(M02)=DEFERRED_NO_BINDING` — out of scope |

---

## 3. PASS chain (frozen)

On **M01 host** log within observation window:

```text
PROFILE01_SHADOW_RUNTIME phase=SHADOW_SESSION_STARTED
  OR SHADOW hook=SESSION_STARTED outcome=APPLIED

PROFILE01_SHADOW_RUNTIME phase=WIRING_SESSION_STARTED
  transportScopeActive=true

PTT_TIMING event=captureON session=<hostSessionId>   (≥1; harness 3×)

PROFILE01_SHADOW_RUNTIME phase=SHADOW_TX_ACTIVITY
  packetsSent > 0
  audioTrackOwner=SHADOW_METRICS_ONLY
```

---

## 4. Driver plan (to implement)

**Entry script (new):** `talkback/scripts/phase-a-sr4-tx-field-first-run.ps1`

```powershell
# Wrapper → p1c-package-field-first-run.ps1 -DriverMode PaSr4Tx
# Adjudicator → phase-a-sr4-tx-field-adjudicate.ps1
```

| Phase | Behavior |
|-------|----------|
| Build + install | Same as PA-SR4 |
| PRE-G / PRE-I / PRE-J + J0 | Same |
| Observation | `ShadowObservationSec` default 45s |
| PTT | 3× M01 `btnPtt` burst (stimulus only) |
| SR5/SR6 | SKIP |
| Adjudicate | TX gate only |

---

## 5. Verdict matrix

| Verdict | Condition |
|---------|-----------|
| `PASS_PA_SR4_TX` | All §3 PASS lines on M01 |
| `FAIL_PA_SR4_TX` | Prereqs PASS but missing SESSION_STARTED or TX_ACTIVITY |
| `INVALID_PRE_*` | Standard prereq failures |

---

## 6. On PASS

```text
PR-PA-SR4-TX     FIELD-PROVEN
PA-SR4           VALID FAIL / RX NEXT
Next             PR-PA-SR4-RX implement + field (`meeting-product-integration-phase-a-sr4-rx-field-run-card.md`)
```

---

## 7. Synthetic fixtures

Minimum selftest fixtures under `talkback/scripts/fixtures/phase-a-sr4-tx-field/`:

- `pass-synthetic-host-tx.txt` — all PASS lines
- `fail-no-tx-activity.txt` — session started, packetsSent=0
- `fail-no-host-session.txt` — DEFERRED_NO_FACT only
