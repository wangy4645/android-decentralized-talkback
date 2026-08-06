# ADR-0039: Endpoint Text as Transient Control Event

## Status

Accepted

## Context

Operators need short one-to-one text between EndpointKeys (Contacts “Send Message”) without starting a voice Session. The product must not slide into IM semantics (history, inbox, ACK, offline queue, attachments).

`SignalEnvelope.sessionId` and Session / Floor / Foreground Activity admission already form a media control plane. Reusing them for text would couple control events to media FSMs and invite BUSY rejects, session creation, and persistence pressure.

ADR-0010 already documents Conference membership vs media projection — it must not be overloaded for Endpoint Text.

## Decision

Endpoint Text is a **transient control-plane event**:

- New `SignalType.ENDPOINT_TEXT` on the existing signed UDP `SignalEnvelope` path.
- `SignalEnvelope.sessionId` is always the empty string for ENDPOINT_TEXT; identity is `messageId` in the JSON payload only.
- Send and receive **explicitly bypass** Session touch, Floor FSM, Foreground Activity / Media Admission, and BUSY rejection. Coordinator routes ENDPOINT_TEXT to `EndpointTextController` and returns before session dispatch.
- Business address is EndpointKey; mesh routing uses `to.moduleId` only.
- V1 delivery is best-effort: no ACK, no outbox, no inbox, no store-and-forward, no notification persistence.
- Receiver dedups by `messageId` (LRU), not `receiveGeneration`.
- Sender rate-limits per `(senderEndpointKey, receiverEndpointKey, ENDPOINT_TEXT)` (~1/s) with silent drop.
- Payload is UTF-8 text ≤256 characters; `priority` / `sessionHint` are wire-reserved / log-only.

**Canonical invariant:** Endpoint Text is a transient control event with no persistence, no inbox, and no replay semantics.

## Consequences

- TalkbackRuntime exposes `sendEndpointText` / `onEndpointTextReceived` as the single seam (tested via `TestTalkbackNode` + `InMemorySignalingHub`).
- UI (#26) may render INLINE overlays from the callback only; it must not invent history or Session binding.
- Endpoint Attachment (images/files) remains a separate V2 data-plane track — not stuffed into ENDPOINT_TEXT.
- Owner-conflict / WiFi-recovery work that historically reserved “ADR-0039” naming in field notes is an independent track and must not be merged into this ADR’s scope.

## Alternatives considered

- **Reuse Unicast `sessionId` / bind text to active call:** Rejected — pollutes Session FSM and blocks send/receive while busy.
- **IM outbox + history store:** Rejected for V1 — reverses the amnesic control-event model.
- **Document under ADR-0010:** Rejected — ADR-0010 already means Conference membership vs media projection.
