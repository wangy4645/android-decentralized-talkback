# ADR-0050 R2a — Directed Negotiation Ingress Soak Run Card

**Status:** **AUTHORIZED** · Field **GO** (after #167 merge)  
**Name:** ADR-0050 R2a Directed Ingress Soak  
**(Not** RNA WiFi-recovery “Directed #5”.)  
**Merge gate:** PR [#167](https://github.com/wangy4645/android-decentralized-talkback/pull/167) → `main` @ `40a984c`  
**Adjudication:** [adr0050-r2a-architecture-adjudication.md](./adr0050-r2a-architecture-adjudication.md)  
**IC:** [adr0050-r2a-negotiation-ingress-readiness-ic.md](./adr0050-r2a-negotiation-ingress-readiness-ic.md)  
**Contrast prior:** `logs/adr0050-admission-20260811-154011/` (lease PASS · `REMOTE_INGRESS_ABSENT` · ~47s late answer)  
**Adjudicate:** `scripts/adr0050-r2a-directed-ingress-adjudicate.ps1`

---

## Board

```text
ADR-0050 Admission Lease          VERIFIED
R2a Negotiation Ingress Gate       MERGED (#167) · field AUTHORIZED
R2b Offer Arbitration              HOLD
```

---

## Single goal (narrow)

> 在 lease 已授权的前提下，避免 offer 发到一个还没有 ingress 能力的 peer。

**Prove:**

```text
LEASE_ADMITTED
        ↓
NEGOTIATION_INGRESS_PENDING (optional)
        ↓
REMOTE_NEGOTIATION_READY
        ↓
RECOVERY_ICE_RESTART_DISPATCHED
        ↓
ANSWER (bounded window)
```

**Not proving:** WiFi recovery success · EDGE_RECOVERED · DEGRADED / UVCP · completion predicate · R2b.

---

## Topology / APK

| Role | Module | Serial |
|------|--------|--------|
| Observer | M01 | `HTUBB21B09220661` |
| Flap target | M02 | `2d73067a` |
| Observer | M03 | `MDX0220416001963` |

SSID: **`happy`** only · APK: `main` containing #167 (`40a984c` or later).

```powershell
cd talkback
.\gradlew.bat :talkback-app:assembleDebug
adb -s HTUBB21B09220661 install -r talkback-app\build\outputs\apk\debug\talkback-app-debug.apk
adb -s MDX0220416001963 install -r talkback-app\build\outputs\apk\debug\talkback-app-debug.apk
adb -s 2d73067a push talkback-app\build\outputs\apk\debug\talkback-app-debug.apk /sdcard/Download/talkback/talkback-app-debug.apk
```

---

## Execution (thin)

```powershell
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$LogDir = "logs\adr0050-r2a-ingress-$stamp"
.\scripts\wifi-recovery-start-run.ps1 -Scenario W2 -LogDir $LogDir
# Pre: 3-party joined, media OK, SSID=happy
# T0: M02 WiFi OFF ~10–20s → ON  (no USER_LEAVE)
# Soak 60–120s for M01→M02 / M03→M02
# Stop collectors, then:
.\scripts\adr0050-r2a-directed-ingress-adjudicate.ps1 -LogDir $LogDir
```

Annotate: flap `T0`, M02 outbound up, soak end.

---

## P0 markers (score these)

| Marker | Expectation |
|--------|-------------|
| `NEGOTIATION_LEASE_ADMITTED` | ≥1 on inbound edge to M02 |
| `NEGOTIATION_INGRESS_PENDING` | optional |
| `REMOTE_NEGOTIATION_READY` | appears before dispatch |
| `RECOVERY_ICE_RESTART_DISPATCHED` | after READY |
| Answer after offer | in reasonable window (T3) |
| `NEGOTIATION_NON_OWNER_BLOCKED` | 0 |

**Do not score as R2a P0:**

```text
RECOVERY_REMOTE_INGRESS_ABSENT   — delivery observation only; not negotiation readiness
EDGE_RECOVERED / DEGRADED / UI
```

If DEGRADED appears: ask only「media 有没有 CONNECTED？」— do not reopen UVCP.

---

## Three timings only (T1 / T2 / T3)

| Id | From | To | Meaning |
|----|------|-----|---------|
| **T1** | `NEGOTIATION_LEASE_ADMITTED` | `REMOTE_NEGOTIATION_READY` | ingress gate latency |
| **T2** | `REMOTE_NEGOTIATION_READY` | `RECOVERY_ICE_RESTART_DISPATCHED` | R2a controls dispatch |
| **T3** | `RECOVERY_ICE_RESTART_DISPATCHED` / offer | Answer / SRD ANSWER on peer | ingress black-hole? |

**Critical = T3.** 154011 counterfactual: offer → ~47s answer.

---

## Case classification

| Case | Observation | Verdict |
|------|-------------|---------|
| **A** | READY → DISPATCH → ANSWER (T3 bounded) | **FIELD SUPPORTED** |
| **B** | READY → DISPATCH → still no ANSWER | R2a OK; execution/answer — **no rollback** |
| **REFUSE** | PENDING → DEADLINE → no DISPATCH | **correct R2a block** — not failure |

---

## Frozen (do not touch this run)

```text
❌ R2b / single offerer
❌ enlarge timeout
❌ expand retry
❌ ICE policy
❌ residency
❌ UVCP
```

---

## Auth

```text
#167 merge: DONE
Field Finding #1: FIELD SUPPORTED
R2a predicate: FROZEN
Acceptance: LEASE → READY → DISPATCH → ANSWER (not RECOVERY_REMOTE_INGRESS_ABSENT)
Not scored: EDGE_RECOVERED · DEGRADED · UI · delivery ABSENT
R2b: HOLD (FUTURE ONLY)
```
