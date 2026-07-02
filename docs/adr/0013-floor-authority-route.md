# ADR-0013: Floor authority route is a canonical projection

## Status

Accepted (revised RO-6, 2026-07-02)

## Context

GROUP PTT floor requests are sent to the floor authority (anchor module) over UDP signaling.
After roster or mesh mutations, `remotePeersByModule` could retain a stale UDP target while
`groupMembers` already reflected the correct authority binding. Requests then reached a
non-authority peer and were dropped (`NOT_AUTHORITY`) while the authority received nothing.

Runtime Ownership Refactor (RO-4–RO-6) replaced ad-hoc `signalPeersByModule` reads with
`TransportRegistry` as the sole signaling transport source for Floor routing.

## Decision

Floor routing MUST be a **pure function** of four inputs assembled at the Coordinator boundary:

1. `authorityModuleId` — from Membership (`floorAuthorityModuleId` or initiator fallback)
2. `authorityEndpoint` — the authority row in canonical `groupMembers`
3. `authorityEpoch` — `floorAuthorityEpoch` (bumped on GROUP roster mutation)
4. `transport` — `TransportRegistry.resolve(authorityModuleId)` (`TransportBinding`)

`FloorAuthorityRoute.resolve(authority, endpoint, epoch, transport)` MUST NOT take
`TalkbackSession` or read session caches directly.

Floor routing MUST NOT read `remotePeersByModule`, `discoveredByModule`, or `signalPeersByModule`.

`bumpFloorAuthorityEpoch` MUST call `TransportRegistry.invalidate(authority)` so bindings
do not silently span topology mutations (**Invariant F3**).

If `transport` is missing or `transport.epoch < authorityEpoch`, routing is **fail-closed**
(no fallback to another module).

## Invariant

> Floor routing MUST be a pure function of (canonical authority endpoint + authority epoch + TransportRegistry binding). Any cached or derived peer resolution outside this function is invalid.

## Consequences

- `FloorAuthorityRoute.resolve` is the single resolver; Coordinator only splits Membership vs Transport fields.
- `FLOOR_ROUTE_DECISION` and `FLOOR_REQUEST_SEND` logs include `authorityEpoch`, `resolvedFrom=ROSTER_ENDPOINT+TRANSPORT_REGISTRY`, and `peerSetHash`.
- Polluting legacy `signalPeersByModule` does not affect Floor routing (RO-5 red test).
- Conference membership reducer and mesh planner are unchanged.
