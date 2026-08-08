# ADR-0043 Appendix B — Passive Observation Run Card

**Status:** **COMPLETE** · **Appendix B PASS** · **Field NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Adjudication:** [adr0043-appendix-b-adjudication.md](./adr0043-appendix-b-adjudication.md)  
**Checklist:** [adr0043-appendix-b-passive-observation-checklist.md](./adr0043-appendix-b-passive-observation-checklist.md)  
**Baseline:** `main` @ `7820d87+` · run `logs/adr0043-appendix-b-20260808-185802/`

---

## Devices

| Role | Module | Serial | APK state |
|------|--------|--------|-----------|
| Host / authority | M01 | `HTUBB21B09220661` | Installed |
| Peer | M02 | `2d73067a` | Manual install from `/sdcard/Download/talkback-adr0043-main-debug.apk` |
| DUT / observer primary | M03 | `MDX0220416001963` | Installed |

**SSID:** `happy` only

---

## Log setup

```powershell
cd d:\workspace\project\talkback\talkback
.\scripts\adr0043-appendix-b-start-run.ps1
```

Actions:
- `logcat -G 16M` on M01/M02/M03
- `logcat -c` clear
- Start `Talkback:I` collectors → `logs/adr0043-appendix-b-<timestamp>/`

Stop after episode:

```powershell
.\scripts\adr0043-appendix-b-stop-run.ps1 -LogDir logs\adr0043-appendix-b-<timestamp>
```

---

## Test steps (passive — no directed flap)

### Step 0 — Preflight

- [ ] All three on SSID `happy`
- [ ] M02 APK installed (if not yet: install from Download)
- [ ] Log collectors running (`COLLECTOR_PIDS.txt` present)
- [ ] `logcat -g` shows **16M** buffer on each device

### Step 1 — Steady conference (≥ 2 min)

- [ ] M02 hosts CH-01 conference
- [ ] M01 + M03 joined
- [ ] Media stable (ICE connected, no active recovery)
- [ ] Wait **≥ 120 s** steady state

### Step 2 — Natural recovery trigger (pick one)

Choose **one** mild stimulus only:

| Option | Action |
|--------|--------|
| A (recommended) | M03: toggle WiFi OFF **10–15 s** → ON |
| B | M03: background app **30 s** → foreground |
| C | M03: brief weak-network spot (walk, no full disconnect) |

**Do NOT:** directed flap matrix · USER_LEAVE · repeated WiFi cycles · timeout hunting

### Step 3 — Observation window (≥ 3 min post-stimulus)

- [ ] After stimulus, wait **≥ 180 s**
- [ ] Do not force-restart app
- [ ] Note approximate stimulus time in `RUN_NOTES.txt`

### Step 4 — Stop collection

```powershell
.\scripts\adr0043-appendix-b-stop-run.ps1 -LogDir <LogDir>
```

---

## Adjudication (M03 primary, M01 authority corroboration)

Search logs for ADR-0043 chain. **PASS** requires semantic compliance, not recovery success.

### Point 1 — No PRESENT → no GROUP_RESYNC

```text
GROUP_RESYNC_DISPATCH_BLOCKED reason=ADR0043_*
```

Must NOT see `GROUP_RESYNC_REQUEST_SENT` without prior `MEMBERSHIP_CONTEXT_EXISTENCE_EVIDENCE answer=PRESENT` (or equivalent P1 chain).

### Point 2 — UNKNOWN → probe

```text
MEMBERSHIP_CONTEXT_EXISTENCE_QUERY_SENT
```

UNKNOWN must not auto-dispatch. No `RECOVERY FAILED` solely from UNKNOWN.

### Point 3 — PRESENT ≠ GRANT

If `answer=PRESENT`, dispatch still requires O1 allow path (not instant dispatch on PRESENT alone when blocked).

### Point 4 — Authority mismatch

Non-authority responses must show `AUTHORITY_MISMATCH` or reject — never promoted to PRESENT dispatch.

---

## Record template

Create `<LogDir>/ADJUDICATION_APPENDIX_B.txt`:

```text
ADR-0043 Appendix B Passive Observation

LogDir: ...
Stimulus: A|B|C @ <time>
Primary log: M03-talkback.log

Point 1 (no PRESENT -> no RESYNC): PASS|FAIL|N/A
Point 2 (UNKNOWN -> probe):          PASS|FAIL|N/A
Point 3 (PRESENT != GRANT):          PASS|FAIL|N/A
Point 4 (authority mismatch):        PASS|FAIL|N/A

Roll-up: PASS|FAIL|INCONCLUSIVE

Notes:
- recovery outcome NOT scored
- field authorization NOT claimed
```

---

## Explicit non-goals

```text
NOT: WiFi flap matrix · recovery success rate · ICE/timeout adjudication · field PASS
```

---

## One-line

> Passive Appendix B: normal conference + one mild recovery stimulus; verify P1+O1 log chain on M03; not field validation.
