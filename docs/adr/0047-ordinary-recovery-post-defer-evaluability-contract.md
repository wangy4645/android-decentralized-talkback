# ADR-0047: Ordinary Recovery Post-Defer Evaluability Contract

## Status

**ACCEPTED** (2026-08-09) · **Acceptance Q1 = A1** · **Impl-Auth F1** · **PR #148 MERGED** (`434313c`) · **V-field' post-merge observation AUTHORIZED**

**Parents / observation:**

- [vfield-case-b-20260809-112330-media-online-obligation-pending.md](../analysis/vfield-case-b-20260809-112330-media-online-obligation-pending.md) — Case B hollow residency evidence
- [adr0047-implementation-proposal-entry.md](../analysis/adr0047-implementation-proposal-entry.md) — Impl-Auth / Design Decision entry
- [adr0047-vfield-run-card-001.md](../analysis/adr0047-vfield-run-card-001.md) — V-field' post-merge observation (AUTHORIZED)

**Orthogonal (do not amend / merge):**

- [ADR-0046](./0046-successor-admission-terminal-convergence-contract.md) — successor terminal convergence (**separate lifecycle**)
- [ADR-0044](./0044-user-visible-connectivity-semantics-media-residency.md) — SYNCING projection
- [ADR-0038](./0038-recovery-completion-admission-contract.md) — completion success predicate
- [ADR-0045](./0045-post-obligation-failed-media-residency-clear-admission.md) — post-obligation failed-media residency clear

```text
ADR-0046:     ACCEPTED ✅  (successor track — independent)
Case B:       CONFIRMED ✅  (ordinary edge hollow residency — observation)
ADR-0047:     ACCEPTED ✅  (normative boundary A1'–O1')
Impl-Auth:    I1 ✅  Planning / Design AUTHORIZED
DP-ACCEPT:    PASS ✅
Runtime:      PR #148 MERGED ✅ · V-field' observation ⏳
```

---

## Context (observation facts only)

Repeatable ordinary-recovery path (V-field Case B; M01→M03):

```text
ICE_DISCONNECTED
        |
        v
RECOVERY_ATTEMPT_OPENED (UPSERT_EDGE)
        |
        v
NEGOTIATION_SETTLING / defer
        |
        v
negotiation budget SUPERSEDE
        |
        v
no provable post-defer evaluator
        |
        v
media CONNECTED + obligationOpen=true
        |
        v
finalPresence=SYNCING
        |
        v
closes only via MEMBERSHIP_LEFT (external)
```

```text
media plane:              CONNECTED
recovery obligation:      OPEN
evaluator:                not provably persistent post-defer
projection (ADR-0044):    SYNCING (faithful)

≠ media connectivity failure
≠ ADR-0046 successor admission failure
≠ UVCP / UI defect
```

**Same UI symptom as successor MISS_SETTLING; different lifecycle entry.**

| Symptom | Route |
|---------|--------|
| `ADMIT_SUCCESSOR` → long Sync | ADR-0046 |
| Ordinary `RECOVERY_ATTEMPT_OPENED` → defer → hollow residency | **This ADR** |

---

## Decision (normative)

> **Ordinary recovery obligation must carry an auditable post-defer evaluability attribution contract so post-defer lifecycle cannot form hollow residency.**

### Semantic constraints (A1'–O1')

| Id | Normative meaning |
|----|-------------------|
| **A1'** | `contract` = **evaluability attribution class** — auditable post-defer evaluability owner class attribution. Not SUCCESS/FAILED/CLOSED guarantee; not mechanism. |
| **B4'** | **Split bind/manifest:** evaluability class **intent** at obligation open (`RECOVERY_ATTEMPT_OPENED`); **auditable post-defer attribution** no later than **defer-exit** (class concept). |
| **S4'** | **Anti-hollow floor** — forbid persisting in `obligationOpen` + post-defer + no auditable attribution. |
| **E2'** | External close (e.g. `MEMBERSHIP_LEFT`) **may** close episode; **does not** substitute post-defer evaluability attribution or retroactively prove non-hollow residency. |
| **P1'** | `provable` = **auditable attribution existence** at defer-exit manifest deadline — not outcome proof, not continuous monitoring, not diagnostic sufficiency. |
| **O1'** | Scope = **ordinary recovery obligation** episodes (non-successor). Orthogonal to ADR-0046 / 0044 / 0038 / 0045. No membership redesign. Case B is evidence, not path gate. |

```text
ordinary recovery obligation
        |
        v
must carry post-defer evaluability attribution contract
        |
        +-- A1' class object
        +-- B4' intent @ open + manifest <= defer-exit
        +-- S4' anti-hollow floor
        +-- E2' external-close orthogonality
        +-- P1' provable semantics
        +-- O1' scope fence
```

### Sibling routing (frozen)

```text
ADR-0046:  ADMIT_SUCCESSOR → terminal convergence contract
ADR-0047:  ordinary recovery → post-defer evaluability attribution contract

Same SYNCING symptom ≠ same lifecycle
No merge · no mutual amend · no shared implementation authorization
```

**Acceptance does not grant Implementation authorization.**

---

## Scope boundary (locked)

| Item | This ADR |
|------|----------|
| Subject | Ordinary recovery edge |
| Entry | `RECOVERY_ATTEMPT_OPENED` and non-successor paths (e.g. `UPSERT_EDGE`, `ICE_DISCONNECTED`) |
| Core gap | Post-defer obligation may lack provable evaluator → hollow residency |
| Evidence | V-field Case B-20260809-112330 |
| vs ADR-0046 | **Orthogonal** — do not cite successor terminal convergence contract |
| vs ADR-0044 | **Orthogonal** — do not change SYNCING projection |
| vs ADR-0038 | **Orthogonal** — do not change completion predicate |

---

## Out of scope (remains frozen)

```text
✗ timeout / watchdog / retry numbers
✗ RecoveryPolicy
✗ RECOVERY_PENDING / FSM rewrite
✗ UVCP / EndpointStatus / UI Sync change
✗ ICE terminal definition or auto-terminalization
✗ whether MEMBERSHIP_LEFT should change
✗ how convergence is produced (mechanism)
✗ runtime patch or PR scope (until Impl-Auth)
✗ merging with ADR-0046 successor contract
✗ amending ADR-0038 / 0044 / 0045
```

**Acceptance does not grant Implementation authorization.**

---

## Grill — Q1 SEALED: What does **contract** mean?

**Adjudication:** **A1' — Evaluability attribution class**

```text
contract =
  ordinary recovery episode must carry auditable
  post-defer evaluability owner class attribution

contract ≠
  SUCCESS / FAILED / CLOSED guarantee
  watchdog / timer / retry / budget / scheduler
  successor terminal convergence contract (0046)
  UI / projection change
```

| Alignment | Ruling |
|-----------|--------|
| Object vs mechanism | **Yes** — obligation attribution / evaluability semantic constraint only |
| Post-defer vs whole-life | **A1' (post-defer)** — reject A3'; Case B gap is after SUPERSEDE |
| Provable | **P1'-family** — auditable existence / attribution; not terminal proof or liveness |
| External close | **Preliminary** — cannot treat external-only close as the sole post-defer evaluability story; terminal weights deferred to later grill |

### Q1 adjudication record

| Item | Result |
|------|--------|
| Q1 choice | **A1'** |
| Rationale | Case B shows post-defer hollow residency; contract names evaluability owner class, not convergence mechanism |
| Rejected options | **A2'** (outcome predicate, not primary object); **A3'** (over-broad lifetime); **A4'** (Decision NO); **0046 re-export** |

```text
Q1 sealed (A1') → Grill Q2 OPEN
```

---

## Grill — Q2 SEALED: Binding time

**Adjudication:** **B4' — Split bind / manifest**

```text
RECOVERY_ATTEMPT_OPENED
        |
        v
evaluability class intent exists (early)
        |
        v
NEGOTIATION_SETTLING / defer
        |
        v
defer-exit (class concept)
        |
        v
post-defer attribution MUST be auditable (<= defer-exit)
```

| Split leg | Ruling |
|-----------|--------|
| **Intent** | Early — at obligation open (`RECOVERY_ATTEMPT_OPENED` / **B1'** leg) |
| **Manifest** | Post-defer auditable attribution **no later than** defer-exit (**B3'** leg) |

```text
intent attribution        ≠        post-defer auditable attribution
```

| Alignment | Ruling |
|-----------|--------|
| Earliest vs latest | **Earliest intent; latest manifest = defer-exit** |
| B3' alone | **Rejected** — defer period lacks accountable identity if bind only at exit |
| B1' alone | **Rejected** — open-time bind without manifest deadline permits Case B hollow residency |
| defer-exit class | **Accepted** — category includes settling end, capability change, negotiation close, budget SUPERSEDE, peer wakeup-class exits; **no** priority or writer choice here |
| Non-defer paths (**B5'**) | **Rejected** — A1' applies to ordinary recovery episodes generally; defer is observation scene, not scope gate |

### Q2 adjudication record

| Item | Result |
|------|--------|
| Q2 choice | **B4'** |
| Intent binding | **B1'** — `RECOVERY_ATTEMPT_OPENED` |
| Manifest deadline | **B3'** — defer-exit (class concept) |
| Rationale | Split avoids late-only bind (defer identity gap) and open-only bind (post-defer hollow residency) |
| Rejected options | **B3'** alone; **B1'** alone; **B5'** |

```text
Q2 sealed (B4') → Grill Q3 OPEN
```

---

## Grill — Q3 SEALED: Minimum obligation strength

**Adjudication:** **S4' — Anti-hollow explicit floor**

```text
contract forbids persisting in:

obligationOpen=true
        +
post-defer phase
        +
no auditable evaluability attribution
```

| Alignment | Ruling |
|-----------|--------|
| S1' identity-only | **Rejected** — naming alone does not forbid hollow residency |
| S2' / S3' | **Not chosen as floor** — S4' names forbidden shape directly; stronger accountability legs deferred |
| S5' external-only | **Rejected** — contradicts Q1 preliminary |
| DIAGNOSTIC_ONLY | **Does not satisfy** S4' — diagnostic ≠ auditable post-defer evaluability attribution |

### Q3 adjudication record

| Item | Result |
|------|--------|
| Q3 choice | **S4'** |
| Rationale | Case B hollow residency is the observed defect shape; floor must forbid it explicitly |
| Rejected options | **S1'**, **S5'**; S2'/S3' not selected as primary floor |

```text
Q3 sealed (S4') → Grill Q4 OPEN
```

---

## Grill — Q4 SEALED: External close semantics

**Role:** Under **A1' + B4' + S4'**, what is the status of **external close events** (e.g. `MEMBERSHIP_LEFT`) relative to evaluability contract satisfaction?

**Adjudication:** **E2' — External close allowed but not attribution substitute**

```text
close authority              ≠          evaluability attribution

MEMBERSHIP_LEFT  →  may close obligation
MEMBERSHIP_LEFT  ↛  retroactive proof of non-hollow post-defer residency
```

| Id | Verdict |
|----|---------|
| **E1'** Terminally sufficient | **Rejected** — retroactively legitimizes hollow residency until external close; weakens S4' |
| **E2'** Allowed, not substitute | **Accepted** — external close may end episode; S4' must still hold **before** close in post-defer window |
| **E3'** External close excluded | **Rejected** — over-broad; impinges membership close authority; out of Candidate |
| **E4'** Defer | **Rejected** — Q4 must adjudicate for Decision YES coherence |

### Case B read under E2'

```text
post-defer hollow residency occurred   →  S4' violation (observation fact)
MEMBERSHIP_LEFT closed obligation      →  legitimate close authority
close event                            ↛  proof contract was satisfied
```

### Q4 adjudication record

| Item | Result |
|------|--------|
| Q4 choice | **E2'** |
| Rationale | Preserves distinction between episode close authority and post-defer evaluability completeness |
| Rejected options | **E1'**, **E3'**, **E4'** |

```text
Q4 sealed (E2') → Grill Q5 OPEN
```

---

## Grill — Q5 SEALED: Provable / auditable (P-family)

**Adjudication:** **P1' — Auditable attribution existence**

```text
defer-exit
      |
      v
auditable attribution exists
      |
      v
post-defer evaluability identity established

provable ≠
  SUCCESS proof
  closure proof
  continuous monitoring proof
  diagnostic evidence
```

| Alignment | Ruling |
|-----------|--------|
| P1' vs P2' | **P1'** — manifest-deadline snapshot; reject P2' (continuous proof ≈ liveness monitoring; out of scope) |
| vs S4' | **Complementary** — P1' defines provable; S4' forbids hollow residency shape |
| vs E2' | **Aligned** — hollow window + later `MEMBERSHIP_LEFT` = S4' violation; close still合法 under E2' |
| vs ADR-0046 P1' | **Same family** — auditable bind/manifest; 0046 = terminal convergence obligation, 0047 = evaluability attribution |

### Q5 adjudication record

| Item | Result |
|------|--------|
| Q5 choice | **P1'** |
| Rationale | Point-in-time auditable attribution at defer-exit; avoids lifecycle guardian semantics |
| Rejected options | **P2'**, **P3'**, **P4'** |

```text
Q5 sealed (P1') → Grill Q6 OPEN
```

---

## Grill — Q6 SEALED: Orthogonality fence (O-family)

**Adjudication:** **O1' — Composite orthogonality**

```text
Scope:
  ordinary recovery obligation
  post-defer evaluability completeness

NOT limited to:
  ICE_DISCONNECTED / UPSERT_EDGE / Case B trigger shape
```

| Fence | Ruling |
|-------|--------|
| vs **ADR-0046** | **Sibling** — successor terminal convergence; no amend / merge / extension |
| vs **ADR-0044** | **Frozen** — no SYNCING / recovering / UI projection change |
| vs **ADR-0038** | **Frozen** — no completion predicate / admission change |
| vs **ADR-0045** | **Frozen** — no failed-media / clear gate change |
| vs **Membership** | **E2' preserved** — close authority yes; attribution substitute no; no membership redesign |
| Side observations | **Not absorbed** (e.g. M03→M01 peer non-convergence) |

```text
ADR-0046:  successor admission / terminal convergence contract
ADR-0047:  ordinary recovery / post-defer evaluability attribution
           (shared SYNCING symptom only)
```

### Q6 adjudication record

| Item | Result |
|------|--------|
| Q6 choice | **O1'** |
| Rationale | Case B is evidence sample, not scope gate; ordinary recovery class coverage |
| Rejected options | **O2'** (too narrow / bug-pattern scope); **O3'** (merges 0046); **O4'** |

```text
Q6 sealed (O1') → Grill Q7 OPEN
```

---

## Grill — Q7 SEALED: Decision seal (D-family)

**Adjudication:** **D1' — Decision YES — Candidate seal**

```text
Decision YES:
  ordinary recovery obligation must carry auditable
  post-defer evaluability attribution contract
  to avoid hollow residency

Status:
  Decision YES ✅
  ACCEPTED ✅
  Implementation ❌
```

| Id | Verdict |
|----|---------|
| **D1'** Candidate Decision YES | **Accepted** — Q1–Q6 compose normative boundary; remain CANDIDATE until Acceptance |
| **D2'** Skip to ACCEPTED | **Rejected** — skips Acceptance gate |
| **D3'** NO / OPEN | **Rejected** — contradicts sealed Q1–Q6 |

### Q7 adjudication record

| Item | Result |
|------|--------|
| Q7 choice | **D1'** |
| Rationale | Q1–Q6 complete semantic chain; Decision is lifecycle constraint on ordinary recovery, not Case B patch |
| Rejected options | **D2'**, **D3'** |

```text
Q7 sealed (D1') → Grill COMPLETE → Acceptance Q1 next (separate gate)
```

---

## Grill roadmap

| Q | Topic | Status |
|---|--------|--------|
| **Q1** | What `contract` denotes | **SEALED — A1'** |
| **Q2** | Binding time | **SEALED — B4'** (intent=B1', manifest=B3') |
| **Q3** | Minimum obligation strength | **SEALED — S4'** |
| **Q4** | External-close semantics | **SEALED — E2'** |
| **Q5** | Provable / auditable (P-family) | **SEALED — P1'** |
| **Q6** | Orthogonality fence (O-family) | **SEALED — O1'** |
| **Q7** | Decision YES/NO seal | **SEALED — D1'** |

---

## Governance record

| Item | Result |
|------|--------|
| Grill Q1–Q7 | **COMPLETE** |
| Decision | **YES** (D1') |
| Acceptance Q1 | **A1 — Accept now** |
| Document lifecycle | **ACCEPTED** |
| Normative content | Decision YES semantic boundary (A1'–O1') |
| Implementation | **Planning / Design AUTHORIZED** (Impl-Auth I1); **Runtime NOT AUTHORIZED** |
| Runtime | **NONE** |
| Case B field proof | **Not** an Acceptance precondition (P1') |

```text
ADR-0047 Candidate
        |
        | Acceptance Q1 = A1
        v
ADR-0047 ACCEPTED
        |
        +--> Normative boundary established (A1'–O1')
        |
        +--> Impl-Auth Q1 = I1 ✅
        |         |
        |         +--> Planning / Design Proposal AUTHORIZED
        |         |
        |         +--> Runtime ❌ until Design Decision
        |
        +--> Runtime changes: NONE
```

**Next governance gate:** Design Decision grill (proposal entry open).

---

## Implementation Authorization record

### Impl-Auth Q1 (2026-08-09)

| Item | Result |
|------|--------|
| Impl-Auth Q1 | **I1 — Allow** (Planning / Design track) |
| Trigger | Explicit owner authorization: start implementation workstream |
| Owner | Explicit (session grant) |
| Proposal entry | [adr0047-implementation-proposal-entry.md](../analysis/adr0047-implementation-proposal-entry.md) |
| Validation entry | Post-design ordinary-recovery compliance (see proposal) |
| Planning / Design Proposal | **AUTHORIZED** |
| Design Decision Q1 | **C1 = N1 + N2** |
| Design Decision Q2 | **B1' + V-desk' + V-field'** |
| Design Decision Q3 | **C-Kmin + K5'** |
| Design Decision Q4 | **C1'** |
| C1' package | [adr0047-design-proposal-c1.md](../analysis/adr0047-design-proposal-c1.md) **DP-ACCEPT** |
| Runtime Authorization | **Q1=RA1 · Q2=R2' · Q3=G1'+P1'–P3' · Q4=F1** |
| Scope | R2' — ordinary open + defer-exit manifest |
| Merge gate | G1' — R2' + D1–D7 + P1'–P3'; V-field' post-merge |
| Direct code / PR | **MERGED #148** (`434313c`) |
| V-field' | [adr0047-vfield-run-card-001.md](../analysis/adr0047-vfield-run-card-001.md) — **AUTHORIZED** |

```text
ADR-0047 ACCEPTED
        |
        +-- normative boundary ✅
        |
        +-- Impl-Auth I1 ✅
        |         |
        |         +-- Planning / Design AUTHORIZED
        |         |
        |         +-- Runtime ❌ until Design Decision
        |
        +-- mechanism not yet chosen
```

---

## Acceptance — Q1 SEALED

**Adjudication:** **A1 — Accept now**

| Item | Result |
|------|--------|
| Acceptance Q1 | **A1** |
| Rationale | Q1–Q7 compose closed normative chain; sufficient as lifecycle constraint without mechanism |
| Rejected options | **A2**, **A3** |

```text
Acceptance Q1 = A1 → ADR-0047 ACCEPTED → Impl-Auth NOT OPEN
```

---

## Consequences

**Establishes:**

- Normative lifecycle boundary: ordinary recovery post-defer evaluability attribution contract (A1'–O1').
- Sibling separation from ADR-0046 despite shared SYNCING symptom.

**Does not establish / authorize (yet):**

- Runtime code, PR, watchdog/timeout/retry numbers.
- UVCP / SYNCING projection change.
- Amendment of ADR-0046 / 0044 / 0038 / 0045.

**Impl-Auth I1 authorizes Planning / Design only** — not runtime.

---

## Grill Appendix — Decision YES Semantic Closure

| Q | Id | Adjudication |
|---|-----|--------------|
| Q1 | **A1'** | Evaluability attribution class (post-defer). |
| Q2 | **B4'** | Intent @ open + manifest ≤ defer-exit. |
| Q3 | **S4'** | Anti-hollow evaluability floor. |
| Q4 | **E2'** | External close allowed; not attribution substitute. |
| Q5 | **P1'** | Provable = auditable attribution at defer-exit. |
| Q6 | **O1'** | Ordinary recovery scope; orthogonal fences. |
| Q7 | **D1'** | Decision YES sealed → ACCEPTED via A1. |

---

## References

- Case B archive: [vfield-case-b-20260809-112330-media-online-obligation-pending.md](../analysis/vfield-case-b-20260809-112330-media-online-obligation-pending.md)
- LogDir: `talkback/logs/adr0046-vfield-20260809-112330/` (disposition: NOT_ADR0046_VFIELD_SAMPLE)
- ADR-0046 V-field run card side note: [adr0046-vfield-post-merge-run-card.md](../analysis/adr0046-vfield-post-merge-run-card.md)
- [adr0047-implementation-proposal-entry.md](../analysis/adr0047-implementation-proposal-entry.md)
