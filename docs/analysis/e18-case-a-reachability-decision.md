# E.18 Case-A Reachability Decision

**Status:** `DECISION_ADOPTED` (2026-08-04) — Option B ratified; see [e18-closure-vote-record.md](./e18-closure-vote-record.md)  
**Does not:** amend ADR | modify code | authorize field | claim `Case-A failed` | claim `UNWIRED unreachable`  
**Inputs:** [e18-case-a-recovery-reachability-analysis.md](./e18-case-a-recovery-reachability-analysis.md) | [e18-attempt4c-a-postmortem.md](./e18-attempt4c-a-postmortem.md) | [e18-closure-review.md](./e18-closure-review.md) | ADR-0022 §E.18 / §E.21.3  
**Field posture:** `FROZEN` — no Attempt-4c-A replay; no artificial UNWIRED; no resolver/cache changes.

---

## Decision question

> Under current runtime authority lifecycle, is **E.21.3 Case A** still a reasonable **production recovery field obligation** for E.18 closure?

This is a **protocol / closure contract** question — not a field sampling problem.

---

## Exception record (closure wording — frozen)

**Do not write:** `Case-A = exception` (reads as "gave up because we could not measure").

**Write:**

```text
Case-A production recovery field cell = REACHABILITY_EXCEPTION

Reason:
  the exercised recovery stimulus causes authority lifecycle transition
  before UNWIRED observation can exist.
```

The exception is **not** abnormal system behavior. It is a **verification-matrix vs lifecycle-model mismatch**.

| Case | REACHABILITY_EXCEPTION nature |
|------|-------------------------------|
| Case-B | comparison projection identity mismatch (CONFERENCE vs GROUP digest) |
| Case-A | authority ownership transitions during recovery before Tprobe |

Do **not** merge into a single "unverified" bucket.

---

## Step 1 — Frozen facts (evidence-backed)

### Fact 1 — Case-A's implicit model vs runtime FSM

**Case-A field contract aimed to prove:**

```text
authority absent
        ↓
UNWIRED
        ↓
completion hold
```

**Observed production recovery FSM (125125 and resolver code):**

```text
authority lost (host ICE)
        ↓
AUTHORITY_LOST / bootstrap reevaluate
        ↓
successor authority elected (LOCAL_IS_MEMBERSHIP_AUTHORITY)
        ↓
CHECKED(converged=*)
```

`absence` is not a **stable** state during the Attempt-4c-A recovery stimulus (M03 host flap). The system transitions toward replacement authority.

This is **not** a resolver bug. It is consistent with:

```text
LOCAL_IS_MEMBERSHIP_AUTHORITY  >  CHECKED  >  UNWIRED
```

(`MembershipAuthorityResolver` short-circuit when `isLocalMembershipAuthority`.)

---

### Fact 2 — Observer is not a static role

**Insufficient (v1 contract):**

```text
observerModule != resolvedMembershipAuthority   @ T0 preflight
```

**Required for Case-A observation (v2):**

```text
observerModule != resolvedMembershipAuthority   @ Tprobe
```

Equivalently, for the observer path:

```text
membershipAuthorityOwnershipStableDuringProbe == true
```

**125125 timeline:**

```text
T0:  M03 authority, M02 observer     (preflight PASS)
T1:  M03 host ICE lost
T2:  M02 LOCAL_IS_MEMBERSHIP_AUTHORITY   (Tprobe FAIL — observer role gone)
```

Case-A did not "fail." The **observation identity** required by E.21.3 Case A was destroyed before probe evaluation.

---

### Fact 3 — Case-A vs Case-B/C boundary under promotion

Whenever recovery triggers authority promotion on the observation module:

```text
Case-A (UNWIRED branch)  →  unreachable on that episode
Case-B/C (CHECKED path)  →  expected
```

| Run | T0 | Tprobe | Outcome |
|-----|-----|--------|---------|
| `204240` | observer == authority | — | `PRECONDITION_NOT_SATISFIED` (setup error) |
| `125125` | observer != authority | observer == authority | `ATTEMPT4C_A_WRONG_CASE` (stimulus-induced transition) |

**Do not merge** these runs into one narrative.

---

### Fact 4 — ADR stimulus vs Attempt-4c-A exercise (scope boundary)

ADR §E.21.3 Case A **stimulus topology** is:

```text
old lineage → supersede → successor admission
        → digest unavailable; local not membership authority
```

Attempt-4c-A (follower) used:

```text
M03 host WiFi flap → ICE_DISCONNECTED → AUTHORITY_LOST on observer
```

These are **different exercise classes**. Attempt-4c-A was already split from E.21.2 successor admission. Field evidence therefore tests **recovery ingress + authority-loss promotion**, not the ADR-written successor-admission Case-A path.

This does not invalidate ADR semantics. It limits what Attempt-4c-A can prove about Case-A.

---

## Step 2 — Decision options

### Option A — Retain Case-A as mandatory field obligation

**Requires new precondition:**

```text
authority ownership frozen during probe window
```

Implementation paths (all costly or non-production):

- disable / defer bootstrap promotion during probe
- lock authority epoch before recovery probe
- test-only injection that prevents `LOCAL_IS_MEMBERSHIP_AUTHORITY`

| | |
|--|--|
| **Pros** | Preserves literal "field-verify UNWIRED" matrix cell |
| **Cons** | Changes or fakes runtime to observe a state production actively exits; conflates test harness with E.18 safety evidence |

**Architect view:** Rejected for E.18 closure. Optimizes for artifact, not product semantics.

---

### Option B — Case-A as lifecycle / reachability exception (adopted)

**Define:**

```text
E.21.3 Case A UNWIRED branch:
  code-path obligation     →  retained (resolver + facts exist)
  production recovery field obligation  →  excepted when authority transition occurs on observer
```

When:

```text
authority lost → successor promoted → CHECKED
```

classify as **expected recovery path**, not E.18 failure or "Case-A failed."

| | |
|--|--|
| **Pros** | Matches runtime; no product change; aligns with 125125; separates safety from coverage |
| **Cons** | Case-A **field matrix cell** not field-verified via Attempt-4c-A path |

**Closure posture:** `CLOSED_WITH_EXCEPTION` for Case-A field coverage — not full matrix closure.

---

### Option C — Artificial UNWIRED exercise

Force UNWIRED via cleared state, bootstrap block, or synthetic missing authority.

| | |
|--|--|
| **Pros** | Can produce `CASE_A_PASS` artifact |
| **Cons** | Not production recovery path; would misrepresent E.18 field evidence |

**Architect view:** Rejected for closure. Useful for unit tests only.

---

## Step 3 — Architect recommendation (adopted)

### Primary decision: **Option B + reachability note**

Adopt **Option B** for E.18 closure review, with an explicit reachability appendix.

### Rationale (senior architect)

**1. E.18 safety goal is already met without Case-A field.**

E.18 exists to prevent:

```text
wired authority mismatch  →  silent RECOVERED
infrastructure gap        →  silent default-open (CHECKED as if converged)
```

Evidence:

- **Case-C** (`FIELD_VERIFIED`): mismatch → `CHECKED(false)`, not silent recovery
- **Authority wiring** (PR-D + traces): probe disposition is observable
- **125125**: even under authority promotion, completion did not silently claim `RECOVERED` with `UNWIRED` falsely collapsed — episode stayed on `CHECKED` + `CONTROL_HANDSHAKE_PENDING`

The **dangerous failure mode** Case-A guards against in ADR text ("infrastructure missing is not epoch mismatch" / no silent default-open) is structurally addressed by:

- named `UNWIRED` sentinel (E.18.1 landed)
- Case-C proving mismatch is not collapsed to `UNWIRED`
- resolver priority ensuring local authority does not fake unwired absence

**2. Case-A field via Attempt-4c-A was testing the wrong stable state.**

UNWIRED is an **observer-side** classification when:

```text
!localMembershipAuthority && authorityDigest == null
```

Production recovery on a two-party mesh **actively removes** that configuration when the authority host drops. Continuing to hunt UNWIRED via M03 flap is verifying a state the architecture **by design** exits — not discovering a defect.

**3. Do not change runtime to freeze authority for closure (reject Option A).**

Option A would make E.18 closure depend on behavior the product does not promise. That weakens the evidentiary chain R4 and operators must trust.

**4. Do not fabricate UNWIRED (reject Option C).**

**5. ADR amendment is not required for Option B.**

ADR §E.21.3 Case A remains valid as a **code-path / classification contract**. What changes is the **closure matrix**:

```text
Case-A field verification (Attempt-4c-A recovery flap path)  →  REACHABILITY_EXCEPTION
Case-A resolver branch (UNWIRED when !local && !digest)       →  retained, unit-tested
```

If future work needs field confirmation of UNWIRED, it must use a stimulus class consistent with ADR §E.21.3 (successor-admission episode, observer stable at Tprobe) — a **new** exercise, not Attempt-4c-A replay.

---

## E.18 closure ruling (adopted)

```text
E.18 safety objective SATISFIED:

  missing authority not disguised as recover
      +
  wired mismatch not silent-open
      +
  authority transition is explicit lifecycle behavior

E.18.1 Observable Gap              LANDED
E.18.2 Authority Wiring            VERIFIED
Case-C Membership Mismatch         FIELD_VERIFIED
Case-B Match                       REACHABILITY_EXCEPTION
                                     reason: digest projection identity mismatch
Case-A production recovery field   REACHABILITY_EXCEPTION
                                     reason: authority lifecycle transition
Overall                            CLOSED_WITH_EXCEPTION

Case-A semantic:
  NOT "failed"
  NOT "unreachable forever"
  NOT "gave up because UNWIRED not measured"
  IS verification matrix incompatible with exercised recovery lifecycle
```

**R4-impl:** remains `WAITING`. `E.18 closed ≠ R4 implementation approved`.

---

## Contract upgrades (documentation only)

For any future Case-A field attempt (if ever re-opened):

| Invariant | Scope |
|-----------|-------|
| `observer != resolvedMembershipAuthority` | **T0 and Tprobe** |
| `membershipAuthorityOwnershipStableDuringProbe` | explicit gate |
| Stimulus class | must match ADR §E.21.3 topology — not conflated with ingress flap |

### Observer invariants (frozen)

```text
observer != authority @ T0     → necessary, not sufficient
observer != authority @ Tprobe → required for Case-A UNWIRED observation
```

```text
Tprobe authority ownership transition
is itself an observed recovery behavior,
not a Case-A violation.
```

**125125 positive lifecycle evidence:**

```text
M03 lost → authority unavailable → resolver reacts → M02 promotes
```

### R4 architecture constraint (closure record)

```text
R4 must not assume observer identity stability
across recovery authority transitions.
```

### Frozen (do not pursue)

- No Attempt-4c-A replay
- No artificial UNWIRED / authority freeze hacks
- No resolver or cache changes for closure
- No ADR amendment to satisfy field artifact

---

## Status board (adopted)

```text
Case-A production recovery field   REACHABILITY_EXCEPTION (Option B adopted)

Field:
  FROZEN (no replay)

E.18 overall:
  CLOSED_WITH_EXCEPTION
```

---

## Closure chain (adopted)

```text
e18-case-a-reachability-decision.md
        |
        v
e18-closure-vote-record.md
        |
        v
E.18 = CLOSED_WITH_EXCEPTION
        |
        v
R4 design review (R4-impl still WAITING)
```

---

## Next steps

1. Maintain closure record in [e18-closure-review.md](./e18-closure-review.md) and [e18-closure-vote-record.md](./e18-closure-vote-record.md).
2. **R4 design review** — separately gated; do not conflate with E.18 closure.
3. Probe projection alignment (Case-B follow-up) — separate implementation slice.

---

## References

| Artifact | Role |
|----------|------|
| `204240` | Host topology precondition discovery |
| `125125` | Follower preflight PASS; Tprobe authority promotion |
| [e18-case-a-recovery-reachability-analysis.md](./e18-case-a-recovery-reachability-analysis.md) | Technical archaeology |
| ADR §E.21.3 | Case A code-path contract (unchanged) |
| ADR §E.21.3 stimulus | Successor admission — not Attempt-4c flap |

---

*Decision adopted for E.18 closure. Does not alter ADR acceptance criteria or implementation behavior.*
