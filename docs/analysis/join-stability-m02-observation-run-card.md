# Join Stability — M02 Enter-Meeting Observation Card

**Status:** **AUTHORIZED** · Observation only · **NO PATCH**  
**Track:** Session Churn / Join Stability (**P1**)  
**Not:** WiFi Recovery / ADR-0050 / R2a / R2b / Media Edge Convergence / UVCP  
**DUT:** M02 (`2d73067a`) · Host M01 · Peer M03 · SSID **`happy`** only  
**Prior park:** [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md) (M02 enter-meeting failures OPEN)  
**Collectors:** `scripts/join-stability-m02-start-run.ps1` / `join-stability-m02-stop-run.ps1`  
**Adjudicate:** `scripts/join-stability-m02-adjudicate.ps1`

---

## Portfolio board

```text
WiFi Recovery Protocol              CLOSED / VERIFIED
ADR-0050 Admission + R2a            FIELD SUPPORTED / FROZEN
Presentation (RCA-003)              CLOSED
Media Edge Convergence              DEFERRED (no “recover then no media” repro)
Session Churn / Join Stability      OPEN ← this card (P1)
R2b Offer Arbitration               HOLD
```

---

## Single goal

> 归类 **M02 进不了会** 卡在哪一层 — 不是修 recovery，也不是判 EDGE_RECOVERED。

```text
INVITE / REJOIN / RESYNC / ROSTER / UI-wait / BUSY-misclass
```

**Success of this round = classified layer**, not “joined PASS”.

---

## Explicit non-goals

```text
❌ reopen ADR-0050 / R2a / lease / ownership
❌ score RECOVERY_REMOTE_INGRESS_ABSENT / REMOTE_NEGOTIATION_READY
❌ score EDGE_RECOVERED / DEGRADED as join PASS/FAIL
❌ USER_LEAVE mid-run unless reproducing “leave then cannot rejoin”
❌ fold into “WiFi recovery still broken”
```

---

## Failure taxonomy (classify only)

| Class | Symptom | Typical markers |
|-------|---------|-----------------|
| **J1 Invite never arrives** | M02 taps join; host never sends / M02 never sees invite | no `GROUP_INVITE` on M02; host no send |
| **J2 Duplicate / BUSY gate** | Same session treated as new invite → BUSY | `prepareForGroupInvite` false · `GROUP_BUSY` · missing reconnect accept |
| **J3 Rejoin path miss** | Should `CONFERENCE_REJOIN` / reconnect accept; falls through | no `reconnect accepted` / `GROUP_ACCEPT_HANDOFF path=RECONNECT` |
| **J4 Membership / roster** | Join UX ok but roster wrong / epoch stuck | `GROUP_RESYNC` · epoch mismatch · member missing |
| **J5 UI stuck** | Protocol joined; UI still “joining/syncing” | session accepted + UI wait (presentation — separate if proven) |
| **J6 Env / APK** | wrong APK / SSID / cold start race | note in RUN_META |

---

## Operator preconditions

1. APK known (record commit / build in `RUN_META.txt`)
2. SSID = **happy**
3. Prefer reproduce the **same path** that failed before:
   - cold start then join?
   - WiFi flap then re-enter meeting screen?
   - killed app then rejoin?
   - host already in conference, M02 joins late?

Annotate one line: `stimulus=<cold_join|late_join|post_flap_rejoin|app_restart_rejoin>`

---

## Execution

```powershell
cd talkback
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$LogDir = "logs\join-stability-m02-$stamp"
.\scripts\join-stability-m02-start-run.ps1 -LogDir $LogDir
# Reproduce M02 cannot enter (do NOT force USER_LEAVE unless that is the stimulus)
# When stuck or after ~90–120s:
.\scripts\join-stability-m02-stop-run.ps1 -LogDir $LogDir
.\scripts\join-stability-m02-adjudicate.ps1 -LogDir $LogDir
```

Topology: M01 host meeting up → M02 attempts enter (M03 optional contrast).

---

## Observe order (facts only)

```text
1. M02 UI action → join / rejoin intent
2. Outbound: GROUP_JOIN / CONFERENCE_REJOIN / GROUP_RESYNC_REQUEST
3. Inbound: GROUP_INVITE / GROUP_ACCEPT / GROUP_BUSY / reject
4. Host: invite sent? reconnect accept? BUSY?
5. Membership: roster / epoch on M01 vs M02
6. Session: accepted / channelId / sessionId match
7. Only then: media (contrast — not join verdict)
```

---

## PASS / FAIL of observation (desk)

| Result | Meaning |
|--------|---------|
| **CLASSIFIED** | One of J1–J6 assigned with log anchors |
| **INSUFFICIENT** | No invite/join markers; need cleaner stimulus |
| **ENV_INVALID** | wrong SSID / APK / collectors late |

Do **not** open patch until **CLASSIFIED** on ≥1 solid run (prefer 2).

---

## Frozen domains (do not touch)

```text
recovery ownership · admission lease · R2a predicate · UVCP · R2b · completion predicate
```
