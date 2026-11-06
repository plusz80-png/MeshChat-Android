# ADR 0001: End-to-end security boundary and opaque relays

- Status: accepted as an architectural constraint; cryptographic library selection remains open.
- Date: 2026-11-06

## Context

MeshChat must operate over local and Internet paths, including volunteer repeaters. A relay may be offline, untrusted, or operated by a third party. It therefore cannot be part of the trusted computing base for message confidentiality.

A static public-key encryption helper alone is not a complete messaging protocol: it does not by itself provide a ratchet, forward secrecy, replay handling, membership changes, or post-compromise recovery.

## Decision

- Keep all message encryption/decryption and all private identity/session keys on endpoint devices.
- Treat BLE, Wi-Fi Direct, mesh forwarding and Internet relay as interchangeable byte transports. A transport receives only an opaque sealed payload plus the minimum routing metadata.
- Use the Relay Packet v1 framing draft for the first transport-neutral packet boundary.
- Do not implement cryptographic primitives, a Double Ratchet, or a group ratchet from scratch.
- Evaluate an audited, maintained Android-compatible implementation of the IETF Messaging Layer Security protocol (MLS, RFC 9420) as the common session/group layer. A two-member conversation can be represented as a two-member MLS group; private communities use authenticated membership changes and Welcome delivery. This is a candidate, not yet integrated or verified.
- Bind the immutable packet header to the E2EE ciphertext as AEAD associated data. The mutable hop counter is excluded and is only a best-effort propagation limit.
- Any future server stores/forwards opaque bytes only. It must not hold endpoint private keys, MLS state, plaintext, or message previews.

## Consequences

- A relay can observe packet size, timing, expiry, packet ID and a pseudonymous destination token. The first framing version does not provide traffic-analysis resistance or anonymity.
- A stable destination token could enable correlation; token rotation and discovery design are still required.
- Group invitation authenticity depends on binding the invite to a verified inviter credential, not merely possessing a QR image or link.
- The project must not advertise E2EE as available until the selected library, identity binding, key storage, group membership, replay protection and negative/tamper tests are implemented.
- Android background relay must be explicitly user-enabled, visible to the user, stoppable, battery-aware and implemented only through Android-approved execution mechanisms. No hidden always-on service is assumed.

## Security acceptance criteria before a public beta

1. Two devices establish identities and verify each other's credential fingerprints.
2. A message decrypts only on intended member devices.
3. A relay/server can forward a packet but cannot decrypt it; this is tested with no endpoint keys present.
4. Header/ciphertext tampering is rejected.
5. Replayed and expired packets are rejected with bounded persistent storage.
6. Community add/remove operations update the cryptographic epoch and removed members cannot decrypt subsequent messages.
7. Key backup, device loss, reinstall and identity rotation behavior are documented and tested.
8. Permission prompts and background behavior are tested on Android 10, 12, 13, 14 and current Android.
