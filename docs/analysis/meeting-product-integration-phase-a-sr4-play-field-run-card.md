# PA-SR4-PLAY — Field Run Card (Shadow Playout Execution Gate)

**RCA:** `meeting-product-integration-phase-a-sr4-play-rca-20260910-092105.md`  
**TX/RX prerequisite:** PR-PA-SR4-TX + PR-PA-SR4-RX **CLOSED / FIELD-PROVEN**  
**Status:** **IMPLEMENTATION AUTHORIZED**

```text
PR-PA-SR4-TX     CLOSED / UNIT+FIELD-PROVEN
PR-PA-SR4-RX     CLOSED / UNIT+FIELD-PROVEN
PR-PA-SR4-PLAY   AUTHORIZED → field gate below
PA-SR4           VALID FAIL / PLAY NEXT (closes on PLAY PASS)
```

---

## 1. Question this gate answers

With TX + RX + ingress proven in the same topology, does the peer execute **playout execution lifecycle** and produce real shadow metrics playout writes?

```text
TX > 0
→ RX admitted / ingress > 0
→ SHADOW_PLAYOUT_ARMED
→ decode/mix cycle
→ PLAYOUT_ACTIVITY successfulPlayoutWrites > 0
→ audioTrackOwner=SHADOW_METRICS_ONLY
```

---

## 2. Hard boundaries

| # | Boundary |
|---|----------|
| 1 | PRE-G / PRE-I / PRE-J + J0 PASS — else `INVALID_*` |
| 2 | M01 `SHADOW_TX_ACTIVITY packetsSent>0` |
| 3 | M02 `INGRESS_ACTIVITY packetsReceived>0` (prerequisite) |
| 4 | **M02** adjudication for PLAY PASS |
| 5 | No production AudioTrack for shadow |
| 6 | `RecordingPlayoutMetricsSeam` remains evidence outlet |
| 7 | SR5/SR6 skipped on this driver |

---

## 3. PASS chain (frozen)

**Prerequisites:**

```text
M01 SHADOW_TX_ACTIVITY packetsSent>0
M02 WIRING_SESSION_STARTED transportScopeActive=true
M02 INGRESS_ACTIVITY packetsReceived>0
```

**Diagnostic (not PASS alone):**

```text
SHADOW_PLAYOUT_ARMED outcome=APPLIED
SHADOW_PLAYOUT_CYCLE
activeJitterSources>0
liveDecoders>0
```

**ONLY PASS:**

```text
M02 PLAYOUT_ACTIVITY successfulPlayoutWrites>0
audioTrackOwner=SHADOW_METRICS_ONLY
```

---

## 4. PA-SR4 closure (on PLAY PASS)

```text
PA-SR4
  TX             PASS
  RX             PASS
  INGRESS        PASS
  PLAYOUT        PASS
  Audio isolation PASS
→ CLOSED / FIELD-PROVEN
```

**Next:** PA-SR5 leave/rejoin → PA-SR6 teardown

---

## 5. Harness

```powershell
.\talkback\scripts\phase-a-sr4-play-field-first-run.ps1
.\talkback\scripts\phase-a-sr4-play-field-adjudicate.ps1 -LogDir <dir>
.\talkback\scripts\phase-a-sr4-play-field-adjudicate-selftest.ps1
```

Driver: `p1c-package-field-first-run.ps1 -DriverMode PaSr4Play`
