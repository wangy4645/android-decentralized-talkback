# PA-SR4-RX — Field Run Card (Shadow Multicast Receive Gate)

**Authorization:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-rx-implementation-authorization.md`  
**Design:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-rx-design.md`  
**TX prerequisite:** PR-PA-SR4-TX **CLOSED / FIELD-PROVEN** (`20260909-171232`)  
**Parent:** `meeting-product-integration-phase-a-sr4-field-run-card.md` (PA-SR4 full — still open)  
**Status:** **UNIT-PROVEN / HARNESS NOT YET IMPLEMENTED**

```text
PR-PA-SR4-TX     CLOSED / UNIT+FIELD-PROVEN
PA-SR4           VALID FAIL / RX NEXT
PA-SR4-RX        AUTHORIZED (harness TBD)
PA-SR4-PLAY      HOLD
ADR-0056         KEEP
```

---

## 1. Question this gate answers

With **host TX proven** in the same topology, does the **peer** execute product shadow receive and produce **real ingress activity** — without claiming playout or PA-SR4 full PASS?

```text
TX prerequisite (host packets on wire)
→ Peer WIRING_SESSION_STARTED transportScopeActive=true
→ RX executor alive
→ datagram received > 0
→ admitDatagram path exercised
→ INGRESS_ACTIVITY packetsReceived > 0
```

---

## 2. Hard boundaries

| # | Boundary |
|---|----------|
| 1 | PRE-G / PRE-I / PRE-J + J0 PASS — else `INVALID_*` |
| 2 | **TX field prerequisite** — host must show prior-TX-class evidence in same run (`SHADOW_TX_ACTIVITY packetsSent>0` on M01) |
| 3 | **Peer (M02)** adjudication for RX PASS — not host |
| 4 | `PLAYOUT_ACTIVITY` — **observe only**, not PASS criteria |
| 5 | `SHADOW_TX_ACTIVITY` on peer — observe only (peer is not TX subject) |
| 6 | No manual datagram inject / synthetic wire replay |
| 7 | M01 `MEMBER_MEDIA_READY(M02)=DEFERRED_NO_BINDING` — out of scope (unchanged from TX) |

---

## 3. PASS chain (frozen)

Within observation window, **same `hostSessionId=H`**:

### Prerequisites (necessary — not sufficient alone)

**M01 host:**

```text
PROFILE01_SHADOW_RUNTIME phase=SHADOW_TX_ACTIVITY
  packetsSent > 0
  (host TX stimulus path alive — same class as TX field PASS)
```

**M02 peer:**

```text
PROFILE01_SHADOW_RUNTIME phase=WIRING_SESSION_STARTED
  sessionId=H transportScopeActive=true

SHADOW hook=SESSION_STARTED outcome=APPLIED
  OR PROFILE01_SHADOW_RUNTIME phase=SHADOW_SESSION_STARTED
```

Optional diagnostic (not PASS alone):

```text
PROFILE01_SHADOW_RUNTIME phase=SHADOW_RX_ARMED outcome=APPLIED
PROFILE01_SHADOW_RUNTIME phase=SHADOW_RX_DATAGRAM datagramsPolled>0
```

### PASS criterion (only)

**M02 peer:**

```text
PROFILE01_SHADOW_RUNTIME phase=INGRESS_ACTIVITY
  sessionId=H
  packetsReceived > 0
  OR ingressAccepted > 0
```

`audioTrackOwner=SHADOW_METRICS_ONLY` when field present (hard fence if `PRODUCTION*`).

---

## 4. Driver plan (to implement)

**Entry script (new):** `talkback/scripts/phase-a-sr4-rx-field-first-run.ps1`

```powershell
# Wrapper → p1c-package-field-first-run.ps1 -DriverMode PaSr4Rx
# Adjudicator → phase-a-sr4-rx-field-adjudicate.ps1
```

| Phase | Behavior |
|-------|----------|
| Build + install | Same as PA-SR4-TX |
| PRE-G / PRE-I / PRE-J + J0 | Same |
| Observation | `ShadowObservationSec` default 45s |
| PTT | 3× M01 `btnPtt` burst (**stimulus** — host TX; not RX PASS alone) |
| SR5/SR6 | SKIP |
| Adjudicate | **RX gate only** (M02 ingress) |

**DriverMode addition:** `PaSr4Rx` in `p1c-package-field-first-run.ps1` (mirror `PaSr4Tx`).

---

## 5. Verdict matrix

| Verdict | Condition |
|---------|-----------|
| `PASS_PA_SR4_RX` | §3 prerequisites + M02 `INGRESS_ACTIVITY packetsReceived>0` |
| `FAIL_PA_SR4_RX` | Prerequisites green (incl. host TX activity) but peer ingress absent |
| `INVALID_PRE_*` | PRE-G/I/J/J0 failures |
| `INVALID_TX_PREREQUISITE` | Host missing `SHADOW_TX_ACTIVITY packetsSent>0` in same run |
| `INVALID_PEER_SESSION` | M02 missing `WIRING_SESSION_STARTED` / session not live |

### FAIL vs INVALID

```text
INVALID_*           → gate not validly exercised
FAIL_PA_SR4_RX      → valid exercise; RX integration gap (post-RX implementation)
```

Use `SHADOW_RX_REJECT` / `SHADOW_TX_FENCED` breakdown in adjudicator output for RCA routing (diagnostic only).

---

## 6. Adjudicator outputs (required)

Mirror TX adjudicator structure:

```text
PREREQUISITE_SAME_SESSION
TX_PREREQUISITE_HOST_PACKETS=True|False
PEER_WIRING_SESSION_LIVE=True|False
RX-ESTABLISH_SHADOW_RX_ARMED=True|False (diagnostic)
RX-RECEIVE_DATAGRAM_POLLED=True|False (diagnostic)
RX-INGRESS_INGRESS_ACTIVITY=True|False packetsReceived_max=N

FENCE_BREAKDOWN_DIAGNOSTIC_ONLY
SHADOW_RX_REJECT_lines=N reason_top=…
UNKNOWN_SSRC_lines=… (from wire reject OBS if present)

observe_only: PLAYOUT_ACTIVITY (not PASS)
```

---

## 7. Synthetic fixtures (minimum selftest)

Under `talkback/scripts/fixtures/phase-a-sr4-rx-field/`:

| Fixture | Purpose |
|---------|---------|
| `pass-synthetic-peer-ingress.txt` | M01 TX + M02 INGRESS_ACTIVITY |
| `fail-no-ingress.txt` | TX + peer session live, packetsReceived=0 |
| `invalid-no-tx.txt` | peer ingress lines but host packetsSent=0 |
| `invalid-no-peer-session.txt` | DEFERRED_NO_FACT / no WIRING on M02 |

---

## 8. On PASS

```text
PR-PA-SR4-RX     CLOSED / UNIT+FIELD-PROVEN
PA-SR4           VALID FAIL / PLAY or full SR4 re-field NEXT
PR-PA-SR4-PLAY   DESIGN NEXT if PLAYOUT still zero — else OBS closure
```

**Does not close PA-SR4 full gate** until both ingress and playout proven per `meeting-product-integration-phase-a-sr4-field-authorization.md`.

---

## 9. On FAIL (valid run)

Route RCA by breakdown:

| Observation | Route |
|-------------|-------|
| No `SHADOW_RX_ARMED` | RX lifecycle wiring |
| `SHADOW_RX_DATAGRAM>0` but no INGRESS | admission / SSRC / epoch (existing pipeline) |
| `SHADOW_RX_REJECT reason=UNKNOWN_SSRC` | catalog/binding timing vs TX |
| Host TX=0 | TX regression — not RX FAIL |
| Ingress present, playout 0 | **Not RX FAIL** — PLAY track |

---

## 10. References

- TX field PASS: `talkback/logs/phase-a-sr4-tx-field-20260909-171232/`
- TX run card: `meeting-product-integration-phase-a-sr4-tx-field-run-card.md`
- SR4 RCA GAP-C: `meeting-product-integration-phase-a-sr4-rca-runtime-execution-20260909-161221.md`
- Full SR4 gate: `meeting-product-integration-phase-a-sr4-field-run-card.md`
