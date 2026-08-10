# RCA-003 — Presentation Convergence after Recovery (observation round 1)

**Status:** AUTHORIZED — **observe only / no code**  
**Parent:** [rca-003-presentation-convergence-entry.md](./rca-003-presentation-convergence-entry.md)  
**Baseline:** [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)  
**Do not touch:** Phase-2 · Delivery · Ownership · Reattach · acceptance · completion predicates

## Goal (frozen wording)

**Not:** 修 UI  

**Yes:** 证明 recovery terminal state 是否正确传播到 presentation projection

```text
EDGE_RECOVERED
        |
        v
Conference Runtime State
        |
        v
Roster / ActivityFrame
        |
        v
UI recovering pill
```

## Gate question (every episode)

```text
EDGE_RECOVERED 是否已经产生？
```

| Answer | Ownership |
|--------|-----------|
| yes | recovery **does not own** the remaining UX gap |
| no | only then re-enter recovery triage (rare under Last-mile v1) |

## Cases (observation only)

| Case | Pattern | Meaning |
|------|---------|---------|
| **A** | `EDGE_RECOVERED` → UI clears recovering | Normal convergence |
| **B** | `EDGE_RECOVERED` → runtime recovered → UI still recovering / sync / missing roster | Presentation projection gap |
| **C** | no `EDGE_RECOVERED` | Return to recovery triage (out of RCA-003 impl scope until proven) |

## Three facts to record

| Fact | Question |
|------|----------|
| F1 | `RECOVERY_EDGE_RECOVERED` present for the peer? |
| F2 | Runtime / projection cleared recovering member? (`recovering=` / barrier / presence) |
| F3 | UI consumed latest state? (pill / roster / sync) |

Classification:

```text
F1=no                         → Case C (not RCA-003 fix track)
F1=yes, F2=yes, F3=yes        → Case A
F1=yes, F2=yes, F3=no         → Case B (UI consume)
F1=yes, F2=no                 → Case B (runtime/projection)
```

## Stimulus (minimal)

```text
1. 3-party conference stable on SSID happy
2. Single-peer short flap (prefer M02), stay in meeting
3. Wait until EDGE_RECOVERED (or timeout ~60s)
4. Photograph / note UI pill + roster
5. Stop collectors
```

Do **not** chase M02 join failures in this round — that is **Session Churn / Join Stability** (separate name; not “WiFi recovery join issue”).

## Collectors

```powershell
cd talkback
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$LogDir = "logs\rca003-pres-conv-$stamp"
.\scripts\conf-same-session-rejoin-start-run.ps1 -LogDir $LogDir -LogBuffer 16M
# flap once; annotate T0
.\scripts\conf-same-session-rejoin-stop-run.ps1 -LogDir $LogDir
```

## Log greps (desk)

```text
RECOVERY_EDGE_RECOVERED
recovering=
ConferenceBarrier
ConferencePresence
ConferenceRuntime
SYNCING / syncing
```

Prefer host (M01) for roster/pill authority; DUT for edge terminal.

## Round-1 exit

```text
≥1 Case A  OR  ≥1 Case B with F1–F3 timestamps
Case C → file under recovery triage; do not expand RCA-003
No production code in round 1
```

## Explicit non-goals

```text
fix recovering pill
change UVCP / banner without admission episode
reopen recovery last-mile
rename join churn as WiFi recovery issue
```
