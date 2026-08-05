# RNA Directed #3 Run Card — RNA-5 v2 Intent Terminal Closure

**Status:** PASS (RNA-5 lifecycle closure) — FIELD VERIFIED
**Evidence:** `talkback/logs/wifi-recovery-m03-rna0037-directed3-20260805-193602`
**Authorized:** 2026-08-05 (architecture sign-off after PR-3C-A..D)
**Parent:** [Gate 3C plan](./gate3c-negotiation-intent-terminal-closure-plan.md)
**Contract:** [RNA-5 v2 amendment](../adr/0037-rna-5-intent-terminal-contract-amendment.md)
**Predecessor:** Directed #2 ARCHIVED — `talkback/logs/wifi-recovery-m03-rna0037-directed-20260805-183625`

**Goal:** Prove RNA-5 v2 eliminates Directed #2 dangling intent — every admitted negotiation intent reaches exactly one terminal. This run is **not** a recovery-success / UI ONLINE verdict.

---

## Architecture sign-off (frozen)

```text
PR-3C-A  Single terminal writer
PR-3C-B  Deferred supersede bridge
PR-3C-C  Ghost intent elimination
PR-3C-D  Independent negotiation intent budget
Gate 3C  VERIFIED / READY FOR FIELD
```

Lifecycle shift:

```text
before: DEFERRED_DANGLING tolerated
after:  every negotiation intent has bounded lifecycle
```

---

## Topology / devices

| Role | Module | Serial |
|------|--------|--------|
| Peer | M01 | `HTUBB21B09220661` |
| Peer | M02 | `2d73067a` |
| DUT  | M03 | `MDX0220416001963` |

- SSID: **`happy`** only (not `happy_5G`)
- Same topology as Directed #2

---

## Preconditions (hard)

Must hold before flap and through observation:

```text
display=3
connected=3
no USER_LEAVE
no Hangup
no membership mutation
```

Also:

- Install build containing Gate 3C (PR-3C-A..D on `main`)
- Clear prior collectors / start fresh log dir
- Do **not** enlarge attempt budget, force deferred ICE, or completion-bypass

---

## Exercise

Recommended W2-class flap (same as matrix W2):

```text
Baseline (display=3, connected=3)
        |
        v
M03 WiFi OFF
        |
        | ~15s
        |
M03 WiFi ON
        |
        v
observe >= negotiation intent budget + recovery window
```

Scripts:

```powershell
# start collectors (W2 = 15s off)
.\scripts\wifi-recovery-start-run.ps1 -Scenario W2

# after ON + soak, adjudicate only — do not patch field mid-run
.\scripts\wifi-recovery-adjudicate.ps1 -LogDir <logDir>
```

Log dir naming suggestion:

```text
talkback/logs/wifi-recovery-m03-rna0037-directed3-<yyyyMMdd-HHmmss>
```

---

## RNA-5 v2 mandatory observe chain

Do **not** stop at ONLINE / presence. Collect in order:

```text
same RecoveryNegotiationKey
        |
        v
RECOVERY_NEGOTIATION_OWNER_RESOLVED
        |
        v
RECOVERY_NEGOTIATION_INTENT (CREATED / DEFERRED) with intentId != NONE
        |
        v
exactly one of:
  EXECUTED | BLOCKED_BY_GLARE | EXPIRED | SUPERSEDED
        |
        = RECOVERY_NEGOTIATION_INTENT_TERMINAL
        |
        v
NEGOTIATION_RECOVERY_FACT   (if negotiation path continues)
        |
        v
RECOVERY_EDGE_RECOVERED     (completion still OPEN — observe, do not fail on isolation)
```

Useful companion logs:

- `NEGOTIATION_INTENT_CLOSE_REQUEST` / `source=...`
- `NEGOTIATION_BUDGET_EXHAUSTED` (if settling deadlock path)
- `DEFERRED_INTENT_SUPERSEDED` must pair with RNA terminal `SUPERSEDED` / `source=MEDIA_ACTION_SUPERSEDE`
- `RECOVERY_MEDIA_ACTION_DEFERRED` with `MEDIA_NOT_READY` must **not** create RNA intent

---

## PASS rules

All required:

```text
intent_terminal_count == 1
AND terminal in {EXECUTED, BLOCKED_BY_GLARE, EXPIRED, SUPERSEDED}
AND no ghost intent (no RECOVERY_NEGOTIATION_INTENT with intentId=NONE / MEDIA_NOT_READY)
AND OWNER_CONFLICT == 0
AND same RecoveryNegotiationKey retained across episode
```

---

## FAIL rules

Any of:

```text
intent CREATED + no terminal (+ budget should have expired)
MEDIA_NOT_READY + RECOVERY_NEGOTIATION_INTENT(intentId=null|NONE)
terminal_count > 1 for same intentId
DEFERRED_DANGLING treated as pass
```

---

## Out of scope (do not adjudicate this run)

Keep isolation:

- membership convergence (RCA-0036 already RE-SIGNED)
- presence projection / UI ONLINE
- completion predicate changes
- enlarging recovery / attempt budget

If `FAIL_EDGE_COMPLETION_NEGOTIATION` / `mediaIssueObserved` appears: treat as edge/negotiation domain observation — **not** RCA-0036 reopen.

---

## Adjudication posture

1. Collect logs only
2. Apply PASS/FAIL rules above
3. Archive log dir + short verdict note
4. **Do not** immediately patch field code from Directed #3 outcome

Focus question:

> Did RNA-5 v2 eliminate Directed #2 dangling negotiation intent?

---

## Field result (2026-08-05)

```text
RNA-0037 Directed #3                PASS (RNA-5 lifecycle closure)
Gate 3C                             FIELD VERIFIED
DEFERRED_DANGLING                   CLOSED BY RNA-5 v2 + Directed #3 PASS
Owner bilateral convergence         OPEN
M03↔M02 OWNER_CONFLICT              OUT_OF_SCOPE_FOR_RNA0037_DIRECTED3
Recovery Completion                 OPEN
```

Evidence dir: `talkback/logs/wifi-recovery-m03-rna0037-directed3-20260805-193602`

Primary chain (M01 → edge=M03):
- R1 / R2: CREATED → BUDGET_ARMED → BUDGET_EXHAUSTED → CLOSE_REQUEST → TERMINAL(EXPIRED)
- ghost intent = 0; MEDIA_NOT_READY isolated to media defer only

Next (offline, no code): RNA-0037 evidence completion audit —
why intent closes without NEGOTIATION_RECOVERY_FACT / RECOVERY_EDGE_RECOVERED.