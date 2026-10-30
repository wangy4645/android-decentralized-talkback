# TEST-INFRA DEBT — F8 SelectedOperationalNetworkRegistry singleton leakage

**Status:** REGISTERED / NON-BLOCKING  
**Date:** 2026-09-15  
**Does not block:** P0′ LOCAL-SOURCE-REGISTRY-PROJECTION · R2-A field confirmation · release APK build

## Classification

```text
TEST-INFRA EXISTING DEFECT

SelectedOperationalNetworkRegistry
→ global singleton / state leakage across JVM unit tests
→ duplicate registration F8
→ fails during @Before / TestTalkbackNode construction
→ before Profile01 path executes
```

## Evidence (2026-09-15)

- Broad `com.talkback.app.*` run: 173 failures, common root `IllegalStateException: F8: SelectedOperationalNetworkRegistry already registered`.
- Isolated `TalkbackCoordinatorIntegrationTest`: 47/47 same F8 at `TestTalkbackNode.<init>` → `TalkbackRuntimeFactory.createBundle`.
- Temporary removal of new host-local SOURCE projection call: still 47/47 same F8 → **not caused by P0′ code change**.

## Escalation (only if)

1. Release APK build fails because of this, or
2. R2 / P0′ field confirmation requires these integration tests and existing directed unit tests no longer cover the contract.

Neither condition holds for the current RC path. Do not divert RC work to green the full app suite until escalated.
