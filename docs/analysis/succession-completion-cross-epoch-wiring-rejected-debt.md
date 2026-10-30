# SEMANTIC/OBSERVABILITY DEBT — cross-epoch succession completion reports WIRING_REJECTED

**Status:** REGISTERED / NON-BLOCKING
**Date:** 2026-09-15
**Does not block:** P0′-SOURCE-SUCCESSION-COMPLETION · R2-A field confirmation · release APK build

## Classification

```text
OBSERVABILITY/SEMANTIC MISMATCH (not a lifecycle defect)

successor incarnation at a NEW mediaKeyEpoch
→ rotateMediaKeyEpoch() already revoked + cleared the old incarnation
→ ConferenceSessionMediaWiring.replaceMember(old@epochN, new@epochN+1)
   fails its same-epoch precondition and its catalog-entry precondition
→ MEMBER_REPLACED recorded as WIRING_REJECTED

BUT
→ registry.consumeMemberReplace() already ran
→ replacement obligation IS completed
→ following member-ready installs the successor
```

## Why it is accepted for now

The product lifecycle closes: `DEFERRED_REPLACE_PENDING` → completion → `INSTALL` → current
incarnation in catalog → TX admitted. Only the recorded outcome name is wrong; it says "wiring
rejected" where the truth is "old incarnation was already retired by epoch rotation".

Adding a dedicated `ShadowOutcome` would be a succession lifecycle semantic change, which is out of
scope while P0′ is the release blocker.

## Guard against regression-by-misreading

`Profile01SourceSuccessionCompletionTest.hostReplay_completesSuccession_thenInstallsCurrentIncarnation`
asserts `WIRING_REJECTED` explicitly, so anyone who reads the field log and concludes "completion
failed" will break that test before breaking the lifecycle.

## Escalation (only if)

1. A future succession case needs to distinguish genuine wiring rejection from epoch-retired
   completion in the field, or
2. Same-epoch succession starts reporting `WIRING_REJECTED` — that would be a real defect, not this
   debt.
