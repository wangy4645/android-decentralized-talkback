# ADR-0043 — O1 Boundary

**Status:** **APPROVED** · **thin closing doc** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Wording patch:** dispatch permission **within authorization rules**  
**Ownership:** [adr0043-o-selection-decision.md](./adr0043-o-selection-decision.md) (**ACCEPTED** · **O1**)  
**Close:** [adr0043-architecture-close.md](./adr0043-architecture-close.md) (**APPROVED**)

---

## Status board

```text
O1 Boundary:              APPROVED
Architecture Close:       APPROVED
Implementation / Field:   FROZEN
```

---

## Separation (frozen)

```text
Authority truth
      ↓
P1 evidence
      ↓
Issuer O1 authorization
      ↓
GROUP_RESYNC dispatch decision
```

```text
truth ≠ evidence ≠ authorization
O1 consumes proof · O1 does not create proof
O1 owns authorization decision · not membership decision
```

---

## Issuer MAY

```text
consume PRESENT P1 evidence
validate scope ∧ decision-epoch (F-MIN-001) at authorization evaluation time
validate correlation (wrong-ask / replay attachment)
decide dispatch permission within authorization rules given valid PRESENT
frame recovery intent (which conference / edge) using local session as intent only
withhold GROUP_RESYNC when evidence is ABSENT / UNKNOWN or AUTH denies
```

```text
PRESENT + O1 authorization constraints → possible grant
≠ PRESENT → issuer free grant
```

---

## Issuer MUST NOT

```text
create / mutate / invent membership truth
promote digest / topology / reachability → PRESENT
promote local TalkbackSession → authority membership proof
convert UNKNOWN → ABSENT
convert DENY → ABSENT
treat PRESENT as automatic GRANT (P1-AUTH-001)
transfer PRESENT across decision epochs
become producer of the membership proof it evaluates
soften authority NO_MEMBERSHIP_CONTEXT via local workaround
```

Immutable watch: no “remembered PRESENT” local creation → MIXED recurrence.

---

## Authority remains

```text
accepted membership context truth
P1 evidence production (existence answer for scope)
explicit ABSENT assertion
snapshot capability when context exists
handler NO_MEMBERSHIP_CONTEXT terminal (unchanged · no Option C bypass)
```

---

## One-line statement

> O1: issuer validates authority PRESENT within authorization rules and may grant dispatch; never creates proof, never invents ABSENT, never auto-promotes PRESENT.
