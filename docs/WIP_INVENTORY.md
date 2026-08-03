# WIP Inventory Freeze — P2 slicing (2026-08-03)

**Status:** FREEZE ONLY — not a commit, not a PR.
**Baseline:** `origin/main` @ `5e9fad3` (R3 VERIFIED).
**Purpose:** map local WIP to ADR ownership boundaries before PR-A/B/C/D.

---

## Critical finding — main is not compile-closed

Clean worktree of `5e9fad3` (`compileDebugKotlin`) fails. HEAD already references
symbols whose defining sources remain **untracked WIP**. Same class of failure P0
closed for `RecoveryIngressObservation`, now broader.

| Untracked symbol (WIP) | Referenced from HEAD (tracked) |
|---|---|
| `RecoveryHandlerOutcome` | `EdgeRecoveryModels`, `ConferenceEdgeRecoveryController`, `RecoveryDeliveryFact` |
| `DeferredIntentAuthority` | `ConferenceEdgeRecoveryController` |
| `Pr52cDebugInjection` | `ConferenceEdgeRecoveryController` |
| `RecoveryCompletionPolicy` | `ConferenceEdgeRecoveryController` |
| `CompletionObservationProjection` | `ConferenceEdgeRecoveryController` |
| `ControlReconciliationEvaluator` | `ConferenceEdgeRecoveryController` |
| `RecoveryControlReconciliationFact` | `ConferenceEdgeRecoveryController` |
| `PeerSignalingReachabilityProjection` / admission helpers | `ConferenceEdgeRecoveryController`, `RecoveryOfferDeliveryPolicy` |

**Implication for P2:** PR order must restore compile closure. Pure file-ownership
slicing that leaves Controller referencing untracked types will keep main red.
Land defining sources + tests first (PR-A/B/C), wire Coordinator last (PR-D).

Also: `RecoveryReattachAckPayload` WIP requires `handlerOutcome`; committed call sites
omit it until outcome type + payload land together.

---

## Classification legend

| Target | Meaning |
|---|---|
| **PR-A** | Recovery observation foundation (delivery / ingress / offer observation) |
| **PR-B** | DeferredIntent authority |
| **PR-C** | Completion authority + observation projection (+ control reconciliation) |
| **PR-D** | Coordinator / Runtime / app wiring |
| **HOLD** | Harness / soak / D1 field / Joint — not in P2 product PRs |
| **AMBIG** | Touches multiple domains; split or attach with note |
| **OUT** | Build caches / logs — never commit |

---

## PR-A — Recovery Observation Foundation

Title intent: `refactor(recovery): establish recovery observation foundation`

| File | Kind | ADR / note | Status |
|---|---|---|---|
| `core/util/OfferDeliveryObservation.kt` | M | ADR-0035 / delivery observation correlation | WIP |
| `core/util/RecoveryDeliveryFactTest.kt` | M | delivery facts | WIP |
| `core/model/RecoveryHandlerOutcome.kt` | U | ADR-0035 PR4 ACK outcome; **closure hole** | WIP |
| `core/model/RecoveryReattachAckPayload.kt` | M | wire `handlerOutcome` | WIP |
| `core/model/RecoveryReattachAckPayloadTest.kt` | M | ACK contract | WIP |
| `core/session/RecoveryAdmissionFreshness.kt` | U | PR3-0 admission projection (observation-only) | WIP |
| `core/session/EdgeReachabilitySnapshot.kt` | M | `ADMISSION_CONFIDENCE_*`, `DELIVERY_CONFIRMED` | WIP |
| `core/session/PeerSignalingReachabilityProjectionTest.kt` | U | admission projection UT | WIP |
| `core/session/RecoveryAdmissionGateTest.kt` | U | admission x delivery gate UT | WIP |
| `core/session/RecoveryDeliveryPolicyAdmissionTest.kt` | U | delivery policy + admission | WIP |
| `core/session/RecoveryDeliveryPolicyRetryTest.kt` | U | delivery retry | WIP |
| `core/session/RecoveryDeliveryDispatchBudgetTest.kt` | U | delivery budget | WIP |
| `core/session/Pr52cInboundDeliveryAcceptanceTest.kt` | U | inbound delivery / ACK | WIP |
| `core/session/RecoveryHandlerAckContractTest.kt` | U | handler ACK contract | WIP |
| `docs/adr/0035-recovery-scoped-delivery-assurance.md` | M | delivery assurance ADR | WIP |
| `docs/investigations/ice-restart-offer-delivery-investigation.md` | M | investigation notes | WIP |
| `scripts/analyze-ice-restart-offer-delivery.ps1` | M | analyzer | WIP |
| `scripts/analyze-recovery-delivery.ps1` | M | analyzer | WIP |

**Already on main (do not re-land):** `RecoveryIngressObservation` + R3 supersede / replay (`5bf3ebd`..`5e9fad3`).

**PR-A must NOT include:** negotiation gate, `DeferredIntentAuthority`, `RecoveryCompletionPolicy`, Coordinator bulk.

**Closure note:** landing `RecoveryHandlerOutcome` + ACK payload may need minimal Controller/payload call-site compile fixes in the same PR. Prefer not to pull full PR-D wiring.

---

## PR-B — DeferredIntent Authority

Title intent: `refactor(recovery): consolidate deferred intent authority`

| File | Kind | ADR / note | Status |
|---|---|---|---|
| `core/session/DeferredIntentAuthority.kt` | U | ADR-0022 E.16.1 Slice-1; **closure hole** | WIP |
| `core/session/DeferredIntentAuthoritySlice1Test.kt` | U | Slice-1 lifecycle UT | WIP |
| `core/session/DeferredIntentAuthoritySlice1JointTest.kt` | U | Joint-shaped DI UT (not field harness) | WIP |
| `core/session/InvDi001ReleaseIntentTest.kt` | U | INV-DI-001 release | WIP |
| `core/session/Pr52cDeferredIntentHoldTest.kt` | U | HELD path | WIP |
| `core/session/DebugExplicitSupersedePhase3aTest.kt` | U | explicit supersede (DI plane) | WIP |
| `core/session/NegotiationDeferredDrainAuthorityTest.kt` | M | drain x DI seam — verify before land | WIP |

**PR-B must NOT include:** delivery lineage mutation, CompletionPolicy, D1 drop injection.

**Closure note:** Controller already constructs `DeferredIntentAuthority` — PR-B lands the class; Coordinator debug API stays PR-D.

---

## PR-C — RecoveryCompletionPolicy

Title intent: `refactor(recovery): isolate completion authority and observation projection`

| File | Kind | ADR / note | Status |
|---|---|---|---|
| `core/session/RecoveryCompletionPolicy.kt` | U | ADR-0022 Q1-A; **closure hole** | WIP |
| `core/session/CompletionObservationProjection.kt` | U | PR5-0 read-only observation; **closure hole** | WIP |
| `core/session/CompletionObservationProjectionTest.kt` | U | projection UT | WIP |
| `core/session/RecoveryCompletionPolicyTest.kt` | U | completion UT | WIP |
| `core/session/ControlReconciliationEvaluator.kt` | U | PR5-2b Q6-2; **closure hole** | WIP |
| `core/session/ControlReconciliationEvaluatorTest.kt` | U | Q6-2 UT | WIP |
| `core/util/RecoveryControlReconciliationFact.kt` | U | observation fact; **closure hole** | WIP |
| `core/util/RecoveryControlReconciliationFactTest.kt` | U | fact UT | WIP |
| `core/session/MembershipAuthorityResolver.kt` | U | ADR-0022 Q7 membership convergence | WIP |
| `core/session/MembershipAuthorityResolveTrace.kt` | U | Q7-DIAG-0 | WIP |
| `core/session/MembershipAuthorityResolverTest.kt` | U | Q7 UT | WIP |
| `core/session/MembershipAuthorityResolveTraceTest.kt` | U | Q7 trace UT | WIP |
| `core/session/RecoveryAttemptOwnerTest.kt` | U | PR5-1 attempt ownership (no completion mutation) | WIP |

**PR-C must NOT include:** DeferredIntent lifecycle authority, D1 harness, Coordinator bulk.

---

## PR-D — Coordinator integration

Title intent: `refactor(recovery): wire coordinator through recovery authorities`

| File | Kind | ADR / note | Status |
|---|---|---|---|
| `app/TalkbackCoordinator.kt` | M | large integration (+513/-52) | WIP |
| `app/TalkbackRuntime.kt` | M | debug APIs + admission freshness config | WIP |
| `app/TalkbackRuntimeFactory.kt` | M | wires `recoveryAdmissionFreshnessMs` | WIP |
| `talkback-app/.../TalkbackForegroundService.kt` | M | DEBUG broadcast receivers | WIP |
| `core/signaling/UdpSignalingChannel.kt` | M | REMOTE_RECEIVE + D1 drop hook | AMBIG |

**Rule:** land only after PR-A/B/C restore symbol closure so Coordinator diffs are wiring, not definition smuggling.

---

## HOLD — harness / D1 / soak / Joint (not P2 product)

| File | Kind | Note |
|---|---|---|
| `core/util/D1IngressMissDebugInjection.kt` | U | ADR-0022 E.15 D1 Option A |
| `core/util/D1IngressMissInjectionTest.kt` | U | D1 injection UT |
| `core/session/Pr52cDebugInjection.kt` | U | PR5-2c-C; **HEAD Controller imports** — see AMBIG |
| `core/session/Pr52cDebugInjectionValidationTest.kt` | U | injection validation |
| `scripts/analyze-d1-delivery.ps1` | U | D1 |
| `scripts/analyze-joint-d1-c.ps1` | U | Joint |
| `scripts/analyze-pr52b-q6-soak.ps1` | U | soak |
| `scripts/analyze-pr52b-q7-gold-chain.ps1` | U | Q7 gold |
| `scripts/analyze-pr52c-a-dual-canonical.ps1` | U | dual canonical |
| `scripts/analyze-pr52c-c-deferred-intent.ps1` | U | PR52c-C DI |
| `scripts/analyze-completion-observation.ps1` | U | script HOLD; policy is PR-C |
| `scripts/analyze-admission-confidence.ps1` | U | admission analyzer |
| `scripts/analyze-43e-b30-observation.ps1` | U | observation analyzer |
| `scripts/analyze-r3-attempt4b-replay.ps1` | U | R3 replay analyzer (evidence already in UT/ADR) |
| `scripts/analyze-recovery-attempt-state.ps1` | U | attempt-state diagnostic |
| `scripts/run-joint-d1-c-orchestrator.ps1` | U | Joint orchestrator |
| `scripts/run-phase3a-ownership-isolation.ps1` | U | Phase-3A |
| `scripts/run-phase3b-retry-a.ps1` | U | Phase-3B |
| `scripts/run-phase3c-b-protected-window.ps1` | U | Attempt-4b/4c harness |
| `scripts/soak-*.ps1` | U | field soaks |
| `logs/**` (~1796) | U | field evidence — **OUT** of git |

---

## AMBIG — resolve before / during first PR

| Item | Conflict | Recommended disposition |
|---|---|---|
| `Pr52cDebugInjection` | HOLD (exercise) but HEAD Controller imports it | Land thin debug object with PR-B or PR-D compile closure; keep field arming HOLD |
| `UdpSignalingChannel` + D1 | Product REMOTE_RECEIVE vs D1 drop | Product observation in PR-A/D; D1 behind HOLD / no-op until harness |
| `RecoveryHandlerOutcome` | Delivery ACK (PR-A) vs Completion reads outcome (PR-C) | **PR-A owns type**; PR-C consumes |
| Membership Q7 stack | Control reconciliation vs topology | Keep with **PR-C** |
| Admission freshness | Observation (PR-A) vs Runtime config (PR-D) | Types **PR-A**; wire **PR-D** |
| `ConferenceEdgeRecoveryController` | On main already calls WIP types | Each PR-A/B/C must leave Controller compiling |

---

## OUT — never commit in P2

| Path | Reason |
|---|---|
| `logs/` | Local field captures |
| `build-*`, `android-board-talkback/build-drain-ut/`, `build-pr1-init.gradle` | Local build scratch |

---

## Suggested land order (compile-aware)

```text
PR-A  observation + delivery ACK / admission projection types
PR-B  DeferredIntentAuthority (+ Slice-1 tests)
PR-C  CompletionPolicy + CompletionObservation + control reconciliation + Q7
PR-D  Coordinator + Runtime + ForegroundService (+ Udp product path)

HOLD  D1 / Joint / soak / SUPPRESS_SUCCESSOR_ATTEMPT (R4/P3)
```

**P2 does not:** implement `SUPPRESS_SUCCESSOR_ATTEMPT`, run Attempt-4c, reopen Joint,
change PR5-3 gate, start R4 code.

---

## Counts (source/scripts/docs WIP only)

| Bucket | Approx. files |
|---|---|
| Modified tracked | 15 |
| Untracked source/test | 32 |
| Untracked scripts | 20 |
| Logs (OUT) | ~1796 |

---

## Next action after freeze

1. Confirm AMBIG dispositions (`Pr52cDebugInjection`, D1 in `UdpSignalingChannel`).
2. Open PR-A from a branch with only PR-A rows + minimum compile fixes.
3. Do not mix PR-B/C/D files into PR-A.

---

## AMBIG resolutions (2026-08-03) — frozen

| Item | Decision |
|---|---|
| Pr52cDebugInjection | Real type in compile closure; inert unless armed; not exercise primitive |
| Udp D1 drop | Product REMOTE_RECEIVE in PR-A; D1 HOLD |
| RecoveryHandlerOutcome | PR-A owns; PR-C consumes |

## PR-A compile-closure carry (not ownership transfer)

Controller on main already references these; PR-A lands real definitions so clean checkout compiles.
Ownership / semantic review remains:

| Type | Ownership |
|---|---|
| DeferredIntentAuthority | PR-B |
| RecoveryCompletionPolicy / CompletionObservationProjection | PR-C |
| ControlReconciliationEvaluator / RecoveryControlReconciliationFact | PR-C |
| Pr52cDebugInjection | compile-closure now; harness HOLD/P3 |

PR-A does **not** land Coordinator WIP, D1 injection, Membership Q7, Joint/soak harness.
PR-D WIP recovered to `git stash@{0}`: `P2 HOLD: PR-D coordinator/runtime/udp/service WIP (recovered)`.