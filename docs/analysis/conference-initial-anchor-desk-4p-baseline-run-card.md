# IA-1/2/3 Desk 4p Baseline Run Card

## Status

**PASS** (2026-08-21) · evidence: `logs/conference-initial-anchor-desk-4p-20260821-102308/`

**Blocks:** ~~P0.1g field~~ → **P0.1g field AUTHORIZED** ([p01g-field-authorization-001.md](./conference-control-media-execution-isolation-p01g-field-authorization-001.md))

**Does NOT validate:** SRD · ICE · Audio · Ready · Recovery · failover

---

## Goal

Prove:

```text
Conference CREATE
    |
    v
Initial Anchor Policy
    |
    v
initiator = initial anchor = current anchor
```

Audit boundary (IA-2):

```text
CREATE ownership decision   (INITIAL_ANCHOR_POLICY)
        vs
RUN topology evolution      (PRESERVED_ANCHOR / RANKING_CANDIDATE / FAILOVER_RANKING)
```

---

## Precondition

### Devices

```text
M01 · M02 · M03 · M04   (all online)
```

### Session hygiene

Before start:

```text
no stale conference session
no stale anchor snapshot
no recovering state
GROUP cleaned
conferenceSessionCount=0
```

---

## Action

### Step 1 — M01 creates conference

Record:

```text
initiatorModuleId=M01
```

### Step 2 — Wait for threshold

```text
members=4
```

Triggers:

```text
MESH → ANCHOR
```

---

## Required evidence

### 1. Initial Anchor decision

Must exist:

```text
ANCHOR_DECISION
 phase=CREATE
 initiator=M01
 candidate=M01
 admitted=M01
 reason=INITIAL_ANCHOR_POLICY
```

### 2. Snapshot

Must:

```text
snapshot.anchorId=M01
```

(from `CONFERENCE_TOPOLOGY_PUBLISHED ... anchor=M01` or equivalent snapshot line)

### 3. No ranking override on CREATE

**Forbidden** as final CREATE admission:

```text
phase=CREATE
reason=RANKING_CANDIDATE
```

**Allowed later in run** (not expected this run):

```text
phase=RUN
reason=FAILOVER_RANKING
```

### 4. Scope guard

No changes under test to:

```text
GROUP election
AnchorRanking algorithm
Ready / Health / Recovery
```

---

## Pass / Fail

### PASS

```text
initiator=M01
initialAnchor=M01   (ANCHOR_DECISION CREATE admitted)
currentAnchor=M01   (snapshot.anchorId)
```

Desk adjudication helper: `ConferenceAnchorDecisionObservability.evaluateDesk4pBaselineRunCard(logLines, snapshotAnchorId=M01)`

### FAIL — stop immediately

| Condition |
|-----------|
| `anchor != M01` on CREATE admission |
| missing `INITIAL_ANCHOR_POLICY` on CREATE |
| CREATE admission logged as `RANKING_CANDIDATE` |
| `snapshot.anchorId != M01` |

---

## Post-run state transition

Only when **both**:

```text
IA-3 desk gate PASS
4p baseline run PASS
```

may request:

```text
P0.1g Field Authorization
```

P0.1g then holds fixed:

```text
host=M01 · anchor=M01
```

and tests **media execution isolation only** — not Initial Anchor policy.

---

## Log capture

Suggested directory:

```text
talkback/logs/conference-initial-anchor-desk-4p-<YYYYMMDD-HHMMSS>/
```

Minimum artifacts: M01 coordinator log slice covering CREATE → first `CONFERENCE_TOPOLOGY_PUBLISHED`.
