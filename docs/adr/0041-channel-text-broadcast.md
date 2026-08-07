# ADR-0041: ChannelText as Channel Broadcast Control Event

## Status

Accepted (V1.5)

## Context

Operators need short **task broadcast** text to all members of a Channel (e.g. “Shutdown started in 10 minutes”). This must not be modeled as multi-select Endpoint fan-out (“群发短信”) or as Group Chat.

ADR-0039 already freezes **EndpointText** as 1:1 transient control. Channel broadcast must stay a separate address family so unread, history keys, retry, and delivery stay clean.

## Decision

ChannelText is a **Channel-scoped transient control-plane event**:

- New `SignalType.CHANNEL_TEXT` on the existing signed UDP `SignalEnvelope` path.
- Business address is `channelId` (payload). Envelope `to` is each reachable member EndpointKey for mesh delivery (V1.5 unicast fan-out). `sessionId` stays empty.
- Send/receive **bypass** Session / Floor / Admission / BUSY (same as ADR-0039).
- Do **not** use `to=[M02,M03,…]` as the identity of the message. Identity is `(channelId, messageId)`.
- V1.5 recipients = currently reachable teammates for that channel (same reachability set used for channel mesh), not ad-hoc multi-select.
- Best-effort: no ACK, no persistence, no offline queue. Receiver dedups by `messageId`. Rate-limit per `(senderEndpointKey, channelId)` (~1/s).
- Payload UTF-8 ≤256 chars.
- UI presents Channel threads under Messages → **Channels** section; Direct under **Direct Messages**. Notifications name the channel / sender only — no body.

**Canonical invariant:** ChannelText is broadcast to a Channel, not a multi-endpoint IM group.

## Consequences

- `MessageTarget` = `Endpoint | Channel` at the presentation/control boundary.
- EndpointText and ChannelText share amnesic policy but never share address keys.
- Group Chat / member-managed rooms remain V2.
- Owner-conflict ADR naming collision with historical “ADR-0039” field notes stays out of scope.

## Alternatives considered

- **Multi-select Contacts send:** Rejected — no group identity; unread/history explode.
- **Reuse ENDPOINT_TEXT N times into Direct threads:** Rejected — pollutes 1:1 Conversations.
- **True IP multicast:** Deferred — mesh already uses unicast signaling; V1.5 fan-out is explicit.
