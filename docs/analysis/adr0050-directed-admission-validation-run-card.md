# ADR-0050 — Directed Admission Validation Run Card

**Status:** **ADMISSION GATE FIELD-VERIFIED** (154011) · full directed EDGE_RECOVERED **NOT PASS** · Execution OPEN → R1  
**Name:** ADR-0050 Directed Admission Validation  
**(Not** RNA WiFi-recovery “Directed #5” — that maturity track remains closed.)  
**Parent:** [0050-negotiation-admission-handoff.md](../adr/0050-negotiation-admission-handoff.md)  
**IC:** [adr0050-phase1-implementation-candidate.md](./adr0050-phase1-implementation-candidate.md)  
**Finding:** [rca-004-media-edge-recovery-convergence-finding.md](./rca-004-media-edge-recovery-convergence-finding.md)  
**Field evidence:** `logs/adr0050-admission-20260811-154011/` · [FIELD_RESULT.md](../../logs/adr0050-admission-20260811-154011/FIELD_RESULT.md)  
**Follow-up:** [adr0050-r1-ice-restart-execution-attribution-audit.md](./adr0050-r1-ice-restart-execution-attribution-audit.md)  
**Adjudicate:** `scripts/adr0050-directed-admission-adjudicate.ps1`  
**Impl gate:** PR #165 **MERGED** → `main` @ `de58d6c`

---

## Portfolio status (observe only)

```text
RCA-003 Presentation Convergence     CLOSED
RCA-004 Media Edge Recovery          FINDING COMPLETE
ADR-0050 Negotiation Admission       GATE FIELD-VERIFIED (#165 / 154011)
                                     Execution OPEN → ADR-0050-R1
```

## Goal (single)

Validate **negotiation admission handoff**, not recovery success rate / soak maturity:

```text
Recovery intent exists
  + negotiationOwner = remote (flapped peer)
  + local media-action ∈ {PENDING, HOST_RESTART}
        ↓
NEGOTIATION_LEASE_GRANTED / ADMITTED
        ↓
RECOVERY_ICE_RESTART_DISPATCHED
        ↓
ICE progress → EDGE_RECOVERED (or classified failure layer)
```

**Not proving:** WiFi recovery %, UVCP pill, membership, completion predicate change.

---

## Scope discipline

```text
ONLY:  M02 WiFi flap (inbound edges to flapped peer)
FOCUS: M01→M02 and M03→M02 admission + restart execution

DO NOT:
  - USER_LEAVE
  - M01 or M03 as primary flap
  - UVCP / degraded / residency clear changes
  - retry / ICE timeout / membership / completion predicate
  - edge-scoped owner redesign
  - reopen RCA-003 / last-mile / RNA Directed maturity track
```

---

## Topology

| Role | Module | Serial |
|------|--------|--------|
| Observer / inbound | M01 | `HTUBB21B09220661` |
| Flap target | M02 | `2d73067a` |
| Observer / inbound | M03 | `MDX0220416001963` |

SSID: **`happy`** only

```text
M01
 |
 M02  <-- flap target
 |
 M03
```

**APK:** build containing ADR-0050 lease patch (#165 merge commit or CI artifact).

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
$LogDir = "logs\adr0050-admission-$stamp"
.\scripts\wifi-recovery-start-run.ps1 -Scenario W2 -LogDir $LogDir
# Pre: 3-party joined, media OK, SSID=happy
# T0: M02 WiFi OFF ~10–20s → ON  (no USER_LEAVE)
# Wait M02 outbound CONNECTED (contrast)
# Soak 60–120s for M01→M02 / M03→M02
# Stop collectors, then:
.\scripts\adr0050-directed-admission-adjudicate.ps1 -LogDir $LogDir
```

Annotate in chat: `T0` (flap), `T1` (M02 outbound up), `T2` (end soak).

---

## Observe order (facts)

```text
1. RECOVERY_NEGOTIATION_OWNER_BOOTSTRAP  owner=<flapped/remote>  (INV-1 baseline)
2. NEGOTIATION_LEASE_GRANTED             (≈ LEASE_CREATED)
3. NEGOTIATION_LEASE_ADMITTED            (≈ LEASE_USED)
4. RECOVERY_ICE_RESTART_DISPATCHED
5. createOffer / setLocalDescription / candidates / ANSWER  (execution)
6. ICE CONNECTED / RECOVERY_EDGE_RECOVERED
7. NEGOTIATION_LEASE_EXPIRED             (optional; INV-3 — must NOT alone imply FAILED_MEDIA)
```

**Ownership check (INV-1):** after lease admit, `canonicalNegotiationOwnerModuleId` / bootstrap owner **unchanged** (still remote). No “fix by rewriting owner.”

**Dual-restart watch (Risk B):** same episode window, same edge pair — flag if local `HOST_RESTART` and remote `PARTICIPANT_REATTACH` both actively dispatch. If seen → classify **lease arbitration needed**, not rollback of Option A.

---

## PASS criteria

On **at least one** of {M01, M03} observing edge `remote=M02` (prefer both):

```text
PASS when ALL hold:

  A. NEGOTIATION_NON_OWNER_BLOCKED count for remote=M02 == 0
     (after lease-eligible attempt; pre-lease blocked lines in old APK N/A)

  B. ≥1 NEGOTIATION_LEASE_ADMITTED (remote=M02)

  C. Subsequent RECOVERY_ICE_RESTART_DISPATCHED (remote=M02)

  D. Subsequent ICE CONNECTED evidence OR RECOVERY_EDGE_RECOVERED (remote=M02)

  E. No owner rewrite: LEASE_ADMITTED does not change negotiation owner to LOCAL
```

M02 outbound recovery to peers is **contrast only** (expect healthy path; not the admission under test).

---

## Failure classification (next layer)

| Observation | Layer | Next |
|-------------|-------|------|
| No `NEGOTIATION_LEASE_*` + still `NON_OWNER_BLOCKED` | admission gate | APK/gate regression |
| Lease ADMITTED, no `ICE_RESTART_DISPATCHED` | admission→dispatch seam | controller bug |
| Dispatched, no createOffer / local description | restart execution | media/engine |
| Offer present, no ANSWER | peer acceptance | bilateral / glare |
| ICE CONNECTED, no `EDGE_RECOVERED` | completion | ADR-0038 domain — **do not patch here** |
| `LEASE_EXPIRED` alone → FAILED_MEDIA | INV-3 violation | fix mapping; not enlarge budget |
| Concurrent HOST_RESTART + PARTICIPANT_REATTACH dispatch | lease arbitration | new ADR knife — not rollback |

---

## Explicit non-goals

```text
≠ prove high recovery success rate
≠ UVCP / presentation soak
≠ membership convergence field
≠ Directed maturity upgrade for closed WiFi RNA chain
≠ authorize CompletionPolicy / timeout / retry changes
```

---

## Auth gate

```text
DRAFTED → MERGED #165 → field auth (「合并然后开测」 2026-08-11) → thin directed run → adjudicate
```
