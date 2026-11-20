# Relay packet v1

Status: protocol framing draft, implemented as a Kotlin codec; relay replay admission and persistent local deduplication are implemented on the feature branch. No transport or encryption engine is connected yet.

## Purpose

Carry an already end-to-end-encrypted message across BLE, Wi-Fi Direct, or an Internet relay without exposing message text or sender identity to intermediate nodes. The codec is deliberately transport-neutral.

## Binary wire format

All integers use network byte order (big-endian).

| Field | Size | Meaning |
|---|---:|---|
| Magic | 4 bytes | ASCII `MCHT` |
| Version | 1 byte | `1` |
| Packet ID | 16 bytes | Random unique identifier |
| Destination token | 16 bytes | Opaque routing token; never a username or public identity key |
| Expiry | 8 bytes | Unix time in seconds |
| Remaining hops | 1 byte | Mutable forwarding budget, 0–8 |
| Payload length | 4 bytes | Length validated to 16–65,536 |
| Sealed payload | variable | E2EE ciphertext produced by the future crypto module |

The fixed header is 50 bytes. The maximum encoded packet is 65,586 bytes. BLE transports will need bounded fragmentation/reassembly; this codec does not implement that layer.

## End-to-end authenticated data

Before encryption, the E2EE engine must authenticate this exact associated-data byte sequence. With MLS (RFC 9420), supply these bytes as the authenticated-data input if the selected MLS API exposes it. Do not wrap MLS in a second custom AEAD layer. If the selected binding cannot set this field, put the same immutable values inside the authenticated application payload and verify them after decryption:

1. ASCII `MESHCHAT-RELAY-AAD`
2. Version byte `0x01`
3. Packet ID (16 bytes)
4. Destination token (16 bytes)
5. Expiry (8-byte big-endian Unix timestamp)

The remaining-hop counter is intentionally excluded so a relay can decrement it without the recipient's key. A receiver must reject a packet if E2EE authentication fails. The current codec only produces these bytes; it does not authenticate them.

## Replay and relay admission

The feature branch adds:

- `RelayPacket.replayFingerprint()`: SHA-256 over the immutable authenticated header plus the sealed payload. The mutable hop counter is excluded, so forwarding the same packet does not evade deduplication.
- `RelayReplayGuard`: rejects expired packets, packets with more than seven days of remaining lifetime, exhausted hop budgets, duplicates and capacity exhaustion.
- `SqliteRelayReplayStore`: a persistent local SQLite table with an atomic write-transaction claim, expiry pruning, a 20,000-record default bound and fail-closed behavior when full.
- The database stores only the 32-byte digest (hex-encoded) and expiry timestamp. It never stores ciphertext, plaintext, identity keys or MLS state.

The replay fingerprint is only a deduplication key, not a MAC or signature. A malicious party can still submit altered ciphertext with a different fingerprint; the recipient must reject it through E2EE authentication. Replay suppression is local to one relay and is not a substitute for MLS/application replay handling at the destination. The seven-day TTL is a v1 operational limit and should be revisited with offline-delivery requirements.

## Relay rules

- A relay may read the routing token, packet ID, expiry and hop budget. These fields are metadata, not secret.
- A relay must never receive plaintext, private identity keys, group secrets or session state.
- A relay forwards the exact sealed payload unchanged and decrements the hop budget.
- A relay drops expired packets, malformed packets, duplicates and packets with no remaining hops.
- The hop budget is a best-effort propagation limit, not a cryptographic proof: a malicious relay can rewrite this unauthenticated field.
- The destination token is pseudonymous, not anonymous. Reuse can permit correlation; a later discovery/mailbox design should rotate tokens and avoid publishing stable user identifiers.
- No sender ID, display name, community name, plaintext preview or encryption key is present in this outer frame.

## Current implementation boundary

`RelayPacket` validates and serializes the frame. The replay guard and SQLite store provide local relay admission/deduplication only. There is still no encryption, decryption, sender authentication, network routing, packet queue, BLE, Wi-Fi Direct, Internet relay or chat. Do not pass plaintext to `sealedPayload`. The minimum 16-byte payload size reserves room for an AEAD tag; it is not proof that the bytes are encrypted.

## Required next work

1. Select and audit an Android-compatible E2EE implementation; do not invent a ratchet or group key schedule.
2. Integrate the E2EE library and bind immutable routing fields to authenticated message data.
3. Add receiver-side replay and membership/epoch validation.
4. Add transport-specific framing and tests, especially BLE MTU fragmentation.
5. Run JVM and Android instrumentation tests in CI, then interoperability checks on at least two physical Android devices.
