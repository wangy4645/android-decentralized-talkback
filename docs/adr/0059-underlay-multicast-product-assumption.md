# ADR-0059: Underlay Multicast Is a Product Dependency, Not a Lab Gate

## Status

**ACCEPTED — PRODUCT RISK ASSUMPTION** (2026-08-27)

The product owner guarantees that the fixed-IP Mesh underlay provides reliable,
efficient multicast with underlay-owned routing and bounded replication. The
project has no qualifying RF laboratory, so ADR-0058 is allowed to proceed on
this dependency assumption without a Gate 0 signature. This is deliberate risk
acceptance, not evidence that the underlay has passed capacity or field tests.

## Decision

Talkback treats the underlay as providing:

```text
fixed Module IP addresses
multicast join and delivery across the supported Mesh
route selection and multi-hop forwarding below the App
controlled forwarding rather than App-layer flooding
finite loss, jitter and route-change interruption
```

Talkback must not implement next-hop routing, a forwarding tree, hop-by-hop ACK,
or a second multicast reliability protocol. Route changes must not change
Conference epoch, Membership, media key, or Sender Source identity.

## Consequences

```text
ADR-0058 contract acceptance no longer waits for a laboratory signature.
The removed Gate 0 documents and lab templates are not acceptance requirements.
Implementation still requires an explicit authorization.
ADR-0056 remains available until multicast Conference passes product field gates.
```

The absence of laboratory qualification remains an explicit product risk:

```text
8–10 member capacity is not yet evidenced.
Full-source concurrency is not yet evidenced.
Worst-hop and route-reorganization behavior are not yet evidenced.
Screen-off / background behavior is not yet evidenced.
```

Field validation must measure the actual product workload at 3 / 4 / 6 / 8 / 10
members before ADR-0056 can be deleted. Failure at a capacity gate means the
multicast implementation or supported envelope must be corrected; it must not
be rewritten as an underlay PASS.

**Amendment (ADR-0060, 2026-09-03):** This ADR's underlay multicast dependency
applies to the **optimization path** only. Conference media **correctness** MUST
NOT depend on multicast delivery. See
[ADR-0060](./0060-conference-media-transport-resilience-contract.md).
Capacity envelope evidence MUST be re-run under sender-side unicast fan-out
before Media Engine cutover.

`MaxUnderlayDatagramResidence` remains undeclared. Therefore V1 must allocate a
fresh Media Group Endpoint for each Conference and must not reuse a terminated
Conference endpoint. This restriction removes the need to invent an unverified
quarantine duration.

## Superseded material

This decision replaces and removes:

```text
0058-underlay-multicast-gate-0.md
0058-gate0-qualification-execution-pack.md
docs/adr/gate0-lab/
```

Their historical thresholds and signature workflow must not be cited as having
run, passed, failed, or qualified the underlay.
