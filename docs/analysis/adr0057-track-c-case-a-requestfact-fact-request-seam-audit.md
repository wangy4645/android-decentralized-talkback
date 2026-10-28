# ADR-0057 Track C Case A — RequestFact → FACT_REQUEST Production Seam Audit

## Status

**READ-ONLY AUDIT** (2026-08-31)  
Field evidence: `talkback/logs/adr0057-track-c-case-a-20260831-145315/`  
FTPH preconditions: **PASS** (trust not blocking)

---

## Field adjudication (split findings)

```text
Track C Case A — FIELD FAIL

Generation convergence: FAIL
Group bootstrap:        FAIL
Media readiness:        FAIL

F1 — CONFIRMED (code / orchestration)
  RequestFact effect is produced,
  but no FACT_REQUEST is emitted.

  Failure domain:
    acquisition orchestration / production wire bridge

F2 — CONFIRMED (test contamination)
  M04 remained visible/participating,
  causing membership/bootstrap context divergence.

  Failure domain:
    field precondition / discovery isolation
```

**Do NOT conflate F1 and F2.** F1 is the primary generation-convergence blocker; F2
contaminates bootstrap interpretation and must be eliminated before the next re-run.

---

## ADR posture (unchanged)

```text
Production Trust Integration (PW/PTI/PAP/OE): PASS — unchanged
GBC Core semantics:                           PASS — unchanged
Track C field:                                FAIL

Confirmed field gap:
  RequestFact → FACT_REQUEST production orchestration

ADR-0057 COMPLETE: NO
Do NOT reopen PV / PTI / PAP / OE for this finding.
```

---

## Seam chain (code)

```text
GroupBootstrapConvergence.onAcquisitionRequired
  → ConvergenceEffect.RequestFact
  → GroupBootstrapConvergenceWiring.dispatch → effectSink
  → TalkbackCoordinator.executeGroupBootstrapConvergenceEffects
  → FactDeliveryOrchestrator.scheduleAcquisition
  → TalkbackCoordinator.sendGenerationFactRequests
  → signalingChannel.send(FACT_REQUEST)
```

### 1. Who consumes RequestFact?

`TalkbackCoordinator.executeGroupBootstrapConvergenceEffects` — **wired**.

```kotlin
is ConvergenceEffect.RequestFact -> {
    val correlation = "gbc-acq-${effect.channelId}"
    val schedule = factDeliveryOrchestrator.scheduleAcquisition(effect.channelId, correlation)
    sendGenerationFactRequests(schedule)
}
```

Stale comment on the function says *"Production GENERATION_FACT transport remains NOT
AUTHORIZED"* — **incorrect wording**. Transport send path exists; field failure is not
"wire unauthorized".

### 2. Does consumption call FactDeliveryOrchestrator?

**Yes.** `scheduleAcquisition` → `beginAcquisitionExchange` + `observeAcquisitionIfUnresolved`
+ snapshot `locatedHolders(channelId)`.

### 3. Where do holders come from?

**HELLO locate only:**

```kotlin
payload.channelId?.takeIf { it.isNotBlank() }?.let { ch ->
    factDeliveryOrchestrator.onHelloLocate(ch, payload.moduleId)
}
```

`onHelloLocate` **does not** trigger FACT_REQUEST send. It only appends to
`locatedHolders[channelId]`.

Outbound HELLO sets `channelId = digestSession?.channelId` — typically only when an
accepted GROUP session exists for digest projection.

### 4. UNKNOWN / empty holder — silent swallow?

**Yes.** `sendGenerationFactRequests`:

```kotlin
for (holderId in schedule.selectedHolders) {
    if (holderId == localModuleId.value) continue
    val peer = resolvePeerForModule(holderId) ?: continue
    ...
}
```

If `selectedHolders` is empty → **loop never runs → no log → no FACT_REQUEST sent.**

This matches field evidence: `GBC_EFFECT RequestFact` present, `FACT_REQUEST sent = 0`.

### 5. waitingForPrimary / session gates?

`reconcileGroupBootstrap` calls `observeAcquisitionIfUnresolved` **before** the
`waitingForPrimary` early return. So M02/M03 **do** emit RequestFact even while stuck in
`waitingForPrimary=true` — but still with empty/stale holder snapshot at schedule time.

`waitingForPrimary` blocks **mesh bootstrap**, not RequestFact emission. It does **not**
explain FACT_REQUEST absence; empty `selectedHolders` does.

### 6. Is FACT_REQUEST codec/send on production signaling?

**Yes.** `SignalType.FACT_REQUEST` handler exists inbound; outbound uses
`buildSignedEnvelope` + `signalingChannel.send`. Wire is production-path capable.

---

## Field timing correlation (M01)

```text
14:54:39.234  GBC_EFFECT RequestFact          ← scheduleAcquisition (holders likely {})
14:54:39.319  GROUP_SESSION_CREATE M01,M04
14:54:40.734  first HELLO from M04            ← after RequestFact; locate may not bind CH-01
```

RequestFact fires at bootstrap reconcile **before** peer HELLO locate population for CH-01.

---

## Idempotency gap (no retry after locate)

`GroupBootstrapConvergenceWiring.observeAcquisitionIfUnresolved`:

```kotlin
if (snap.obligation == ObligationState.OPEN) {
    return emptyList()   // no second RequestFact
}
```

`GroupBootstrapConvergence.onAcquisitionRequired`:

```kotlin
if (obligations.isOpen()) {
    return listOf(ConvergenceEffect.RequestFact(channelId))  // CAN re-emit RequestFact
}
```

Wiring layer suppresses re-dispatch while obligation OPEN; GBC module would re-emit if called
directly. Combined with **no send-on-locate hook**, a single early RequestFact with empty
holders is a **terminal no-wire** for the observation window.

This violates the frozen intent:

```text
acquisition obligation != attempt
scheduler silence MUST NOT swallow acquisition intent
```

Interpretation: obligation OPEN without wire attempt is **orchestration defect**, not GBC
semantic failure.

---

## F2 — M04 contamination (field)

```text
Expected participants: M01, M02, M03
Observed:
  M01 GROUP_SESSION_CREATE currentMembers=M01,M04
  M02/M03 HELLO from M04 + LATE_PEER_ADMISSION_SKIPPED (HELLO_CHANNEL_ABSENT)
```

Next re-run precondition (mandatory):

```text
□ M04 force-stop (not merely "don't open app")
□ PRECONDITIONS prove observed roster M01/M02/M03 only on all three devices
```

---

## Classification

| Question | Answer |
|----------|--------|
| GBC RequestFact semantics wrong? | **No evidence** — effect fires as designed |
| Verifier / trust regression? | **No** — FTPH PASS; no candidate path reached |
| Production wire missing entirely? | **No** — send path exists |
| Orchestration bridge defect? | **Yes (F1)** — empty-holder schedule + no locate retry |
| Test hygiene defect? | **Yes (F2)** — M04 in bootstrap context |

---

## Implementation cut (NOT authorized here)

Narrowest fix candidates (for future ADR/cut decision only):

1. **Send-on-locate:** when `onHelloLocate` adds first holder for OPEN obligation +
   pending correlation → emit FACT_REQUEST (or re-schedule acquisition).
2. **Empty-holder guard:** if `selectedHolders.isEmpty()` at RequestFact → log +
   `observeNoReachableHolder` / explicit deferral — not silent no-op.
3. **Stale log comment:** remove "production crypto verifier NOT AUTHORIZED" from
   RequestFact log line (wording debt only).

**Out of scope:** GBC obligation predicate changes, verifier changes, completion predicate,
PV/PTI/PAP/OE reopen.

---

## References

- Run: `talkback/logs/adr0057-track-c-case-a-20260831-145315/ADJUDICATION.txt`
- FTPH: `talkback/logs/adr0057-preconditions-20260831-145126/`
- Coordinator: `TalkbackCoordinator.executeGroupBootstrapConvergenceEffects`,
  `sendGenerationFactRequests`, HELLO `onHelloLocate`
- Orchestrator: `FactDeliveryOrchestrator.scheduleAcquisition`
- Wiring idempotency: `GroupBootstrapConvergenceWiring.observeAcquisitionIfUnresolved`
